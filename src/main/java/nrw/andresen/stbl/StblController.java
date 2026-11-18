package nrw.andresen.stbl;

import nrw.andresen.stbl.services.CanBus;
import nrw.andresen.stbl.services.CanBusSeite;
import nrw.andresen.stbl.services.EmailService;
import nrw.andresen.stbl.services.Fehlstarts;
import nrw.andresen.stbl.services.Konfiguration;
import nrw.andresen.stbl.services.KonfigurationSeite;
import nrw.andresen.stbl.services.PushoverService;
import nrw.andresen.stbl.services.Rahmen;
import nrw.andresen.stbl.services.SignalService;
import nrw.andresen.stbl.services.StatusService;
import nrw.andresen.stbl.services.Tagesuebersicht;
import nrw.andresen.stbl.services.Ueberwachung;
import nrw.andresen.stbl.services.Version;
import nrw.andresen.stbl.services.WaermebedarfVergleich;
import nrw.andresen.stbl.services.Jahresvergleich;
import nrw.andresen.stbl.services.JahresvergleichSeite;
import nrw.andresen.stbl.services.Zustand;
import nrw.andresen.stbl.services.ZustandSeite;
import nrw.andresen.stbl.services.Waermepumpe;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private SignalService signalService;
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
    @Autowired
    private Konfiguration konfiguration;
    @Autowired
    private EmailService emailService;
    @Autowired
    private PushoverService pushover;
    // Result of the last test message for the configuration page
    private volatile String testErgebnis;
    @Autowired
    private Jahresvergleich jahresvergleich;
    @Autowired
    private Zustand zustand;
    @Autowired
    private Version version;
    // The answer with the waiting page has to reach the browser before the service closes
    private static final Duration NEUSTART_VERZOEGERUNG = Duration.ofSeconds(2);

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



    /**
     * The address of the service alone opens the overview
     */
    @GetMapping("/")
    public ResponseEntity<Void> start() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "status").build();
    }

    @RequestMapping(value = "/status", produces = "text/html;charset=UTF-8")
    public String status() throws Exception{
        return statusService.getStatus();
    }

    /**
     * All values of the heat pump, warnings, fault list, evaluations and the service
     */
    @GetMapping(value = "/anlage", produces = "text/html;charset=UTF-8")
    public String anlage() {
        return statusService.getAnlage();
    }

    /**
     * Electricity of the heating per month in the last three years as a page, with haushalt=ja and wallbox=ja also
     * the household electricity and the wallbox
     */
    @GetMapping(value = "/jahresvergleich", produces = "text/html;charset=UTF-8")
    public String jahresvergleich(@RequestParam(required = false) String haushalt,
                                  @RequestParam(required = false) String wallbox) {
        return Rahmen.einsetzen(JahresvergleichSeite.render(jahresvergleich.getVergleich(), influxController.isAktiv()
                        ? null : "Für den Jahresvergleich braucht der Dienst die InfluxDB, sie ist ausgeschaltet "
                        + "(influx.enabled=false).", "ja".equals(haushalt), "ja".equals(wallbox)),
                signalService.kopf("jahresvergleich", ""));
    }

    /**
     * Year comparison as JSON, null until it is calculated
     */
    @GetMapping("/jahresvergleich/daten")
    public Jahresvergleich.Vergleich jahresvergleichDaten() {
        return jahresvergleich.getVergleich();
    }

    /**
     * Condition of the heat pump over the heating seasons: values that show ageing or a problem
     */
    @GetMapping(value = "/zustand", produces = "text/html;charset=UTF-8")
    public String zustand() {
        return Rahmen.einsetzen(ZustandSeite.render(zustand.getErgebnis(), influxController.isAktiv() ? null
                : "Für den Zustand braucht der Dienst die InfluxDB, sie ist ausgeschaltet (influx.enabled=false)."),
                signalService.kopf("zustand", ""));
    }

    /**
     * Condition over the heating seasons as JSON, null until it is calculated
     */
    @GetMapping("/zustand/daten")
    public Zustand.Ergebnis zustandDaten() {
        return zustand.getErgebnis();
    }

    /**
     * Configuration page: settings of this service from the configuration file, linked with the status page
     */
    @GetMapping(value = "/konfiguration", produces = "text/html;charset=UTF-8")
    public String konfiguration(@RequestParam(required = false) String meldung, HttpServletRequest request) {
        return konfigurationSeite(Map.of(), Map.of(), meldung, request);
    }

    /**
     * CAN bus monitoring page: adapter, rates, nodes, requested values and the last messages
     */
    @GetMapping(value = "/canbus", produces = "text/html;charset=UTF-8")
    public String canbus(@RequestParam(required = false) String knoten,
                         @RequestParam(defaultValue = "" + CanBusSeite.ANZAHL_STANDARD) int anzahl) {
        return Rahmen.einsetzen(CanBusSeite.render(canBus.getVerbindung(), canBus.getAbfragen(),
                canBus.getStatistik().getRahmen(), CanBusSeite.knoten(knoten), CanBusSeite.anzahl(anzahl),
                Instant.now()), signalService.kopf("canbus", ""));
    }

    /**
     * Page of the scan of all Elster indices: state, start button and the answers with a value
     */
    @GetMapping(value = "/canbus/scan", produces = "text/html;charset=UTF-8")
    public String scan(@RequestParam(defaultValue = "nein") String nullen, HttpServletRequest request) {
        // The start button sends a POST, Spring Security wants the token against forms from other sites
        Object csrf = request.getAttribute(CsrfToken.class.getName());
        return Rahmen.einsetzen(CanBusSeite.scan(canBus.getIndexScan().status(), canBus.getIndexScan().ergebnisse(),
                canBus.getAngefragt(), "ja".equals(nullen),
                csrf instanceof CsrfToken token ? token.getParameterName() : null,
                csrf instanceof CsrfToken token ? token.getToken() : null, Instant.now()),
                signalService.kopf("canbus", "../"));
    }

    /**
     * Starts the scan of all Elster indices, read only, about 25 minutes; back to the scan page
     */
    @PostMapping("/canbus/scan")
    public ResponseEntity<Void> scanStarten() {
        canBus.starteScan();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, "scan").build();
    }

    @GetMapping(value = "/canbus/scan.csv", produces = "text/csv;charset=UTF-8")
    public String scanCsv() {
        return canBus.getIndexScan().csv();
    }

    /**
     * Start time of the service; the page shown during a restart waits until it changes
     */
    @GetMapping(value = "/konfiguration/gestartet", produces = "text/plain;charset=UTF-8")
    public String gestartet() {
        return version.getGestartet().toString();
    }

    /**
     * Checks the form and writes the values into the configuration file. With the button "Speichern und neu
     * starten" the service then restarts itself if the file differs from the running service. "Nur speichern"
     * answers with a redirect to the page, so a reload does not send the form again.
     */
    @PostMapping(value = "/konfiguration", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> konfigurationSpeichern(@RequestParam Map<String, String> eingabe,
                                                         HttpServletRequest request) {
        Map<String, String> werte = new LinkedHashMap<>();
        Map<String, String> fehler = new LinkedHashMap<>(Konfiguration.pruefen(eingabe, werte));
        if (fehler.isEmpty()) {
            try {
                konfiguration.speichern(werte);
                logger.info("Configuration file changed on the configuration page: " + werte.keySet());
                boolean sofort = sofortUebernehmen();
                String meldung = sofort && !konfiguration.stand().neustartNoetig() ? KonfigurationSeite.SOFORT
                        : KonfigurationSeite.GESPEICHERT;
                if (KonfigurationSeite.NEUSTART.equals(eingabe.get("aktion"))) {
                    if (!konfiguration.stand().neustartNoetig()) {
                        meldung = sofort ? KonfigurationSeite.SOFORT : KonfigurationSeite.UNVERAENDERT;
                    } else if (Application.neustarten(NEUSTART_VERZOEGERUNG)) {
                        return ResponseEntity.ok(KonfigurationSeite.neustart(gestartet()));
                    }
                }
                return ResponseEntity.status(HttpStatus.SEE_OTHER)
                        .header(HttpHeaders.LOCATION, "konfiguration?meldung=" + meldung).build();
            } catch (Exception e) {
                logger.warn("Configuration file could not be written: " + e.getMessage());
                fehler.put("", "Die Datei ließ sich nicht schreiben: " + e.getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(konfigurationSeite(eingabe, fehler, null, request));
    }

    /**
     * Hands the saved settings that need no restart to the running service
     *
     * @return true if one was changed
     */
    private boolean sofortUebernehmen() {
        boolean geaendert = false;
        for (Konfiguration.Wert wert : konfiguration.stand().werte()) {
            String name = wert.feld().name();
            if (Konfiguration.SOFORT.contains(name) && wert.neustartNoetig()) {
                if (name.equals("can.logall")) {
                    canBus.setLogall(Boolean.parseBoolean(wert.datei()));
                }
                konfiguration.uebernommen(name, wert.datei());
                geaendert = true;
            }
        }
        return geaendert;
    }

    /**
     * Sends a test alert by mail and Pushover with the settings of the running service and shows the result
     */
    @PostMapping(value = "/konfiguration/test")
    public ResponseEntity<Void> konfigurationTest() {
        emailService.sendAlertSofort("Wärmepumpe: Testnachricht", "Testnachricht von der Konfigurationsseite. Kommt sie "
                + "an, kommen auch die Warnungen an.");
        List<String> teile = new ArrayList<>();
        teile.add(!emailService.isKonfiguriert() ? "Mail nicht eingerichtet"
                : emailService.getLetzterFehler() == null ? "Mail gesendet"
                : "Mail fehlgeschlagen: " + emailService.getLetzterFehler());
        teile.add(!pushover.isKonfiguriert() ? "Pushover nicht eingerichtet"
                : pushover.getLetzterFehler() == null ? "Pushover gesendet"
                : "Pushover fehlgeschlagen: " + pushover.getLetzterFehler());
        testErgebnis = "Testnachricht: " + String.join(", ", teile) + ".";
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, "../konfiguration?meldung=" + KonfigurationSeite.TEST).build();
    }

    private String konfigurationSeite(Map<String, String> eingabe, Map<String, String> fehler, String meldung,
                                      HttpServletRequest request) {
        // Token against forms sent from other sites, checked by Spring Security for every POST
        Object csrf = request.getAttribute(CsrfToken.class.getName());
        return Rahmen.einsetzen(KonfigurationSeite.render(konfiguration.stand(), eingabe, fehler, meldung,
                csrf instanceof CsrfToken token ? token.getParameterName() : null,
                csrf instanceof CsrfToken token ? token.getToken() : null, testErgebnis, statusService.getSystem()),
                signalService.kopf("konfiguration", ""));
    }

    /**
     * Course of a stored value as means, name as in InfluxDB without WP_, range 6h, 24h (default), 7d, 30d or 1y
     */
    @RequestMapping("/history/{name}")
    public InfluxController.History history(@PathVariable String name,
                                            @RequestParam(defaultValue = "24h") String range) {
        HistoryRange historyRange = historyRange(range);
        if (StatusService.WIRKLEISTUNG.equals(name)) {
            // Converted value by value before the means, like the electricity of the days
            InfluxController.History schein = influxController.history("LeistungInverter", historyRange.range(),
                    historyRange.window(), Waermepumpe.wirkleistungFlux(50));
            return new InfluxController.History(name, schein.time(), schein.value());
        }
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
