package nrw.andresen.stbl.services.can;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
    // A minute is closed only after at least half a minute
    private static final double MIN_MINUTE = 0.5;
    // Last messages kept for the CAN bus page: with all messages received about 2 minutes of the bus
    public static final int RAHMEN_MAX = 1000;

    // 0x100 gets the time from the manager every minute like a display (Elster: FES_COMFORT); 0x601 named after the
    // Elster module list (Mixer), not confirmed on this installation; 0x380 gets the compressor speed from the heat
    // pump now and then but never answers (2026-11-16), probably an optional device that is not installed
    private static final Map<Integer, String> KNOTEN_NAMEN = Map.of(
            0x100, "Bedienteil",
            0x180, "Kessel",
            0x480, "Manager",
            0x500, "Wärmepumpe",
            0x514, "Heizmodul",
            0x601, "Mischer HK2",
            0x380, "unbekannt, antwortet nicht",
            // This service, CanBus.CAN_SENDER_ID
            0x681, "stbl-service");

    private final AtomicLong empfangen = new AtomicLong();
    private final AtomicLong antworten = new AtomicLong();
    private final AtomicLong nichtVerfuegbar = new AtomicLong();
    private final AtomicLong gesendet = new AtomicLong();
    private final AtomicLong bits = new AtomicLong();
    private final AtomicLong neustarts = new AtomicLong();
    private final Map<Integer, Knoten> knoten = new ConcurrentHashMap<>();
    // Newest first, guarded by itself
    private final Deque<Rahmen> rahmen = new ArrayDeque<>();
    private final AtomicLong fehler = new AtomicLong();
    private volatile Instant letzterFehler;
    private volatile String letzterFehlerText;

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
     * One message on the bus, received or sent by this service
     */
    public record Rahmen(Instant zeit, boolean gesendet, int id, byte[] daten) {
    }

    /**
     * Any message received on the bus, kept for the CAN bus page
     */
    public void empfangen(int canId, byte[] daten, Instant zeit) {
        empfangen(canId, daten.length, zeit);
        merken(new Rahmen(zeit, false, canId, daten.clone()));
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
     * Message of this service handed to the adapter for the bus, kept for the CAN bus page. Not when it is queued:
     * the adapter sends one message after the other, each after the acknowledge of the one before.
     */
    public void uebertragen(int canId, byte[] daten, Instant zeit) {
        merken(new Rahmen(zeit, true, canId, daten.clone()));
    }

    /**
     * Request sent by this service
     */
    public void gesendet(int datenBytes) {
        gesendet.incrementAndGet();
        bits.addAndGet(BITS_PRO_RAHMEN + 8L * datenBytes);
    }

    private void merken(Rahmen r) {
        synchronized (rahmen) {
            if (rahmen.size() >= RAHMEN_MAX) {
                rahmen.removeLast();
            }
            rahmen.addFirst(r);
        }
    }

    /**
     * The last messages, the newest first
     */
    public List<Rahmen> getRahmen() {
        synchronized (rahmen) {
            return List.copyOf(rahmen);
        }
    }

    /**
     * Error of the adapter, e.g. in its event thread or when connecting
     */
    public void fehler(String text, Instant zeit) {
        fehler.incrementAndGet();
        letzterFehler = zeit;
        letzterFehlerText = text;
    }

    public long getFehler() {
        return fehler.get();
    }

    public Instant getLetzterFehler() {
        return letzterFehler;
    }

    public String getLetzterFehlerText() {
        return letzterFehlerText;
    }

    public void verbunden(String firmware, String hardware, String seriennummer, Instant zeit) {
        this.firmware = version(firmware);
        this.hardware = version(hardware);
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
            if (minuten < MIN_MINUTE) {
                // Two closings shortly after each other, e.g. when the scheduler catches up after a start: the few
                // messages in between would be extrapolated to absurd rates (bus load 266 % on 2026-10-08), so
                // they are counted with the next minute
                return;
            }
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

    /**
     * The USBtin reports versions as XXYY, e.g. 0108 for firmware 1.8. Other texts are kept.
     */
    static String version(String roh) {
        if (roh == null || !roh.matches("\\d{4}")) {
            return roh;
        }
        return Integer.parseInt(roh.substring(0, 2)) + "." + Integer.parseInt(roh.substring(2));
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
