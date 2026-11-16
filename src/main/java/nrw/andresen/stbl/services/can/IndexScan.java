package nrw.andresen.stbl.services.can;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One time read scan of all indices of the Elster table at the manager and the heat pump, like can_scan, to find
 * every value this installation really answers. Only read requests are sent. Indices the service requests anyway are
 * skipped: the received values are kept per index, an answer from another node would overwrite them.
 */
public class IndexScan {

    // 0x700 does not answer requests of this service, 0x100 is the control unit
    public static final List<Integer> KNOTEN = List.of(0x180, 0x480, 0x500, 0x514);
    private static final int NICHT_VERFUEGBAR = 0x8000;

    /**
     * Answer of a node to an index with a value; wert as the Elster table formats it, roh 0..0xffff
     */
    public record Ergebnis(int knoten, short index, String name, String wert, int roh, Instant zeit) {
    }

    /**
     * State of the scan
     */
    public record Status(boolean laeuft, Instant start, Instant ende, int gesendet, int gesamt, int mitWert,
                         int nichtVerfuegbar) {
    }

    private final Map<Integer, Ergebnis> ergebnisse = new ConcurrentHashMap<>();
    private final AtomicInteger nichtVerfuegbar = new AtomicInteger();
    private final AtomicInteger gesendet = new AtomicInteger();
    private final ElsterTable elsterTable = new ElsterTable();
    private volatile boolean laeuft;
    private volatile Instant start;
    private volatile Instant ende;
    private volatile int gesamt;

    /**
     * Indices to scan, without those the service requests anyway
     */
    public List<Short> indizes(Set<Short> ausgenommen) {
        return elsterTable.alleIndizes().stream().filter(index -> !ausgenommen.contains(index)).toList();
    }

    /**
     * Sends the read requests with the given pause and blocks until all are sent and the last answers had time to
     * arrive. Returns false if a scan is running already.
     */
    public boolean scannen(int senderId, Set<Short> ausgenommen, Duration pause, Duration nachlauf,
                           Consumer<ElsterMessage> sender) {
        synchronized (this) {
            if (laeuft) {
                return false;
            }
            laeuft = true;
        }
        try {
            ergebnisse.clear();
            nichtVerfuegbar.set(0);
            gesendet.set(0);
            List<Short> indizes = indizes(ausgenommen);
            gesamt = indizes.size() * KNOTEN.size();
            start = Instant.now();
            ende = null;
            for (short index : indizes) {
                for (int knoten : KNOTEN) {
                    sender.accept(ElsterMessage.readRequest(senderId, knoten, index));
                    gesendet.incrementAndGet();
                    Thread.sleep(pause.toMillis());
                }
            }
            Thread.sleep(nachlauf.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } finally {
            ende = Instant.now();
            laeuft = false;
        }
    }

    public boolean laeuft() {
        return laeuft;
    }

    /**
     * Answer to this service while the scan runs; answers of other nodes and 0x8000 (not available) are not kept
     *
     * @return true if the answer belongs to the scan
     */
    public boolean antwort(ElsterMessage antwort, Instant zeit) {
        Short roh = antwort.getRawValue();
        if (!laeuft || roh == null || !KNOTEN.contains(antwort.getId())) {
            return false;
        }
        if ((roh & 0xffff) == NICHT_VERFUEGBAR) {
            nichtVerfuegbar.incrementAndGet();
            return true;
        }
        short index = antwort.getElsterIndex().getIndex();
        ergebnisse.put(antwort.getId() << 16 | (index & 0xffff), new Ergebnis(antwort.getId(), index,
                antwort.getElsterIndex().getName(), antwort.getValue(), roh & 0xffff, zeit));
        return true;
    }

    /**
     * All answers with a value, by index and node
     */
    public List<Ergebnis> ergebnisse() {
        List<Ergebnis> liste = new ArrayList<>(ergebnisse.values());
        liste.sort(Comparator.comparingInt((Ergebnis e) -> e.index() & 0xffff).thenComparingInt(Ergebnis::knoten));
        return liste;
    }

    public Status status() {
        return new Status(laeuft, start, ende, gesendet.get(), gesamt, ergebnisse.size(), nichtVerfuegbar.get());
    }

    /**
     * All answers with a value as CSV
     */
    public String csv() {
        StringBuilder csv = new StringBuilder("knoten;index;name;wert;roh_hex;roh;zeit\n");
        for (Ergebnis e : ergebnisse()) {
            csv.append(String.format("0x%03X;0x%04X;%s;%s;0x%04X;%d;%s%n", e.knoten(), e.index() & 0xffff, e.name(),
                    e.wert(), e.roh(), e.roh(), e.zeit()));
        }
        return csv.toString();
    }
}
