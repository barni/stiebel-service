package nrw.andresen.stbl.services.can;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * One time read scan of all indices of the Elster table at the given nodes, like can_scan. Used to find where the
 * WPM3 keeps its fault list. Only read requests are sent. Indices the service requests anyway are skipped, an answer
 * from another node would overwrite their values (the received values are kept per index only).
 */
public class IndexScan {

    public static final List<Integer> KNOTEN = List.of(0x180, 0x480, 0x500, 0x514);
    private static final int NICHT_VERFUEGBAR = 0x8000;
    // Known from the fault list on the WPM display (2026-10-07): 8256 = INV H Ausgangsstrombegrenzung, 69 is the
    // FEHLERMELDUNG of the WPM, 67 would be INV H ROTORVEKTOR in the numbering of the newer WPM (20067)
    private static final Map<Integer, String> GESUCHT = Map.of(
            0x2040, "8256 INV H Ausgangsstrombegrenzung",
            69, "69 wie FEHLERMELDUNG",
            67, "67 ROTORVEKTOR (neue Nummerierung)",
            0x0e05, "14.05. als Tag/Monat",
            0x1904, "25.04. als Tag/Monat",
            0x1235, "18:53 als Stunde/Minute",
            1853, "18:53 als Zahl",
            0x070a, "07:10 als Stunde/Minute",
            710, "07:10 als Zahl");

    /**
     * Answer of a node to an index, wert 0..0xffff
     */
    public record Ergebnis(int knoten, short index, String name, int wert, Instant zeit) {
    }

    /**
     * State and evaluation of the scan
     */
    public record Status(boolean laeuft, Instant start, Instant ende, int gesendet, int gesamt, int mitWert,
                         int nichtVerfuegbar, List<Treffer> treffer, List<Haeufung> haeufungen) {
    }

    public record Treffer(Ergebnis ergebnis, String grund) {
    }

    /**
     * A value that one node answers for many indices, e.g. the same fault code in several list entries
     */
    public record Haeufung(int knoten, int wert, int anzahl, List<String> indizes) {
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
     * Sends the read requests with the given pause, blocks until all are sent. Returns false if a scan is running.
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
            // Late answers
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
     * Answer to the service during the scan
     */
    public void antwort(int knoten, short index, short rohwert, Instant zeit) {
        if (!laeuft || !KNOTEN.contains(knoten)) {
            return;
        }
        int wert = rohwert & 0xffff;
        if (wert == NICHT_VERFUEGBAR) {
            nichtVerfuegbar.incrementAndGet();
            return;
        }
        ergebnisse.put(knoten << 16 | (index & 0xffff),
                new Ergebnis(knoten, index, elsterTable.findByIndex(index).getName(), wert, zeit));
    }

    public List<Ergebnis> ergebnisse() {
        List<Ergebnis> liste = new ArrayList<>(ergebnisse.values());
        liste.sort(Comparator.comparingInt(Ergebnis::knoten).thenComparingInt(e -> e.index() & 0xffff));
        return liste;
    }

    public Status status() {
        List<Ergebnis> liste = ergebnisse();
        List<Treffer> treffer = liste.stream().filter(e -> GESUCHT.containsKey(e.wert()))
                .map(e -> new Treffer(e, GESUCHT.get(e.wert()))).toList();
        return new Status(laeuft, start, ende, gesendet.get(), gesamt, liste.size(), nichtVerfuegbar.get(), treffer,
                haeufungen(liste));
    }

    /**
     * Values other than 0 that a node answers for at least 5 indices, the most frequent first
     */
    static List<Haeufung> haeufungen(List<Ergebnis> liste) {
        Map<Long, List<Ergebnis>> gruppen = liste.stream().filter(e -> e.wert() != 0)
                .collect(Collectors.groupingBy(e -> (long) e.knoten() << 32 | e.wert(), LinkedHashMap::new,
                        Collectors.toList()));
        return gruppen.values().stream().filter(g -> g.size() >= 5)
                .map(g -> new Haeufung(g.get(0).knoten(), g.get(0).wert(), g.size(),
                        g.stream().map(e -> String.format("0x%04X", e.index())).toList()))
                .sorted(Comparator.comparingInt(Haeufung::anzahl).reversed())
                .limit(20)
                .toList();
    }

    /**
     * All answers with a value as CSV
     */
    public String csv() {
        StringBuilder csv = new StringBuilder("knoten;index;name;wert_hex;wert;zeit\n");
        for (Ergebnis e : ergebnisse()) {
            csv.append(String.format("0x%03X;0x%04X;%s;0x%04X;%d;%s%n", e.knoten(), e.index(), e.name(), e.wert(),
                    e.wert(), e.zeit()));
        }
        return csv.toString();
    }
}
