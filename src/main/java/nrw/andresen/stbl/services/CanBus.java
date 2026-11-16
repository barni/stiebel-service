package nrw.andresen.stbl.services;

import de.fischl.usbtin.FilterChain;
import de.fischl.usbtin.FilterMask;
import de.fischl.usbtin.FilterValue;
import de.fischl.usbtin.USBtin;
import de.fischl.usbtin.USBtinException;
import jakarta.annotation.PreDestroy;
import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterMessage;
import nrw.andresen.stbl.services.can.ElsterTable;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.IndexBeschreibung;
import nrw.andresen.stbl.services.can.IndexScan;
import nrw.andresen.stbl.services.can.SynchronizedUSBtin;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

import static nrw.andresen.stbl.services.can.ElsterTable.BETRIEBS_STATUS;
import static nrw.andresen.stbl.services.can.ElsterTable.FEIN_FDF5;
import static nrw.andresen.stbl.services.can.ElsterTable.FEIN_RUECKLAUF;
import static nrw.andresen.stbl.services.can.ElsterTable.FEIN_VORLAUF;
import static nrw.andresen.stbl.services.can.ElsterTable.PUMPE_PWM;
import static nrw.andresen.stbl.services.can.ElsterTable.REGEL_INTEGRAL;
import static nrw.andresen.stbl.services.can.ElsterTable.FREQUENZ_ANFORDERUNG;
import static nrw.andresen.stbl.services.can.ElsterTable.AUSSENTEMP;
import static nrw.andresen.stbl.services.can.ElsterTable.HEIZKREIS_ISTTEMP;
import static nrw.andresen.stbl.services.can.ElsterTable.ROH_07A8;
import static nrw.andresen.stbl.services.can.ElsterTable.ROH_0A36;
import static nrw.andresen.stbl.services.can.ElsterTable.ROH_FE07;
import static nrw.andresen.stbl.services.can.ElsterTable.ROH_FE09;
import static nrw.andresen.stbl.services.can.ElsterTable.RUECKLAUFISTTEMP;
import static nrw.andresen.stbl.services.can.ElsterTable.SPEICHERBEDARF;

/**
 * Access to the heat pump over the USBtin: sends read requests, keeps the latest answer per index, counts the
 * messages and restarts the USB connection if no answer arrives
 */
@Component
public class CanBus {

    public static final int CAN_SENDER_ID = 0x681;
    private static final byte[] CAN_SENDER_ID_BYTE_CODED = ElsterMessage.convertReceiverID(CAN_SENDER_ID);
    // Payload sent by a device which does not provide the requested index
    private static final short VALUE_NOT_AVAILABLE = (short) 0x8000;
    // The USB connection is restarted if no answer arrived for this time
    private static final Duration MAX_STILLE = Duration.ofSeconds(120);
    // While the adapter stays silent, alerts about its restarts are at least this far apart
    static final Duration MELDUNG_ABSTAND = Duration.ofHours(1);

    // Names of the indices for the CAN bus page
    private static final ElsterTable TABELLE = new ElsterTable();

    private final Logger logger = LoggerFactory.getLogger(CanBus.class);
    @Autowired
    private EmailService emailService;
    private SynchronizedUSBtin usbtin;
    // Latest answer per index; the answers carry only the index, so an index is requested from one node only
    private final Map<Short, ElsterMessage> msgs = new ConcurrentHashMap<>();
    // The same answers by node and index, for indices that several nodes answer with different values
    private final Map<Integer, ElsterMessage> msgsKnoten = new ConcurrentHashMap<>();
    // Values read along, separate from msgs: AUSSENTEMP and RUECKLAUFISTTEMP are also requested from other devices
    private final Map<Short, ElsterMessage> mitgelesen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ElsterMessage> queue = new ConcurrentLinkedQueue<>();
    // Indices requested by this service, answers to other indices are ignored
    private final Set<Short> angefragt = ConcurrentHashMap.newKeySet();
    // Time of the last and of the request before per index, for the CAN bus page
    private final Map<Short, Instant> angefragtUm = new ConcurrentHashMap<>();
    private final Map<Short, Instant> vorherAngefragt = new ConcurrentHashMap<>();

    /**
     * A value another device writes on the bus, taken over like an answer. Only seen with can.logall, the adapter
     * filters it otherwise.
     */
    record Mitlesen(int von, int an, short index, boolean antwort) {
    }

    // The heat pump answers a request for SPEICHERBEDARF with 0x8000 (tested 2026-11-15), the manager writes it
    // every 10 s
    static final List<Mitlesen> MITLESEN = List.of(
            new Mitlesen(0x480, 0x500, SPEICHERBEDARF, false),
            new Mitlesen(0x480, 0x500, REGEL_INTEGRAL, false),
            new Mitlesen(0x480, 0x500, HEIZKREIS_ISTTEMP, false),
            new Mitlesen(0x480, 0x700, PUMPE_PWM, false),
            // 0x700 answers own requests for these with 0x8000 (tested 2026-09-23), but answers the manager
            new Mitlesen(0x700, 0x480, FEIN_VORLAUF, true),
            new Mitlesen(0x700, 0x480, FEIN_RUECKLAUF, true),
            new Mitlesen(0x700, 0x480, FEIN_FDF5, true),
            new Mitlesen(0x700, 0x480, ROH_FE07, true),
            new Mitlesen(0x700, 0x480, ROH_FE09, true),
            // Outdoor temperature of the manager (heating curve), broadcast by 0x180 every 5 s
            new Mitlesen(0x180, 0x579, AUSSENTEMP, false),
            // Return temperature at the manager, 0x180 answers the control unit every 10 s
            new Mitlesen(0x180, 0x100, RUECKLAUFISTTEMP, true),
            new Mitlesen(0x500, 0x480, ROH_07A8, true),
            new Mitlesen(0x500, 0x480, ROH_0A36, true),
            new Mitlesen(0x500, 0x480, FREQUENZ_ANFORDERUNG, true));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant letzteAntwort;
    // Time of the last alert about a USB restart and the restarts since then, see checkUSBisAlive
    private Instant letzteMeldung;
    private int neustartsSeitMeldung;
    // Messages on the bus and state of the USBtin adapter, kept over USB restarts
    private final CanStatistik canStatistik = new CanStatistik();
    // Fault list of the WPM, its fields are kept apart from the other values
    private final Fehlerliste fehlerliste = new Fehlerliste();
    // Time of the last request of the fault list, null before the first one
    private volatile Instant fehlerlisteAngefragt;
    // One time scan of all Elster indices, started from the scan page
    private final IndexScan indexScan = new IndexScan();
    // Pause between two scan requests, about 10 per second like can_scan, the scan takes about 25 minutes
    private static final Duration SCAN_PAUSE = Duration.ofMillis(100);
    // Wait for late answers after the last request
    private static final Duration SCAN_NACHLAUF = Duration.ofSeconds(2);
    private final String usbPort;
    private final int usbSpeed;
    // Changed at runtime by setLogall, the next USB restart uses the new value too
    private volatile boolean logall;

    public CanBus(@Value("${USBtin.port}") String usbPort, @Value("${USBtin.speed}") int usbSpeed,
                  @Value("${can.logall}") boolean logall) {
        this.usbPort = usbPort;
        this.usbSpeed = usbSpeed;
        this.logall = logall;
        this.letzteAntwort = Instant.now();
        startUsbTin();
    }

    private void startUsbTin() {
        try {
            running.set(true);
            Thread consumer = new Thread(new Consumer());
            consumer.start();

            usbtin = new SynchronizedUSBtin(canStatistik);
            usbtin.connect(usbPort);
            usbtin.setFilter(filter(logall));

            usbtin.addMessageListener(canmsg -> {
                try {
                    Instant jetzt = Instant.now();
                    canStatistik.empfangen(canmsg.getId(), canmsg.getData(), jetzt);
                    empfangen(new ElsterMessage(canmsg), jetzt);
                } catch (Exception e) {
                    logger.error("ERROR during receiving: ", e);
                }
            });

            usbtin.openCANChannel(usbSpeed, USBtin.OpenMode.ACTIVE);

            logger.info("FirmwareVersion: " + usbtin.getFirmwareVersion());
            logger.info("HardwareVersion: " + usbtin.getHardwareVersion());
            logger.info("SerialNumber: " + usbtin.getSerialNumber());
            canStatistik.verbunden(usbtin.getFirmwareVersion(), usbtin.getHardwareVersion(),
                    usbtin.getSerialNumber(), Instant.now());
        } catch (Exception e) {
            canStatistik.getrennt();
            canStatistik.fehler("Verbinden fehlgeschlagen: " + e.getMessage(), Instant.now());
            logger.error("ERROR During initialization: ", e);
        }
    }

    /**
     * Filter of the adapter: null lets every message through, otherwise only messages to this service
     */
    static FilterChain[] filter(boolean logall) {
        return logall ? null : new FilterChain[]{
                new FilterChain(
                        new FilterMask(0x000, (byte) 0xf0, (byte) 0xff),
                        new FilterValue[]{
                                new FilterValue(0x000, CAN_SENDER_ID_BYTE_CODED[0], CAN_SENDER_ID_BYTE_CODED[1])
                        }
                )
        };
    }

    /**
     * Receives all messages or only the answers to this service, without a restart of the service. If the adapter
     * cannot be changed, the watchdog restarts the USB connection and that uses the new value.
     */
    public synchronized void setLogall(boolean an) {
        if (an == logall) {
            return;
        }
        logall = an;
        try {
            usbtin.filterSetzen(filter(an), usbSpeed);
            logger.info("Receiving " + (an ? "all CAN messages" : "only the answers to this service"));
        } catch (Exception e) {
            canStatistik.fehler("Filter ändern fehlgeschlagen: " + e.getMessage(), Instant.now());
            logger.error("Changing the CAN filter failed, the watchdog restarts the USB connection: ", e);
        }
    }

    public boolean isLogall() {
        return logall;
    }

    private void empfangen(ElsterMessage elsterMessage, Instant jetzt) {
        if (elsterMessage.isResponse() && elsterMessage.getReceiverId() == CAN_SENDER_ID) {
            short index = elsterMessage.getElsterIndex().getIndex();
            Short rawValue = elsterMessage.getRawValue();
            if (Fehlerliste.istFeld(elsterMessage.getId(), index) && rawValue != null) {
                letzteAntwort = jetzt;
                fehlerliste.feld(index, rawValue, jetzt);
                canStatistik.antwort(rawValue == VALUE_NOT_AVAILABLE, jetzt);
            }
            // Answers to the scan only for indices the service does not request anyway
            if (!angefragt.contains(index) && !Fehlerliste.istFeld(elsterMessage.getId(), index)
                    && indexScan.antwort(elsterMessage, jetzt)) {
                letzteAntwort = jetzt;
                canStatistik.antwort(rawValue != null && rawValue == VALUE_NOT_AVAILABLE, jetzt);
            }
            if (angefragt.contains(index)) {
                letzteAntwort = jetzt;
                canStatistik.antwort(rawValue != null && index != BETRIEBS_STATUS
                        && rawValue == VALUE_NOT_AVAILABLE, jetzt);
                // 0x8000 is a valid Betriebsstatus (EVU Sperre), for all other values it means not available
                if (rawValue != null && (index == BETRIEBS_STATUS || rawValue != VALUE_NOT_AVAILABLE)) {
                    queue.add(elsterMessage);
                }
            }
        }
        if (mitlesen(elsterMessage)) {
            mitgelesen.put(elsterMessage.getElsterIndex().getIndex(), elsterMessage);
        }
        if (logger.isDebugEnabled()) {
            logger.debug(elsterMessage + " INDEX: " + elsterMessage.getElsterIndex().getName() +
                    " Payload: " + elsterMessage.getValue());
        }
    }

    private void stopUsbTin() {
        try {
            running.set(false);
            canStatistik.getrennt();
            logger.info("Closing the USBtin");
            usbtin.closeCANChannel();
            usbtin.disconnect();
        } catch (Exception e) {
            logger.error("Error during shutdown. ", e);
        }
    }

    /**
     * Moves the received answers from the queue of the USBtin thread into the map
     */
    private class Consumer implements Runnable {
        public void run() {
            ElsterMessage msg;
            while (running.get()) {
                while ((msg = queue.poll()) != null) {
                    msgs.put(msg.getElsterIndex().getIndex(), msg);
                    msgsKnoten.put(schluessel(msg.getId(), msg.getElsterIndex().getIndex()), msg);
                    logger.debug("Add elster Message: " + msg.getElsterIndex().getName() +
                            " Payload: " + msg.getValue());
                }
                try {
                    Thread.sleep(500);
                } catch (Exception ex) {
                    logger.error("Error in thread: ", ex);
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        stopUsbTin();
    }

    /**
     * Sends a read request for each index to the node
     */
    public synchronized void anfragen(int knoten, short... indices) throws USBtinException {
        for (short index : indices) {
            angefragt.add(index);
            Instant vorher = angefragtUm.put(index, Instant.now());
            if (vorher != null) {
                vorherAngefragt.put(index, vorher);
            }
            usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, knoten, index).getMessage());
        }
    }

    /**
     * Starts the scan of all Elster indices at 0x180, 0x480, 0x500 and 0x514 in the background, read only. False if
     * one runs already. Indices the service requests anyway and the fault list are skipped.
     */
    public boolean starteScan() {
        if (indexScan.laeuft()) {
            return false;
        }
        Set<Short> ausgenommen = new HashSet<>(angefragt);
        for (int feld = 0; feld < Fehlerliste.FELDER; feld++) {
            ausgenommen.add((short) (Fehlerliste.ERSTES_FELD + feld));
        }
        Thread scan = new Thread(() -> indexScan.scannen(CAN_SENDER_ID, ausgenommen, SCAN_PAUSE, SCAN_NACHLAUF,
                anfrage -> {
                    try {
                        usbtin.send(anfrage.getMessage());
                    } catch (Exception e) {
                        logger.warn("Scan request failed: " + e.getMessage());
                    }
                }), "index-scan");
        scan.setDaemon(true);
        scan.start();
        logger.info("Scan of all Elster indices started");
        return true;
    }

    public IndexScan getIndexScan() {
        return indexScan;
    }

    /**
     * Indices the service requests anyway, they are not part of the scan
     */
    public Set<Short> getAngefragt() {
        return Set.copyOf(angefragt);
    }

    /**
     * Requests all fields of the fault list
     */
    public synchronized void fehlerlisteAnfragen() throws USBtinException {
        fehlerlisteAngefragt = Instant.now();
        for (int feld = 0; feld < Fehlerliste.FELDER; feld++) {
            usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, Fehlerliste.KNOTEN,
                    (short) (Fehlerliste.ERSTES_FELD + feld)).getMessage());
        }
    }

    /**
     * True if all fields of the fault list were answered since the last request
     */
    public boolean fehlerlisteVollstaendig() {
        return fehlerlisteAngefragt != null && fehlerliste.vollstaendigSeit(fehlerlisteAngefragt);
    }

    public Instant getFehlerlisteAngefragt() {
        return fehlerlisteAngefragt;
    }

    /**
     * Latest answer for the index, null if none was received
     */
    public ElsterMessage nachricht(short index) {
        return msgs.get(index);
    }

    /**
     * Latest answer of the node for the index, null if none was received
     */
    public ElsterMessage nachricht(int knoten, short index) {
        return msgsKnoten.get(schluessel(knoten, index));
    }

    private static int schluessel(int knoten, short index) {
        return knoten << 16 | (index & 0xffff);
    }

    /**
     * Last message read along for the index, see MITLESEN
     */
    public ElsterMessage mitgelesen(short index) {
        return mitgelesen.get(index);
    }

    public Fehlerliste getFehlerliste() {
        return fehlerliste;
    }

    public CanStatistik getStatistik() {
        return canStatistik;
    }

    public String getPort() {
        return usbPort;
    }

    public int getBitrate() {
        return usbSpeed;
    }

    public void minuteAbschliessen() {
        canStatistik.minuteAbschliessen(Instant.now(), usbSpeed);
    }

    /**
     * Restarts the USB connection if no answer was received for 2 minutes, checked every 30 s. After a restart the
     * adapter gets the same 2 minutes again. While it stays silent, an alert is sent for the first restart and then
     * once an hour: an adapter that was pulled off would otherwise send a mail and a push message every 30 s.
     */
    @Scheduled(fixedRate = 30000)
    public synchronized void checkUSBisAlive() {
        Instant jetzt = Instant.now();
        long sekunden = Duration.between(letzteAntwort, jetzt).toSeconds();
        if (sekunden > MAX_STILLE.toSeconds()) {
            logger.error("Restarting USB, no message received for: " + sekunden + " s");
            neustartsSeitMeldung++;
            if (meldungFaellig(letzteMeldung, jetzt)) {
                emailService.sendAlert("Restarting USB", meldungstext(sekunden, neustartsSeitMeldung));
                letzteMeldung = jetzt;
                neustartsSeitMeldung = 0;
            }
            canStatistik.neustart(jetzt);
            stopUsbTin();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startUsbTin();
            letzteAntwort = Instant.now();
        }
    }

    /**
     * True if no alert about a USB restart was sent within the last hour
     */
    static boolean meldungFaellig(Instant letzteMeldung, Instant jetzt) {
        return letzteMeldung == null || !jetzt.isBefore(letzteMeldung.plus(MELDUNG_ABSTAND));
    }

    static String meldungstext(long sekunden, int neustarts) {
        String text = "Restarting USB, no message received for: " + sekunden + " s";
        return neustarts > 1 ? text + " (" + neustarts + " restarts since the last message)" : text;
    }

    /**
     * Value of the last complete minute of the CAN statistics
     */
    public ValueContainer<Double> minute(ToDoubleFunction<CanStatistik.Minute> wert) throws Exception {
        CanStatistik.Minute minute = canStatistik.getLetzteMinute();
        if (minute == null) {
            throw new Exception("NO_VALUES_RECEIVED");
        }
        return new ValueContainer<>(wert.applyAsDouble(minute), minute.ende());
    }

    public ValueContainer<Double> getUsbNeustarts() {
        return new ValueContainer<>((double) canStatistik.getNeustarts(), Instant.now());
    }

    /**
     * Seconds since the last answer to a request of this service
     */
    public ValueContainer<Double> getSekundenSeitAntwort() throws Exception {
        Instant antwort = canStatistik.getLetzteAntwort();
        if (antwort == null) {
            throw new Exception("NO_VALUES_RECEIVED");
        }
        Instant jetzt = Instant.now();
        return new ValueContainer<>((double) Duration.between(antwort, jetzt).toSeconds(), jetzt);
    }

    public ValueContainer<Boolean> isAdapterVerbunden() {
        return new ValueContainer<>(canStatistik.isVerbunden(), Instant.now());
    }

    /**
     * Name of the InfluxDB series with the messages per minute of a node
     */
    static String knotenSerie(int id) {
        return String.format("CAN_Knoten_%03X", id);
    }

    /**
     * State of the USBtin adapter and the CAN bus for the REST API
     */
    public record Verbindung(boolean verbunden, String port, int bitrate, String firmware, String hardware,
                             String seriennummer, Instant verbundenSeit, long usbNeustarts, Instant letzterNeustart,
                             Instant letzteNachricht, Instant letzteAntwort, CanStatistik.Minute letzteMinute,
                             List<CanStatistik.KnotenStatus> knoten, long adapterFehler, Instant letzterFehler,
                             String letzterFehlerText, boolean alleNachrichten) {
    }

    // Time the device gets for an answer before a request counts as unanswered
    static final Duration ANTWORTZEIT = Duration.ofSeconds(30);

    /**
     * A value requested by this service with its latest valid answer
     *
     * @param angefragt time of the last request
     * @param zeit      time of the latest valid answer, null if none arrived yet
     */
    public record Abfrage(short index, String name, String wert, Instant angefragt, Instant zeit, Instant vorher,
                          boolean mitgelesen) {

        public Abfrage(short index, String name, String wert, Instant angefragt, Instant zeit) {
            this(index, name, wert, angefragt, zeit, null, false);
        }

        /**
         * True if the last request got no valid answer within the answer time, or the request before got none until
         * the last one. The one before counts too: a value requested every 20 s has a last request younger than
         * 30 s all the time. Works for every request interval, the settings are requested only once an hour. Values
         * read along are never open.
         */
        public boolean offen(Instant jetzt) {
            if (mitgelesen) {
                return false;
            }
            boolean letzteOffen = angefragt != null && angefragt.isBefore(jetzt.minus(ANTWORTZEIT))
                    && unbeantwortet(angefragt);
            return letzteOffen || (vorher != null && unbeantwortet(vorher));
        }

        private boolean unbeantwortet(Instant anfrage) {
            return zeit == null || zeit.isBefore(anfrage);
        }
    }

    /**
     * True if the message is a write this service reads along, with a valid value
     */
    static boolean mitlesen(ElsterMessage m) {
        if (!(m.isWrite() || m.isResponse()) || m.getRawValue() == null || m.getRawValue() == VALUE_NOT_AVAILABLE) {
            return false;
        }
        short index = m.getElsterIndex().getIndex();
        boolean antwort = m.isResponse();
        return MITLESEN.stream().anyMatch(l -> l.von() == m.getId() && l.an() == m.getReceiverId()
                && l.index() == index && l.antwort() == antwort);
    }

    /**
     * All requested values and the values read along, the unanswered first, then by name
     */
    public List<Abfrage> getAbfragen() {
        Instant jetzt = Instant.now();
        List<Abfrage> abfragen = new java.util.ArrayList<>(angefragt.stream().map(index -> {
            ElsterMessage m = msgs.get(index);
            return new Abfrage(index, TABELLE.findByIndex(index).getName(), m == null ? null : m.getValue(),
                    angefragtUm.get(index), m == null ? null : m.getTimestamp(), vorherAngefragt.get(index), false);
        }).toList());
        for (Mitlesen l : MITLESEN) {
            ElsterMessage m = mitgelesen.get(l.index());
            abfragen.add(new Abfrage(l.index(), TABELLE.findByIndex(l.index()).getName(),
                    m == null ? null : m.getValue(), null, m == null ? null : m.getTimestamp(), null, true));
        }
        abfragen.sort(Comparator.comparing((Abfrage a) -> !a.offen(jetzt)).thenComparing(a -> IndexBeschreibung.anzeigename(a.index(), a.name())));
        return abfragen;
    }

    public Verbindung getVerbindung() {
        return new Verbindung(canStatistik.isVerbunden(), usbPort, usbSpeed, canStatistik.getFirmware(),
                canStatistik.getHardware(), canStatistik.getSeriennummer(), canStatistik.getVerbundenSeit(),
                canStatistik.getNeustarts(), canStatistik.getLetzterNeustart(), canStatistik.getLetzteNachricht(),
                canStatistik.getLetzteAntwort(), canStatistik.getLetzteMinute(), canStatistik.getKnoten(),
                canStatistik.getFehler(), canStatistik.getLetzterFehler(), canStatistik.getLetzterFehlerText(), logall);
    }
}
