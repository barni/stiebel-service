package nrw.andresen.stbl;

import nrw.andresen.stbl.services.CanBus;
import nrw.andresen.stbl.services.Fehlstarts;
import nrw.andresen.stbl.services.StatusService;
import nrw.andresen.stbl.services.Tagesuebersicht;
import nrw.andresen.stbl.services.Ueberwachung;
import nrw.andresen.stbl.services.WaermebedarfVergleich;
import nrw.andresen.stbl.services.Waermepumpe;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Main rest-controller
 */
@RestController
public class StblController {

    private static final Logger logger = LoggerFactory.getLogger(StblController.class);

    /**
     * Selectable ranges of the course shown when a value on the status page is clicked, the mean window keeps it at
     * about 360 to 730 points
     */
    record HistoryRange(Duration range, Duration window) {
    }

    private static final Map<String, HistoryRange> HISTORY_RANGES = Map.of(
            "6h", new HistoryRange(Duration.ofHours(6), Duration.ofMinutes(1)),
            "24h", new HistoryRange(Duration.ofHours(24), Duration.ofMinutes(2)),
            "7d", new HistoryRange(Duration.ofDays(7), Duration.ofMinutes(15)),
            "30d", new HistoryRange(Duration.ofDays(30), Duration.ofHours(1)),
            "1y", new HistoryRange(Duration.ofDays(365), Duration.ofHours(12)));

    @Autowired
    private Waermepumpe waermepumpe;
    @Autowired
    private CanBus canBus;
    @Autowired
    private StatusService statusService;
    @Autowired
    private WaermebedarfVergleich waermebedarfVergleich;
    @Autowired
    private Tagesuebersicht tagesuebersicht;
    @Autowired
    private Ueberwachung ueberwachung;
    @Autowired
    private Fehlstarts fehlstarts;

    record Tage(Tagesuebersicht.Tag heute, Tagesuebersicht.Tag gestern, Tagesuebersicht.Vergleichstage aehnlich) {
    }

    record WarnungStatus(String name, boolean aktiv, Instant seit, String text, String regel) {
    }

    record UeberwachungStatus(List<WarnungStatus> warnungen, List<Ueberwachung.Pruefung> pruefungen,
                              Instant geprueft) {
    }
    @Autowired
    private InfluxController influxController;

    /**
     * The getters throw an exception if no current value is available, e.g. VERDICHTER_AUS or NO_VALUES_RECEIVED.
     * Answer 503 with the reason instead of 500 with a stack trace, Home Assistant shows the sensor as unavailable.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> keinWert(Exception e) {
        logger.debug("No value: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> ungueltig(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }



    @RequestMapping(value = "/status", produces = "text/html;charset=UTF-8")
    public String status() throws Exception{
        return statusService.getStatus();
    }

    /**
     * Course of a stored value as means, name as in InfluxDB without WP_, range 6h, 24h (default), 7d, 30d or 1y
     */
    @RequestMapping("/history/{name}")
    public InfluxController.History history(@PathVariable String name,
                                            @RequestParam(defaultValue = "24h") String range) {
        HistoryRange historyRange = historyRange(range);
        return influxController.history(name, historyRange.range(), historyRange.window());
    }

    static HistoryRange historyRange(String range) {
        HistoryRange historyRange = HISTORY_RANGES.get(range);
        if (historyRange == null) {
            throw new IllegalArgumentException("Unbekannter Zeitbereich: " + range);
        }
        return historyRange;
    }

    /**
     * State of the USBtin adapter and the CAN bus: version, restarts, messages per minute, answer rate, bus load
     */
    @RequestMapping("/verbindung")
    public CanBus.Verbindung verbindung() {
        return canBus.getVerbindung();
    }

    /**
     * Fault list of the WPM, the newest entry first
     */
    @RequestMapping("/fehlerliste")
    public List<Fehlerliste.Eintrag> fehlerliste() {
        return canBus.getFehlerliste().eintraege();
    }

    /**
     * Starts, run time and heat per day by outdoor temperature and setting Wärmebedarf, updated every 6 hours
     */
    @RequestMapping("/waermebedarf")
    public List<WaermebedarfVergleich.Gruppe> waermebedarf() {
        return waermebedarfVergleich.getGruppen();
    }

    /**
     * Starts, run time, heat, estimated electric energy, efficiency and defrosts of today and yesterday
     */
    @RequestMapping("/tagesuebersicht")
    public Tage tagesuebersicht() {
        return new Tage(tagesuebersicht.getHeute(), tagesuebersicht.getGestern(), tagesuebersicht.getAehnlicheTage());
    }

    /**
     * Active warnings and the result of the checks of the service
     */
    @RequestMapping("/ueberwachung")
    public UeberwachungStatus ueberwachung() {
        return new UeberwachungStatus(ueberwachung.getWarnungen().stream()
                .map(w -> new WarnungStatus(w.getName(), w.isAktiv(), w.getAktivSeit(), w.getText(), w.getRegel()))
                .toList(), ueberwachung.getPruefungen(), ueberwachung.getGeprueft());
    }

    /**
     * Starts and failed starts per heating season, the current one first, updated every hour
     */
    @RequestMapping("/fehlstarts")
    public List<Fehlstarts.Saison> fehlstarts() {
        return fehlstarts.getSaisons();
    }

    @RequestMapping("/aufnahmeLeistung")
    public ValueContainer<Double> aufnahmeLeistung() throws Exception{
        return waermepumpe.getAufnahmeLeistung();
    }

    @RequestMapping("/abgabeWaerme")
    public ValueContainer<Double> abgabeWaerme() throws Exception{
        return waermepumpe.getAbgabeWaerme();
    }

    @RequestMapping("/betriebstatus")
    public ValueContainer<Short> betriebstatus() throws Exception {
        return waermepumpe.getBetriebstatus();
    }

    @RequestMapping("/vorlaufIstTemp")
    public ValueContainer<Double> vorlaufIstTemp() throws Exception {
        return waermepumpe.getVorlaufIstTemp();
    }

    @RequestMapping("/ruecklaufIstTemp")
    public ValueContainer<Double> ruecklaufIstTemp() throws Exception {
        return waermepumpe.getRuecklaufIstTemp();
    }

    @RequestMapping("/stromInverter")
    public ValueContainer<Double> speicherIstTemp() throws Exception {
        return waermepumpe.getStromInverter();
    }


    @RequestMapping("/spannungInverter")
    public ValueContainer<Double> spannungInverter() throws Exception {
        return waermepumpe.getSpannungInverter();
    }


    @RequestMapping("/hochdruck")
    public ValueContainer<Double> hochdruck() throws Exception {
        return waermepumpe.getHochdruck();
    }

    @RequestMapping("/niederdruck")
    public ValueContainer<Double> niederdruck() throws Exception {
        return waermepumpe.getNiederdruck();
    }

    @RequestMapping("/heizungsdruck")
    public ValueContainer<Double>  heizungsdruck() throws Exception {
        return waermepumpe.getHeizungsdruck();
    }

    @RequestMapping("/waermeZusatzheizung")
    public ValueContainer<Double> waermeZusatzheizung() throws Exception {
        return waermepumpe.getWaermeZusatzheizung();
    }

    @RequestMapping("/aussentemp")
    public ValueContainer<Double> aussentemp() throws Exception {
        return waermepumpe.getAussentemp();
    }

    @RequestMapping("/heissgasTemp")
    public ValueContainer<Double> heissgasTemp() throws Exception {
        return waermepumpe.getHeissgasTemp();
    }

    @RequestMapping("/verdichterDrehzahl")
    public ValueContainer<Double> verdichterDrehzahl() throws Exception {
        return waermepumpe.getVerdichterDrehzahl();
    }

    @RequestMapping("/verdichterEintrittstemp")
    public ValueContainer<Double> verdichterEintrittstemp() throws Exception {
        return waermepumpe.getVerdichterEintrittstemp();
    }

    @RequestMapping("/verdampferTemp")
    public ValueContainer<Double> verdampferTemp() throws Exception {
        return waermepumpe.getVerdampferTemp();
    }

    @RequestMapping("/oelsumpfTemp")
    public ValueContainer<Double> oelsumpfTemp() throws Exception {
        return waermepumpe.getOelsumpfTemp();
    }

    @RequestMapping("/effizienzKorrigiert")
    public ValueContainer<Double> effizienzKorrigiert() throws Exception {
        return waermepumpe.getEffizienzKorrigiert();
    }

    @RequestMapping("/volumenstrom")
    public ValueContainer<Double> volumenstrom() throws Exception {
        return waermepumpe.getVolumenstrom();
    }

    @RequestMapping("/waermeleistung")
    public ValueContainer<Double> waermeleistung() throws Exception {
        return waermepumpe.getWaermeleistung();
    }

    @RequestMapping("/arbeitszahl")
    public ValueContainer<Double> arbeitszahl() throws Exception {
        return waermepumpe.getArbeitszahl();
    }

    @RequestMapping("/laufzeit_DHC1")
    public ValueContainer<Double>  laufzeit_DHC1() throws Exception {
        return waermepumpe.getLaufzeit_DHC1();
    }
    @RequestMapping("/laufzeit_DHC2")
    public ValueContainer<Double>  laufzeit_DHC2() throws Exception {
        return waermepumpe.getLaufzeit_DHC2();
    }
    @RequestMapping("/laufzeit_DHC12")
    public ValueContainer<Double>  laufzeit_DHC12() throws Exception {
        return waermepumpe.getLaufzeit_DHC12();
    }

    @RequestMapping("/laufzeitVerdichterHeizen")
    public ValueContainer<Double> laufzeitVerdichterHeizen() throws Exception {
        return waermepumpe.getLaufzeitVerdichterHeizen();
    }

    @RequestMapping("/laufzeitVerdichterAbtauen")
    public ValueContainer<Double> laufzeitVerdichterAbtauen() throws Exception {
        return waermepumpe.getLaufzeitVerdichterAbtauen();
    }

    @RequestMapping("/verdichterStarts")
    public ValueContainer<Double> verdichterStarts() throws Exception {
        return waermepumpe.getVerdichterStarts();
    }

    @RequestMapping("/ueberhitzung")
    public ValueContainer<Double> ueberhitzung() throws Exception {
        return waermepumpe.getIstUeberhitzung();
    }

    @RequestMapping("/luefterDrehzahl")
    public ValueContainer<Double> luefterDrehzahl() throws Exception {
        return waermepumpe.getLuefterIstDrehzahl();
    }

}
