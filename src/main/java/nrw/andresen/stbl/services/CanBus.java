package nrw.andresen.stbl.services;

import de.fischl.usbtin.FilterChain;
import de.fischl.usbtin.FilterMask;
import de.fischl.usbtin.FilterValue;
import de.fischl.usbtin.USBtin;
import de.fischl.usbtin.USBtinException;
import jakarta.annotation.PreDestroy;
import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterMessage;
import nrw.andresen.stbl.services.can.Fehlerliste;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

import static nrw.andresen.stbl.services.can.ElsterTable.BETRIEBS_STATUS;

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

    private final Logger logger = LoggerFactory.getLogger(CanBus.class);
    @Autowired
    private EmailService emailService;
    private USBtin usbtin;
    // Latest answer per index; the answers carry only the index, so an index is requested from one node only
    private final Map<Short, ElsterMessage> msgs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ElsterMessage> queue = new ConcurrentLinkedQueue<>();
    // Indices requested by this service, answers to other indices are ignored
    private final Set<Short> angefragt = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant letzteAntwort;
    // Messages on the bus and state of the USBtin adapter, kept over USB restarts
    private final CanStatistik canStatistik = new CanStatistik();
    // Fault list of the WPM, its fields are kept apart from the other values
    private final Fehlerliste fehlerliste = new Fehlerliste();
    // Time of the last request of the fault list, null before the first one
    private volatile Instant fehlerlisteAngefragt;
    private final String usbPort;
    private final int usbSpeed;
    private final boolean logall;

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
            if (!logall) {
                usbtin.setFilter(new FilterChain[]{
                        new FilterChain(
                                new FilterMask(0x000, (byte) 0xf0, (byte) 0xff),
                                new FilterValue[]{
                                        new FilterValue(0x000, CAN_SENDER_ID_BYTE_CODED[0], CAN_SENDER_ID_BYTE_CODED[1])
                                }
                        )
                });
            } else {
                usbtin.setFilter(null);
            }

            usbtin.addMessageListener(canmsg -> {
                try {
                    Instant jetzt = Instant.now();
                    canStatistik.empfangen(canmsg.getId(), canmsg.getData().length, jetzt);
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
            logger.error("ERROR During initialization: ", e);
        }
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
        logger.debug(elsterMessage + " INDEX: " + elsterMessage.getElsterIndex().getName() +
                " Payload: " + elsterMessage.getValue());
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
            usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, knoten, index).getMessage());
        }
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
     * Restarts the USB connection if no answer was received for 2 minutes, checked every 30 s
     */
    @Scheduled(fixedRate = 30000)
    public synchronized void checkUSBisAlive() {
        long sekunden = Duration.between(letzteAntwort, Instant.now()).toSeconds();
        if (sekunden > MAX_STILLE.toSeconds()) {
            logger.error("Restarting USB, no message received for: " + sekunden + " s");
            emailService.sendAlert("Restarting USB", "Restarting USB, no message received for: " + sekunden + " s");
            canStatistik.neustart(Instant.now());
            stopUsbTin();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startUsbTin();
        }
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
                             List<CanStatistik.KnotenStatus> knoten) {
    }

    public Verbindung getVerbindung() {
        return new Verbindung(canStatistik.isVerbunden(), usbPort, usbSpeed, canStatistik.getFirmware(),
                canStatistik.getHardware(), canStatistik.getSeriennummer(), canStatistik.getVerbundenSeit(),
                canStatistik.getNeustarts(), canStatistik.getLetzterNeustart(), canStatistik.getLetzteNachricht(),
                canStatistik.getLetzteAntwort(), canStatistik.getLetzteMinute(), canStatistik.getKnoten());
    }
}
