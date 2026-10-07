package nrw.andresen.stbl;

import nrw.andresen.stbl.services.StblService;
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
    private StblService stblService;
    @Autowired
    private InfluxController influxController;

    /**
     * The getters throw an exception if no current value is available, e.g. VERDICHTER_AUS or NO_VALUES_REVEIVED.
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
        return stblService.getStatus();
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
    public StblService.Verbindung verbindung() {
        return stblService.getVerbindung();
    }

    @RequestMapping("/aufnahmeLeistung")
    public ValueContainer<Double> aufnahmeLeistung() throws Exception{
        return stblService.getAufnahmeLeistung();
    }

    @RequestMapping("/abgabeWaerme")
    public ValueContainer<Double> abgabeWaerme() throws Exception{
        return stblService.getAbgabeWaerme();
    }

    @RequestMapping("/betriebstatus")
    public ValueContainer<Short> betriebstatus() throws Exception {
        return stblService.getBetriebstatus();
    }

    @RequestMapping("/vorlaufIstTemp")
    public ValueContainer<Double> vorlaufIstTemp() throws Exception {
        return stblService.getVorlaufIstTemp();
    }

    @RequestMapping("/ruecklaufIstTemp")
    public ValueContainer<Double> ruecklaufIstTemp() throws Exception {
        return stblService.getRuecklaufIstTemp();
    }

    @RequestMapping("/stromInverter")
    public ValueContainer<Double> speicherIstTemp() throws Exception {
        return stblService.getStromInverter();
    }


    @RequestMapping("/spannungInverter")
    public ValueContainer<Double> spannungInverter() throws Exception {
        return stblService.getSpannungInverter();
    }


    @RequestMapping("/hochdruck")
    public ValueContainer<Double> hochdruck() throws Exception {
        return stblService.getHochdruck();
    }

    @RequestMapping("/niederdruck")
    public ValueContainer<Double> niederdruck() throws Exception {
        return stblService.getNiederdruck();
    }

    @RequestMapping("/heizungsdruck")
    public ValueContainer<Double>  heizungsdruck() throws Exception {
        return stblService.getHeizungsdruck();
    }

    @RequestMapping("/waermeZusatzheizung")
    public ValueContainer<Double> waermeZusatzheizung() throws Exception {
        return stblService.getWaermeZusatzheizung();
    }

    @RequestMapping("/aussentemp")
    public ValueContainer<Double> aussentemp() throws Exception {
        return stblService.getAussentemp();
    }

    @RequestMapping("/heissgasTemp")
    public ValueContainer<Double> heissgasTemp() throws Exception {
        return stblService.getHeissgasTemp();
    }

    @RequestMapping("/verdichterDrehzahl")
    public ValueContainer<Double> verdichterDrehzahl() throws Exception {
        return stblService.getVerdichterDrehzahl();
    }

    @RequestMapping("/verdichterEintrittstemp")
    public ValueContainer<Double> verdichterEintrittstemp() throws Exception {
        return stblService.getVerdichterEintrittstemp();
    }

    @RequestMapping("/verdampferTemp")
    public ValueContainer<Double> verdampferTemp() throws Exception {
        return stblService.getVerdampferTemp();
    }

    @RequestMapping("/oelsumpfTemp")
    public ValueContainer<Double> oelsumpfTemp() throws Exception {
        return stblService.getOelsumpfTemp();
    }

    @RequestMapping("/effizienzKorrigiert")
    public ValueContainer<Double> effizienzKorrigiert() throws Exception {
        return stblService.getEffizienzKorrigiert();
    }

    @RequestMapping("/volumenstrom")
    public ValueContainer<Double> volumenstrom() throws Exception {
        return stblService.getVolumenstrom();
    }

    @RequestMapping("/waermeleistung")
    public ValueContainer<Double> waermeleistung() throws Exception {
        return stblService.getWaermeleistung();
    }

    @RequestMapping("/arbeitszahl")
    public ValueContainer<Double> arbeitszahl() throws Exception {
        return stblService.getArbeitszahl();
    }

    @RequestMapping("/laufzeit_DHC1")
    public ValueContainer<Double>  laufzeit_DHC1() throws Exception {
        return stblService.getLaufzeit_DHC1();
    }
    @RequestMapping("/laufzeit_DHC2")
    public ValueContainer<Double>  laufzeit_DHC2() throws Exception {
        return stblService.getLaufzeit_DHC2();
    }
    @RequestMapping("/laufzeit_DHC12")
    public ValueContainer<Double>  laufzeit_DHC12() throws Exception {
        return stblService.getLaufzeit_DHC12();
    }

    @RequestMapping("/laufzeitVerdichterHeizen")
    public ValueContainer<Double> laufzeitVerdichterHeizen() throws Exception {
        return stblService.getLaufzeitVerdichterHeizen();
    }

    @RequestMapping("/laufzeitVerdichterAbtauen")
    public ValueContainer<Double> laufzeitVerdichterAbtauen() throws Exception {
        return stblService.getLaufzeitVerdichterAbtauen();
    }

    @RequestMapping("/verdichterStarts")
    public ValueContainer<Double> verdichterStarts() throws Exception {
        return stblService.getVerdichterStarts();
    }

    @RequestMapping("/ueberhitzung")
    public ValueContainer<Double> ueberhitzung() throws Exception {
        return stblService.getIstUeberhitzung();
    }

    @RequestMapping("/luefterDrehzahl")
    public ValueContainer<Double> luefterDrehzahl() throws Exception {
        return stblService.getLuefterIstDrehzahl();
    }

}
