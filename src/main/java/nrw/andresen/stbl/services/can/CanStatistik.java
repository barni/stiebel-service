package nrw.andresen.stbl.services.can;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the CAN messages and the state of the USBtin adapter. Messages arrive in the jssc event thread, the minute
 * is closed by the scheduler, so all counters are thread safe.
 */
public class CanStatistik {

    // Bits of a standard CAN frame without data: start, 11 bit id, control, CRC, ACK, end of frame, interframe space.
    // Bit stuffing is not counted, so the bus load is a lower estimate.
    private static final int BITS_PRO_RAHMEN = 47;

    private static final Map<Integer, String> KNOTEN_NAMEN = Map.of(
            0x180, "Kessel",
            0x480, "Manager",
            0x500, "Wärmepumpe",
            0x514, "Heizmodul");

    private final AtomicLong empfangen = new AtomicLong();
    private final AtomicLong antworten = new AtomicLong();
    private final AtomicLong nichtVerfuegbar = new AtomicLong();
    private final AtomicLong gesendet = new AtomicLong();
    private final AtomicLong bits = new AtomicLong();
    private final AtomicLong neustarts = new AtomicLong();
    private final Map<Integer, Knoten> knoten = new ConcurrentHashMap<>();

    private volatile Instant letzteNachricht;
    private volatile Instant letzteAntwort;
    private volatile Instant verbundenSeit;
    private volatile Instant letzterNeustart;
    private volatile boolean verbunden;
    private volatile String firmware;
    private volatile String hardware;
    private volatile String seriennummer;

    // Counter values at the end of the last minute, the differences are the values of the minute
    private long empfangenVorher;
    private long antwortenVorher;
    private long nichtVerfuegbarVorher;
    private long gesendetVorher;
    private long bitsVorher;
    private Instant minuteVorher;
    private volatile Minute letzteMinute;

    private static class Knoten {
        final AtomicLong nachrichten = new AtomicLong();
        volatile Instant zuletzt;
        long nachrichtenVorher;
        volatile double proMinute;
    }

    /**
     * Values of the last complete minute, rates per minute, Antwortquote and Buslast in percent
     */
    public record Minute(Instant ende, double empfangen, double antworten, double gesendet, double nichtVerfuegbar,
                         double antwortquote, double buslast) {
    }

    /**
     * Messages per minute of one CAN node and the time it was seen last
     */
    public record KnotenStatus(int id, String name, double proMinute, Instant zuletzt) {
    }

    /**
     * Any message received on the bus
     */
    public void empfangen(int canId, int datenBytes, Instant zeit) {
        empfangen.incrementAndGet();
        bits.addAndGet(BITS_PRO_RAHMEN + 8L * datenBytes);
        letzteNachricht = zeit;
        Knoten k = knoten.computeIfAbsent(canId, id -> new Knoten());
        k.nachrichten.incrementAndGet();
        k.zuletzt = zeit;
    }

    /**
     * Answer to a request of this service, nichtVerfuegbar if the device answered 0x8000
     */
    public void antwort(boolean nichtVerfuegbar, Instant zeit) {
        antworten.incrementAndGet();
        if (nichtVerfuegbar) {
            this.nichtVerfuegbar.incrementAndGet();
        }
        letzteAntwort = zeit;
    }

    /**
     * Request sent by this service
     */
    public void gesendet(int datenBytes) {
        gesendet.incrementAndGet();
        bits.addAndGet(BITS_PRO_RAHMEN + 8L * datenBytes);
    }

    public void verbunden(String firmware, String hardware, String seriennummer, Instant zeit) {
        this.firmware = firmware;
        this.hardware = hardware;
        this.seriennummer = seriennummer;
        this.verbundenSeit = zeit;
        this.verbunden = true;
    }

    public void getrennt() {
        verbunden = false;
    }

    /**
     * Restart of the USB connection by the watchdog
     */
    public void neustart(Instant zeit) {
        neustarts.incrementAndGet();
        letzterNeustart = zeit;
    }

    /**
     * Closes the minute, the first call only sets the start
     */
    public synchronized void minuteAbschliessen(Instant zeit, int bitrate) {
        long e = empfangen.get(), a = antworten.get(), n = nichtVerfuegbar.get(), g = gesendet.get(), b = bits.get();
        if (minuteVorher != null) {
            double minuten = Duration.between(minuteVorher, zeit).toMillis() / 60000d;
            if (minuten > 0) {
                double sekunden = minuten * 60;
                long anfragen = g - gesendetVorher;
                double quote = anfragen > 0 ? Math.min(100, 100d * (a - antwortenVorher) / anfragen) : 0;
                letzteMinute = new Minute(zeit, (e - empfangenVorher) / minuten, (a - antwortenVorher) / minuten,
                        (g - gesendetVorher) / minuten, (n - nichtVerfuegbarVorher) / minuten, quote,
                        100d * (b - bitsVorher) / sekunden / bitrate);
                for (Knoten k : knoten.values()) {
                    long anzahl = k.nachrichten.get();
                    k.proMinute = (anzahl - k.nachrichtenVorher) / minuten;
                    k.nachrichtenVorher = anzahl;
                }
            }
        }
        empfangenVorher = e;
        antwortenVorher = a;
        nichtVerfuegbarVorher = n;
        gesendetVorher = g;
        bitsVorher = b;
        minuteVorher = zeit;
    }

    public Minute getLetzteMinute() {
        return letzteMinute;
    }

    /**
     * Nodes sorted by CAN id
     */
    public List<KnotenStatus> getKnoten() {
        List<KnotenStatus> liste = new ArrayList<>();
        knoten.forEach((id, k) -> liste.add(new KnotenStatus(id, knotenName(id), k.proMinute, k.zuletzt)));
        liste.sort(Comparator.comparingInt(KnotenStatus::id));
        return liste;
    }

    public static String knotenName(int id) {
        return String.format("0x%03X", id) + (KNOTEN_NAMEN.containsKey(id) ? " " + KNOTEN_NAMEN.get(id) : "");
    }

    public long getNeustarts() {
        return neustarts.get();
    }

    public Instant getLetzteNachricht() {
        return letzteNachricht;
    }

    public Instant getLetzteAntwort() {
        return letzteAntwort;
    }

    public Instant getVerbundenSeit() {
        return verbundenSeit;
    }

    public Instant getLetzterNeustart() {
        return letzterNeustart;
    }

    public boolean isVerbunden() {
        return verbunden;
    }

    public String getFirmware() {
        return firmware;
    }

    public String getHardware() {
        return hardware;
    }

    public String getSeriennummer() {
        return seriennummer;
    }
}
