package nrw.andresen.stbl.services;

import nrw.andresen.stbl.Application;

import com.influxdb.client.write.Point;
import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterMessage;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static nrw.andresen.stbl.services.Waermepumpe.MAX_AGE_20;
import static nrw.andresen.stbl.services.Waermepumpe.MAX_AGE_60;
import static nrw.andresen.stbl.services.can.ElsterTable.*;

/**
 * Requests the values of the heat pump on schedule, stores them in InfluxDB and reports new faults by mail
 */
@Component
public class StblService {

    // The fault list (140 requests) and the settings are requested half a minute after the minute of the CAN
    // statistics closes. Sent exactly at its end, the requests counted in one minute and their answers in the next,
    // which showed an answer rate of 35 % every 10 minutes although everything was answered (seen 2026-10-09).
    private static final long VERSATZ_MS = 30000;
    // The minute of the CAN statistics is closed between two runs of the 20 s cycle and before the requests of the
    // 60 s cycle. Started together, the 15 requests of the 20 s cycle were counted sometimes before and sometimes
    // after the end of the minute, their answers always after it: 89 requests and 74 answers showed an answer rate
    // of 83 % in single minutes although everything was answered (seen 2026-10-10, 20 times in two hours).
    private static final long VERSATZ_60_MS = 10000;
    // The settings are stored this long after their request, when the answers have arrived
    private static final long SPEICHERN_3600_MS = 30000;
    // An older setting means the last request was not answered
    private static final Duration MAX_ALTER_EINSTELLUNG = Duration.ofMinutes(5);

    private final Logger logger = LoggerFactory.getLogger(StblService.class);
    @Autowired
    private EmailService emailService;
    @Autowired
    private InfluxController influxController;
    @Autowired
    private CanBus can;
    @Autowired
    private Waermepumpe wp;
    // The first run of each cycle only sends the requests: the answers are not there yet, storing would log a
    // warning for every value at each start of the service
    private boolean erster20 = true;
    // Start and stop of the compressor are read once from the stored values after the start of the service
    private boolean vorgeschichteGelesen;
    private boolean erster60 = true;

    /**
     * Check every 20 seconds
     */
    @Scheduled(fixedRate = 20000, scheduler = Application.CAN_SCHEDULER)
    public synchronized void check20() {
        try {
            wp.anfragen20();
        } catch (Exception e) {
            logger.error("Request failure: ", e);
        }
        wp.aktualisieren();
        if (erster20) {
            erster20 = false;
            return;
        }
        if (!vorgeschichteGelesen) {
            vorgeschichteGelesen = true;
            vorgeschichte();
        }
        storeValues20();
    }

    /**
     * Start of the current run, or the last stop at standstill, from the stored compressor speed of the last 7 days
     */
    private void vorgeschichte() {
        if (!influxController.isAktiv()) {
            return;
        }
        String name = "VerdichterDrehzahlHz";
        Instant jetzt = Instant.now();
        Instant von = jetzt.minus(Duration.ofDays(7));
        try {
            Instant laeuftSeit = null;
            Instant bis = jetzt;
            if (wp.getVerdichterLaeuftSeit() != null) {
                // Running: the run started with the first speed above 0 after the last 0
                Instant letzteNull = influxController.zeitpunkt(name, false, von, jetzt, false);
                if (letzteNull == null) {
                    return;
                }
                laeuftSeit = influxController.zeitpunkt(name, true, letzteNull, jetzt, true);
                bis = letzteNull;
            }
            // The stop before: the first 0 after the last run before bis
            Instant letzterLauf = influxController.zeitpunkt(name, true, von, bis, false);
            Instant gestopptUm = letzterLauf == null ? null
                    : influxController.zeitpunkt(name, false, letzterLauf, jetzt, true);
            Instant laufBeginn = null;
            if (laeuftSeit != null) {
                // Across defrosts: the speed and the defrosts of the last 2 days
                Instant ab = jetzt.minus(Duration.ofDays(2));
                String bucket = influxController.getBucket();
                java.util.SortedMap<Instant, Double> drehzahl = new java.util.TreeMap<>(influxController.werte(
                        "from(bucket: \"" + bucket + "\") |> range(start: " + ab + ") |> filter(fn: (r) => "
                                + "r._measurement == \"WP_" + name + "\" and r._field == \"value\")"));
                List<Instant> abtauungen = List.copyOf(influxController.werte("from(bucket: \"" + bucket
                        + "\") |> range(start: " + ab + ") |> filter(fn: (r) => r._measurement == \"WP_Abtauung\""
                        + " and r._field == \"value\" and r._value > 0.0)").keySet());
                laufBeginn = Waermepumpe.laufBeginn(drehzahl, abtauungen);
            }
            wp.vorgeschichte(laeuftSeit, gestopptUm, laufBeginn);
            logger.info("Compressor from stored values: running since " + laeuftSeit + ", run with defrosts since "
                    + laufBeginn + ", stopped at " + gestopptUm);
        } catch (Exception e) {
            logger.warn("Start and stop of the compressor could not be read: " + e.getMessage());
        }
    }

    /**
     * Check every 60 seconds, 10 s after a run of the 20 s cycle
     */
    @Scheduled(initialDelay = VERSATZ_60_MS, fixedRate = 60000, scheduler = Application.CAN_SCHEDULER)
    public synchronized void check60() {
        can.minuteAbschliessen();
        try {
            wp.anfragen60();
        } catch (Exception e) {
            logger.error("Request failure: ", e);
        }
        if (erster60) {
            erster60 = false;
            return;
        }
        storeValues60();
    }

    /**
     * Requests the settings every hour
     */
    @Scheduled(initialDelay = VERSATZ_MS, fixedRate = 3600000, scheduler = Application.CAN_SCHEDULER)
    public synchronized void check3600() {
        try {
            wp.anfragen3600();
        } catch (Exception e) {
            logger.error("Request failure: ", e);
        }
    }

    /**
     * Stores the settings half a minute after their request. Stored together with the next request they appeared
     * an hour late in InfluxDB and a restart of the service lost one value.
     */
    @Scheduled(initialDelay = VERSATZ_MS + SPEICHERN_3600_MS, fixedRate = 3600000, scheduler = Application.CAN_SCHEDULER)
    public synchronized void speichern3600() {
        storeValues3600();
    }

    /**
     * Reads the fault list every 10 minutes. The answers of the previous request are checked first: a new entry is
     * logged and sent by mail. The first complete read only remembers the list. Each complete list is stored in
     * InfluxDB, an entry already stored only replaces its point.
     */
    @Scheduled(initialDelay = VERSATZ_MS, fixedRate = 600000, scheduler = Application.CAN_SCHEDULER)
    public synchronized void checkFehlerliste() {
        if (can.fehlerlisteVollstaendig()) {
            try {
                influxController.storePoints(can.getFehlerliste().eintraege().stream()
                        .map(InfluxController::createFehlerPoint).toList());
            } catch (Exception e) {
                logger.error("Storing the fault list failed: ", e);
            }
            for (Fehlerliste.Eintrag eintrag : can.getFehlerliste().neueEintraege()) {
                String text = Fehlerliste.ZEITFORMAT.format(eintrag.zeit()) + " " + eintrag.text()
                        + " (Code " + eintrag.code() + ")";
                logger.warn("New entry in the fault list of the heat pump: " + text);
                emailService.sendAlert("Wärmepumpe: " + eintrag.text(),
                        "Neuer Eintrag in der Fehlerliste der Wärmepumpe:\n" + text);
            }
        }
        try {
            can.fehlerlisteAnfragen();
        } catch (Exception e) {
            logger.error("Request failure: ", e);
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
        } catch (KeinMesswert e) {
            // Expected, e.g. no efficiency while the compressor is off: a warning every minute would hide real ones
            logger.debug("Not storing " + name + ": " + e.getMessage());
        } catch (Exception e) {
            logger.warn("Not storing " + name + ": " + e.getMessage());
        }
    }

    /**
     * Stores the single energy counters, to verify whether the sum counters already contain the current day
     */
    private void addCounterPoints(List<Point> points, short... indices) {
        for (short index : indices) {
            ElsterMessage msg = can.nachricht(index);
            String name = msg != null ? msg.getElsterIndex().getName() : String.format("%04X", index);
            addPoint(points, "Zaehler_" + name, () -> wp.getDecimalValue(index), MAX_AGE_60);
        }
    }

    private void storeValues20() {
        if (!influxController.isAktiv()) {
            return;
        }
        try {
            List<Point> points = new ArrayList<>();
            addPoint(points, "Heizungsdruck", wp::getHeizungsdruck, MAX_AGE_20);
            addPoint(points, "Hochdruck", wp::getHochdruck, MAX_AGE_20);
            addPoint(points, "Niederdruck", wp::getNiederdruck, MAX_AGE_20);
            addPoint(points, "RuecklaufIstTemp", wp::getRuecklaufIstTemp, MAX_AGE_20);
            addPoint(points, "SpannungInverter", wp::getSpannungInverter, MAX_AGE_20);
            addPoint(points, "StromInverter", wp::getStromInverter, MAX_AGE_20);
            addPoint(points, "LeistungInverter", wp::getLeistungInverter, MAX_AGE_20);
            addPoint(points, "VorlaufIstTemp", wp::getVorlaufIstTemp, MAX_AGE_20);
            addPoint(points, "Spreizung", wp::getSpreizung, MAX_AGE_20);
            addPoint(points, "Aussentemp", wp::getAussentemp, MAX_AGE_20);
            addPoint(points, "HeissgasTemp", wp::getHeissgasTemp, MAX_AGE_20);
            addPoint(points, "VorlaufSoll", wp::getVorlaufSoll, MAX_AGE_20);
            addPoint(points, "Anforderung", wp::getAnforderung, MAX_AGE_20);
            // Read along with can.logall, see ElsterTable: fine flow and return, pump, controller, and raw values
            // not decoded yet
            addPoint(points, "VorlaufFein", wp::getVorlaufFein, MAX_AGE_20);
            addPoint(points, "RuecklaufFein", wp::getRuecklaufFein, MAX_AGE_20);
            addPoint(points, "Fuehler_FDF5", wp::getFdf5, MAX_AGE_20);
            addPoint(points, "PumpePWM", wp::getPumpePwm, MAX_AGE_20);
            addPoint(points, "RegelIntegral", wp::getRegelIntegral, MAX_AGE_20);
            addPoint(points, "Roh_FE07", () -> wp.mitgelesenerWert(ROH_FE07, 1), MAX_AGE_20);
            addPoint(points, "Roh_07A8", () -> wp.mitgelesenerWert(ROH_07A8, 1), MAX_AGE_20);
            addPoint(points, "Roh_0A36", () -> wp.mitgelesenerWert(ROH_0A36, 1), MAX_AGE_20);
            addPoint(points, "Roh_FE09", () -> wp.mitgelesenerWert(ROH_FE09, 1), MAX_AGE_20);
            addPoint(points, "AussentempWPM", wp::getAussentempWpm, MAX_AGE_20);
            addPoint(points, "RuecklaufWPM", wp::getRuecklaufWpm, MAX_AGE_20);
            addPoint(points, "FrequenzAnforderung", wp::getFrequenzAnforderung, MAX_AGE_20);
            addPoint(points, "VerdichterDrehzahlHz", wp::getVerdichterDrehzahl, MAX_AGE_20);
            addPoint(points, "VerdichterEintrittstemp", wp::getVerdichterEintrittstemp, MAX_AGE_20);
            addPoint(points, "VerdampferTemp", wp::getVerdampferTemp, MAX_AGE_20);
            addPoint(points, "OelsumpfTemp", wp::getOelsumpfTemp, MAX_AGE_20);
            addPoint(points, "TaupunktReferenz", wp::getTaupunktReferenz, MAX_AGE_20);
            addPoint(points, "TaupunktKompensiert", wp::getTaupunktKompensiert, MAX_AGE_20);
            addPoint(points, "Kondensationstemp", wp::getKondensationstemp, MAX_AGE_20);
            addPoint(points, "AbtauReserve", wp::getAbtauReserve, MAX_AGE_20);
            addPoint(points, "Abtauung", wp::getAbtauung, MAX_AGE_60);
            influxController.storePoints(points);

        } catch (Exception e) {
            logger.error("Error during storing data", e);
        }

    }

    private void storeValues60() {
        if (!influxController.isAktiv()) {
            return;
        }
        try {
            List<Point> points = new ArrayList<>();
            addPoint(points, "AbgabeWaerme", wp::getAbgabeWaerme, MAX_AGE_60);
            addPoint(points, "LaufzeitDHZ1", wp::getLaufzeit_DHC1, MAX_AGE_60);
            addPoint(points, "LaufzeitDHZ2", wp::getLaufzeit_DHC2, MAX_AGE_60);
            addPoint(points, "LaufzeitDHZ12", wp::getLaufzeit_DHC12, MAX_AGE_60);
            addPoint(points, "AufnahmeLeistung", wp::getAufnahmeLeistung, MAX_AGE_60);
            addPoint(points, "WaermeZusatzheizung", wp::getWaermeZusatzheizung, MAX_AGE_60);
            addPoint(points, "WasserVolumenstrom", wp::getVolumenstrom, MAX_AGE_60);
            addPoint(points, "WaermeleistungBerechnet", wp::getWaermeleistung, MAX_AGE_60);
            addPoint(points, "ArbeitszahlGeschaetzt", wp::getArbeitszahl, MAX_AGE_60);
            addPoint(points, "EffizienzKorrigiert", wp::getEffizienzKorrigiert, MAX_AGE_60);
            addPoint(points, "EffizienzZaehler", wp::getEffizienz, MAX_AGE_60);
            addPoint(points, "LaufzeitVerdichterHeizen", wp::getLaufzeitVerdichterHeizen, MAX_AGE_60);
            addPoint(points, "LaufzeitVerdichterAbtauen", wp::getLaufzeitVerdichterAbtauen, MAX_AGE_60);
            addPoint(points, "DauerLetzteAbtauung", wp::getDauerLetzteAbtauung, MAX_AGE_60);
            addPoint(points, "VerdichterStarts", wp::getVerdichterStarts, MAX_AGE_60);
            addPoint(points, "LaufzeitProStart", wp::getLaufzeitProStart, MAX_AGE_60);
            addPoint(points, "VerdichterSollDrehzahlHz", wp::getVerdichterSollDrehzahl, MAX_AGE_60);
            addPoint(points, "UeberhitzungSoll", wp::getSollUeberhitzung, MAX_AGE_60);
            addPoint(points, "UeberhitzungIst", wp::getIstUeberhitzung, MAX_AGE_60);
            addPoint(points, "OeffnungsgradEXV", wp::getOeffnungsgradExv, MAX_AGE_60);
            addPoint(points, "LuefterIstDrehzahlHz", wp::getLuefterIstDrehzahl, MAX_AGE_60);
            addPoint(points, "LuefterSollDrehzahlHz", wp::getLuefterSollDrehzahl, MAX_AGE_60);
            addPoint(points, "LuefterLeistung", wp::getLuefterLeistung, MAX_AGE_60);
            addPoint(points, "UmgebungstempInverter", wp::getUmgebungstempInverter, MAX_AGE_60);
            addPoint(points, "TempInverterVerdichter", wp::getTempInverterVerdichter, MAX_AGE_60);
            for (Waermepumpe.Rohwert rohwert : Waermepumpe.ROHWERTE) {
                addPoint(points, rohwert.name(), () -> wp.getRohwert(rohwert), MAX_AGE_60);
            }
            addPoint(points, "CAN_Empfangen", () -> can.minute(CanStatistik.Minute::empfangen), MAX_AGE_60);
            addPoint(points, "CAN_Antworten", () -> can.minute(CanStatistik.Minute::antworten), MAX_AGE_60);
            addPoint(points, "CAN_Gesendet", () -> can.minute(CanStatistik.Minute::gesendet), MAX_AGE_60);
            addPoint(points, "CAN_NichtVerfuegbar", () -> can.minute(CanStatistik.Minute::nichtVerfuegbar),
                    MAX_AGE_60);
            addPoint(points, "CAN_Antwortquote", () -> can.minute(CanStatistik.Minute::antwortquote), MAX_AGE_60);
            addPoint(points, "CAN_Buslast", () -> can.minute(CanStatistik.Minute::buslast), MAX_AGE_60);
            addPoint(points, "CAN_UsbNeustarts", can::getUsbNeustarts, MAX_AGE_60);
            for (CanStatistik.KnotenStatus knoten : can.getStatistik().getKnoten()) {
                addPoint(points, CanBus.knotenSerie(knoten.id()), () -> can.minute(m -> knoten.proMinute()), MAX_AGE_60);
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
        if (!influxController.isAktiv()) {
            return;
        }
        try {
            List<Point> points = new ArrayList<>();
            for (Waermepumpe.Einstellung einstellung : wp.getEinstellungen()) {
                addPoint(points, "Einstellung_" + einstellung.name(), einstellung.wert(), MAX_ALTER_EINSTELLUNG);
            }
            influxController.storePoints(points);

        } catch (Exception e) {
            logger.error("Error during storing data", e);
        }

    }
}
