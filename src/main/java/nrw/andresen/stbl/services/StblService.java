package nrw.andresen.stbl.services;

import com.influxdb.client.write.Point;
import de.fischl.usbtin.FilterChain;
import de.fischl.usbtin.FilterMask;
import de.fischl.usbtin.FilterValue;
import de.fischl.usbtin.USBtin;
import de.fischl.usbtin.USBtinException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterBetriebsstatus;
import nrw.andresen.stbl.services.can.ElsterMessage;
import nrw.andresen.stbl.services.can.SynchronizedUSBtin;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

import static nrw.andresen.stbl.services.can.ElsterTable.*;


/**
 * Main Service handling heartbeat
 */
@Component
public class StblService {

    @Autowired
    private EmailService emailService;
    @Autowired
    private InfluxController influxController;
    private Logger logger = LoggerFactory.getLogger(StblService.class);
    private USBtin usbtin;
    private Map<Short, ElsterMessage> msgs = new ConcurrentHashMap<>();
    private ConcurrentLinkedQueue<ElsterMessage> concurrentLinkedQueue = new ConcurrentLinkedQueue<>();
    public static int CAN_SENDER_ID = 0x681;
    private static byte[] CAN_SENDER_ID_BYTE_CODED = ElsterMessage.convertReceiverID(CAN_SENDER_ID);
    // Payload sent by a device which does not provide the requested index
    private static short VALUE_NOT_AVAILABLE = (short) 0x8000;
    // Specific heat capacity of water in kJ/(kg*K)
    private static double WAERMEKAPAZITAET_WASSER = 4.19;
    // Real power = WIRKLEISTUNG_STEIGUNG * apparent power - WIRKLEISTUNG_OFFSET_W, fitted from about 2300
    // compressor starts and stops in the house meter 2024 and 2025. The share rises with the load.
    private static double WIRKLEISTUNG_STEIGUNG = 1.24;
    private static double WIRKLEISTUNG_OFFSET_W = 270;
    // Limits of the share, below 500 VA there is no data, above 1000 VA the real power reaches the apparent power
    private static double WIRKLEISTUNGSANTEIL_MIN = 0.7;
    private static double WIRKLEISTUNGSANTEIL_MAX = 1.0;
    // The electric counter of the heat pump counts 0.83 (2024) and 0.85 (2025) of the real energy
    private static double STROMZAEHLER_KORREKTUR = 1.2;
    // Values older than two request cycles are not stored
    private static Duration MAX_AGE_20 = Duration.ofSeconds(45);
    private static Duration MAX_AGE_60 = Duration.ofSeconds(130);
    private static Duration MAX_AGE_3600 = Duration.ofMinutes(130);
    // Heat output and efficiency are calculated only after the start phase, while starting the compressor runs up to
    // 40 Hz, the pump already runs and the single values do not fit together
    private static Duration ANLAUFZEIT = Duration.ofSeconds(60);
    // Defrost by reversing the cycle (seen 2026-09-25): the EXV opens to 100 % (about 20 % while heating), then the
    // compressor runs about 40 s with 50 Hz and heats the evaporator above the flow temperature. Heat is taken from
    // the heating water and the pump runs with 30 l/min, the calculated heat output is meaningless.
    private static double ABTAUUNG_EXV_PROZENT = 90;
    // The EXV also opens to 100 % for a few seconds at every start after a long standstill (seen 2026-09-25/26), it
    // means defrost only if the compressor stopped shortly before (1.2 and 1.3 min at the defrosts on 2026-09-25)
    private static Duration ABTAUUNG_MAX_STILLSTAND = Duration.ofMinutes(5);
    // While defrosting the evaporator is 15 K above the flow, at a start after standstill it can be about as warm as
    // the flow (3 K below on 2026-09-26 06:00)
    private static double ABTAUUNG_VERDAMPFER_UEBER_VORLAUF_K = 5;
    // Heat output and efficiency are not calculated during the defrost and this time after it
    private static Duration ABTAUUNG_NACHLAUF = Duration.ofSeconds(60);
    // The four parts of an energy counter are answered within milliseconds; parts from different request cycles do not
    // fit together, e.g. the new sum and the old day value at midnight would add the day twice
    private static Duration ZAEHLER_GLEICHZEITIG = Duration.ofSeconds(10);
    // Heat pump as seen by the manager and as external access, the latter also provides counters and settings
    private static int WAERMEPUMPE = 0x500;
    private static int HEIZMODUL = 0x514;
    private static short[] BETRIEBSWERTE_HEIZMODUL = {
            LZ_VERDICHTER_HEIZEN, LZ_VERDICHTER_ABTAUEN, ZEIT_LETZTE_ABTAUUNG, VERDICHTER_STARTS_K, VERDICHTER_STARTS,
            SOLLDREHZAHL_VERDICHTER, SOLL_UEBERHITZUNG, IST_UEBERHITZUNG,
            ISTDREHZAHL_LUEFTER, SOLLDREHZAHL_LUEFTER, UMGEBUNGSTEMPERATUR_INVERTER, TEMPERATUR_INV_VERDICHTER
    };
    private static short[] EINSTELLUNGEN_HEIZMODUL = {
            AUSLEGUNGSTEMPERATUR, WAERMEBEDARF, SOLLSPREIZUNG, BIVALENZTEMPERATUR_HZG, EINSATZGRENZE_HZG,
            SILENT_LEISTUNG, SILENT_LUEFTER
    };
    private static Set<Short> REQUESTED_INDICES = Set.of(
            BETRIEBS_STATUS,
            EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH,
            EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH,
            WAERMEERTRAG_HEIZ_SUM_KWH,
            WAERMEERTRAG_HEIZ_SUM_MWH,
            EL_AUFNAHMELEISTUNG_HEIZ_TAG_KWH,
            EL_AUFNAHMELEISTUNG_HEIZ_TAG_WH,
            WAERMEERTRAG_HEIZ_TAG_KWH,
            WAERMEERTRAG_HEIZ_TAG_WH,
            TEST_OBJEKT_113_STROM_INVERTER,
            TEST_OBJEKT_112_SPANNUNG_INVERTER,
            WPVORLAUFIST,
            ANZEIGE_HOCHDRUCK,
            ANZEIGE_NIEDERDRUCK,
            ANZEIGE_HEIZUNGSDRUCK,
            LAUFZEIT_DHC1,
            LAUFZEIT_DHC2,
            LAUFZEIT_DHC12,
            RUECKLAUFISTTEMP,
            AUSSENTEMP,
            HEISSGAS_TEMP,
            VERDICHTER_DREHZAHL,
            VERDICHTER_EINTRITTSTEMP,
            VERDAMPFERTEMP,
            OELSUMPFTEMP,
            WP_WASSERVOLUMENSTROM,
            WAERMEERTRAG_2WE_HEIZ_TAG_WH,
            WAERMEERTRAG_2WE_HEIZ_TAG_KWH,
            WAERMEERTRAG_2WE_HEIZ_SUM_KWH,
            WAERMEERTRAG_2WE_HEIZ_SUM_MWH,
            LZ_VERDICHTER_HEIZEN,
            LZ_VERDICHTER_ABTAUEN,
            ZEIT_LETZTE_ABTAUUNG,
            VERDICHTER_STARTS_K,
            VERDICHTER_STARTS,
            SOLLDREHZAHL_VERDICHTER,
            SOLL_UEBERHITZUNG,
            IST_UEBERHITZUNG,
            OEFFNUNGSGRAD_EXV,
            ISTDREHZAHL_LUEFTER,
            SOLLDREHZAHL_LUEFTER,
            LUEFTERLEISTUNG_REL,
            UMGEBUNGSTEMPERATUR_INVERTER,
            TEMPERATUR_INV_VERDICHTER,
            AUSLEGUNGSTEMPERATUR,
            WAERMEBEDARF,
            SOLLSPREIZUNG,
            BIVALENZTEMPERATUR_HZG,
            EINSATZGRENZE_HZG,
            SILENT_LEISTUNG,
            SILENT_LUEFTER
    );
    private AtomicBoolean running = new AtomicBoolean(false);
    // Time of the first received compressor speed above 0, null while the compressor stands still
    private volatile Instant verdichterLaeuftSeit;
    // Time of the last stop of the compressor, null if no stop was seen since the start of the service
    private volatile Instant verdichterGestopptUm;
    // Time of the last 20 s cycle with defrost signs, null if none seen since the start of the service
    private volatile Instant letzteAbtauung;
    // Highest energy per counter (key: index of the MWh counter), the reported sum may fall, see getEnergie
    private Map<Short, Double> hoechsteEnergie = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastMsgReceived;
    // Messages on the bus and state of the USBtin adapter, kept over USB restarts
    private final CanStatistik canStatistik = new CanStatistik();
    private String usbPort;
    private int usbSpeed;
    private boolean logall;

    public StblService(@Value("${USBtin.port}") String usbPort, @Value("${USBtin.speed}") int usbSpeed, @Value("${can.logall}") boolean logall) throws USBtinException {
        this.usbPort = usbPort;
        this.usbSpeed = usbSpeed;
        this.logall = logall;
        this.lastMsgReceived = LocalDateTime.now();
        startUsbTin();
    }

    private void startUsbTin(){
        try {
            running.set(true);
            Thread consumer = new Thread(new Consumer(concurrentLinkedQueue));
            consumer.start();

            usbtin = new SynchronizedUSBtin(canStatistik);
            usbtin.connect(usbPort);
            if (!logall) {
                usbtin.setFilter(new FilterChain[]{
                        new FilterChain(
                                new FilterMask(0x000, (byte) 0xf0, (byte) 0xff),
                                new FilterValue[]{
                                        new FilterValue(0x000,  CAN_SENDER_ID_BYTE_CODED[0], CAN_SENDER_ID_BYTE_CODED[1])
                                }
                        )
                });

            }else{
                usbtin.setFilter(null);
            }

            usbtin.addMessageListener(canmsg -> {
                try {
                    Instant jetzt = Instant.now();
                    canStatistik.empfangen(canmsg.getId(), canmsg.getData().length, jetzt);
                    ElsterMessage elsterMessage = new ElsterMessage(canmsg);
                    short index = elsterMessage.getElsterIndex().getIndex();
                    if (REQUESTED_INDICES.contains(index) && elsterMessage.isResponse()
                            && elsterMessage.getReceiverId() == CAN_SENDER_ID) {
                        lastMsgReceived = LocalDateTime.now();
                        Short rawValue = elsterMessage.getRawValue();
                        canStatistik.antwort(rawValue != null && index != BETRIEBS_STATUS
                                && rawValue == VALUE_NOT_AVAILABLE, jetzt);
                        // 0x8000 is a valid Betriebsstatus (EVU Sperre), for all other values it means not available
                        if (rawValue != null && (index == BETRIEBS_STATUS || rawValue != VALUE_NOT_AVAILABLE)) {
                            concurrentLinkedQueue.add(elsterMessage);
                        }
                    }

                    logger.debug(elsterMessage.toString() + " INDEX: " + elsterMessage.getElsterIndex().getName() +
                            " Payload: " + elsterMessage.getValue());


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

    public void stopUsbTin(){
        try {
            running.set(false);
            canStatistik.getrennt();
            logger.info("Shutting down StblService");
            usbtin.closeCANChannel();
            usbtin.disconnect();
        } catch (Exception e) {
            logger.error("Error during shutdown. ", e);
        }
    }

    // the consumer removes strings from the queue
    class Consumer implements Runnable {

        ConcurrentLinkedQueue<ElsterMessage> queue;
        Consumer(ConcurrentLinkedQueue<ElsterMessage> queue){
            this.queue = queue;
        }
        public void run() {
            ElsterMessage msg;
            while (running.get()){
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


    @PostConstruct
    public void init() {

    }

    @PreDestroy
    public void destroy() {
        stopUsbTin();
    }

    public ValueContainer<Short> getBetriebstatus() {
        ElsterMessage betriebsstatus = msgs.get(BETRIEBS_STATUS);
        if (betriebsstatus == null) {
            return new ValueContainer<>((short) 0, "NO_VALUE", Instant.MIN);
        }
        // Payload is formatted as decimal, so the bit field has to be taken from the raw value
        return new ValueContainer<>(betriebsstatus.getRawValue(),
                betriebsstatus.getValue(), betriebsstatus.getTimestamp());
    }


    private ValueContainer<Boolean> isBetriebsstatusSet(ElsterBetriebsstatus flag) throws Exception {
        ElsterMessage betriebsstatus = msgs.get(BETRIEBS_STATUS);
        if (betriebsstatus == null) {
            throw new Exception("NO_VALUES_REVEIVED");
        }

        return new ValueContainer<>(
                (betriebsstatus.getRawValue() & flag.id) == flag.id,
                betriebsstatus.getTimestamp()
        );
    }

    private ValueContainer<Boolean> isVerdichterOn() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.verdichter_1);
    }

    private ValueContainer<Boolean> isPufferladepumpeOn() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.pufferladepumpe_1);
    }

    private ValueContainer<Boolean> isWarmwasserladepumpe() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.warmwasserladepumpe);
    }

    private ValueContainer<Boolean> isDHC_1On() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.dhc_1);
    }

    private ValueContainer<Boolean> isDHC_2On() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.dhc_2);
    }

    private ValueContainer<Boolean> isEvuSperre() throws Exception {
        return isBetriebsstatusSet(ElsterBetriebsstatus.evu_sperre);
    }


    /**
     * Energy in MWh, the sum counters do not contain the values of the current day. The value never falls: at midnight
     * the heat pump adds only the whole kWh of the day to the sum counter and drops the remaining Wh (e.g. 2026-09-23
     * 111.938458 -> 111.938 MWh), during the defrost the heat counter falls by a few Wh. The highest value is kept
     * until the counters exceed it again, it is lost on a restart. Parts from different request cycles are not used,
     * a too high value would be kept.
     */
    private ValueContainer<Double> getEnergie(short sumMWH, short sumKWH, short tagKWH, short tagWH) throws Exception {
        ElsterMessage elsterMessageMWH = msgs.get(sumMWH);
        ElsterMessage elsterMessageKWH = msgs.get(sumKWH);
        ElsterMessage elsterMessageTagKWH = msgs.get(tagKWH);
        ElsterMessage elsterMessageTagWH = msgs.get(tagWH);
        if (elsterMessageMWH == null || elsterMessageKWH == null || elsterMessageTagKWH == null || elsterMessageTagWH == null) {
            throw new Exception("NO_VALUES_REVEIVED");
        }

        List<Instant> zeiten = Arrays.asList(elsterMessageKWH.getTimestamp(), elsterMessageMWH.getTimestamp(),
                elsterMessageTagKWH.getTimestamp(), elsterMessageTagWH.getTimestamp());
        if (!gleichzeitig(zeiten)) {
            throw new Exception("ZAEHLER_UNVOLLSTAENDIG");
        }

        Integer summeMWH =  Integer.parseInt(elsterMessageMWH.getValue());
        Integer summeKWH =  Integer.parseInt(elsterMessageKWH.getValue());
        Integer tagesKWH =  Integer.parseInt(elsterMessageTagKWH.getValue());
        Integer tagesWH =  Integer.parseInt(elsterMessageTagWH.getValue());

        double summe = hoechsteEnergie.merge(sumMWH, energie(summeMWH, summeKWH, tagesKWH, tagesWH), Math::max);

        return new ValueContainer<>(summe, Collections.min(zeiten));
    }

    static boolean gleichzeitig(List<Instant> zeiten) {
        return Duration.between(Collections.min(zeiten), Collections.max(zeiten)).compareTo(ZAEHLER_GLEICHZEITIG) <= 0;
    }

    static double energie(int summeMWH, int summeKWH, int tagesKWH, int tagesWH) {
        return summeMWH + (summeKWH/1000d) + (tagesKWH/1000d) + (tagesWH/(1000d*1000d));
    }

    public ValueContainer<Double> getAufnahmeLeistung() throws Exception {
        return getEnergie(EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH, EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH,
                EL_AUFNAHMELEISTUNG_HEIZ_TAG_KWH, EL_AUFNAHMELEISTUNG_HEIZ_TAG_WH);
    }

    public ValueContainer<Double> getAbgabeWaerme() throws Exception {
        return getEnergie(WAERMEERTRAG_HEIZ_SUM_MWH, WAERMEERTRAG_HEIZ_SUM_KWH,
                WAERMEERTRAG_HEIZ_TAG_KWH, WAERMEERTRAG_HEIZ_TAG_WH);
    }

    public ValueContainer<Double> getWaermeZusatzheizung() throws Exception {
        return getEnergie(WAERMEERTRAG_2WE_HEIZ_SUM_MWH, WAERMEERTRAG_2WE_HEIZ_SUM_KWH,
                WAERMEERTRAG_2WE_HEIZ_TAG_KWH, WAERMEERTRAG_2WE_HEIZ_TAG_WH);
    }


    /**
     * Efficiency with the electric counter corrected, it counts about 20 % too little
     */
    public ValueContainer<Double> getEffizienzKorrigiert() throws Exception {
        ValueContainer<Double> abgabeWaerme = getAbgabeWaerme();
        ValueContainer<Double> aufnahmeLeistung = getAufnahmeLeistung();
        return new ValueContainer<>(abgabeWaerme.getValue() / (aufnahmeLeistung.getValue() * STROMZAEHLER_KORREKTUR),
                Collections.min(Arrays.asList(abgabeWaerme.getTimestamp(), aufnahmeLeistung.getTimestamp())));
    }

    private ValueContainer<Double> getEffezienz() throws Exception {
        ValueContainer<Double> abgabeWaerme = getAbgabeWaerme();
        ValueContainer<Double> aufnahmeLeistung = getAufnahmeLeistung();
        return new ValueContainer<>(abgabeWaerme.getValue() / aufnahmeLeistung.getValue(),
                Collections.min(Arrays.asList(abgabeWaerme.getTimestamp(), aufnahmeLeistung.getTimestamp())));
    }

    private ValueContainer<Double> getDecimalValue(short index) throws Exception {
        ElsterMessage msg = msgs.get(index);
        if (msg == null) {
            throw new Exception("NO_VALUES_REVEIVED");
        }

        Double value = Double.parseDouble(msg.getValue().replace(",", "."));

        return new ValueContainer<>(value, msg.getTimestamp());
    }

    public ValueContainer<Double> getStromInverter() throws Exception {
        return getDecimalValue(TEST_OBJEKT_113_STROM_INVERTER);
    }

    public ValueContainer<Double> getVorlaufIstTemp() throws Exception {
        return getDecimalValue(WPVORLAUFIST);
    }

    public ValueContainer<Double> getRuecklaufIstTemp() throws Exception {
        return getDecimalValue(RUECKLAUFISTTEMP);
    }

    public ValueContainer<Double> getSpannungInverter() throws Exception {
        return getDecimalValue(TEST_OBJEKT_112_SPANNUNG_INVERTER);
    }

    public ValueContainer<Double> getLeistungInverter() throws Exception {
        ValueContainer<Double> spannungInverter = getSpannungInverter();
        ValueContainer<Double> stromInverter = getStromInverter();
        return new ValueContainer<>(spannungInverter.getValue() * stromInverter.getValue(),
                Collections.min(Arrays.asList(spannungInverter.getTimestamp(), stromInverter.getTimestamp())));
    }

    public ValueContainer<Double> getSpreizung() throws Exception {
        ValueContainer<Double> vorlauf = getVorlaufIstTemp();
        ValueContainer<Double> ruecklauf = getRuecklaufIstTemp();
        return new ValueContainer<>(vorlauf.getValue() - ruecklauf.getValue(),
                Collections.min(Arrays.asList(vorlauf.getTimestamp(), ruecklauf.getTimestamp())));
    }

    public ValueContainer<Double> getHeizungsdruck() throws Exception {
        return getDecimalValue(ANZEIGE_HEIZUNGSDRUCK);
    }

    public ValueContainer<Double> getLaufzeit_DHC1() throws Exception {
        return getDecimalValue(LAUFZEIT_DHC1);
    }

    public ValueContainer<Double> getLaufzeit_DHC2() throws Exception {
        return getDecimalValue(LAUFZEIT_DHC2);
    }

    public ValueContainer<Double> getLaufzeit_DHC12() throws Exception {
        return getDecimalValue(LAUFZEIT_DHC12);
    }

    public ValueContainer<Double> getHochdruck() throws Exception {
        return getDecimalValue(ANZEIGE_HOCHDRUCK);
    }

    public ValueContainer<Double> getNiederdruck() throws Exception {
        return getDecimalValue(ANZEIGE_NIEDERDRUCK);
    }

    public ValueContainer<Double> getAussentemp() throws Exception {
        return getDecimalValue(AUSSENTEMP);
    }

    public ValueContainer<Double> getHeissgasTemp() throws Exception {
        return getDecimalValue(HEISSGAS_TEMP);
    }

    /**
     * Value from the raw payload, for indices whose type in ElsterTable is unknown or wrong
     */
    private ValueContainer<Double> getScaledValue(short index, double divisor) throws Exception {
        ElsterMessage msg = msgs.get(index);
        if (msg == null) {
            throw new Exception("NO_VALUES_REVEIVED");
        }
        return new ValueContainer<>(msg.getRawValue() / divisor, msg.getTimestamp());
    }

    public ValueContainer<Double> getVerdichterDrehzahl() throws Exception {
        return getScaledValue(VERDICHTER_DREHZAHL, 1);
    }

    public ValueContainer<Double> getVerdichterEintrittstemp() throws Exception {
        return getScaledValue(VERDICHTER_EINTRITTSTEMP, 10);
    }

    public ValueContainer<Double> getVerdampferTemp() throws Exception {
        return getScaledValue(VERDAMPFERTEMP, 10);
    }

    public ValueContainer<Double> getOelsumpfTemp() throws Exception {
        return getScaledValue(OELSUMPFTEMP, 10);
    }

    public ValueContainer<Double> getVolumenstrom() throws Exception {
        return getScaledValue(WP_WASSERVOLUMENSTROM, 10);
    }

    public ValueContainer<Double> getLaufzeitVerdichterHeizen() throws Exception {
        return getScaledValue(LZ_VERDICHTER_HEIZEN, 1);
    }

    public ValueContainer<Double> getLaufzeitVerdichterAbtauen() throws Exception {
        return getScaledValue(LZ_VERDICHTER_ABTAUEN, 1);
    }

    public ValueContainer<Double> getDauerLetzteAbtauung() throws Exception {
        return getScaledValue(ZEIT_LETZTE_ABTAUUNG, 1);
    }

    public ValueContainer<Double> getVerdichterStarts() throws Exception {
        ValueContainer<Double> tausender = getScaledValue(VERDICHTER_STARTS_K, 1);
        ValueContainer<Double> rest = getScaledValue(VERDICHTER_STARTS, 1);
        return new ValueContainer<>(tausender.getValue() * 1000 + rest.getValue(),
                Collections.min(Arrays.asList(tausender.getTimestamp(), rest.getTimestamp())));
    }

    /**
     * Average compressor runtime per start in h since commissioning
     */
    public ValueContainer<Double> getLaufzeitProStart() throws Exception {
        ValueContainer<Double> laufzeit = getLaufzeitVerdichterHeizen();
        ValueContainer<Double> starts = getVerdichterStarts();
        if (starts.getValue() <= 0) {
            throw new Exception("KEINE_STARTS");
        }
        return new ValueContainer<>(laufzeit.getValue() / starts.getValue(),
                Collections.min(Arrays.asList(laufzeit.getTimestamp(), starts.getTimestamp())));
    }

    public ValueContainer<Double> getVerdichterSollDrehzahl() throws Exception {
        return getScaledValue(SOLLDREHZAHL_VERDICHTER, 1);
    }

    public ValueContainer<Double> getSollUeberhitzung() throws Exception {
        return getScaledValue(SOLL_UEBERHITZUNG, 10);
    }

    public ValueContainer<Double> getIstUeberhitzung() throws Exception {
        return getScaledValue(IST_UEBERHITZUNG, 10);
    }

    public ValueContainer<Double> getOeffnungsgradExv() throws Exception {
        return getScaledValue(OEFFNUNGSGRAD_EXV, 10);
    }

    public ValueContainer<Double> getLuefterIstDrehzahl() throws Exception {
        return getScaledValue(ISTDREHZAHL_LUEFTER, 1);
    }

    public ValueContainer<Double> getLuefterSollDrehzahl() throws Exception {
        return getScaledValue(SOLLDREHZAHL_LUEFTER, 1);
    }

    public ValueContainer<Double> getLuefterLeistung() throws Exception {
        return getScaledValue(LUEFTERLEISTUNG_REL, 1);
    }

    public ValueContainer<Double> getUmgebungstempInverter() throws Exception {
        return getScaledValue(UMGEBUNGSTEMPERATUR_INVERTER, 10);
    }

    public ValueContainer<Double> getTempInverterVerdichter() throws Exception {
        return getScaledValue(TEMPERATUR_INV_VERDICHTER, 10);
    }

    public ValueContainer<Double> getAuslegungstemperatur() throws Exception {
        return getScaledValue(AUSLEGUNGSTEMPERATUR, 10);
    }

    public ValueContainer<Double> getWaermebedarf() throws Exception {
        return getScaledValue(WAERMEBEDARF, 10);
    }

    public ValueContainer<Double> getSollSpreizung() throws Exception {
        return getScaledValue(SOLLSPREIZUNG, 10);
    }

    public ValueContainer<Double> getBivalenztemperatur() throws Exception {
        return getScaledValue(BIVALENZTEMPERATUR_HZG, 10);
    }

    public ValueContainer<Double> getEinsatzgrenzeHeizen() throws Exception {
        return getScaledValue(EINSATZGRENZE_HZG, 10);
    }

    public ValueContainer<Double> getSilentLeistung() throws Exception {
        return getScaledValue(SILENT_LEISTUNG, 1);
    }

    public ValueContainer<Double> getSilentLuefter() throws Exception {
        return getScaledValue(SILENT_LUEFTER, 1);
    }

    /**
     * Heat output calculated from flow rate and temperature difference
     */
    public ValueContainer<Double> getWaermeleistung() throws Exception {
        ValueContainer<Double> drehzahl = getVerdichterDrehzahl();
        if (drehzahl.getValue() <= 0) {
            return new ValueContainer<>(0d, drehzahl.getTimestamp());
        }
        if (!nachAnlauf(verdichterLaeuftSeit, Instant.now())) {
            throw new Exception("VERDICHTER_STARTET");
        }
        if (inAbtauung(letzteAbtauung, Instant.now())) {
            throw new Exception("ABTAUUNG");
        }
        ValueContainer<Double> volumenstrom = getVolumenstrom();
        // The flow rate is requested only every 60 s and can still be 0 from before the start
        if (volumenstrom.getValue() <= 0) {
            throw new Exception("KEIN_VOLUMENSTROM");
        }
        ValueContainer<Double> spreizung = getSpreizung();
        return new ValueContainer<>(waermeleistung(volumenstrom.getValue(), spreizung.getValue()),
                Collections.min(Arrays.asList(volumenstrom.getTimestamp(), spreizung.getTimestamp())));
    }

    /**
     * True if the compressor runs longer than the start phase
     */
    static boolean nachAnlauf(Instant laeuftSeit, Instant jetzt) {
        return laeuftSeit != null && !Duration.between(laeuftSeit, jetzt).minus(ANLAUFZEIT).isNegative();
    }

    /**
     * True if the values show a defrost: EXV (almost) fully open, or the evaporator warmer than the flow while the
     * compressor runs
     */
    /**
     * @param stillstand standstill before the current run or up to now, null if unknown
     */
    static boolean abtauung(double exvProzent, double verdampferTemp, double vorlaufTemp, double drehzahlHz,
                            Duration stillstand) {
        boolean kurzGestoppt = stillstand != null && stillstand.compareTo(ABTAUUNG_MAX_STILLSTAND) <= 0;
        return (exvProzent >= ABTAUUNG_EXV_PROZENT && kurzGestoppt)
                || (drehzahlHz > 0 && verdampferTemp > vorlaufTemp + ABTAUUNG_VERDAMPFER_UEBER_VORLAUF_K);
    }

    static Duration stillstand(Instant laeuftSeit, Instant gestopptUm, Instant jetzt) {
        if (gestopptUm == null) {
            return null;
        }
        return Duration.between(gestopptUm, laeuftSeit != null ? laeuftSeit : jetzt);
    }

    /**
     * True during the defrost and shortly after it
     */
    static boolean inAbtauung(Instant letzteAbtauung, Instant jetzt) {
        return letzteAbtauung != null && Duration.between(letzteAbtauung, jetzt).compareTo(ABTAUUNG_NACHLAUF) < 0;
    }

    /**
     * Remembers the last defrost, from the latest received values
     */
    private void updateAbtauung() {
        try {
            if (abtauung(getOeffnungsgradExv().getValue(), getVerdampferTemp().getValue(),
                    getVorlaufIstTemp().getValue(), getVerdichterDrehzahl().getValue(),
                    stillstand(verdichterLaeuftSeit, verdichterGestopptUm, Instant.now()))) {
                letzteAbtauung = Instant.now();
            }
        } catch (Exception e) {
            // values missing, no decision possible
        }
    }

    private ValueContainer<Boolean> isAbtauung() throws Exception {
        ValueContainer<Double> exv = getOeffnungsgradExv();
        return new ValueContainer<>(inAbtauung(letzteAbtauung, Instant.now()), exv.getTimestamp());
    }

    public ValueContainer<Double> getAbtauung() throws Exception {
        ValueContainer<Boolean> abtauung = isAbtauung();
        return new ValueContainer<>(abtauung.getValue() ? 1d : 0d, abtauung.getTimestamp());
    }

    /**
     * Remembers when the compressor started, from the latest received speed
     */
    private void updateVerdichterLaeuftSeit() {
        try {
            ValueContainer<Double> drehzahl = getVerdichterDrehzahl();
            if (drehzahl.getValue() <= 0) {
                if (verdichterLaeuftSeit != null) {
                    verdichterGestopptUm = drehzahl.getTimestamp();
                }
                verdichterLaeuftSeit = null;
            } else if (verdichterLaeuftSeit == null) {
                verdichterLaeuftSeit = drehzahl.getTimestamp();
            }
        } catch (Exception e) {
            verdichterLaeuftSeit = null;
        }
    }

    /**
     * Heat output in kW from flow rate in l/min and temperature difference in K
     */
    static double waermeleistung(double volumenstromLproMin, double spreizungK) {
        return volumenstromLproMin / 60d * WAERMEKAPAZITAET_WASSER * spreizungK;
    }

    /**
     * Estimated real power in W from the apparent power of the inverter in VA
     */
    static double wirkleistung(double scheinleistungVA) {
        if (scheinleistungVA <= 0) {
            return 0;
        }
        double anteil = WIRKLEISTUNG_STEIGUNG - WIRKLEISTUNG_OFFSET_W / scheinleistungVA;
        anteil = Math.max(WIRKLEISTUNGSANTEIL_MIN, Math.min(WIRKLEISTUNGSANTEIL_MAX, anteil));
        return scheinleistungVA * anteil;
    }

    /**
     * Estimated efficiency, calculated heat output divided by the estimated real power of the inverter
     */
    public ValueContainer<Double> getArbeitszahl() throws Exception {
        if (!nachAnlauf(verdichterLaeuftSeit, Instant.now())) {
            throw new Exception("VERDICHTER_AUS");
        }
        ValueContainer<Double> waermeleistung = getWaermeleistung();
        ValueContainer<Double> scheinleistung = getLeistungInverter();
        double wirkleistung = wirkleistung(scheinleistung.getValue());
        if (wirkleistung <= 0) {
            throw new Exception("VERDICHTER_AUS");
        }
        return new ValueContainer<>(waermeleistung.getValue() * 1000d / wirkleistung,
                Collections.min(Arrays.asList(waermeleistung.getTimestamp(), scheinleistung.getTimestamp())));
    }



    private synchronized void requestBetriebsstatus() throws USBtinException {
        //0x0176
        String STR_BETRIEBS_STATUS = "3100fa01760000";
        ElsterMessage elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_BETRIEBS_STATUS);
        usbtin.send(elsterMessage.getMessage());

        // public static short ANZEIGE_HEIZUNGSDRUCK = (short)0x0674;
        //t10073100fa06740000
        //t18072200fa067400d2 INDEX: HEIZUNGSDRUCK Payload: 2,10
        //t5007d201fa06748000 INDEX: HEIZUNGSDRUCK Payload: -327,68

        String STR_ANZEIGE_HEIZUNGSDRUCK = "3100fa06740000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_ANZEIGE_HEIZUNGSDRUCK);
        usbtin.send(elsterMessage.getMessage());
    }

    private synchronized void requestAufnahmeleistung() throws USBtinException {
        String STR_AUFNAHMELEISTUNG_HEIZ_SUM_KWH = "a114fa09200000";
        ElsterMessage elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_AUFNAHMELEISTUNG_HEIZ_SUM_KWH);
        usbtin.send(elsterMessage.getMessage());

        String STR_AUFNAHMELEISTUNG_HEIZ_SUM_MWH = "a114fa09210000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_AUFNAHMELEISTUNG_HEIZ_SUM_MWH);
        usbtin.send(elsterMessage.getMessage());


        String STR_AUFNAHMELEISTUNG_HEIZ_TAG_WH = "a114fa091e0000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_AUFNAHMELEISTUNG_HEIZ_TAG_WH);
        usbtin.send(elsterMessage.getMessage());

        String STR_AUFNAHMELEISTUNG_HEIZ_TAG_KWH = "a114fa091f0000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_AUFNAHMELEISTUNG_HEIZ_TAG_KWH);
        usbtin.send(elsterMessage.getMessage());

    }


    private synchronized void requestWaermeErzeugt() throws USBtinException {

        String STR_WAERMEERTRAG_HEIZ_SUM_KWH = "a114fa09300000";
        ElsterMessage elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_WAERMEERTRAG_HEIZ_SUM_KWH);
        usbtin.send(elsterMessage.getMessage());

        String STR_WAERMEERTRAG_HEIZ_SUM_MWH = "a114fa09310000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_WAERMEERTRAG_HEIZ_SUM_MWH);
        usbtin.send(elsterMessage.getMessage());

        String STR_WAERMEERTRAG_HEIZ_TAG_WH = "a114fa092e0000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_WAERMEERTRAG_HEIZ_TAG_WH);
        usbtin.send(elsterMessage.getMessage());

        String STR_WAERMEERTRAG_HEIZ_TAG_KWH = "a114fa092f0000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_WAERMEERTRAG_HEIZ_TAG_KWH);
        usbtin.send(elsterMessage.getMessage());

    }

    private synchronized void requestWaermeZusatzheizung() throws USBtinException {
        String[] STR_WAERMEERTRAG_2WE_HEIZ = {
                "a114fa09260000", // WAERMEERTRAG_2WE_HEIZ_TAG_WH
                "a114fa09270000", // WAERMEERTRAG_2WE_HEIZ_TAG_KWH
                "a114fa09280000", // WAERMEERTRAG_2WE_HEIZ_SUM_KWH
                "a114fa09290000"  // WAERMEERTRAG_2WE_HEIZ_SUM_MWH
        };
        for (String request : STR_WAERMEERTRAG_2WE_HEIZ) {
            usbtin.send(new ElsterMessage(CAN_SENDER_ID, request).getMessage());
        }
    }

    private synchronized void requestVolumenstrom() throws USBtinException {
        usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, 0x500, WP_WASSERVOLUMENSTROM).getMessage());
    }

    private synchronized void requestBetriebswerte() throws USBtinException {
        for (short index : BETRIEBSWERTE_HEIZMODUL) {
            usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, HEIZMODUL, index).getMessage());
        }
        usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, WAERMEPUMPE, LUEFTERLEISTUNG_REL).getMessage());
    }

    private synchronized void requestEinstellungen() throws USBtinException {
        for (short index : EINSTELLUNGEN_HEIZMODUL) {
            usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, HEIZMODUL, index).getMessage());
        }
    }

    private synchronized void requestLaufzeiten() throws USBtinException {
        // 0x500 and 0x514 answer the same values, 0x180 does not have them
        String[] STR_LAUFZEITEN = {
                "a100fa02590000", // LAUFZEIT_DHC1
                "a100fa025a0000", // LAUFZEIT_DHC2
                "a100fa08050000"  // LAUFZEIT_DHC12
        };
        for (String request : STR_LAUFZEITEN) {
            usbtin.send(new ElsterMessage(CAN_SENDER_ID, request).getMessage());
        }
    }

    private void requestMonitoring() throws USBtinException {

        String STR_TEST_OBJEKT_113_STROM_INVERTER = "a100fa06b20000";
        ElsterMessage elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_TEST_OBJEKT_113_STROM_INVERTER);
        usbtin.send(elsterMessage.getMessage());

        String STR_VORLAUFISTTEMP = "a114fa01d60000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_VORLAUFISTTEMP);
        usbtin.send(elsterMessage.getMessage());

        String STR_RUECKLAUFISTTEMP = "a1141600000000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_RUECKLAUFISTTEMP);
        usbtin.send(elsterMessage.getMessage());

        String STR_TEST_OBJEKT_112_SPANNUNG_INVERTER = "a100fa06b10000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_TEST_OBJEKT_112_SPANNUNG_INVERTER);
        usbtin.send(elsterMessage.getMessage());

        String STR_ANZEIGE_HOCHDRUCK = "a100fa07a60000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_ANZEIGE_HOCHDRUCK);
        usbtin.send(elsterMessage.getMessage());

        // public static short ANZEIGE_NIEDERDRUCK = (short)0x07a7;
        String STR_ANZEIGE_NIEDERDRUCK = "a100fa07a70000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_ANZEIGE_NIEDERDRUCK);
        usbtin.send(elsterMessage.getMessage());

        String STR_AUSSENTEMP = "a1000c00000000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_AUSSENTEMP);
        usbtin.send(elsterMessage.getMessage());

        String STR_HEISSGAS_TEMP = "a100fa02650000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_HEISSGAS_TEMP);
        usbtin.send(elsterMessage.getMessage());

        String STR_VERDICHTER_DREHZAHL = "a100fa063d0000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_VERDICHTER_DREHZAHL);
        usbtin.send(elsterMessage.getMessage());

        String STR_VERDICHTER_EINTRITTSTEMP = "a100fa063f0000";
        elsterMessage = new ElsterMessage(CAN_SENDER_ID, STR_VERDICHTER_EINTRITTSTEMP);
        usbtin.send(elsterMessage.getMessage());

        usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, 0x500, VERDAMPFERTEMP).getMessage());
        // Every 20 s, the defrost with the fully open EXV lasts only about 1-2 minutes
        usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, HEIZMODUL, OEFFNUNGSGRAD_EXV).getMessage());
        usbtin.send(ElsterMessage.readRequest(CAN_SENDER_ID, 0x500, OELSUMPFTEMP).getMessage());



    }

    /**
     * Check every 20 seconds
     */
    @Scheduled(fixedRate = 20000)
    public synchronized void check20() {
        try {
            requestBetriebsstatus();
            requestMonitoring();
        } catch (Exception e) {
            logger.error("Rquest failiure: ", e);
        }
        storeValues20();

    }

    /**
     * Check every 60 seconds
     */
    @Scheduled(fixedRate = 60000)
    public synchronized void check60() {
        try {
            requestAufnahmeleistung();
            requestWaermeErzeugt();
            requestWaermeZusatzheizung();
            requestVolumenstrom();
            requestLaufzeiten();
            requestBetriebswerte();
        } catch (Exception e) {
            logger.error("Rquest failiure: ", e);
        }
        canStatistik.minuteAbschliessen(Instant.now(), usbSpeed);
        storeValues60();

    }

    /**
     * Check the settings every hour, the values of the previous request are stored
     */
    @Scheduled(fixedRate = 3600000)
    public synchronized void check3600() {
        try {
            requestEinstellungen();
        } catch (Exception e) {
            logger.error("Rquest failiure: ", e);
        }
        storeValues3600();
    }

    /**
     * Restarts the USB connection if no answer was received for 2 minutes, checked every 30 s
     */
    @Scheduled(fixedRate = 30000)
    public synchronized void checkUSBisAlive() {
        Duration duration = Duration.between(LocalDateTime.now(), lastMsgReceived);
        if (duration.getSeconds() < - 120){
            logger.error("Restarting USB, no messgage received for: "+ duration.getSeconds());
            emailService.sendAlert("Restarting USB", "Restarting USB, no messgage received for: "+ duration.getSeconds());
            canStatistik.neustart(Instant.now());
            stopUsbTin();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            startUsbTin();
        }
    }

    /**
     * Adds a point for the value, skips it if it is missing or outdated so the remaining values are still stored
     */
    private void addPoint(List<Point> points, String name, Callable<ValueContainer<Double>> value, Duration maxAge) {
        try {
            ValueContainer<Double> valueContainer = value.call();
            if (valueContainer.getTimestamp().isBefore(Instant.now().minus(maxAge))) {
                logger.warn("Not storing " + name + ", outdated value received at " + valueContainer.getTimestamp());
                return;
            }
            points.add(influxController.createPoint(name, valueContainer));
        } catch (Exception e) {
            logger.warn("Not storing " + name + ": " + e.getMessage());
        }
    }

    /**
     * Stores the single energy counters, to verify whether the sum counters already contain the current day
     */
    private void addCounterPoints(List<Point> points, short... indices) {
        for (short index : indices) {
            ElsterMessage msg = msgs.get(index);
            String name = msg != null ? msg.getElsterIndex().getName() : String.format("%04X", index);
            addPoint(points, "Zaehler_" + name, () -> getDecimalValue(index), MAX_AGE_60);
        }
    }

    private void storeValues20() {
        updateVerdichterLaeuftSeit();
        updateAbtauung();
        try {
            List<Point> points = new ArrayList<>();
            addPoint(points, "Heizungsdruck", this::getHeizungsdruck, MAX_AGE_20);
            addPoint(points, "Hochdruck", this::getHochdruck, MAX_AGE_20);
            addPoint(points, "Niederdruck", this::getNiederdruck, MAX_AGE_20);
            addPoint(points, "RuecklaufIstTemp", this::getRuecklaufIstTemp, MAX_AGE_20);
            addPoint(points, "SpannungInverter", this::getSpannungInverter, MAX_AGE_20);
            addPoint(points, "StromInverter", this::getStromInverter, MAX_AGE_20);
            addPoint(points, "LeistungInverter", this::getLeistungInverter, MAX_AGE_20);
            addPoint(points, "VorlaufIstTemp", this::getVorlaufIstTemp, MAX_AGE_20);
            addPoint(points, "Aussentemp", this::getAussentemp, MAX_AGE_20);
            addPoint(points, "HeissgasTemp", this::getHeissgasTemp, MAX_AGE_20);
            addPoint(points, "VerdichterDrehzahlHz", this::getVerdichterDrehzahl, MAX_AGE_20);
            addPoint(points, "VerdichterEintrittstemp", this::getVerdichterEintrittstemp, MAX_AGE_20);
            addPoint(points, "VerdampferTemp", this::getVerdampferTemp, MAX_AGE_20);
            addPoint(points, "OelsumpfTemp", this::getOelsumpfTemp, MAX_AGE_20);
            addPoint(points, "Abtauung", this::getAbtauung, MAX_AGE_60);
            influxController.storePoints(points);

        } catch (Exception e) {
            logger.error("Error during storing data", e);
        }

    }


    private void storeValues60() {
        try {
            List<Point> points = new ArrayList<>();
            addPoint(points, "AbgabeWaerme", this::getAbgabeWaerme, MAX_AGE_60);
            addPoint(points, "LaufzeitDHZ1", this::getLaufzeit_DHC1, MAX_AGE_60);
            addPoint(points, "LaufzeitDHZ2", this::getLaufzeit_DHC2, MAX_AGE_60);
            addPoint(points, "LaufzeitDHZ12", this::getLaufzeit_DHC12, MAX_AGE_60);
            addPoint(points, "AufnahmeLeistung", this::getAufnahmeLeistung, MAX_AGE_60);
            addPoint(points, "WaermeZusatzheizung", this::getWaermeZusatzheizung, MAX_AGE_60);
            addPoint(points, "WasserVolumenstrom", this::getVolumenstrom, MAX_AGE_60);
            addPoint(points, "WaermeleistungBerechnet", this::getWaermeleistung, MAX_AGE_60);
            addPoint(points, "ArbeitszahlGeschaetzt", this::getArbeitszahl, MAX_AGE_60);
            addPoint(points, "EffizienzKorrigiert", this::getEffizienzKorrigiert, MAX_AGE_60);
            addPoint(points, "LaufzeitVerdichterHeizen", this::getLaufzeitVerdichterHeizen, MAX_AGE_60);
            addPoint(points, "LaufzeitVerdichterAbtauen", this::getLaufzeitVerdichterAbtauen, MAX_AGE_60);
            addPoint(points, "DauerLetzteAbtauung", this::getDauerLetzteAbtauung, MAX_AGE_60);
            addPoint(points, "VerdichterStarts", this::getVerdichterStarts, MAX_AGE_60);
            addPoint(points, "VerdichterSollDrehzahlHz", this::getVerdichterSollDrehzahl, MAX_AGE_60);
            addPoint(points, "UeberhitzungSoll", this::getSollUeberhitzung, MAX_AGE_60);
            addPoint(points, "UeberhitzungIst", this::getIstUeberhitzung, MAX_AGE_60);
            addPoint(points, "OeffnungsgradEXV", this::getOeffnungsgradExv, MAX_AGE_60);
            addPoint(points, "LuefterIstDrehzahlHz", this::getLuefterIstDrehzahl, MAX_AGE_60);
            addPoint(points, "LuefterSollDrehzahlHz", this::getLuefterSollDrehzahl, MAX_AGE_60);
            addPoint(points, "LuefterLeistung", this::getLuefterLeistung, MAX_AGE_60);
            addPoint(points, "UmgebungstempInverter", this::getUmgebungstempInverter, MAX_AGE_60);
            addPoint(points, "TempInverterVerdichter", this::getTempInverterVerdichter, MAX_AGE_60);
            addPoint(points, "CAN_Empfangen", () -> canMinute(CanStatistik.Minute::empfangen), MAX_AGE_60);
            addPoint(points, "CAN_Antworten", () -> canMinute(CanStatistik.Minute::antworten), MAX_AGE_60);
            addPoint(points, "CAN_Gesendet", () -> canMinute(CanStatistik.Minute::gesendet), MAX_AGE_60);
            addPoint(points, "CAN_NichtVerfuegbar", () -> canMinute(CanStatistik.Minute::nichtVerfuegbar),
                    MAX_AGE_60);
            addPoint(points, "CAN_Antwortquote", () -> canMinute(CanStatistik.Minute::antwortquote), MAX_AGE_60);
            addPoint(points, "CAN_Buslast", () -> canMinute(CanStatistik.Minute::buslast), MAX_AGE_60);
            addPoint(points, "CAN_UsbNeustarts", this::getUsbNeustarts, MAX_AGE_60);
            for (CanStatistik.KnotenStatus knoten : canStatistik.getKnoten()) {
                addPoint(points, knotenSerie(knoten.id()), () -> canMinute(m -> knoten.proMinute()), MAX_AGE_60);
            }
            addCounterPoints(points,
                    EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH, EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH,
                    EL_AUFNAHMELEISTUNG_HEIZ_TAG_KWH, EL_AUFNAHMELEISTUNG_HEIZ_TAG_WH,
                    WAERMEERTRAG_HEIZ_SUM_MWH, WAERMEERTRAG_HEIZ_SUM_KWH,
                    WAERMEERTRAG_HEIZ_TAG_KWH, WAERMEERTRAG_HEIZ_TAG_WH,
                    WAERMEERTRAG_2WE_HEIZ_SUM_MWH, WAERMEERTRAG_2WE_HEIZ_SUM_KWH,
                    WAERMEERTRAG_2WE_HEIZ_TAG_KWH, WAERMEERTRAG_2WE_HEIZ_TAG_WH);
            influxController.storePoints(points);

        } catch (Exception e) {
            logger.error("Error during storing data", e);
        }

    }


    private void storeValues3600() {
        try {
            List<Point> points = new ArrayList<>();
            addPoint(points, "Einstellung_Auslegungstemperatur", this::getAuslegungstemperatur, MAX_AGE_3600);
            addPoint(points, "Einstellung_Waermebedarf", this::getWaermebedarf, MAX_AGE_3600);
            addPoint(points, "Einstellung_SollSpreizung", this::getSollSpreizung, MAX_AGE_3600);
            addPoint(points, "Einstellung_Bivalenztemperatur", this::getBivalenztemperatur, MAX_AGE_3600);
            addPoint(points, "Einstellung_EinsatzgrenzeHeizen", this::getEinsatzgrenzeHeizen, MAX_AGE_3600);
            addPoint(points, "Einstellung_SilentLeistung", this::getSilentLeistung, MAX_AGE_3600);
            addPoint(points, "Einstellung_SilentLuefter", this::getSilentLuefter, MAX_AGE_3600);
            influxController.storePoints(points);

        } catch (Exception e) {
            logger.error("Error during storing data", e);
        }

    }


    /**
     * Value of the last complete minute of the CAN statistics
     */
    private ValueContainer<Double> canMinute(ToDoubleFunction<CanStatistik.Minute> wert) throws Exception {
        CanStatistik.Minute minute = canStatistik.getLetzteMinute();
        if (minute == null) {
            throw new Exception("NO_VALUES_REVEIVED");
        }
        return new ValueContainer<>(wert.applyAsDouble(minute), minute.ende());
    }

    private ValueContainer<Double> getUsbNeustarts() {
        return new ValueContainer<>((double) canStatistik.getNeustarts(), Instant.now());
    }

    /**
     * Seconds since the last answer to a request of this service
     */
    private ValueContainer<Double> getSekundenSeitAntwort() throws Exception {
        Instant letzteAntwort = canStatistik.getLetzteAntwort();
        if (letzteAntwort == null) {
            throw new Exception("NO_VALUES_REVEIVED");
        }
        Instant jetzt = Instant.now();
        return new ValueContainer<>((double) Duration.between(letzteAntwort, jetzt).toSeconds(), jetzt);
    }

    private ValueContainer<Boolean> isAdapterVerbunden() {
        return new ValueContainer<>(canStatistik.isVerbunden(), Instant.now());
    }

    static String knotenSerie(int id) {
        return String.format("CAN_Knoten_%03X", id);
    }

    private static String zeitpunkt(Instant zeit) {
        return zeit == null ? null : ZEITPUNKT.format(zeit.atZone(ZoneId.systemDefault()));
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

    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm:ss");

    private static final String FORMEL_SCHEINLEISTUNG = "Spannung × Strom des Inverters, ohne Leistungsfaktor";

    /**
     * Number for the formulas on the status page, e.g. 4,19 or 0,7
     */
    static String zahl(double value) {
        return new DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value);
    }

    static String formelWaermeleistung() {
        return "Volumenstrom [l/min] ÷ 60 × " + zahl(WAERMEKAPAZITAET_WASSER) + " kJ/(kg·K) × Spreizung [K]. "
                + "0 bei stehendem Verdichter, kein Wert in den ersten " + ANLAUFZEIT.toSeconds()
                + " s nach dem Start, während der Abtauung und ohne Volumenstrom";
    }

    static String formelArbeitszahl() {
        return "Wärmeleistung ÷ Wirkleistung, Wirkleistung = Scheinleistung × (" + zahl(WIRKLEISTUNG_STEIGUNG)
                + " − " + (int) WIRKLEISTUNG_OFFSET_W + " W ÷ Scheinleistung), Anteil begrenzt auf "
                + zahl(WIRKLEISTUNGSANTEIL_MIN) + " bis " + zahl(WIRKLEISTUNGSANTEIL_MAX)
                + " (Abgleich mit dem Hauszähler 2024/25)";
    }

    static String formelAbtauung() {
        return "An, wenn das Expansionsventil innerhalb von " + ABTAUUNG_MAX_STILLSTAND.toMinutes()
                + " min nach einem Verdichterstopp auf mindestens " + (int) ABTAUUNG_EXV_PROZENT
                + " % öffnet oder der Verdampfer bei laufendem Verdichter mehr als "
                + (int) ABTAUUNG_VERDAMPFER_UEBER_VORLAUF_K + " K wärmer als der Vorlauf ist. Bleibt "
                + ABTAUUNG_NACHLAUF.toSeconds() + " s nach dem Ende an";
    }

    /**
     * Returns actual status
     *
     * @return HTML page
     */
    public synchronized String getStatus() {
        StatusPage page = new StatusPage()
                .badge("Verdichter läuft", "Verdichter aus", this::isVerdichterOn)
                .kpi("Außentemperatur", "°C", 1, "Aussentemp", this::getAussentemp)
                .kpi("Vorlauf", "°C", 1, "VorlaufIstTemp", this::getVorlaufIstTemp)
                .kpi("Rücklauf", "°C", 1, "RuecklaufIstTemp", this::getRuecklaufIstTemp)
                .kpi("Verdichter", "Hz", 0, "VerdichterDrehzahlHz", this::getVerdichterDrehzahl)
                .kpi("Inverter (berechnet)", "VA", 0, "LeistungInverter", this::getLeistungInverter)
                .formula(FORMEL_SCHEINLEISTUNG)
                .card("Betrieb")
                .pill("Verdichter", this::isVerdichterOn)
                .pill("Pufferladepumpe", this::isPufferladepumpeOn)
                .pill("Warmwasserladepumpe", this::isWarmwasserladepumpe)
                .pill("DHC 1", this::isDHC_1On)
                .pill("DHC 2", this::isDHC_2On)
                .pill("EVU-Sperre", this::isEvuSperre)
                .pill("Abtauung (berechnet)", this::isAbtauung)
                .formula(formelAbtauung())
                .row("Laufzeit DHC 1", "h", 0, "LaufzeitDHZ1", this::getLaufzeit_DHC1)
                .row("Laufzeit DHC 2", "h", 0, "LaufzeitDHZ2", this::getLaufzeit_DHC2)
                .row("Laufzeit DHC 1+2", "h", 0, "LaufzeitDHZ12", this::getLaufzeit_DHC12)
                .card("Verdichter")
                .row("Laufzeit Heizen", "h", 0, "LaufzeitVerdichterHeizen", this::getLaufzeitVerdichterHeizen)
                .row("Starts", "", 0, "VerdichterStarts", this::getVerdichterStarts)
                .row("Laufzeit je Start (berechnet)", "h", 2, this::getLaufzeitProStart)
                .formula("Laufzeit Heizen ÷ Verdichterstarts")
                .row("Laufzeit Abtauen", "h", 0, "LaufzeitVerdichterAbtauen", this::getLaufzeitVerdichterAbtauen)
                .row("Dauer letzte Abtauung", "min", 0, "DauerLetzteAbtauung", this::getDauerLetzteAbtauung)
                .note("Zähler seit Inbetriebnahme.")
                .card("Heizkreis")
                .row("Vorlauf", "°C", 1, "VorlaufIstTemp", this::getVorlaufIstTemp)
                .row("Rücklauf", "°C", 1, "RuecklaufIstTemp", this::getRuecklaufIstTemp)
                .row("Spreizung (berechnet)", "K", 1, this::getSpreizung)
                .formula("Vorlauf − Rücklauf")
                .row("Volumenstrom", "l/min", 1, "WasserVolumenstrom", this::getVolumenstrom)
                .row("Wärmeleistung (berechnet)", "kW", 2, "WaermeleistungBerechnet", this::getWaermeleistung)
                .formula(formelWaermeleistung())
                .row("Heizungsdruck", "bar", 2, "Heizungsdruck", this::getHeizungsdruck)
                .card("Kältekreis")
                .row("Verdichter-Drehzahl", "Hz", 0, "VerdichterDrehzahlHz", this::getVerdichterDrehzahl)
                .row("Verdichter-Solldrehzahl", "Hz", 0, "VerdichterSollDrehzahlHz", this::getVerdichterSollDrehzahl)
                .row("Hochdruck", "bar", 2, "Hochdruck", this::getHochdruck)
                .row("Niederdruck", "bar", 2, "Niederdruck", this::getNiederdruck)
                .row("Heißgas", "°C", 1, "HeissgasTemp", this::getHeissgasTemp)
                .row("Verdichter-Eintritt", "°C", 1, "VerdichterEintrittstemp", this::getVerdichterEintrittstemp)
                .row("Verdampfer", "°C", 1, "VerdampferTemp", this::getVerdampferTemp)
                .row("Ölsumpf", "°C", 1, "OelsumpfTemp", this::getOelsumpfTemp)
                .row("Überhitzung", "K", 1, "UeberhitzungIst", this::getIstUeberhitzung)
                .row("Überhitzung Soll", "K", 1, "UeberhitzungSoll", this::getSollUeberhitzung)
                .row("Expansionsventil", "%", 1, "OeffnungsgradEXV", this::getOeffnungsgradExv)
                .row("Lüfter", "Hz", 0, "LuefterIstDrehzahlHz", this::getLuefterIstDrehzahl)
                .row("Lüfter Soll", "Hz", 0, "LuefterSollDrehzahlHz", this::getLuefterSollDrehzahl)
                .row("Lüfterleistung", "%", 0, "LuefterLeistung", this::getLuefterLeistung)
                .card("Inverter")
                .row("Spannung", "V", 1, "SpannungInverter", this::getSpannungInverter)
                .row("Strom", "A", 1, "StromInverter", this::getStromInverter)
                .row("Scheinleistung (berechnet)", "VA", 0, "LeistungInverter", this::getLeistungInverter)
                .formula(FORMEL_SCHEINLEISTUNG)
                .row("Umgebungstemperatur", "°C", 1, "UmgebungstempInverter", this::getUmgebungstempInverter)
                .row("Temperatur Verdichter", "°C", 1, "TempInverterVerdichter", this::getTempInverterVerdichter)
                .note("Spannung × Strom ohne Leistungsfaktor. Die Wirkleistung liegt laut Smartmeter bei etwa "
                        + "80 % bei 600 VA und erreicht ab 1000 VA die Scheinleistung.")
                .card("Energie Heizen")
                .row("Stromaufnahme", "MWh", 3, "AufnahmeLeistung", this::getAufnahmeLeistung)
                .row("Wärmeerzeugung", "MWh", 3, "AbgabeWaerme", this::getAbgabeWaerme)
                .row("Zusatzheizung", "MWh", 3, "WaermeZusatzheizung", this::getWaermeZusatzheizung)
                .row("Effizienz gesamt (Zähler, berechnet)", "", 2, this::getEffezienz)
                .formula("Wärmeerzeugung ÷ Stromaufnahme, beides Zähler der Wärmepumpe")
                .row("Effizienz gesamt (korrigiert, berechnet)", "", 2, "EffizienzKorrigiert", this::getEffizienzKorrigiert)
                .formula("Wärmeerzeugung ÷ (Stromaufnahme × " + zahl(STROMZAEHLER_KORREKTUR)
                        + "), der Stromzähler der Wärmepumpe zählt rund 20 % zu wenig")
                .row("Arbeitszahl aktuell (berechnet)", "", 1, "ArbeitszahlGeschaetzt", this::getArbeitszahl)
                .formula(formelArbeitszahl())
                .note("Zähler der Wärmepumpe seit Inbetriebnahme. Der Stromzähler zählt rund 20 % zu wenig, "
                        + "die korrigierte Effizienz rechnet das heraus. Die aktuelle Arbeitszahl ist geschätzt: "
                        + "berechnete Wärmeleistung geteilt durch die aus der Scheinleistung geschätzte Wirkleistung.")
                .card("Einstellungen")
                .row("Auslegungstemperatur", "°C", 1, "Einstellung_Auslegungstemperatur", this::getAuslegungstemperatur, MAX_AGE_3600)
                .row("Wärmebedarf", "kW", 1, "Einstellung_Waermebedarf", this::getWaermebedarf, MAX_AGE_3600)
                .row("Soll-Spreizung", "K", 1, "Einstellung_SollSpreizung", this::getSollSpreizung, MAX_AGE_3600)
                .row("Bivalenztemperatur", "°C", 1, "Einstellung_Bivalenztemperatur", this::getBivalenztemperatur, MAX_AGE_3600)
                .row("Einsatzgrenze Heizen", "°C", 1, "Einstellung_EinsatzgrenzeHeizen", this::getEinsatzgrenzeHeizen, MAX_AGE_3600)
                .row("Silent Leistung", "%", 0, "Einstellung_SilentLeistung", this::getSilentLeistung, MAX_AGE_3600)
                .row("Silent Lüfter", "%", 0, "Einstellung_SilentLuefter", this::getSilentLuefter, MAX_AGE_3600)
                .note("Heizlast bei Auslegungstemperatur und weitere Einstellungen, stündlich abgefragt.")
                .card("USB-Adapter")
                .pill("Verbindung", this::isAdapterVerbunden)
                .text("Port", usbPort + " · " + usbSpeed / 1000 + " kbit/s")
                .text("Firmware / Hardware", canStatistik.getFirmware() == null ? null
                        : canStatistik.getFirmware() + " / " + canStatistik.getHardware())
                .text("Seriennummer", canStatistik.getSeriennummer())
                .text("Verbunden seit", zeitpunkt(canStatistik.getVerbundenSeit()))
                .row("USB-Neustarts", "", 0, "CAN_UsbNeustarts", this::getUsbNeustarts)
                .text("Letzter Neustart", zeitpunkt(canStatistik.getLetzterNeustart()))
                .note("Neustarts durch die Überwachung (keine Antwort für 2 min), gezählt seit dem Start des "
                        + "Dienstes.")
                .card("CAN-Bus")
                .row("Letzte Antwort vor", "s", 0, this::getSekundenSeitAntwort)
                .row("Empfangen", "/min", 0, "CAN_Empfangen", () -> canMinute(CanStatistik.Minute::empfangen))
                .row("davon Antworten", "/min", 0, "CAN_Antworten", () -> canMinute(CanStatistik.Minute::antworten))
                .row("Anfragen gesendet", "/min", 0, "CAN_Gesendet", () -> canMinute(CanStatistik.Minute::gesendet))
                .row("Antwortquote (berechnet)", "%", 0, "CAN_Antwortquote",
                        () -> canMinute(CanStatistik.Minute::antwortquote))
                .formula("Antworten ÷ gesendete Anfragen der letzten Minute, höchstens 100 %")
                .row("Antwort „nicht verfügbar“", "/min", 0, "CAN_NichtVerfuegbar",
                        () -> canMinute(CanStatistik.Minute::nichtVerfuegbar))
                .row("Buslast (berechnet)", "%", 1, "CAN_Buslast", () -> canMinute(CanStatistik.Minute::buslast))
                .formula("Summe (47 + 8 × Datenbytes) Bit aller empfangenen und gesendeten Nachrichten der letzten "
                        + "Minute ÷ 60 s ÷ " + usbSpeed + " bit/s. Ohne Bit-Stuffing, also eher etwas zu niedrig");
        for (CanStatistik.KnotenStatus knoten : canStatistik.getKnoten()) {
            page.row(knoten.name(), "/min", 0, knotenSerie(knoten.id()),
                    () -> new ValueContainer<>(knoten.proMinute(), knoten.zuletzt()));
        }
        return page
                .note("Werte der letzten vollen Minute, je Gerät die Nachrichten pro Minute. Mit can.logall=false "
                        + "kommen nur die Antworten an diesen Dienst an.")
                .render();
    }
}
