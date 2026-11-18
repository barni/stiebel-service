package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * HTML status page with all current values, the fault list and the state of the CAN connection
 */
@Component
public class StatusService {

    @Autowired
    private Waermepumpe wp;
    @Autowired
    private CanBus can;
    @Autowired
    private WaermebedarfVergleich vergleich;
    @Autowired
    private Tagesuebersicht tagesuebersicht;
    @Autowired
    private Ueberwachung ueberwachung;
    @Autowired
    private Fehlstarts fehlstarts;
    @Autowired
    private Version version;
    @Autowired
    private InfluxController influx;
    @Autowired
    private SignalService signale;
    @Autowired
    private Verlauf24h verlauf24h;
    @Autowired
    private Zustand zustand;
    // Course of the estimated real power: the apparent power of the inverter converted value by value
    public static final String WIRKLEISTUNG = "Wirkleistung";



    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm:ss");

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static String zahl(Double wert, int stellen) {
        return wert == null ? "–" : String.format(Locale.GERMANY, "%." + stellen + "f", wert);
    }

    private static String zeitpunkt(Instant zeit) {
        return zeit == null ? null : ZEITPUNKT.format(zeit.atZone(ZoneId.systemDefault()));
    }

    /**
     * Overview: what the heat pump does right now, tiles with the last 24 hours, a timeline, today and yesterday,
     * faults and checks. All other values are on the page Anlage.
     *
     * @return HTML page
     */
    public String getStatus() {
        StatusPage page = new StatusPage();
        page.kopf(signale.kopf("status", ""), "Wärmepumpe");
        if (!influx.isAktiv()) {
            page.ohneVerlauf();
        }
        Verlauf24h.Daten d = influx.isAktiv() ? verlauf24h.get() : null;
        page.oben(Uebersicht.jetzt(jetzt(d)));
        page.oben(Uebersicht.kacheln(kacheln(d)));
        if (d != null) {
            page.oben(Uebersicht.zeitleiste(zeitleiste(d), ZoneId.systemDefault()));
            page.verlaufDialog();
        }
        page.bereich(null);
        if (influx.isAktiv()) {
            tagesuebersicht(page);
        }
        stoerungen(page);
        pruefungen(page);
        return page.render();
    }

    /**
     * All values of the heat pump, the warnings and the fault list, the evaluations and the service
     *
     * @return HTML page
     */
    public String getAnlage() {
        StatusPage page = new StatusPage();
        page.kopf(signale.kopf("anlage", "") + "<div class=\"seitentitel\"><div><h1>Anlage</h1><p class=\"sub\">"
                + "Alle Messwerte, Einstellungen der Wärmepumpe und Meldungen; ein Klick auf einen Wert zeigt seinen "
                + "Verlauf."
                + "</p></div></div>", "Wärmepumpe · Anlage");
        if (!influx.isAktiv()) {
            // Without InfluxDB: no courses of the values and none of the cards that are calculated from stored values
            page.ohneVerlauf();
        }
        page.bereich("Meldungen", "meldungen");
        warnungen(page);
        fehlerliste(page);
        anlage(page);
        auswertung(page);
        return page.render();
    }

    /**
     * State of the service, the software of the devices and the USB adapter, shown on the configuration page
     *
     * @return HTML of the area, without page
     */
    public String getSystem() {
        StatusPage page = new StatusPage().ohneVerlauf();
        system(page);
        return page.renderBereiche();
    }

    private static <T extends java.io.Serializable> T wert(java.util.concurrent.Callable<ValueContainer<T>> quelle) {
        try {
            return quelle.call().getValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static String zahl(double wert, int stellen, String einheit) {
        return zahl(wert, stellen) + (einheit.isEmpty() ? "" : " " + einheit);
    }

    /**
     * State right now: running, defrosting or standing still, with the numbers of today
     */
    private Uebersicht.Jetzt jetzt(Verlauf24h.Daten d) {
        Boolean laeuft = wert(wp::isVerdichterOn);
        Boolean abtauung = wert(wp::isAbtauung);
        Instant jetzt = Instant.now();
        List<Uebersicht.Zahl> heute = heuteZahlen();
        if (Boolean.TRUE.equals(abtauung)) {
            return new Uebersicht.Jetzt("Taut ab", "abtauen", "üblich 2 bis 5 Minuten", "Der Kreislauf läuft "
                    + "umgekehrt: Heißgas schmilzt das Eis am Verdampfer, die Wärme dafür kommt kurz aus dem "
                    + "Heizwasser.", null, heute);
        }
        if (laeuft == null) {
            return new Uebersicht.Jetzt("Keine Daten", "neutral", "vom CAN-Bus kam noch keine Antwort", null, null,
                    heute);
        }
        if (laeuft) {
            List<String> zeile = new ArrayList<>();
            Instant seit = wp.getVerdichterLaeuftSeit();
            if (seit != null) {
                zeile.add("seit " + SignalService.dauer(seit, jetzt));
            }
            Double schein = wert(wp::getLeistungInverter);
            if (schein != null && schein > 0) {
                zeile.add("Strom ≈ " + SignalService.kw(Waermepumpe.wirkleistung(schein)) + " kW");
            }
            Double waerme = wert(wp::getWaermeleistung);
            if (waerme != null) {
                zeile.add("Wärme " + zahl(waerme, 1) + " kW");
            }
            Double az = wert(wp::getArbeitszahl);
            if (az != null) {
                zeile.add("Arbeitszahl ≈ " + zahl(az, 1));
            }
            List<String> text = new ArrayList<>();
            Double vl = wert(wp::getVorlaufFein);
            Double rl = wert(wp::getRuecklaufFein);
            if (vl != null && rl != null) {
                text.add("Vorlauf " + zahl(vl, 1) + " °C, Rücklauf " + zahl(rl, 1) + " °C");
            }
            Double fluss = wert(wp::getVolumenstrom);
            if (fluss != null) {
                text.add("Durchfluss " + zahl(fluss, 1) + " l/min");
            }
            Double hz = wert(wp::getVerdichterDrehzahl);
            if (hz != null) {
                text.add("Verdichter " + zahl(hz, 0) + " Hz");
            }
            Double reserve = wert(wp::getAbtauReserve);
            Uebersicht.Balken balken = reserve == null
                    ? new Uebersicht.Balken("Reserve bis Abtauung", "", "", 0, "neutral",
                    "Erscheint " + Waermepumpe.ABTAU_RESERVE_AB.toMinutes() + " Minuten nach dem Start, wenn die "
                            + "Wärmepumpe ihren Bezug gelernt hat.")
                    : new Uebersicht.Balken("Reserve bis Abtauung", zahl(reserve, 1) + " von "
                    + zahl(Waermepumpe.ABTAU_SCHWELLE_K, 1) + " K", "", reserve / Waermepumpe.ABTAU_SCHWELLE_K,
                    reserve < 1 ? "achtung" : "aktiv", "Sinkt, während Eis am Verdampfer wächst; bei 0 taut die "
                    + "Wärmepumpe ab.");
            return new Uebersicht.Jetzt("Heizt", "aktiv", String.join(" · ", zeile),
                    text.isEmpty() ? null : String.join(", ", text) + ".", balken, heute);
        }
        Instant gestoppt = wp.getVerdichterGestopptUm();
        String zeile = gestoppt == null ? "" : "seit " + SignalService.UHRZEIT.format(gestoppt) + " · "
                + SignalService.dauer(gestoppt, jetzt);
        List<String> text = new ArrayList<>();
        if (d != null) {
            List<long[]> laeufe = laeufe(d);
            if (!laeufe.isEmpty()) {
                long[] l = laeufe.get(laeufe.size() - 1);
                text.add("Letzter Lauf etwa " + SignalService.UHRZEIT.format(Instant.ofEpochMilli(l[0])) + "–"
                        + SignalService.UHRZEIT.format(Instant.ofEpochMilli(l[1])) + ".");
            }
        }
        Double integral = wert(wp::getRegelIntegral);
        Uebersicht.Balken balken = null;
        if (integral != null) {
            text.add("Gestartet wird, wenn das Regler-Integral auf −1000 gefallen ist.");
            balken = new Uebersicht.Balken("−1000 · Start", "Regler-Integral " + zahl(integral, 0), "+1000 · Stopp",
                    (integral + 1000) / 2000, "neutral", null);
        }
        return new Uebersicht.Jetzt("Stillstand", "neutral", zeile, text.isEmpty() ? null : String.join(" ", text),
                balken, heute);
    }

    private List<Uebersicht.Zahl> heuteZahlen() {
        Tagesuebersicht.Tag heute = tagesuebersicht.getHeute();
        Tagesuebersicht.Tag gestern = tagesuebersicht.getGestern();
        if (heute == null || gestern == null) {
            return List.of();
        }
        return List.of(
                new Uebersicht.Zahl("Laufzeit heute", zahl(heute.laufzeitH(), 1), "h",
                        "gestern " + zahl(gestern.laufzeitH(), 1, "h")),
                new Uebersicht.Zahl("Starts", String.valueOf(heute.starts()), "", "gestern " + gestern.starts()),
                new Uebersicht.Zahl("Wärme", zahl(heute.waermeKWh(), 1), "kWh",
                        "gestern " + zahl(gestern.waermeKWh(), 1, "kWh")),
                new Uebersicht.Zahl("Strom ≈", zahl(heute.stromKWh(), 1), "kWh",
                        "gestern " + zahl(gestern.stromKWh(), 1, "kWh")),
                new Uebersicht.Zahl("Arbeitszahl ≈", zahl(heute.arbeitszahl(), 1), "",
                        "gestern " + zahl(gestern.arbeitszahl(), 1)),
                new Uebersicht.Zahl("Abtauungen", String.valueOf(heute.abtauungen()), "",
                        "gestern " + gestern.abtauungen()));
    }

    /**
     * Estimated real power of the compressor from the apparent power of the inverter, one value per window
     */
    private static double[] strom(InfluxController.History schein) {
        double[] w = new double[schein.value().length];
        for (int i = 0; i < w.length; i++) {
            w[i] = Waermepumpe.wirkleistung(schein.value()[i]);
        }
        return w;
    }

    private static List<long[]> laeufe(Verlauf24h.Daten d) {
        InfluxController.History schein = d.reihe("LeistungInverter");
        return Uebersicht.laeufe(schein.time(), strom(schein), 50, Verlauf24h.FENSTER.toMillis());
    }

    private List<Uebersicht.Kachel> kacheln(Verlauf24h.Daten d) {
        InfluxController.History leer = new InfluxController.History("", new long[0], new double[0]);
        List<Uebersicht.Kachel> kacheln = new ArrayList<>();
        Double aussenWpm = wert(wp::getAussentempWpm);
        String aussenReihe = aussenWpm != null ? "AussentempWPM" : "Aussentemp";
        Double aussen = aussenWpm != null ? aussenWpm : wert(wp::getAussentemp);
        InfluxController.History a = d == null ? leer : d.reihe(aussenReihe);
        kacheln.add(new Uebersicht.Kachel("Außentemperatur", aussen == null ? "–" : zahl(aussen, 1), "°C",
                spanne(a, 1, "°C"), a.time(), a.value(), min(a, -1), max(a, 1), "aussen",
                d == null ? null : aussenReihe, 1));
        InfluxController.History schein = d == null ? leer : d.reihe("LeistungInverter");
        double[] w = strom(schein);
        Double jetztSchein = wert(wp::getLeistungInverter);
        double kWh = 0;
        double imLauf = 0;
        int lauf = 0;
        for (double x : w) {
            kWh += x * Verlauf24h.FENSTER.toMinutes() / 60 / 1000;
            if (x > 50) {
                imLauf += x;
                lauf++;
            }
        }
        kacheln.add(new Uebersicht.Kachel("Strom jetzt ≈", jetztSchein == null ? "–"
                : zahl(Waermepumpe.wirkleistung(jetztSchein), 0), "W", d == null ? "Wirkleistung des Verdichters"
                : "24 h: " + zahl(kWh, 1) + " kWh" + (lauf == 0 ? "" : " · im Lauf Ø " + zahl(imLauf / lauf, 0) + " W"),
                schein.time(), w, 0, 1000, "strom", d == null ? null : StatusService.WIRKLEISTUNG, 0));
        InfluxController.History druck = d == null ? leer : d.reihe("Heizungsdruck");
        Double p = wert(wp::getHeizungsdruck);
        kacheln.add(new Uebersicht.Kachel("Heizungsdruck", p == null ? "–" : zahl(p, 2), "bar",
                (druck.value().length == 0 ? "" : spanne(druck, 2, "bar") + " · ") + "Warnung unter 1,3",
                druck.time(), druck.value(), min(druck, -0.05), max(druck, 0.05), "druck",
                d == null ? null : "Heizungsdruck", 2));
        InfluxController.History hz = d == null ? leer : d.reihe("VerdichterDrehzahlHz");
        Double drehzahl = wert(wp::getVerdichterDrehzahl);
        double hoechst = 0;
        for (double x : hz.value()) {
            hoechst = Math.max(hoechst, x);
        }
        kacheln.add(new Uebersicht.Kachel("Verdichter", drehzahl == null ? "–" : zahl(drehzahl, 0), "Hz",
                hz.value().length == 0 ? "Drehzahl des Inverter-Verdichters"
                        : "höchstens " + zahl(hoechst, 0) + " Hz in 24 h (10-Minuten-Mittel)",
                hz.time(), hz.value(), 0, Math.max(60, hoechst), "drehzahl", d == null ? null : "VerdichterDrehzahlHz",
                0));
        return kacheln;
    }

    private static String spanne(InfluxController.History h, int stellen, String einheit) {
        if (h.value().length == 0) {
            return "";
        }
        return "24 h: " + zahl(min(h, 0), stellen) + " bis " + zahl(max(h, 0), stellen) + " " + einheit;
    }

    private static double min(InfluxController.History h, double rand) {
        double m = Double.MAX_VALUE;
        for (double x : h.value()) {
            m = Math.min(m, x);
        }
        return h.value().length == 0 ? 0 : m + rand;
    }

    private static double max(InfluxController.History h, double rand) {
        double m = -Double.MAX_VALUE;
        for (double x : h.value()) {
            m = Math.max(m, x);
        }
        return h.value().length == 0 ? 1 : m + rand;
    }

    private Uebersicht.Zeitleiste zeitleiste(Verlauf24h.Daten d) {
        long ende = d.geholt().toEpochMilli();
        long start = ende - Verlauf24h.SPANNE.toMillis();
        InfluxController.History schein = d.reihe("LeistungInverter");
        double[] w = strom(schein);
        double kWh = 0;
        for (double x : w) {
            kWh += x * Verlauf24h.FENSTER.toMinutes() / 60 / 1000;
        }
        // A defrost marks the window it starts in; following windows belong to the same defrost
        List<Long> abtauungen = new ArrayList<>();
        InfluxController.History ab = d.reihe("Abtauung");
        for (int i = 0; i < ab.value().length; i++) {
            if (ab.value()[i] > 0 && (i == 0 || ab.value()[i - 1] <= 0)) {
                abtauungen.add(ab.time()[i] - Verlauf24h.FENSTER.toMillis() / 2);
            }
        }
        List<Uebersicht.Linie> linien = new ArrayList<>();
        linien.add(linie(d, "Vorlauf", "vorlauf", "VorlaufFein", "VorlaufIstTemp"));
        linien.add(linie(d, "Rücklauf", "ruecklauf", "RuecklaufFein", "RuecklaufIstTemp"));
        linien.add(linie(d, "Außen", "aussen", "AussentempWPM", "Aussentemp"));
        List<long[]> laeufe = Uebersicht.laeufe(schein.time(), w, 50, Verlauf24h.FENSTER.toMillis());
        return new Uebersicht.Zeitleiste(start, ende, schein.time(), w, abtauungen, linien,
                schein.value().length == 0 ? null
                        : Uebersicht.zusammenfassung(laeufe, kWh, abtauungen.size(), ZoneId.systemDefault()));
    }

    private static Uebersicht.Linie linie(Verlauf24h.Daten d, String name, String klasse, String reihe,
                                          String ersatz) {
        InfluxController.History h = d.reihe(reihe);
        if (h.value().length == 0) {
            h = d.reihe(ersatz);
        }
        return new Uebersicht.Linie(name, klasse, h.time(), h.value());
    }

    /**
     * Failed starts of the season and the newest entry of the fault list
     */
    private void stoerungen(StatusPage page) {
        page.card("Störungen");
        List<Fehlstarts.Saison> saisons = fehlstarts.getSaisons();
        if (!saisons.isEmpty()) {
            Fehlstarts.Saison s = saisons.get(saisons.size() - 1);
            page.text("Fehlstarts Saison " + s.name(), s.fehlstarts() + (s.jeTausendMild() == null ? ""
                    : " · " + zahl(s.jeTausendMild(), 1) + " je 1000 milde Starts"))
                    .info("Der Verdichter lief nicht im ersten Anlauf an (INV H ROTORVEKTOR). Auffällig wäre deutlich "
                            + "mehr als etwa 30 in einem Winter.");
        }
        List<Fehlerliste.Eintrag> eintraege = can.getFehlerliste().eintraege();
        if (eintraege.isEmpty()) {
            page.text("Fehlerliste", can.getFehlerlisteAngefragt() == null ? null : "keine Einträge");
        } else {
            Fehlerliste.Eintrag neuester = eintraege.get(0);
            page.text("Letzter Eintrag", Fehlerliste.ZEITFORMAT.format(neuester.zeit()) + " · " + neuester.text())
                    .info("Fehlerliste des WPM mit 20 Plätzen, alle 10 Minuten gelesen. Ein neuer Eintrag wird per "
                            + "Mail gemeldet.");
        }
        page.note("<a class=\"nav\" href=\"anlage#meldungen\">Fehlerliste und alle Warnungen</a>");
    }

    /**
     * Warnings, condition and service in one card; active warnings with their text
     */
    private void pruefungen(StatusPage page) {
        page.card("Prüfungen");
        List<Warnung> warnungen = ueberwachung.getWarnungen();
        List<Warnung> aktiv = warnungen.stream().filter(Warnung::isAktiv).toList();
        if (aktiv.isEmpty()) {
            page.textArt("Warnungen", "keine · " + warnungen.size() + " Prüfungen", "ok")
                    .info("Jede Minute geprüft. Wird eine Warnung aktiv, kommt eine Mail und, falls eingerichtet, eine "
                            + "Push-Nachricht.");
        }
        for (Warnung w : aktiv) {
            page.textArt(w.getName(), "seit " + Fehlerliste.ZEITFORMAT.format(w.getAktivSeit().atZone(
                    ZoneId.systemDefault())) + ": " + w.getText(), "warn").info(w.getRegel());
        }
        if (influx.isAktiv()) {
            Zustand.Ergebnis e = zustand.getErgebnis();
            Rahmen.Signal z = SignalService.zustand(e == null ? null : e.pruefungen());
            page.textArt("Zustand und Alterung", e == null ? "wird berechnet" : ZustandSeite.gesamt(e.pruefungen()),
                            z.art())
                    .info("Vergleich der Heizsaisons bei gleicher Außentemperatur, alle 6 Stunden neu berechnet.");
        }
        Rahmen.Signal dienst = SignalService.dienst(ueberwachung.getPruefungen(), null);
        page.textArt("Dienst", dienst.wert(), dienst.art()).info("Prüfung des Dienstes alle 15 Minuten: InfluxDB, USB-Adapter, Mail, "
                + "Pushover.");
        page.note("<a class=\"nav\" href=\"zustand\">Zustand und Alterung</a> · "
                + "<a class=\"nav\" href=\"konfiguration#system\">System</a>");
    }

    private void warnungen(StatusPage page) {
        page.card("Warnungen");
        List<Warnung> warnungen = ueberwachung.getWarnungen();
        boolean keine = true;
        for (Warnung warnung : warnungen) {
            if (warnung.isAktiv()) {
                keine = false;
                String seit = Fehlerliste.ZEITFORMAT.format(warnung.getAktivSeit().atZone(ZoneId.systemDefault()));
                page.textArt(warnung.getName(), "seit " + seit + ": " + warnung.getText(), "warn")
                        .info(warnung.getRegel());
            }
        }
        if (keine) {
            page.textArt("Aktive Warnungen", "keine", "ok")
                    .info("Jede Minute geprüft. Wird eine Warnung aktiv, kommt eine Mail an stbl.mail.to und, falls "
                            + "eingerichtet, eine Push-Nachricht über Pushover.");
        }
        page.details("Alle " + warnungen.size() + " Prüfungen");
        for (Warnung warnung : warnungen) {
            page.textArt(warnung.getName(), warnung.isAktiv() ? "aktiv" : "nein", warnung.isAktiv() ? "warn" : "ok")
                    .info(warnung.getRegel());
        }
        page.detailsEnde();
    }

    private void tagesuebersicht(StatusPage page) {
        page.card("Heute und gestern");
        Tagesuebersicht.Tag heute = tagesuebersicht.getHeute();
        Tagesuebersicht.Tag gestern = tagesuebersicht.getGestern();
        if (heute == null || gestern == null) {
            page.text("Tage", "wird berechnet");
            return;
        }
        Tagesuebersicht.Vergleichstage gleich = tagesuebersicht.getAehnlicheTage();
        List<List<String>> zeilen = new ArrayList<>();
        zeilen.add(List.of("Außen °C", zahl(heute.aussentemp(), 1), zahl(gestern.aussentemp(), 1),
                gleich == null ? "–" : zahl(gleich.aussentemp(), 1)));
        zeilen.add(List.of("Starts", String.valueOf(heute.starts()), String.valueOf(gestern.starts()),
                gleich == null ? "–" : zahl(gleich.starts(), 1)));
        zeilen.add(List.of("Laufzeit h", zahl(heute.laufzeitH(), 1), zahl(gestern.laufzeitH(), 1),
                gleich == null ? "–" : zahl(gleich.laufzeitH(), 1)));
        zeilen.add(List.of("h je Start", zahl(heute.laufzeitProStart(), 2), zahl(gestern.laufzeitProStart(), 2),
                gleich == null ? "–" : zahl(gleich.laufzeitProStart(), 2)));
        zeilen.add(List.of("Wärme kWh", zahl(heute.waermeKWh(), 1), zahl(gestern.waermeKWh(), 1),
                gleich == null ? "–" : zahl(gleich.waermeKWh(), 1)));
        zeilen.add(List.of("Strom kWh ≈", zahl(heute.stromKWh(), 1), zahl(gestern.stromKWh(), 1),
                gleich == null ? "–" : zahl(gleich.stromKWh(), 1)));
        zeilen.add(List.of("Arbeitszahl ≈", zahl(heute.arbeitszahl(), 1), zahl(gestern.arbeitszahl(), 1),
                gleich == null ? "–" : zahl(gleich.arbeitszahl(), 1)));
        zeilen.add(List.of("Abtauungen", String.valueOf(heute.abtauungen()), String.valueOf(gestern.abtauungen()),
                gleich == null ? "–" : zahl(gleich.abtauungen(), 1)));
        zeilen.add(List.of("Druckhub ≈", "–", zahl(tagesuebersicht.getDruckhubGestern(), 2), "–"));
        page.table(List.of("", "Heute", "Gestern", "Ø gleiche Temp."), zeilen, false, Map.ofEntries(
                        Map.entry("Heute", "Von Mitternacht bis jetzt."),
                        Map.entry("Ø gleiche Temp.", "Mittel aller Tage seit " + vergleich.getStart().format(DATUM)
                                + ", deren Tagesmittel der Außentemperatur höchstens "
                                + zahl(Tagesuebersicht.AEHNLICH_K, 1) + " K von gestern abweicht. Zeigt, ob gestern "
                                + "normal war. Die Arbeitszahl stammt nur aus den Tagen mit Stromwert, der Strom ist "
                                + "die mittlere Wärme geteilt durch diese Arbeitszahl."),
                        Map.entry("Außen °C", "Tagesmittel der Außentemperatur."),
                        Map.entry("Starts", "Verdichterstarts nach einem Stillstand von mindestens "
                                + Fehlstarts.MIN_STILLSTAND_START.toMinutes() + " Minuten, gezählt aus der "
                                + "Inverterleistung. Der Wiederanlauf nach einer Abtauung (etwa 2 Minuten Pause) "
                                + "zählt nicht, er steht in der Zeile Abtauungen."),
                        Map.entry("Laufzeit h", "Stunden, in denen der Verdichter lief."),
                        Map.entry("h je Start", "Mittlere Laufzeit zwischen zwei echten Pausen, Abtauungen unterbrechen den "
                                + "Lauf nicht. Länger ist effizienter und schont den "
                                + "Verdichter."),
                        Map.entry("Wärme kWh", "Abgegebene Heizwärme laut Wärmezähler der Wärmepumpe."),
                        Map.entry("Strom kWh ≈", "Geschätzte Wirkleistung des Verdichter-Inverters, aufsummiert; ohne Pumpe und "
                                + "Regelung, daher etwas zu niedrig."),
                        Map.entry("Arbeitszahl ≈", "Wärme ÷ Strom des Tages. Wegen des geschätzten Stroms eher etwas zu hoch."),
                        Map.entry("Abtauungen", "Heute und gestern: vom Dienst erkannte Abtauungen (Kreislaufumkehr, etwa "
                                + "2 Minuten). Vergleichstage: Wiederanläufe nach einer Pause unter "
                                + Fehlstarts.MIN_STILLSTAND_START.toMinutes() + " Minuten, weil die Erkennung erst "
                                + "seit 25.09.2026 gespeichert wird."),
                        Map.entry("Druckhub ≈", "Tagesspanne des Heizungsdrucks geteilt durch die Tagesspanne der mittleren "
                                + "Wassertemperatur, in bar je 10 K. Nur ein grober Tageswert: innerhalb eines Tages "
                                + "ändert sich das Wasser im Fußboden viel weniger als Vor- und Rücklauf, und der "
                                + "Pumpendruck mischt sich ein. Für den Zustand des Ausdehnungsgefäßes zählt die "
                                + "Druckschwankung auf der Seite Zustand (Tagesmittel über einen Monat). Nur für Tage "
                                + "mit mindestens "
                                + zahl(Tagesuebersicht.DRUCKHUB_MIN_LAUFZEIT_H, 0) + " h Laufzeit und "
                                + zahl(Tagesuebersicht.DRUCKHUB_MIN_TEMPERATURHUB_K, 0) + " K Temperaturspanne, sonst "
                                + "„–“. Wird täglich als WP_DruckhubJe10K gespeichert.")))
                .note("„Ø gleiche Temp.“: Mittel aller Tage seit " + vergleich.getStart().format(DATUM)
                        + ", deren Tagesmittel höchstens " + zahl(Tagesuebersicht.AEHNLICH_K, 1)
                        + " K von gestern abweicht" + (gleich == null ? "" : " (" + gleich.tage() + " Tage)")
                        + ". Daran siehst du, ob gestern normal war. Strom = geschätzte Wirkleistung des "
                        + "Verdichter-Inverters, Arbeitszahl = Wärme ÷ Strom. Stand "
                        + zeitpunkt(tagesuebersicht.getBerechnet()) + ".");
    }

    private void fehlerliste(StatusPage page) {
        page.card("Fehlerliste");
        List<Fehlerliste.Eintrag> eintraege = can.getFehlerliste().eintraege();
        if (eintraege.isEmpty()) {
            page.text("Einträge", can.getFehlerlisteAngefragt() == null ? null : "keine")
                    .info("Die Fehlerliste des Wärmepumpenmanagers wird alle 10 Minuten gelesen.");
            return;
        }
        Map<Integer, Long> anzahl = new LinkedHashMap<>();
        eintraege.forEach(e -> anzahl.merge(e.code(), 1L, Long::sum));
        anzahl.entrySet().stream().sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .forEach(e -> page.text(e.getValue() + " ×", Fehlerliste.text(e.getKey()))
                        .info("Code " + e.getKey() + (Fehlerliste.erklaerung(e.getKey()) == null ? ""
                                : ": " + Fehlerliste.erklaerung(e.getKey())) + " Anzahl unter den "
                                + eintraege.size() + " Einträgen der Fehlerliste."));
        Fehlerliste.Eintrag neuester = eintraege.get(0);
        page.text("Letzter Eintrag", Fehlerliste.ZEITFORMAT.format(neuester.zeit()) + " · " + neuester.text())
                .info("Fehlerliste des WPM (DIAGNOSE → FEHLERLISTE) mit 20 Plätzen, alle 10 Minuten gelesen. Ein "
                        + "neuer Eintrag wird per Mail gemeldet.")
                .details("Alle " + eintraege.size() + " Einträge");
        for (Fehlerliste.Eintrag eintrag : eintraege) {
            page.text(Fehlerliste.ZEITFORMAT.format(eintrag.zeit()), eintrag.text())
                    .info("Code " + eintrag.code() + (Fehlerliste.erklaerung(eintrag.code()) == null ? ""
                            : ": " + Fehlerliste.erklaerung(eintrag.code())) + " Platz " + (eintrag.platz() + 1)
                            + " im Ringspeicher des WPM.");
        }
        page.detailsEnde();
    }

    private void anlage(StatusPage page) {
        page.bereich("Heizkreis · Kältekreis · Verdichter", "werte")
                .card("Heizkreis")
                .row("Vorlauf", "°C", 1, "VorlaufIstTemp", wp::getVorlaufIstTemp)
                .info("Temperatur des Wassers, das von der Wärmepumpe zum Heizkreis fließt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .row("Hilfskessel-Soll", "°C", 1, "VorlaufSoll", wp::getVorlaufSoll)
                .info("Temperatur, die der Heizungsregler (0x180) alle 10 s an die Wärmepumpe schreibt "
                        + "(HILFSKESSELSOLL, 0x01D7), abgefragt bei der Wärmepumpe (0x500). Folgt der Heizkurve, ist aber "
                        + "nicht der Vorlauf-Soll, auf den die Wärmepumpe regelt: Am 16.11.2026 lag dieser bei etwa "
                        + "33 °C, gut 6 K über diesem Wert. Gespeichert als VorlaufSoll.")
                .row("Anforderung", "", 0, "Anforderung", wp::getAnforderung)
                .info("Anforderung, die der Manager alle 10 s an die Wärmepumpe schreibt (SPEICHERBEDARF, 0x005F). "
                        + "Die Wärmepumpe beantwortet keine Abfrage danach, der Wert wird deshalb auf dem Bus "
                        + "mitgelesen, nur mit „Alle Nachrichten empfangen“. Am 15.11.2026 0 bei Stillstand und 512 "
                        + "beim Heizen, andere Werte sind noch unbekannt.")
                .row("Vorlauf fein", "°C", 1, "VorlaufFein", wp::getVorlaufFein)
                .info("Vorlauf in 0,1 K, den das Gerät 0x700 dem Manager alle 5 s meldet (0xFDF3), auf dem Bus "
                        + "mitgelesen, nur mit „Alle Nachrichten empfangen“. Lag beim Heizen am 15.11.2026 bis 1 K über "
                        + "dem groben Vorlauf, im Lauf vom 12. bis 17.11.2026 im Median 0,7 K darüber, immer innerhalb einer "
                        + "Stufe. Danach regelt der Manager: das Regler-Integral folgt diesem Wert.")
                .row("Rücklauf fein", "°C", 1, "RuecklaufFein", wp::getRuecklaufFein)
                .info("Rücklauf in 0,1 K von 0x700 (0xFDF4), wie der feine Vorlauf mitgelesen. Im Lauf vom 12. bis "
                        + "17.11.2026 im Median 0,3 K über dem groben Rücklauf.")
                .row("Rücklauf WPM", "°C", 1, "RuecklaufWPM", wp::getRuecklaufWpm)
                .info("Rücklauf am Fühler des Wärmepumpenmanagers (0x180, 0x0016), den er dem Bedienteil alle 10 s "
                        + "meldet, auf dem Bus mitgelesen, nur mit „Alle Nachrichten empfangen“. Lag am 16.11.2026 im "
                        + "Stillstand etwa 1 K unter dem Rücklauf der Wärmepumpe. Denselben Wert schreibt der Manager "
                        + "als Heizkreis-Isttemperatur (0x02CA) an die Wärmepumpe, am 16.11.2026 höchstens 0,2 K "
                        + "abweichend.")
                .row("Spreizung fein ≈", "K", 1, null, wp::getSpreizungFein)
                .info("Feiner Vorlauf minus feiner Rücklauf. Im Lauf vom 12. bis 17.11.2026 im Median 5,4 K, der "
                        + "Sollwert ist 5 K.")
                .formula("Vorlauf fein − Rücklauf fein")
                .row("Pumpe PWM", "%", 0, "PumpePWM", wp::getPumpePwm)
                .info("Wert, den der Manager an 0x700 schreibt (0xFE1B), gedeutet als PWM-Signal der Umwälzpumpe: "
                        + "100 im Stillstand, beim Heizen 55 bis 72, 0 beim Abtauen (volle Drehzahl). Je kleiner, desto "
                        + "mehr Durchfluss, im Lauf vom 12. bis 17.11.2026 r = −0,99 zum Volumenstrom.")
                .row("Regler-Integral", "", 0, "RegelIntegral", wp::getRegelIntegral)
                .info("Integral der Regelabweichung, das der Manager alle 10 s an die Wärmepumpe schreibt (0x0917). "
                        + "Steigt, solange der Vorlauf über dem Sollwert liegt (etwa 34 °C, mit der Heizkurve "
                        + "wandernd), und fällt darunter, umso schneller, je weiter "
                        + "er davon entfernt ist. Bei -1000 springt das Integral auf 0, 40 bis 60 s später startet "
                        + "der Verdichter, kurz mit 40 Hz. Fällt es danach weiter, steigt die Frequenz-Anforderung und "
                        + "der Verdichter fährt von etwa 26 Hz hoch, steigt es, läuft er mit kleinster Drehzahl. Bei etwa +1000 "
                        + "stoppt er, und das Integral springt wieder auf 0 (beobachtet am 16.11.2026 bei 8 °C).")
                .row("Rücklauf", "°C", 1, "RuecklaufIstTemp", wp::getRuecklaufIstTemp)
                .info("Temperatur des Wassers, das vom Heizkreis zur Wärmepumpe zurückkommt (0x514). Wird nur in "
                        + "Schritten von etwa 1,1 K gemeldet.")
                .row("Spreizung ≈", "K", 1, "Spreizung", wp::getSpreizung)
                .info("Temperaturunterschied zwischen Vor- und Rücklauf. Der Sollwert steht unter Einstellungen "
                        + "(Soll-Spreizung).")
                .formula("Vorlauf − Rücklauf")
                .row("Volumenstrom", "l/min", 1, "WasserVolumenstrom", wp::getVolumenstrom)
                .info("Heizwasser, das durch die Wärmepumpe fließt (0x500), jede Minute abgefragt. Die Pumpe "
                        + "regelt ihn so, dass die Soll-Spreizung erreicht wird; bei kleiner Leistung daher oft nur "
                        + "8–10 l/min. Laut Anleitung WPL 17: mindestens 10 l/min (0,6 m³/h), nenn 22 l/min bei "
                        + "A−7/W35, beim Abtauen etwa 30 l/min.")
                .row("Wärmeleistung ≈", "kW", 2, "WaermeleistungBerechnet", wp::getWaermeleistung)
                .info("Wärme, die die Wärmepumpe gerade an das Heizwasser abgibt. Laut Anleitung kann die WPL 17 "
                        + "bei A7/W35 2,6–8,5 kW und bei A−7/W35 3,0–7,8 kW; braucht das Haus weniger als das "
                        + "Minimum, taktet sie.")
                .formula(Waermepumpe.formelWaermeleistung())
                .ersatz("–")
                .row("Arbeitszahl ≈", "", 1, "ArbeitszahlGeschaetzt", wp::getArbeitszahl)
                .info("Momentane Arbeitszahl (COP): abgegebene Wärme je eingesetzter elektrischer Leistung. Nur "
                        + "während der Verdichter läuft. Datenblatt WPL 17: A7/W35 4,76, A2/W35 3,97, A−7/W35 2,92, "
                        + "SCOP 4,48.")
                .formula(Waermepumpe.formelArbeitszahl())
                .ersatz("aus")
                .row("Heizungsdruck", "bar", 2, "Heizungsdruck", wp::getHeizungsdruck)
                .info("Wasserdruck im Heizkreis, gemessen im HM Trend und gemeldet vom Wärmepumpenmanager (0x180). "
                        + "Warnung unter 1,3 und über 2,5 bar, zulässig sind laut Anleitung 3 bar; er sinkt um etwa "
                        + "0,2 bar pro Jahr.")
                .card("Kältekreis")
                .row("Außentemperatur", "°C", 1, "Aussentemp", wp::getAussentemp)
                .info("Außentemperatur am Fühler der Außeneinheit (0x500). Kann vom Außenfühler des "
                        + "Wärmepumpenmanagers etwas abweichen, beim Abtauen steigt sie kurz um einige Kelvin.")
                .row("Außentemperatur WPM", "°C", 1, "AussentempWPM", wp::getAussentempWpm)
                .info("Außentemperatur am Fühler des Wärmepumpenmanagers, nach der er die Heizkurve rechnet. Der "
                        + "Manager (0x180) schickt sie alle 5 s an alle (0x000C), auf dem Bus mitgelesen, nur mit „Alle "
                        + "Nachrichten empfangen“. Am 16.11.2026 8,0 °C, der Fühler der Außeneinheit zeigte 7,4 °C.")
                .row("Hochdruck", "bar", 2, "Hochdruck", wp::getHochdruck)
                .info("Absoluter Druck auf der Hochdruckseite des Kältekreises (0x500). Entspricht der "
                        + "Kondensationstemperatur, die etwa der Vorlauftemperatur folgt. Sensor bis 42 bar, "
                        + "Hochdruckwächter schaltet bei 45 bar ab.")
                .row("Kondensationstemperatur", "°C", 1, "Kondensationstemp", wp::getKondensationstemp)
                .info("Temperatur, bei der das Kältemittel im Verflüssiger kondensiert (0x500, 0x0728), aus dem "
                        + "Hochdruck. Liegt beim Heizen knapp unter dem Vorlauf; je kleiner der Abstand, desto "
                        + "effizienter.")
                .row("Niederdruck", "bar", 2, "Niederdruck", wp::getNiederdruck)
                .info("Absoluter Druck auf der Saugseite des Kältekreises (0x500). Entspricht der "
                        + "Verdampfungstemperatur, im Betrieb einige Kelvin unter der Außentemperatur. Sensor bis "
                        + "16 bar.")
                .row("Heißgas", "°C", 1, "HeissgasTemp", wp::getHeissgasTemp)
                .info("Temperatur des Kältemittels direkt hinter dem Verdichter (0x500).")
                .row("Verdichter-Eintritt", "°C", 1, "VerdichterEintrittstemp", wp::getVerdichterEintrittstemp)
                .info("Temperatur des angesaugten Kältemittels am Verdichtereintritt (0x500).")
                .row("Verdampfer", "°C", 1, "VerdampferTemp", wp::getVerdampferTemp)
                .info("Temperatur am Austritt des Verdampfers in der Außeneinheit (0x500). Steigt beim Abtauen stark "
                        + "an.")
                .row("Taupunkt Referenz", "°C", 1, "TaupunktReferenz", wp::getTaupunktReferenz)
                .info("Kein Messwert, sondern der höchste kompensierte Taupunkt seit der letzten Abtauung, also wie "
                        + "sauber der Verdampfer am besten war (0x500, 0x080A), zum Beispiel −8,7 °C. Nach einer "
                        + "Abtauung steht er auf −40 °C, ab 10 Minuten nach dem Start übernimmt er den kompensierten "
                        + "Wert und folgt ihm danach nur nach oben.")
                .row("Taupunkt kompensiert", "°C", 1, "TaupunktKompensiert", wp::getTaupunktKompensiert)
                .info("Rechengröße der Wärmepumpe (0x500, 0x080B), keine Temperatur an einer Stelle: im Kern, wie "
                        + "viel kälter als die Außenluft das Kältemittel verdampfen muss, bereinigt um die Drehzahl. "
                        + "Sinkt, während Eis am Verdampfer wächst, nur der Abstand zur Referenz zählt.")
                .row("Reserve bis Abtauung ≈", "K", 1, "AbtauReserve", wp::getAbtauReserve)
                .info("Wie weit der kompensierte Taupunkt noch sinken darf, bis die Wärmepumpe abtaut. Alle fünf "
                        + "Abtauungen in der Nacht zum 17.11.2026 starteten bei etwa 3,1 K unter der Referenz. Im "
                        + "Stillstand und beim Starten und Stoppen springt der kompensierte Wert, die Referenz folgt "
                        + "ihm erst ab 10 Minuten nach dem Start, deshalb erst ab 11 Minuten Lauf.")
                .formula("Taupunkt kompensiert − Taupunkt Referenz + 3,1 K")
                .ersatz("nur bei laufendem Verdichter, ab 11 min nach dem Start")
                .row("Überhitzung", "K", 1, "UeberhitzungIst", wp::getIstUeberhitzung)
                .info("Wie viel wärmer das Kältemittel am Verdampferaustritt ist als seine Verdampfungstemperatur "
                        + "(0x514). Darauf regelt das Expansionsventil.")
                .row("Überhitzung Soll", "K", 1, "UeberhitzungSoll", wp::getSollUeberhitzung)
                .info("Zielwert der Überhitzung für das Expansionsventil (0x514).")
                .row("Expansionsventil", "%", 1, "OeffnungsgradEXV", wp::getOeffnungsgradExv)
                .info("Öffnung des elektronischen Expansionsventils (0x514). Beim Heizen meist um 20–25 %, beim "
                        + "Abtauen und kurz nach einem Start bis 100 %.")
                .row("Lüfter", "Hz", 0, "LuefterIstDrehzahlHz", wp::getLuefterIstDrehzahl)
                .info("Ist-Drehzahl des Lüfters der Außeneinheit (0x514).")
                .row("Lüfter Soll", "Hz", 0, "LuefterSollDrehzahlHz", wp::getLuefterSollDrehzahl)
                .info("Von der Regelung geforderte Lüfterdrehzahl (0x514).")
                .row("Lüfterleistung", "%", 0, "LuefterLeistung", wp::getLuefterLeistung)
                .info("Relative Leistung des Lüfters der Außeneinheit (0x500).")
                .card("Verdichter und Inverter")
                .row("Drehzahl", "Hz", 0, "VerdichterDrehzahlHz", wp::getVerdichterDrehzahl)
                .info("Aktuelle Drehzahl des Inverter-Verdichters in der Außeneinheit (0x500). 0 bedeutet, der "
                        + "Verdichter steht.")
                .row("Frequenz-Anforderung", "Hz", 1, "FrequenzAnforderung", wp::getFrequenzAnforderung)
                .info("Frequenz, die der Manager vom Verdichter verlangt (0x0669, in 0,01 Hz), auf dem Bus "
                        + "mitgelesen, nur mit „Alle Nachrichten empfangen“. Folgt dem Regler-Integral, auch im "
                        + "Stillstand: bei -1000 etwa 34 Hz, bei +900 etwa 12 Hz. Über etwa 20 Hz läuft der Verdichter "
                        + "genau damit, darunter bleibt er auf seiner Mindestfrequenz von 20 Hz und liefert mehr als "
                        + "verlangt, bis das Integral den Stopp auslöst.")
                .row("Solldrehzahl", "Hz", 0, "VerdichterSollDrehzahlHz", wp::getVerdichterSollDrehzahl)
                .info("Drehzahl, die die Regelung vom Verdichter fordert (0x514).")
                .row("Spannung", "V", 1, "SpannungInverter", wp::getSpannungInverter)
                .info("Netzspannung am Verdichter-Inverter (0x500). Zeigt dauerhaft etwa 10 V zu wenig an, der "
                        + "Hauszähler misst richtig.")
                .row("Strom", "A", 1, "StromInverter", wp::getStromInverter)
                .info("Stromaufnahme des Verdichter-Inverters (0x500). Laut Anleitung max. 20 A Betriebsstrom, "
                        + "7 A Anlaufstrom, abgesichert mit B25.")
                .row("Scheinleistung ≈", "VA", 0, "LeistungInverter", wp::getLeistungInverter)
                .info("Momentane Leistungsaufnahme des Verdichter-Inverters. Die Wirkleistung liegt laut Smartmeter "
                        + "bei etwa 80 % bei 600 VA und erreicht ab 1000 VA die Scheinleistung.")
                .formula(Waermepumpe.FORMEL_SCHEINLEISTUNG)
                .row("Umgebungstemperatur Inverter", "°C", 1, "UmgebungstempInverter", wp::getUmgebungstempInverter)
                .info("Temperatur in der Umgebung der Inverter-Elektronik (0x514).")
                .row("Temperatur Verdichter", "°C", 1, "TempInverterVerdichter", wp::getTempInverterVerdichter)
                .info("Vom Inverter gemessene Temperatur am Verdichter (0x514).")
                .row("Ölsumpf", "°C", 1, "OelsumpfTemp", wp::getOelsumpfTemp)
                .info("Temperatur des Öls im Verdichter (0x500).");
    }

    private void auswertung(StatusPage page) {
        Duration stunde = Waermepumpe.MAX_AGE_3600;
        page.bereich("Auswertung", "auswertung")
                .zweiSpalten()
                .card("Energie seit Inbetriebnahme")
                .row("Stromaufnahme", "MWh", 3, "AufnahmeLeistung", wp::getAufnahmeLeistung)
                .info("Elektrische Energie fürs Heizen laut Stromzähler der Wärmepumpe (0x514), aus Summe und "
                        + "Tageswert zusammengesetzt. Der Zähler zählt rund 15–20 % zu wenig.")
                .row("Wärmeerzeugung", "MWh", 3, "AbgabeWaerme", wp::getAbgabeWaerme)
                .info("Heizwärme laut Wärmezähler der Wärmepumpe (0x514), aus Summe und Tageswert zusammengesetzt. "
                        + "Stimmt auf etwa 6 % mit der Rechnung aus Durchfluss und Spreizung überein.")
                .row("Zusatzheizung", "MWh", 3, "WaermeZusatzheizung", wp::getWaermeZusatzheizung)
                .info("Vom Heizstab erzeugte Wärme (0x514).")
                .row("Effizienz Zähler ≈", "", 2, "EffizienzZaehler", wp::getEffizienz)
                .info("Arbeitszahl nach den Zählern der Wärmepumpe. Zu hoch, weil der Stromzähler zu wenig zählt.")
                .formula("Wärmeerzeugung ÷ Stromaufnahme, beides Zähler der Wärmepumpe")
                .row("Effizienz korrigiert ≈", "", 2, "EffizienzKorrigiert", wp::getEffizienzKorrigiert)
                .info("Realistische Arbeitszahl mit korrigiertem Stromverbrauch.")
                .formula("Wärmeerzeugung ÷ (Stromaufnahme × " + Waermepumpe.zahl(Waermepumpe.STROMZAEHLER_KORREKTUR)
                        + "), der Stromzähler der Wärmepumpe zählt rund 20 % zu wenig")
                .card("Einstellungen");
        for (Waermepumpe.Einstellung einstellung : wp.getEinstellungen()) {
            if (einstellung.anzeige() != null) {
                String text;
                try {
                    text = einstellung.text(einstellung.wert().call().getValue());
                } catch (Exception e) {
                    text = null;
                }
                page.text(einstellung.label(), text).info(einstellung.info());
            } else {
                page.row(einstellung.label(), einstellung.unit(), einstellung.decimals(),
                        "Einstellung_" + einstellung.name(), einstellung.wert(), stunde).info(einstellung.info());
            }
        }
        page.note("Stündlich vom Wärmepumpenmanager gelesen, eine Änderung wird per Mail gemeldet."
                        + (ueberwachung.getEinstellungsaenderungen().isEmpty() ? ""
                        : " Zuletzt geändert: " + String.join("; ", ueberwachung.getEinstellungsaenderungen()) + "."))
                .card("Laufzeiten seit Inbetriebnahme")
                .row("Laufzeit Verdichter Heizen", "h", 0, "LaufzeitVerdichterHeizen", wp::getLaufzeitVerdichterHeizen)
                .info("Betriebsstunden des Verdichters im Heizbetrieb seit Inbetriebnahme (0x514).")
                .row("Verdichterstarts", "", 0, "VerdichterStarts", wp::getVerdichterStarts)
                .info("Anzahl der Verdichterstarts seit Inbetriebnahme (0x514). Wenige, lange Läufe sind effizienter "
                        + "und schonen den Verdichter.")
                .row("Laufzeit je Start ≈", "h", 2, "LaufzeitProStart", wp::getLaufzeitProStart)
                .info("Durchschnittliche Laufzeit eines Verdichterlaufs über die gesamte Betriebszeit.")
                .formula("Laufzeit Heizen ÷ Verdichterstarts")
                .row("Laufzeit Verdichter Abtauen", "h", 0, "LaufzeitVerdichterAbtauen", wp::getLaufzeitVerdichterAbtauen)
                .info("Betriebsstunden des Verdichters beim Abtauen seit Inbetriebnahme (0x514), zählt nur in "
                        + "ganzen Stunden.")
                .row("Dauer letzte Abtauung", "min", 0, "DauerLetzteAbtauung", wp::getDauerLetzteAbtauung)
                .info("Dauer der letzten Abtauung laut Wärmepumpe (0x514), in ganzen Minuten. Eine Abtauung dauert "
                        + "meist etwa 2 Minuten.")
                .row("Laufzeit DHC 1", "h", 0, "LaufzeitDHZ1", wp::getLaufzeit_DHC1)
                .info("Betriebsstunden der Heizstab-Stufe 1 seit Inbetriebnahme (0x500).")
                .row("Laufzeit DHC 2", "h", 0, "LaufzeitDHZ2", wp::getLaufzeit_DHC2)
                .info("Betriebsstunden der Heizstab-Stufe 2 seit Inbetriebnahme (0x500).")
                .row("Laufzeit DHC 1+2", "h", 0, "LaufzeitDHZ12", wp::getLaufzeit_DHC12)
                .info("Betriebsstunden mit beiden Heizstab-Stufen gleichzeitig seit Inbetriebnahme (0x500).");
        if (!influx.isAktiv()) {
            return;
        }
        fehlstarts(page);
        page.card("Vergleich Einstellung Wärmebedarf").breit();
        List<WaermebedarfVergleich.Vergleich> vergleiche = vergleich.getVergleich();
        if (vergleiche.isEmpty()) {
            page.text("Tage", vergleich.getBerechnet() == null ? "wird berechnet" : "keine");
        } else {
            List<List<String>> zeilen = new ArrayList<>();
            for (WaermebedarfVergleich.Vergleich v : vergleiche) {
                WaermebedarfVergleich.Gruppe alt = v.vorher();
                WaermebedarfVergleich.Gruppe neu = v.nachher();
                zeilen.add(List.of(
                        v.band() + " (" + (alt == null ? "" : alt.tage() + " / ") + neu.tage() + ")",
                        (alt == null ? "" : zahl(alt.waermebedarf(), 1) + " → ") + zahl(neu.waermebedarf(), 1) + " kW",
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::startsProTag, 1),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::laufzeitProStart, 2),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::abtauungenProTag, 1),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::laufzeitProTag, 1),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::waermeProTag, 0),
                        vorherNachher(alt, neu, WaermebedarfVergleich.Gruppe::leistungKW, 2),
                        WaermebedarfVergleich.bewertung(alt, neu)));
            }
            page.table(List.of("Außen (Tage)", "Einstellung", "Starts/Tag", "h/Start", "Abtau./Tag", "h/Tag",
                    "kWh/Tag", "kW im Lauf", "Bewertung"), zeilen, true, Map.of(
                    "Außen (Tage)", "Bereich des Tagesmittels der Außentemperatur, in Klammern die Anzahl der Tage "
                            + "(früher / jetzt).",
                    "Einstellung", "Eingestellter Wärmebedarf im Wärmepumpenmanager, kein Messwert. Bei zwei "
                            + "Einstellungen im Bereich: früher → jetzt.",
                    "Starts/Tag", "Verdichterstarts pro Tag nach einem Stillstand von mindestens "
                            + Fehlstarts.MIN_STILLSTAND_START.toMinutes() + " Minuten. Der Wiederanlauf nach einer "
                            + "Abtauung zählt nicht: Bei Kälte taut die Wärmepumpe rund 20 Mal am Tag ab, das hängt "
                            + "vom Wetter ab und nicht von der Einstellung. Weniger bei gleicher Wärme ist besser.",
                    "h/Start", "Mittlere Laufzeit zwischen zwei echten Pausen, Abtauungen unterbrechen den Lauf nicht. "
                            + "Länger ist besser.",
                    "Abtau./Tag", "Abtauungen pro Tag: Wiederanläufe des Verdichters nach einer Pause unter "
                            + Fehlstarts.MIN_STILLSTAND_START.toMinutes() + " Minuten (eine Abtauung dauert etwa 2 "
                            + "Minuten). Hängt von Außentemperatur und Luftfeuchte ab, am meisten um 0 °C. Jede "
                            + "Abtauung entzieht dem Heizwasser Wärme.",
                    "h/Tag", "Laufzeit pro Tag. Nahe 24 h im kältesten Bereich heißt: Die Einstellung ist zu knapp.",
                    "kWh/Tag", "Heizwärme pro Tag. Sollte bei gleicher Außentemperatur gleich bleiben.",
                    "kW im Lauf", "Mittlere Wärmeleistung während der Verdichter läuft (Wärme ÷ Laufzeit). Kleiner "
                            + "heißt gemächlicher und effizienter.",
                    "Bewertung", "Ab " + WaermebedarfVergleich.MIN_TAGE + " Tagen je Einstellung: besser (≥ "
                            + Math.round((1 - WaermebedarfVergleich.STARTS_BESSER) * 100) + " % weniger Starts), "
                            + "schlechter (≥ 10 % mehr), sonst kaum Unterschied; zu knapp?, wenn der Verdichter > "
                            + (int) WaermebedarfVergleich.MAX_LAUFZEIT_H + " h/Tag läuft. Dazu „weniger Wärme“ "
                            + "oder „mehr Wärme“ bei mehr als " + Math.round(WaermebedarfVergleich.WAERME_TOLERANZ
                            * 100) + " % Unterschied, im Herbst heizt die Sonne mit."));
        }
        page.note("Tage seit " + vergleich.getStart().format(DATUM) + " nach Tagesmittel der Außentemperatur und "
                        + "der Einstellung Wärmebedarf im Wärmepumpenmanager (eingestellter Wert, kein Messwert). "
                        + "Gibt es in einem Bereich Tage mit zwei Einstellungen, steht der frühere Wert vor dem Pfeil. "
                        + "„kW im Lauf“ = Wärme ÷ Laufzeit. Bewertung ab " + WaermebedarfVergleich.MIN_TAGE
                        + " Tagen je Einstellung: „besser“ bei mindestens "
                        + Math.round((1 - WaermebedarfVergleich.STARTS_BESSER) * 100)
                        + " % weniger Starts, „zu knapp?“ wenn der Verdichter mehr als "
                        + (int) WaermebedarfVergleich.MAX_LAUFZEIT_H + " h am Tag läuft. Ändert sich die Wärme pro "
                        + "Tag um mehr als " + Math.round(WaermebedarfVergleich.WAERME_TOLERANZ * 100) + " %, steht "
                        + "„weniger Wärme“ oder „mehr Wärme“ dabei: Das ist ein Hinweis, keine Warnung, denn im Herbst "
                        + "heizt die Sonne mit und die alten Tage stammen meist aus dem Winter. Passt die "
                        + "Einstellung, bleibt die Wärme gleich, die Starts sinken und die Läufe werden länger; dazu "
                        + "bleiben die Räume warm und der Heizstab aus. Stand " + (vergleich.getBerechnet() == null ? "–"
                        : zeitpunkt(vergleich.getBerechnet())) + ".");
    }

    private void system(StatusPage page) {
        CanStatistik statistik = can.getStatistik();
        List<Ueberwachung.Pruefung> pruefungen = ueberwachung.getPruefungen();
        // Mail that is not set up is a decision, not a fault
        List<String> fehler = pruefungen.stream()
                .filter(p -> !p.ok() && !(p.name().equals("Mail") && p.text().equals("nicht eingerichtet")))
                .map(Ueberwachung.Pruefung::name).toList();
        String zusammenfassung = pruefungen.isEmpty() ? "Prüfung läuft"
                : fehler.isEmpty() ? "alles OK" : "Fehler: " + String.join(", ", fehler);
        page.bereich("System · " + zusammenfassung, "system")
                .card("Dienst");
        if (pruefungen.isEmpty()) {
            page.text("Prüfung", "läuft 30 s nach dem Start");
        }
        page.text("Version", version.getText())
                .info("Stand des laufenden Dienstes: Version, Git-Commit und Zeitpunkt des Builds. Steht beim Start "
                        + "auch im Log.")
                .text("Gestartet", zeitpunkt(version.getGestartet()))
                .info("Start des Dienstes, zum Beispiel nach einem Deployment oder Neustart des Raspberry Pi.");
        for (Ueberwachung.Pruefung pruefung : pruefungen) {
            boolean keineMail = pruefung.name().equals("Mail") && pruefung.text().equals("nicht eingerichtet");
            page.textArt(pruefung.name(), pruefung.text(), pruefung.ok() ? "ok" : keineMail ? "neutral" : "warn")
                    .info(pruefung.erklaerung());
        }
        page.note("Geprüft " + (ueberwachung.getGeprueft() == null ? "noch nicht"
                        : zeitpunkt(ueberwachung.getGeprueft())) + ", alle 15 Minuten neu.")
                .card("Software der Geräte")
                .text("Wärmepumpenmanager", wp.getSoftwareManager())
                .info("Softwarenummer und -version des Wärmepumpenmanagers (0x180, 0x0199/0x019A), stündlich "
                        + "gelesen. Beim Abgleich schreibt der Manager 331 Version 11 an die Wärmepumpe, also wohl "
                        + "eine andere Zählung.")
                .text("Außeneinheit", wp.getSoftwareWaermepumpe())
                .info("Softwarenummer und -version der Außeneinheit (0x500), stündlich gelesen. Beim Abgleich "
                        + "meldet sie 70 Version 2.")
                .card("USB-Adapter")
                .pill("Verbindung", can::isAdapterVerbunden)
                .info("USBtin am Pi verbunden und CAN-Kanal geöffnet.")
                .text("Port", can.getPort() + " · " + can.getBitrate() / 1000 + " kbit/s")
                .info("Serielle Schnittstelle des USBtin am Pi und Bitrate des Wärmepumpen-Busses.")
                .text("Firmware / Hardware", statistik.getFirmware() == null ? null
                        : statistik.getFirmware() + " / " + statistik.getHardware())
                .info("Versionen des USBtin, beim Verbinden ausgelesen.")
                .text("Seriennummer", statistik.getSeriennummer())
                .info("Seriennummer des USBtin.")
                .text("Verbunden seit", zeitpunkt(statistik.getVerbundenSeit()))
                .info("Zeitpunkt der letzten erfolgreichen Verbindung, also Start des Dienstes oder letzter "
                        + "USB-Neustart.")
                .row("USB-Neustarts", "", 0, "CAN_UsbNeustarts", can::getUsbNeustarts)
                .info("Wie oft die Überwachung die USB-Verbindung seit dem Start des Dienstes neu gestartet hat, "
                        + "weil 2 min keine Antwort kam.")
                .text("Letzter Neustart", zeitpunkt(statistik.getLetzterNeustart()))
                .info("Zeitpunkt des letzten USB-Neustarts durch die Überwachung.")
;
        page.note("Nachrichten, Antworten, Buslast und die Geräte am Bus stehen auf der Seite "
                + "<a class=\"nav\" href=\"canbus\">CAN-Bus</a>.");
    }

    /**
     * Value of the setting used before and the current one, e.g. "23,5 → 18,2", only the current one without days
     * of an earlier setting
     */
    private static String vorherNachher(WaermebedarfVergleich.Gruppe vorher, WaermebedarfVergleich.Gruppe nachher,
                                        ToDoubleFunction<WaermebedarfVergleich.Gruppe> wert, int stellen) {
        String neu = zahl(wert.applyAsDouble(nachher), stellen);
        return vorher == null ? neu : zahl(wert.applyAsDouble(vorher), stellen) + " → " + neu;
    }

    private void fehlstarts(StatusPage page) {
        page.card("Fehlstarts je Heizsaison");
        List<Fehlstarts.Saison> saisons = fehlstarts.getSaisons();
        if (saisons.isEmpty()) {
            page.text("Saisons", "wird berechnet");
            return;
        }
        List<List<String>> zeilen = new ArrayList<>();
        for (Fehlstarts.Saison saison : saisons) {
            zeilen.add(List.of(saison.name() + (saison.laufend() ? " (laufend)" : ""),
                    String.valueOf(saison.fehlstarts()), zahl(saison.jeMWh(), 1), zahl(saison.jeTausendMild(), 1),
                    String.valueOf(saison.tageMitDaten())));
        }
        page.table(List.of("Saison", "Fehlstarts", "je MWh", "je 1000 Starts 0–15 °C", "Tage mit Daten"), zeilen,
                true, Map.of(
                        "Saison", "Heizsaison von Juli bis Juni.",
                        "Fehlstarts", "Erkannte Fehlstarts der Saison (Fehlerliste: INV H ROTORVEKTOR). Bisher 20–26 "
                                + "pro Winter, auffällig wäre deutlich mehr als ~30.",
                        "je MWh", "Fehlstarts je MWh erzeugter Wärme, unabhängig davon, wie oft die Anlage startet. "
                                + "Bisher 1,0–1,9, auffällig wäre mehr als ~2,5.",
                        "je 1000 Starts 0–15 °C", "Nur Tage mit diesem Tagesmittel, bei Frost gibt es praktisch "
                                + "keine Fehlstarts. Erst ab 03/2022 (Außentemperatur). Bisher 13–16, auffällig wäre "
                                + "mehr als ~20.",
                        "Tage mit Daten", "Tage mit vollständigen Werten. Wenige Tage heißt Lücken, dann sind die "
                                + "Zahlen zu klein."));
        List<Instant> letzte = fehlstarts.getLetzte();
        page.note("Fehlstart: Der Verdichter kommt beim Anlauf nicht in Gang, die Wärmepumpe wartet etwa 23 "
                + "Minuten und startet dann normal. Aus den gespeicherten Werten erkannt (Hochdruck fällt beim "
                + "Startversuch, kein Lauf, Neustart nach der Sperre)."
                + (letzte.isEmpty() ? "" : " Zuletzt erkannt: " + String.join(", ", letzte.stream()
                .map(t -> Fehlerliste.ZEITFORMAT.format(t.atZone(ZoneId.systemDefault()))).toList()) + ".")
                + " Stand " + zeitpunkt(fehlstarts.getBerechnet()) + ".");
    }
}
