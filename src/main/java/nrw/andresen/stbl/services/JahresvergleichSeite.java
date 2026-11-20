package nrw.andresen.stbl.services;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML of the page with the electricity of the heating per month in the last three years, optionally with the
 * household electricity and the devices (e.g. the wallbox) stacked on top, linked with the other pages
 */
public final class JahresvergleichSeite {

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Berlin"));
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMANY);
    private static final int J = Jahresvergleich.JAHRE;
    // Size of the drawing in the units of the viewBox, the browser scales it to the width of the card
    private static final int B = 720;
    private static final int H = 300;
    private static final int LINKS = 52;
    private static final int RECHTS = 8;
    private static final int OBEN = 16;
    private static final int UNTEN = 28;
    // Below this change of the grid import the shares of the change say nothing
    private static final double MIN_AENDERUNG_KWH = 50;
    // A rise of the grid import above this is marked
    private static final double STEIGT_AB_PROZENT = 5;

    private JahresvergleichSeite() {
    }

    /**
     * @param vergleich null while it is not calculated yet
     * @param hinweis   shown instead of the comparison, e.g. that InfluxDB is switched off; null for the comparison
     * @param haushalt  show the household electricity too, only if the house meter is configured
     * @param geraete   show the devices too, only if they are configured
     */
    public static String render(Jahresvergleich.Vergleich vergleich, String hinweis, boolean haushalt,
                                boolean geraete) {
        return render(vergleich, hinweis, haushalt, geraete, null);
    }

    /**
     * @param zeitraum consumption of a chosen period, null if none was asked for
     */
    public static String render(Jahresvergleich.Vergleich vergleich, String hinweis, boolean haushalt,
                                boolean geraete, Jahresvergleich.Zeitraum zeitraum) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Jahresvergleich</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>Jahresvergleich</h1><p class=\"sub\">")
                .append(vergleich == null ? "Stromverbrauch je Jahr und Monat"
                        : (vergleich.gesamt() != null ? "Netzbezug des Hauses und Strom der Heizung, "
                        : "Strom der Heizung je Monat, ") + vergleich.jahre().get(0) + " bis " + vergleich.jahr())
                .append("</p></div><nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"zustand\">Zustand</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
        if (hinweis != null || vergleich == null) {
            html.append("<p class=\"meldung\">").append(text(hinweis != null ? hinweis
                    : "Der Vergleich wird berechnet, das dauert nach dem Start des Dienstes einige Minuten."))
                    .append("</p></main></body></html>");
            return html.toString();
        }
        boolean mitHaushalt = haushalt && vergleich.mitHaushalt();
        boolean mitGeraete = geraete && !vergleich.geraete().isEmpty();
        List<Integer> jahre = vergleich.jahre();
        int jahr = vergleich.jahr();
        // With the house meter the tiles below say the same as the sentence
        if (vergleich.gesamt() == null) {
            html.append("<p class=\"meldung\">").append(text(satz(vergleich))).append("</p>");
        }
        html.append("<section class=\"kpis\">");
        if (vergleich.gesamt() != null) {
            html.append(kpi("Gesamt " + jahr + " bis " + DATUM.format(vergleich.bis()), zahl(vergleich.gesamt(), 0),
                            "kWh"))
                    .append(kpi("Gesamt " + (jahr - 1) + " gleiche Tage", zahl(vergleich.gesamtVorjahrGleich(), 0),
                            "kWh"))
                    .append(kpi("Gesamt Veränderung", prozent(vergleich.gesamtAenderung()), ""))
                    .append("</section>").append(jahresbalken(vergleich)).append(ursachen(vergleich))
                    .append(prognose(vergleich))
                    .append("<section class=\"kpis\">");
        }
        // With the house meter the heating of the same days is in "Woran liegt es?" and the whole years in the
        // table per month; without it these tiles are the only summary of the heating
        if (vergleich.gesamt() == null) {
            html.append(kpi("Heizung ≈ " + jahr + " bis " + DATUM.format(vergleich.bis()),
                            zahl(vergleich.heizung(J - 1), 0), "kWh"))
                    .append(kpi("Heizung ≈ " + (jahr - 1) + " gleiche Tage", zahl(vergleich.heizungGleich(J - 2), 0),
                            "kWh"))
                    .append(kpi("Heizung ≈ Veränderung", prozent(vergleich.aenderung()), ""));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Heizung ≈ " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.heizung(i), 0), "kWh"));
            }
        }
        // With the house meter the years are in the bars above
        if (mitHaushalt && vergleich.gesamt() == null) {
            html.append(kpi("Haushalt " + jahr + " bis " + DATUM.format(vergleich.bis()),
                    zahl(vergleich.haushalt(J - 1), 0), "kWh"));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Haushalt " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.haushalt(i), 0), "kWh"));
            }
        }
        // Without the house meter the forecast of the heating follows its tiles
        html.append("</section>").append(vergleich.gesamt() == null ? prognose(vergleich) : "")
                .append("<section class=\"cards\"><article class=\"card breit\"><h2>Strom je Monat</h2>")
                .append("<p class=\"legende\">");
        for (int i = 0; i < J; i++) {
            html.append("<span><i class=\"sw j").append(i).append("\"></i>").append(jahre.get(i)).append("</span>");
        }
        if (mitHaushalt) {
            html.append("<span><i class=\"sw haushalt\"></i>Haushalt</span>");
        }
        if (mitGeraete) {
            for (int k = 0; k < vergleich.geraete().size(); k++) {
                html.append("<span><i class=\"sw g").append(k).append("\"></i>")
                        .append(text(vergleich.geraete().get(k).name())).append("</span>");
            }
        }
        html.append("<span>").append(mitHaushalt || mitGeraete ? "übereinander auf der Heizung, " : "")
                .append("der laufende Monat ist heller, er ist noch nicht vorbei</span></p>");
        List<String> schalter = new ArrayList<>();
        if (vergleich.mitHaushalt()) {
            schalter.add("<a class=\"nav\" href=\"" + adresse(!mitHaushalt, mitGeraete) + "\">Haushaltsstrom "
                    + (mitHaushalt ? "ausblenden" : "einblenden") + "</a>");
        }
        if (!vergleich.geraete().isEmpty()) {
            schalter.add("<a class=\"nav\" href=\"" + adresse(mitHaushalt, !mitGeraete) + "\">Geräte "
                    + (mitGeraete ? "ausblenden" : "einblenden") + "</a>");
        }
        if (!schalter.isEmpty()) {
            html.append("<p class=\"umschalten\">").append(String.join(" · ", schalter)).append("</p>");
        }
        html.append(diagramm(vergleich, mitHaushalt, mitGeraete))
                .append("</article><article class=\"card breit\"><h2>Die Monate</h2>")
                .append(tabelle(kopf(vergleich, mitHaushalt), zeilen(vergleich, mitHaushalt)))
                .append("<p class=\"note\">Heizung ist die geschätzte Wirkleistung des Verdichter-Inverters aus der ")
                .append("gespeicherten Scheinleistung, wie in der Tagesübersicht, ohne Pumpe und Regelung. Der Zähler ")
                .append("der Wärmepumpe zeigt etwa 15 % weniger. Die Änderung vergleicht ").append(jahr)
                .append(" mit ").append(jahr - 1).append(", im laufenden Monat dieselben Tage bis zur gleichen Stunde.")
                .append(vergleich.mitHaushalt() ? " Haushalt ist der Netzbezug laut Hauszähler minus Heizung"
                        + (vergleich.geraete().isEmpty() ? "" : " und " + namen(vergleich))
                        + "; Strom aus der eigenen PV-Anlage ist nicht enthalten." : " Den Haushaltsstrom "
                        + "zeigt die Seite, wenn in der Konfiguration der Hauszähler eingetragen ist.")
                .append(vergleich.geraete().isEmpty() ? "" : " " + namen(vergleich)
                        + ": Zählerstände der Geräte; vor ihrem ersten Wert zählt ein Gerät, das es schon gab, zum "
                        + "Haushalt.")
                .append(vergleich.gesamt() != null ? " Gesamt ist der Netzbezug laut Hauszähler vom 1. Januar bis "
                        + "jetzt, verglichen mit dem Vorjahr bis zur gleichen Stunde." : "")
                .append(" Außentemperatur: Mittel der Tagesmittel.</p>")
                .append("</article></section>")
                .append(zeitraumKarte(vergleich, zeitraum, vergleich.bis().minusYears(1), vergleich.bis().minusDays(1)))
                .append("<p class=\"note\">Stand ").append(ZEIT.format(vergleich.berechnet()))
                .append(", stündlich neu berechnet.</p></main></body></html>");
        return html.toString();
    }

    /**
     * Card "Woran liegt es?": the parts of the grid import from the 1st of January against the same days of the year
     * before, with a sentence that names the biggest change
     */
    static String ursachen(Jahresvergleich.Vergleich v) {
        List<Jahresvergleich.Anteil> anteile = v.aufteilung();
        if (anteile.isEmpty()) {
            return "";
        }
        Jahresvergleich.Anteil gesamt = anteile.get(0);
        String zeitraum = "1. Januar bis " + DATUM.format(v.bis());
        // Three years when the grid import of two years before is known
        boolean dreiJahre = gesamt.vorvorher() != null;
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Anteil a : anteile) {
            boolean mitAnteil = a != gesamt && Math.abs(gesamt.differenz()) >= MIN_AENDERUNG_KWH;
            List<String> zeile = new ArrayList<>();
            zeile.add(geschaetzt(a.name()));
            if (dreiJahre) {
                zeile.add(zahl(a.vorvorher(), 0));
            }
            zeile.add(zahl(a.vorher(), 0));
            zeile.add(zahl(a.jetzt(), 0));
            zeile.add(differenz(a));
            zeile.add(mitAnteil ? anteil(a.differenz() / gesamt.differenz() * 100) : "–");
            zeilen.add(zeile);
        }
        List<String> kopf = new ArrayList<>();
        kopf.add(zeitraum);
        if (dreiJahre) {
            kopf.add((v.jahr() - 2) + " kWh");
        }
        kopf.add((v.jahr() - 1) + " kWh");
        kopf.add(v.jahr() + " kWh");
        kopf.add("Änderung zu " + (v.jahr() - 1));
        kopf.add("Anteil an der Änderung");
        Double prozent = gesamt.prozent();
        boolean steigt = prozent != null && prozent > STEIGT_AB_PROZENT;
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Woran liegt es?</h2>"
                + "<p class=\"ursache" + (steigt ? " steigt" : "") + "\">" + text(ursache(anteile, v.jahr())) + "</p>"
                + "<p class=\"legende\">Abstand zu " + (v.jahr() - 1) + ", seit 1. Januar aufsummiert: über null mehr "
                + "als " + (v.jahr() - 1) + ", unter null weniger; steigt eine Linie, wächst der Abstand gerade.</p>"
                + abstandsverlauf(v, anteile)
                // Two years before only for information, in grey: every change refers to the year before
                + (dreiJahre ? tabelle(kopf, zeilen).replaceFirst("<div class=\"tbl\">",
                "<div class=\"tbl vorvorjahr\">") : tabelle(kopf, zeilen))
                + "<p class=\"note\">Jeweils vom 1. Januar bis zum selben Tag und zur selben Stunde. Änderungen "
                + "und Anteile gegenüber " + (v.jahr() - 1) + (dreiJahre ? ", " + (v.jahr() - 2) + " zum Vergleich" : "")
                + ". Rest ist Netzbezug minus Heizung" + (v.gezeigt().isEmpty() ? "" : " und "
                + String.join(", ", v.gezeigt().stream().map(k -> v.geraete().get(k).name()).toList()))
                + ", also der übrige Haushalt mit Lüftung und Geräten."
                + (v.imRest().isEmpty() ? "" : " " + String.join(", ", v.imRest()) + " zählt zum Rest, bis es für "
                + "beide Jahre Messwerte gibt.")
                + "</p></article></section>";
    }

    /**
     * Card with the forecast up to the end of the year: what is used so far, what is still expected and the whole
     * year against the year before
     */
    static String prognose(Jahresvergleich.Vergleich v) {
        List<Jahresvergleich.Prognose> teile = v.prognose();
        if (teile.isEmpty()) {
            return "";
        }
        int jahr = v.jahr();
        Jahresvergleich.Prognose erste = teile.get(0);
        String was = erste.name().equals("Gesamt") ? "Das Haus wird " + jahr + " voraussichtlich "
                + zahl(erste.jahr(), 0) + " kWh aus dem Netz beziehen"
                : "Die Heizung wird " + jahr + " voraussichtlich " + zahl(erste.jahr(), 0) + " kWh brauchen";
        Double aenderung = erste.aenderung();
        String satz = was + (aenderung == null ? "." : ", " + (Math.abs(aenderung) < 1 ? "etwa so viel wie"
                : zahl(Math.abs(aenderung), 1) + " % " + (aenderung > 0 ? "mehr" : "weniger") + " als") + " "
                + (jahr - 1) + " (" + zahl(erste.vorjahr(), 0) + " kWh).");
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Prognose p : teile) {
            zeilen.add(List.of(geschaetzt(p.name()), zahl(p.bisher(), 0), zahl(p.erwartet(), 0), zahl(p.jahr(), 0),
                    zahl(p.vorjahr(), 0), prozent(p.aenderung())));
        }
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Prognose bis Jahresende</h2>"
                + "<p class=\"ursache\">" + text(satz) + "</p>"
                + tabelle(List.of(String.valueOf(jahr), "bisher kWh", "bis 31.12. erwartet kWh", "Prognose kWh",
                (jahr - 1) + " kWh", "Änderung"), zeilen)
                + "<p class=\"note\">Erwartet bis 31.12.: Heizung wie im Rest der Vorjahre (Mittel aus "
                + (jahr - 1) + " und " + (jahr - 2) + "), der übrige Haushalt wie im Rest von " + (jahr - 1)
                + (v.geraete().isEmpty() ? "" : ", Geräte, die es schon gab, wie im Rest von " + (jahr - 1)
                + ", neue mit dem Verbrauch je Tag der letzten drei vollen Monate") + ". Ein kalter oder milder "
                + "Winter verschiebt die Heizung um einige hundert kWh.</p></article></section>";
    }

    /**
     * Sentence on the change of the grid import and its biggest part
     */
    static String ursache(List<Jahresvergleich.Anteil> anteile, int jahr) {
        Jahresvergleich.Anteil gesamt = anteile.get(0);
        Double prozent = gesamt.prozent();
        if (prozent == null) {
            return "Für " + (jahr - 1) + " fehlt der Netzbezug, ein Vergleich ist nicht möglich.";
        }
        if (Math.abs(gesamt.differenz()) < MIN_AENDERUNG_KWH || Math.abs(prozent) < 1) {
            return "Der Stromverbrauch ist etwa gleich geblieben wie in denselben Tagen " + (jahr - 1) + ".";
        }
        boolean mehr = gesamt.differenz() > 0;
        Jahresvergleich.Anteil treiber = null;
        for (Jahresvergleich.Anteil a : anteile.subList(1, anteile.size())) {
            if (treiber == null || (mehr ? a.differenz() > treiber.differenz() : a.differenz() < treiber.differenz())) {
                treiber = a;
            }
        }
        return "Der Stromverbrauch ist gegenüber denselben Tagen " + (jahr - 1) + " um "
                + zahl(Math.abs(gesamt.differenz()), 0) + " kWh (" + zahl(Math.abs(prozent), 1) + " %) "
                + (mehr ? "gestiegen, vor allem " : "gesunken, vor allem ") + teil(treiber.name(), mehr) + " ("
                + vorzeichen(treiber.differenz()) + zahl(Math.abs(treiber.differenz()), 0) + " kWh).";
    }

    private static String teil(String name, boolean mehr) {
        if (name.startsWith("Rest")) {
            return mehr ? "durch den übrigen Haushalt" : "beim übrigen Haushalt";
        }
        return "bei " + geschaetzt(name);
    }

    /**
     * Names of the devices, e.g. "Wallbox, Klimaanlage"
     */
    static String namen(Jahresvergleich.Vergleich v) {
        return String.join(", ", v.geraete().stream().map(Geraete.Geraet::name).toList());
    }

    /**
     * CSS class of a part: gesamt, heizung, haushalt (also the rest) or g0 … g4 for the devices
     */
    static String klasse(Jahresvergleich.Vergleich v, String name) {
        if (name.equals("Gesamt")) {
            return "gesamt";
        }
        if (name.equals("Heizung")) {
            return "heizung";
        }
        for (int k = 0; k < v.geraete().size(); k++) {
            if (v.geraete().get(k).name().equals(name)) {
                return "g" + k;
            }
        }
        return "haushalt";
    }

    private static String differenz(Jahresvergleich.Anteil a) {
        return vorzeichen(a.differenz()) + zahl(Math.abs(a.differenz()), 0) + " kWh"
                + (a.prozent() == null ? "" : " (" + prozent(a.prozent()) + ")");
    }

    private static String anteil(double prozent) {
        return Math.abs(prozent) < 0.5 ? "0 %" : (prozent < 0 ? "−" : "") + zahl(Math.abs(prozent), 0) + " %";
    }

    private static String vorzeichen(double wert) {
        return wert >= 0.5 ? "+" : wert <= -0.5 ? "−" : "±";
    }

    /**
     * Address of this page with the given layers shown
     */
    static String adresse(boolean haushalt, boolean geraete) {
        // Both are shown unless switched off, the page is about the whole grid import
        List<String> parameter = new ArrayList<>();
        if (!haushalt) {
            parameter.add("haushalt=nein");
        }
        if (!geraete) {
            parameter.add("geraete=nein");
        }
        return "jahresvergleich" + (parameter.isEmpty() ? "" : "?" + String.join("&amp;", parameter));
    }

    /**
     * One sentence without the house meter: the heating this year up to today against the same days of the year
     * before and the whole years before
     */
    static String satz(Jahresvergleich.Vergleich v) {
        int jahr = v.jahr();
        Double aenderung = v.aenderung();
        String text = "Vom 1. Januar bis " + DATUM.format(v.bis()) + " " + jahr + " hat die Heizung "
                + zahl(v.heizung(J - 1), 0) + " kWh Strom gebraucht";
        if (aenderung != null) {
            text += ", " + (Math.abs(aenderung) < 0.5 ? "genauso viel wie"
                    : zahl(Math.abs(aenderung), 1) + " % " + (aenderung > 0 ? "mehr" : "weniger") + " als")
                    + " in denselben Tagen " + (jahr - 1) + " (" + zahl(v.heizungGleich(J - 2), 0) + " kWh)";
        }
        return text + ". Im ganzen Jahr waren es " + zahl(v.heizung(J - 2), 0) + " kWh (" + (jahr - 1) + ") und "
                + zahl(v.heizung(J - 3), 0) + " kWh (" + (jahr - 2) + ").";
    }

    static List<String> kopf(Jahresvergleich.Vergleich v, boolean mitHaushalt) {
        List<String> kopf = new ArrayList<>();
        kopf.add("Monat");
        if (v.gesamt() != null) {
            // With the house meter: the grid import per year first, the heating as a part of it
            v.jahre().forEach(j -> kopf.add("Gesamt " + j + " kWh"));
            kopf.add("Änderung");
            v.jahre().forEach(j -> kopf.add("Heizung ≈ " + j + " kWh"));
            v.jahre().forEach(j -> kopf.add("Außen " + j + " °C"));
            return kopf;
        }
        v.jahre().forEach(j -> kopf.add("Heizung ≈ " + j + " kWh"));
        kopf.add("Änderung");
        if (mitHaushalt) {
            v.jahre().forEach(j -> kopf.add("Haushalt " + j + " kWh"));
        }
        v.jahre().forEach(j -> kopf.add("Außen " + j + " °C"));
        return kopf;
    }

    /**
     * Rows of the table: month, heating per year, change, household per year if shown, outdoor
     * temperature per year
     */
    static List<List<String>> zeilen(Jahresvergleich.Vergleich v, boolean mitHaushalt) {
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Monat m : v.monate()) {
            List<String> zeile = new ArrayList<>();
            zeile.add(m.name());
            if (v.gesamt() != null) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(gesamt(m, i), 0) + (i == J - 1 ? bisHeute(v, m, gesamt(m, i)) : ""));
                }
                Double jetzt = gesamt(m, J - 1);
                Double vorher = gesamt(m, J - 2);
                // The current month is not over, the year before has no value up to the same day
                zeile.add(m.laufend() || jetzt == null || vorher == null || vorher < 5 ? "–"
                        : prozent((jetzt / vorher - 1) * 100));
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.heizung().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.heizung().get(i)) : ""));
                }
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.aussen().get(i), 1));
                }
                zeilen.add(zeile);
                continue;
            }
            for (int i = 0; i < J; i++) {
                zeile.add(zahl(m.heizung().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.heizung().get(i)) : ""));
            }
            zeile.add(prozent(m.aenderung()));
            if (mitHaushalt) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.haushalt().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.haushalt().get(i)) : ""));
                }
            }
            for (int i = 0; i < J; i++) {
                zeile.add(zahl(m.aussen().get(i), 1));
            }
            zeilen.add(zeile);
        }
        return zeilen;
    }

    private static String bisHeute(Jahresvergleich.Vergleich v, Jahresvergleich.Monat m, Double wert) {
        return m.laufend() && wert != null ? " (bis " + v.bis().getDayOfMonth() + ".)" : "";
    }

    /**
     * Bars per month as inline SVG, one per year with the current year in the accent color, the household and the
     * devices stacked on top if shown, a tooltip per bar
     */
    static String diagramm(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitGeraete) {
        int anzahl = mitGeraete ? v.geraete().size() : 0;
        double max = 0;
        for (Jahresvergleich.Monat m : v.monate()) {
            for (int i = 0; i < J; i++) {
                double summe = wert(m.heizung().get(i)) + (mitHaushalt ? wert(m.haushalt().get(i)) : 0);
                for (int k = 0; k < anzahl; k++) {
                    summe += wert(m.geraet(k, i));
                }
                max = Math.max(max, summe);
            }
        }
        double schritt = schritt(max);
        double oben = Math.max(schritt, Math.ceil(max / schritt) * schritt);
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + H
                + "\" width=\"100%\" role=\"img\" aria-label=\"Strom der Heizung" + (mitHaushalt ? ", des Haushalts" : "")
                + (anzahl > 0 ? ", " + text(namen(v)) : "") + " je Monat, " + v.jahre().get(0) + " bis " + v.jahr()
                + "\">");
        for (double t = 0; t <= oben + schritt / 2; t += schritt) {
            double y = y(t, oben);
            svg.append(String.format(Locale.ROOT, "<line class=\"gitter\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>",
                    LINKS, B - RECHTS, y, y));
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>",
                    LINKS - 6, y + 4, zahl(t, 0)));
        }
        double breite = (B - LINKS - RECHTS) / 12.0;
        double balken = Math.min(18, (breite * 0.8) / J);
        for (Jahresvergleich.Monat m : v.monate()) {
            double x0 = LINKS + (m.monat() - 1) * breite + (breite - balken * J) / 2;
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>",
                    x0 + balken * J / 2, H - 8, m.name()));
            for (int i = 0; i < J; i++) {
                Double heizung = m.heizung().get(i);
                if (heizung == null) {
                    continue;
                }
                int jahr = v.jahre().get(i);
                boolean laufend = m.laufend() && i == J - 1;
                Double haushalt = mitHaushalt ? m.haushalt().get(i) : null;
                StringBuilder teile = new StringBuilder();
                for (int k = 0; k < anzahl; k++) {
                    Double g = m.geraet(k, i);
                    if (g != null) {
                        teile.append(", ").append(v.geraete().get(k).name()).append(" ").append(zahl(g, 0))
                                .append(" kWh");
                    }
                }
                String titel = m.name() + " " + jahr + (laufend ? " bis " + v.bis().getDayOfMonth() + "." : "")
                        + ": Heizung " + zahl(heizung, 0) + " kWh"
                        + (haushalt == null ? "" : ", Haushalt " + zahl(haushalt, 0) + " kWh") + teile
                        + (m.aussen().get(i) == null ? "" : ", außen " + zahl(m.aussen().get(i), 1) + " °C")
                        + (m.laufend() && i < J - 1 && m.heizungGleich().get(i) != null
                        ? "; bis zum gleichen Tag " + zahl(m.heizungGleich().get(i), 0) + " kWh" : "");
                double x = x0 + i * balken + 1;
                svg.append(balken("j" + i + (laufend ? " laufend" : ""), x, balken - 2, 0, heizung, oben, titel));
                double unten = heizung;
                if (haushalt != null && haushalt > 0) {
                    svg.append(balken("haushalt j" + i + (laufend ? " laufend" : ""), x, balken - 2, unten,
                            unten + haushalt, oben, titel));
                    unten += haushalt;
                }
                for (int k = 0; k < anzahl; k++) {
                    Double g = m.geraet(k, i);
                    if (g != null && g > 0) {
                        svg.append(balken("geraet g" + k + " j" + i + (laufend ? " laufend" : ""), x, balken - 2,
                                unten, unten + g, oben, titel));
                        unten += g;
                    }
                }
            }
        }
        svg.append(String.format(Locale.ROOT, "<line class=\"achslinie\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>",
                LINKS, B - RECHTS, y(0, oben), y(0, oben)));
        return svg.append("</svg></div>").toString();
    }

    private static String balken(String klasse, double x, double breite, double von, double bis, double oben,
                                 String titel) {
        double y = y(bis, oben);
        double hoehe = Math.max(0, y(von, oben) - y);
        return String.format(Locale.ROOT, "<rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" "
                + "rx=\"2\" data-wert=\"%s\"></rect>", klasse, x, y, breite, hoehe, text(titel));
    }

    private static double y(double wert, double oben) {
        return OBEN + (1 - wert / oben) * (H - OBEN - UNTEN);
    }

    /**
     * Step of the grid lines: 1, 2 or 5 times a power of ten, about 5 lines
     */
    static double schritt(double max) {
        if (max <= 0) {
            return 1;
        }
        double roh = max / 5;
        double stelle = Math.pow(10, Math.floor(Math.log10(roh)));
        double n = roh / stelle;
        return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * stelle;
    }

    private static double wert(Double wert) {
        return wert == null ? 0 : wert;
    }

    private static String kpi(String label, String wert, String einheit) {
        return "<article class=\"kpi\"><div class=\"label\">" + text(label) + "</div><div class=\"value\">"
                + text(wert) + (einheit.isEmpty() ? "" : "<span class=\"unit\">" + einheit + "</span>")
                + "</div></article>";
    }

    private static String tabelle(List<String> kopf, List<List<String>> zeilen) {
        StringBuilder html = new StringBuilder("<div class=\"tbl\"><table><thead><tr>");
        kopf.forEach(k -> html.append("<th>").append(text(k)).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (List<String> zeile : zeilen) {
            html.append("<tr>");
            zeile.forEach(z -> html.append("<td>").append(text(z)).append("</td>"));
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    static String zahl(Double wert, int stellen) {
        return wert == null ? "–" : String.format(Locale.GERMANY, "%,." + stellen + "f", wert);
    }

    static String prozent(Double wert) {
        if (wert == null) {
            return "–";
        }
        return (wert > 0.05 ? "+" : wert < -0.05 ? "−" : "±") + zahl(Math.abs(wert), 1) + " %";
    }

    private static String text(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Grid import of a month: heating, household and the devices together, null without the house meter
     */
    static Double gesamt(Jahresvergleich.Monat m, int i) {
        Double haushalt = m.haushalt().get(i);
        if (haushalt == null) {
            return null;
        }
        double summe = wert(m.heizung().get(i)) + haushalt;
        for (int k = 0; k < m.geraete().size(); k++) {
            summe += wert(m.geraet(k, i));
        }
        return summe;
    }

    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Card to read the consumption of a chosen period, e.g. between the meter readings of two bills: a form with two
     * days and, once asked for, the readings of the house meter at both ends and the parts in between
     */
    static String zeitraumKarte(Jahresvergleich.Vergleich v, Jahresvergleich.Zeitraum z, LocalDate vonVorschlag,
                                LocalDate bisVorschlag) {
        if (v.gesamt() == null) {
            return "";
        }
        LocalDate von = z == null ? vonVorschlag : z.von();
        LocalDate bis = z == null ? bisVorschlag : z.bis();
        StringBuilder html = new StringBuilder("<section class=\"cards\" id=\"zeitraum\"><article class=\"card breit\">"
                + "<h2>Verbrauch im Zeitraum</h2><p class=\"legende\">Zum Abgleich mit der Stromrechnung: die "
                + "Zählerstände des Hauszählers zu Beginn des ersten und am Ende des letzten Tages.</p>"
                + "<form class=\"zeitraum\" method=\"get\" action=\"jahresvergleich#zeitraum\">"
                + "<label>von <input type=\"date\" name=\"von\" required value=\"" + von + "\"></label>"
                + "<label>bis <input type=\"date\" name=\"bis\" required value=\"" + bis + "\"></label>"
                + "<button type=\"submit\">Berechnen</button></form>");
        if (z != null) {
            List<List<String>> zeilen = new ArrayList<>();
            boolean bisJetzt = z.ende().isBefore(z.bis().plusDays(1).atStartOfDay(java.time.ZoneId.of(
                    "Europe/Berlin")).toInstant());
            zeilen.add(List.of("Zählerstand " + TAG.format(z.von()) + " 0:00", zahl(z.standAnfang(), 1)));
            zeilen.add(List.of("Zählerstand " + (bisJetzt ? "jetzt" : TAG.format(z.bis()) + " 24:00"),
                    zahl(z.standEnde(), 1)));
            Double gesamt = z.gesamt();
            zeilen.add(List.of("Netzbezug (" + z.tage() + " Tage)", zahl(gesamt, 0)
                    + (gesamt == null ? "" : " · " + zahl(gesamt / z.tage(), 1) + " je Tag")));
            zeilen.add(List.of("davon Heizung ≈", zahl(z.heizung(), 0)));
            List<String> unbekannt = new ArrayList<>();
            for (int k = 0; k < z.namen().size(); k++) {
                Double g = z.geraete().get(k);
                if (g == null) {
                    unbekannt.add(z.namen().get(k));
                }
                zeilen.add(List.of("davon " + z.namen().get(k), g == null ? "nicht gemessen" : zahl(g, 0)));
            }
            zeilen.add(List.of("davon Haushalt" + (unbekannt.isEmpty() ? "" : " mit " + String.join(", ", unbekannt)),
                    zahl(z.haushalt(), 0)));
            html.append(tabelle(List.of(TAG.format(z.von()) + " bis " + TAG.format(z.bis()), "kWh"), zeilen))
                    .append("<p class=\"note\">Die Zählerstände sind aus den gespeicherten Werten des Hauszählers zum "
                            + "Tagesbeginn gerechnet (zwischen zwei Werten anteilig). Weicht die Rechnung ab, kann sie "
                            + "einen geschätzten oder an einem anderen Tag abgelesenen Stand enthalten. Heizung ≈ ist aus "
                            + "dem Inverter geschätzt, Haushalt der Rest.</p>");
        }
        return html.append("</article></section>").toString();
    }

    /**
     * Card with one bar per year, the current year with its forecast, made of heating, household and the devices:
     * how the whole consumption develops and of what it consists. Empty without the house meter.
     */
    static String jahresbalken(Jahresvergleich.Vergleich v) {
        if (v.gesamt() == null) {
            return "";
        }
        int anzahl = v.geraete().size();
        // Parts: heating, household, then the devices
        List<String> klassen = new ArrayList<>(List.of("heizung", "haushalt"));
        List<String> namen = new ArrayList<>(List.of("Heizung ≈", "Haushalt"));
        for (int k = 0; k < anzahl; k++) {
            klassen.add("g" + k);
            namen.add(v.geraete().get(k).name());
        }
        int teile = klassen.size();
        List<double[]> jahre = new ArrayList<>();
        List<Integer> jahreszahlen = new ArrayList<>();
        // All years that are over with readings of the house meter, so no year drops out on the 1st of January;
        // without them the years of the comparison. A device not measured then is part of the household.
        if (v.jahressummen() != null && !v.jahressummen().isEmpty()) {
            for (Jahresvergleich.Jahressumme j : v.jahressummen()) {
                double[] t = new double[teile];
                t[0] = j.heizung();
                t[1] = Math.max(0, j.haushalt());
                for (int k = 0; k < anzahl && k < j.geraete().size(); k++) {
                    t[2 + k] = wert(j.geraete().get(k));
                }
                jahre.add(t);
                jahreszahlen.add(j.jahr());
            }
        } else {
            for (int i = 0; i < J - 1; i++) {
                double[] t = new double[teile];
                t[0] = v.heizung(i);
                t[1] = wert(v.haushalt(i));
                for (int k = 0; k < anzahl; k++) {
                    t[2 + k] = wert(v.geraet(k, i));
                }
                jahre.add(t);
                jahreszahlen.add(v.jahre().get(i));
            }
        }
        double[] jetzt = new double[teile];
        for (Jahresvergleich.Prognose p : v.prognose()) {
            String kl = klasse(v, p.name());
            int k = klassen.indexOf(kl);
            if (k >= 0) {
                jetzt[k] = p.jahr();
            }
        }
        jahre.add(jetzt);
        jahreszahlen.add(v.jahr());
        int n = jahre.size();
        double max = jahre.stream().mapToDouble(JahresvergleichSeite::summe).max().orElse(0);
        if (max <= 0) {
            return "";
        }
        int zeile = 34;
        int links = 120;
        int rechts = 190;
        int hoehe = n * zeile + 6;
        double breite = B - links - rechts;
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + hoehe
                + "\" width=\"100%\" role=\"img\" aria-label=\"Netzbezug je Jahr\">");
        for (int i = 0; i < n; i++) {
            double[] t = jahre.get(i);
            double y = 4 + i * zeile;
            boolean prognose = i == n - 1;
            int jahr = jahreszahlen.get(i);
            svg.append(String.format(Locale.ROOT, "<text class=\"teil\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">"
                    + "%s</text>", links - 10, y + zeile / 2.0 + 1, jahr + (prognose ? " Prognose" : "")));
            double x = links;
            double summe = summe(t);
            for (int k = 0; k < teile; k++) {
                if (t[k] <= 0) {
                    continue;
                }
                double w = t[k] / max * breite;
                String titel = jahr + (prognose ? " (Prognose)" : "") + ": " + namen.get(k) + " " + zahl(t[k], 0)
                        + " kWh von " + zahl(summe, 0) + " kWh";
                svg.append(String.format(Locale.ROOT, "<rect class=\"teil %s%s\" x=\"%.1f\" y=\"%.1f\" "
                        + "width=\"%.1f\" height=\"%d\" data-wert=\"%s\"></rect>", klassen.get(k),
                        prognose ? " prognose" : "", x, y + 4, Math.max(1, w - 2), zeile - 10, text(titel)));
                x += w;
            }
            // Change against the year before, the current year with its forecast
            String aenderung = "";
            if (i > 0) {
                double vorher = summe(jahre.get(i - 1));
                if (vorher > 0) {
                    aenderung = "<tspan class=\"aenderung\" dx=\"10\">" + prozent((summe / vorher - 1) * 100)
                            + "</tspan>";
                }
            }
            svg.append(String.format(Locale.ROOT, "<text class=\"wert\" x=\"%.1f\" y=\"%.1f\">%s kWh%s</text>",
                    x + 6, y + zeile / 2.0 + 1, (prognose ? "≈ " : "") + zahl(summe, 0), aenderung));
        }
        StringBuilder legende = new StringBuilder();
        for (int k = 0; k < teile; k++) {
            legende.append("<span><i class=\"sw ").append(k == 0 ? "teil-heizung" : klassen.get(k)).append("\"></i>")
                    .append(text(namen.get(k))).append("</span>");
        }
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Netzbezug je Jahr</h2>"
                + "<p class=\"legende\">" + legende + "<span>"
                + v.jahr() + " heller: bis " + DATUM.format(v.bis()) + " gemessen, der Rest ist die Prognose unten; "
                + "% gegenüber dem Vorjahr; ein Gerät vor seinem ersten Messwert steckt im Haushalt"
                + "</span></p>" + svg + "</svg></div></article></section>";
    }

    private static double summe(double[] werte) {
        double s = 0;
        for (double w : werte) {
            s += w;
        }
        return s;
    }

    /**
     * Gap to the year before added up from the 1st of January, one line for the whole grid import and one per
     * part: when the gap grows and through which part. Uses the months that are over and the same days of the
     * current month; the rest is the grid import minus the other parts.
     */
    static String abstandsverlauf(Jahresvergleich.Vergleich v, List<Jahresvergleich.Anteil> anteile) {
        if (v.monate().size() < 12 || anteile.isEmpty()) {
            return "";
        }
        int jahr = v.jahr();
        // The lines in the order of the parts: total, devices, heating, rest
        List<String> klassen = new ArrayList<>();
        List<String> namen = new ArrayList<>();
        for (Jahresvergleich.Anteil a : anteile) {
            klassen.add(klasse(v, a.name()));
            namen.add(a.name().startsWith("Rest") ? "Haushalt" : geschaetzt(a.name()));
        }
        List<Integer> gezeigt = v.gezeigt();
        int linienAnzahl = anteile.size();
        List<List<double[]>> linien = new ArrayList<>();
        for (int k = 0; k < linienAnzahl; k++) {
            List<double[]> l = new ArrayList<>();
            l.add(new double[]{0, 0, -1});
            linien.add(l);
        }
        double[] summe = new double[linienAnzahl];
        double tage = java.time.Year.of(jahr).length();
        for (Jahresvergleich.Monat m : v.monate()) {
            if (m.laufend() || m.monat() > v.bis().getMonthValue()) {
                break;
            }
            Double gJetzt = gesamt(m, J - 1);
            Double gVorher = gesamt(m, J - 2);
            if (gJetzt == null || gVorher == null) {
                return "";
            }
            double[] d = new double[linienAnzahl];
            d[0] = gJetzt - gVorher;
            double rest = d[0];
            for (int z = 0; z < gezeigt.size(); z++) {
                int k = gezeigt.get(z);
                d[1 + z] = wert(m.geraet(k, J - 1)) - wert(m.geraet(k, J - 2));
                rest -= d[1 + z];
            }
            d[linienAnzahl - 2] = wert(m.heizung().get(J - 1)) - wert(m.heizung().get(J - 2));
            rest -= d[linienAnzahl - 2];
            d[linienAnzahl - 1] = rest;
            double x = java.time.YearMonth.of(jahr, m.monat()).atEndOfMonth().getDayOfYear() / tage;
            for (int k = 0; k < linienAnzahl; k++) {
                summe[k] += d[k];
                linien.get(k).add(new double[]{x, summe[k], m.monat()});
            }
        }
        // The last point: the same days up to now, as in the table
        double xHeute = v.bis().getDayOfYear() / tage;
        for (int k = 0; k < linienAnzahl; k++) {
            linien.get(k).add(new double[]{xHeute, anteile.get(k).differenz(), 0});
        }
        double min = 0;
        double max = 0;
        for (List<double[]> l : linien) {
            for (double[] p : l) {
                min = Math.min(min, p[1]);
                max = Math.max(max, p[1]);
            }
        }
        double schritt = schritt(Math.max(max - min, 1));
        double unten = Math.floor(min / schritt) * schritt;
        double oben = Math.max(schritt, Math.ceil(max / schritt) * schritt);
        int rechts = 130;
        double breite = B - LINKS - rechts;
        java.util.function.DoubleUnaryOperator yv = w -> OBEN + (oben - w) / (oben - unten) * (H - OBEN - UNTEN);
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + H
                + "\" width=\"100%\" role=\"img\" aria-label=\"Abstand zu " + (jahr - 1) + " seit 1. Januar\">");
        for (double t = unten; t <= oben + schritt / 2; t += schritt) {
            double y = yv.applyAsDouble(t);
            svg.append(String.format(Locale.ROOT, "<line class=\"%s\" x1=\"%d\" x2=\"%.1f\" y1=\"%.1f\" "
                    + "y2=\"%.1f\"/>", Math.abs(t) < 1e-9 ? "achslinie" : "gitter", LINKS, LINKS + breite, y, y));
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%d\" y=\"%.1f\" "
                    + "text-anchor=\"end\">%s</text>", LINKS - 6, y + 4,
                    (t > 0 ? "+" : t < 0 ? "−" : "") + zahl(Math.abs(t), 0)));
        }
        for (int monat = 1; monat <= 12; monat++) {
            double mitte = java.time.YearMonth.of(jahr, monat).atDay(15).getDayOfYear() / tage;
            svg.append(String.format(Locale.ROOT, "<text class=\"achse\" x=\"%.1f\" y=\"%d\" "
                    + "text-anchor=\"middle\">%s</text>", LINKS + mitte * breite, H - 8,
                    v.monate().get(monat - 1).name()));
        }
        List<double[]> etiketten = new ArrayList<>();
        for (int k = 0; k < linienAnzahl; k++) {
            List<double[]> l = linien.get(k);
            StringBuilder p = new StringBuilder();
            for (double[] x : l) {
                p.append(p.isEmpty() ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f", LINKS + x[0] * breite,
                        yv.applyAsDouble(x[1])));
            }
            svg.append("<polyline class=\"abstand ").append(klassen.get(k)).append("\" points=\"").append(p)
                    .append("\"/>");
            for (int i = 1; i < l.size(); i++) {
                double[] x = l.get(i);
                String wann = x[2] == 0 ? "bis " + DATUM.format(v.bis())
                        : "bis Ende " + v.monate().get((int) x[2] - 1).name();
                svg.append(String.format(Locale.ROOT, "<circle class=\"abstand %s\" cx=\"%.1f\" cy=\"%.1f\" "
                        + "r=\"3.5\" data-wert=\"%s\"></circle>", klassen.get(k), LINKS + x[0] * breite,
                        yv.applyAsDouble(x[1]), text(namen.get(k) + " " + wann + ": " + vorzeichen(x[1])
                                + zahl(Math.abs(x[1]), 0) + " kWh gegenüber " + (jahr - 1))));
            }
            double[] ende = l.get(l.size() - 1);
            etiketten.add(new double[]{yv.applyAsDouble(ende[1]), k, ende[1], LINKS + ende[0] * breite});
        }
        etiketten.sort(java.util.Comparator.comparingDouble(e -> e[0]));
        for (int i = 1; i < etiketten.size(); i++) {
            if (etiketten.get(i)[0] - etiketten.get(i - 1)[0] < 15) {
                etiketten.get(i)[0] = etiketten.get(i - 1)[0] + 15;
            }
        }
        for (double[] e : etiketten) {
            int k = (int) e[1];
            svg.append(String.format(Locale.ROOT, "<text class=\"etikett %s\" x=\"%.1f\" y=\"%.1f\">%s %s</text>",
                    klassen.get(k), e[3] + 8, e[0] + 4, text(namen.get(k).replace(" ≈", "")),
                    vorzeichen(e[2]) + zahl(Math.abs(e[2]), 0)));
        }
        return svg.append("</svg></div>").toString();
    }

    // The colors of the devices are the page's own, light and dark like the tokens of the status page
    private static final String CSS = """
            :root { --g0: #6d5bd0; --g1: #2563eb; --g2: #be185d; --g3: #4d7c0f; --g4: #475569; }
            @media (prefers-color-scheme: dark) { :root { --g0: #a99bf0; --g1: #60a5fa; --g2: #f472b6; --g3: #a3e635; --g4: #cbd5e1; } }
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 14px; }
            .zeitraum { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; margin: 4px 0 12px; }
            .zeitraum label { display: inline-flex; align-items: center; gap: 6px; font-size: 14px; }
            .zeitraum input { font: inherit; padding: 6px 8px; border: 1px solid var(--line); border-radius: 8px;
              background: var(--card); color: var(--text); }
            .zeitraum button { font: inherit; font-weight: 600; padding: 7px 14px; border: 0; border-radius: 8px;
              background: var(--accent); color: #fff; cursor: pointer; }
            .tbl.vorvorjahr td:nth-child(2), .tbl.vorvorjahr th:nth-child(2) { color: var(--muted); }
            .legende { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 4px 0 4px; color: var(--muted);
              font-size: 13px; }
            .legende span { display: inline-flex; align-items: center; gap: 6px; }
            .umschalten { margin: 0 0 8px; font-size: 14px; }
            .ursache { margin: 4px 0 8px; font-weight: 600; }
            .cards + .kpis, .cards + .cards { margin-top: 16px; }
            .ursache.steigt { color: var(--warn); }
            .sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
            .sw.j0 { background: var(--muted); opacity: 0.3; }
            .sw.j1 { background: var(--muted); opacity: 0.65; }
            .sw.j2 { background: var(--accent); }
            .sw.haushalt { background: var(--stale); }
            .diagramm rect.j0 { fill: var(--muted); fill-opacity: 0.3; }
            .diagramm rect.j1 { fill: var(--muted); fill-opacity: 0.65; }
            .diagramm rect.j2 { fill: var(--accent); }
            .diagramm rect.haushalt { fill: var(--stale); }
            .diagramm rect.haushalt.j0 { fill-opacity: 0.35; }
            .diagramm rect.haushalt.j1 { fill-opacity: 0.6; }
            .diagramm rect.haushalt.j2 { fill-opacity: 0.9; }
            .diagramm rect.laufend { fill-opacity: 0.5; }
            .diagramm rect:hover { stroke: var(--text); stroke-width: 1; }
            .diagramm .gitter { stroke: var(--line); stroke-width: 1; }
            .diagramm .achslinie { stroke: var(--muted); stroke-width: 1; }
            .diagramm .achse { fill: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
            .diagramm polyline.abstand { fill: none; stroke-width: 2.5; stroke-linejoin: round; }
            .diagramm polyline.abstand.gesamt { stroke: var(--text); stroke-width: 3; }
            .diagramm polyline.abstand.heizung { stroke: var(--accent); }
            .diagramm polyline.abstand.haushalt { stroke: var(--stale); }
            .diagramm circle.abstand { stroke: var(--card); stroke-width: 1.5; }
            .diagramm circle.abstand.gesamt { fill: var(--text); }
            .diagramm circle.abstand.heizung { fill: var(--accent); }
            .diagramm circle.abstand.haushalt { fill: var(--stale); }
            .diagramm .etikett { font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; fill: var(--text); }
            .diagramm .etikett.heizung { fill: var(--accent); }
            .diagramm .etikett.haushalt { fill: var(--stale); }
            .diagramm rect.teil.heizung { fill: var(--accent); }
            .diagramm rect.teil.haushalt { fill: var(--stale); }
            .diagramm rect.teil.prognose { fill-opacity: 0.55; }
            .diagramm .teil { font-size: 13px; fill: var(--text); }
            .diagramm .aenderung { font-weight: 500; fill: var(--muted); }
            .diagramm .wert { font-size: 13px; fill: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }
            .sw.teil-heizung { background: var(--accent); }
            .sw.g0, .diagramm rect.teil.g0 { background: var(--g0); fill: var(--g0); }
            .diagramm rect.geraet.g0, .diagramm circle.abstand.g0 { fill: var(--g0); }
            .diagramm polyline.abstand.g0 { stroke: var(--g0); }
            .diagramm .etikett.g0 { fill: var(--g0); }
            .sw.g1, .diagramm rect.teil.g1 { background: var(--g1); fill: var(--g1); }
            .diagramm rect.geraet.g1, .diagramm circle.abstand.g1 { fill: var(--g1); }
            .diagramm polyline.abstand.g1 { stroke: var(--g1); }
            .diagramm .etikett.g1 { fill: var(--g1); }
            .sw.g2, .diagramm rect.teil.g2 { background: var(--g2); fill: var(--g2); }
            .diagramm rect.geraet.g2, .diagramm circle.abstand.g2 { fill: var(--g2); }
            .diagramm polyline.abstand.g2 { stroke: var(--g2); }
            .diagramm .etikett.g2 { fill: var(--g2); }
            .sw.g3, .diagramm rect.teil.g3 { background: var(--g3); fill: var(--g3); }
            .diagramm rect.geraet.g3, .diagramm circle.abstand.g3 { fill: var(--g3); }
            .diagramm polyline.abstand.g3 { stroke: var(--g3); }
            .diagramm .etikett.g3 { fill: var(--g3); }
            .sw.g4, .diagramm rect.teil.g4 { background: var(--g4); fill: var(--g4); }
            .diagramm rect.geraet.g4, .diagramm circle.abstand.g4 { fill: var(--g4); }
            .diagramm polyline.abstand.g4 { stroke: var(--g4); }
            .diagramm .etikett.g4 { fill: var(--g4); }
            .diagramm rect.geraet.j0 { fill-opacity: 0.35; }
            .diagramm rect.geraet.j1 { fill-opacity: 0.6; }
            .diagramm rect.geraet.j2 { fill-opacity: 0.9; }
            .diagramm rect.geraet.laufend, .diagramm rect.haushalt.laufend { fill-opacity: 0.45; }
            """;

    /**
     * The electricity of the heating is the estimated real power of the inverter, marked like everywhere with ≈
     */
    static String geschaetzt(String name) {
        return name.equals("Heizung") ? "Heizung ≈" : name;
    }
}
