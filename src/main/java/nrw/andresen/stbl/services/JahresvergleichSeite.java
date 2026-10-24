package nrw.andresen.stbl.services;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML of the page with the electricity of the heating per month in the last three years, optionally with the
 * household electricity and the wallbox stacked on top, linked with the status page, the weekly report and the configuration page
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
     * @param wallbox   show the wallbox too, only if its counter is configured
     */
    public static String render(Jahresvergleich.Vergleich vergleich, String hinweis, boolean haushalt,
                                boolean wallbox) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Jahresvergleich</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>Jahresvergleich</h1><p class=\"sub\">")
                .append(vergleich == null ? "Strom der Heizung je Monat"
                        : "Strom der Heizung je Monat, " + vergleich.jahre().get(0) + " bis " + vergleich.jahr())
                .append("</p></div><nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"wochenbericht\">Wochenbericht</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
        if (hinweis != null || vergleich == null) {
            html.append("<p class=\"meldung\">").append(text(hinweis != null ? hinweis
                    : "Der Vergleich wird berechnet, das dauert nach dem Start des Dienstes einige Minuten."))
                    .append("</p></main></body></html>");
            return html.toString();
        }
        boolean mitHaushalt = haushalt && vergleich.mitHaushalt();
        boolean mitWallbox = wallbox && vergleich.mitWallbox();
        List<Integer> jahre = vergleich.jahre();
        int jahr = vergleich.jahr();
        html.append("<p class=\"meldung\">").append(text(satz(vergleich, mitHaushalt, mitWallbox))).append("</p>")
                .append("<section class=\"kpis\">");
        if (vergleich.gesamt() != null) {
            html.append(kpi("Gesamt " + jahr + " bis " + DATUM.format(vergleich.bis()), zahl(vergleich.gesamt(), 0),
                            "kWh"))
                    .append(kpi("Gesamt " + (jahr - 1) + " gleiche Tage", zahl(vergleich.gesamtVorjahrGleich(), 0),
                            "kWh"))
                    .append(kpi("Gesamt Veränderung", prozent(vergleich.gesamtAenderung()), ""))
                    .append("</section>").append(ursachen(vergleich)).append("<section class=\"kpis\">");
        }
        html.append(kpi("Heizung " + jahr + " bis " + DATUM.format(vergleich.bis()),
                        zahl(vergleich.heizung(J - 1), 0), "kWh"))
                .append(kpi("Heizung " + (jahr - 1) + " gleiche Tage", zahl(vergleich.heizungGleich(J - 2), 0), "kWh"))
                .append(kpi("Heizung Veränderung", prozent(vergleich.aenderung()), ""));
        for (int i = J - 2; i >= 0; i--) {
            html.append(kpi("Heizung " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.heizung(i), 0), "kWh"));
        }
        if (mitHaushalt) {
            html.append(kpi("Haushalt " + jahr + " bis " + DATUM.format(vergleich.bis()),
                    zahl(vergleich.haushalt(J - 1), 0), "kWh"));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Haushalt " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.haushalt(i), 0), "kWh"));
            }
        }
        if (mitWallbox) {
            html.append(kpi("Wallbox " + jahr + " bis " + DATUM.format(vergleich.bis()),
                    zahl(vergleich.wallbox(J - 1), 0), "kWh"));
            for (int i = J - 2; i >= 0; i--) {
                html.append(kpi("Wallbox " + jahre.get(i) + " ganzes Jahr", zahl(vergleich.wallbox(i), 0), "kWh"));
            }
        }
        html.append("</section><section class=\"cards\"><article class=\"card breit\"><h2>Strom je Monat</h2>")
                .append("<p class=\"legende\">");
        for (int i = 0; i < J; i++) {
            html.append("<span><i class=\"sw j").append(i).append("\"></i>").append(jahre.get(i)).append("</span>");
        }
        if (mitHaushalt) {
            html.append("<span><i class=\"sw haushalt\"></i>Haushalt</span>");
        }
        if (mitWallbox) {
            html.append("<span><i class=\"sw wallbox\"></i>Wallbox</span>");
        }
        html.append("<span>").append(mitHaushalt || mitWallbox ? "übereinander auf der Heizung, " : "")
                .append("der laufende Monat ist heller, er ist noch nicht vorbei</span></p>");
        List<String> schalter = new ArrayList<>();
        if (vergleich.mitHaushalt()) {
            schalter.add("<a class=\"nav\" href=\"" + adresse(!mitHaushalt, mitWallbox) + "\">Haushaltsstrom "
                    + (mitHaushalt ? "ausblenden" : "einblenden") + "</a>");
        }
        if (vergleich.mitWallbox()) {
            schalter.add("<a class=\"nav\" href=\"" + adresse(mitHaushalt, !mitWallbox) + "\">Wallbox "
                    + (mitWallbox ? "ausblenden" : "einblenden") + "</a>");
        }
        if (!schalter.isEmpty()) {
            html.append("<p class=\"umschalten\">").append(String.join(" · ", schalter)).append("</p>");
        }
        html.append(diagramm(vergleich, mitHaushalt, mitWallbox))
                .append("</article><article class=\"card breit\"><h2>Die Monate</h2>")
                .append(tabelle(kopf(vergleich, mitHaushalt, mitWallbox), zeilen(vergleich, mitHaushalt, mitWallbox)))
                .append("<p class=\"note\">Heizung ist die geschätzte Wirkleistung des Verdichter-Inverters aus der ")
                .append("gespeicherten Scheinleistung, wie in der Tagesübersicht, ohne Pumpe und Regelung. Der Zähler ")
                .append("der Wärmepumpe zeigt etwa 15 % weniger. Die Änderung vergleicht ").append(jahr)
                .append(" mit ").append(jahr - 1).append(", im laufenden Monat dieselben Tage bis zur gleichen Stunde.")
                .append(vergleich.mitHaushalt() ? " Haushalt ist der Netzbezug laut Hauszähler minus Heizung und "
                        + "Wallbox; Strom aus der eigenen PV-Anlage ist nicht enthalten." : " Den Haushaltsstrom "
                        + "zeigt die Seite, wenn in der Konfiguration der Hauszähler eingetragen ist.")
                .append(vergleich.mitWallbox() ? " Wallbox ist der Zählerstand der Wallbox, nur Ladungen zu Hause."
                        : "")
                .append(vergleich.gesamt() != null ? " Gesamt ist der Netzbezug laut Hauszähler vom 1. Januar bis "
                        + "jetzt, verglichen mit dem Vorjahr bis zur gleichen Stunde." : "")
                .append(" Außentemperatur: Mittel der Tagesmittel.</p>")
                .append("</article></section><p class=\"note\">Stand ").append(ZEIT.format(vergleich.berechnet()))
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
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Anteil a : anteile) {
            boolean mitAnteil = a != gesamt && Math.abs(gesamt.differenz()) >= MIN_AENDERUNG_KWH;
            zeilen.add(List.of(a.name(), zahl(a.vorher(), 0), zahl(a.jetzt(), 0), differenz(a),
                    mitAnteil ? anteil(a.differenz() / gesamt.differenz() * 100) : "–"));
        }
        Double prozent = gesamt.prozent();
        boolean steigt = prozent != null && prozent > STEIGT_AB_PROZENT;
        return "<section class=\"cards\"><article class=\"card breit\"><h2>Woran liegt es?</h2>"
                + "<p class=\"ursache" + (steigt ? " steigt" : "") + "\">" + text(ursache(anteile, v.jahr())) + "</p>"
                + tabelle(List.of(zeitraum, (v.jahr() - 1) + " kWh", v.jahr() + " kWh", "Änderung",
                "Anteil an der Änderung"), zeilen)
                + "<p class=\"note\">Rest ist Netzbezug minus Heizung" + (v.mitWallbox() ? " und Wallbox" : "")
                + ", also der übrige Haushalt mit Warmwasser, Lüftung und Geräten.</p></article></section>";
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
                + (mehr ? "gestiegen, vor allem durch " : "gesunken, vor allem ") + teil(treiber.name(), mehr) + " ("
                + vorzeichen(treiber.differenz()) + zahl(Math.abs(treiber.differenz()), 0) + " kWh).";
    }

    private static String teil(String name, boolean mehr) {
        if (name.startsWith("Rest")) {
            return mehr ? "den übrigen Haushalt" : "beim übrigen Haushalt";
        }
        return (mehr ? "die " : "bei der ") + name;
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
    static String adresse(boolean haushalt, boolean wallbox) {
        List<String> parameter = new ArrayList<>();
        if (haushalt) {
            parameter.add("haushalt=ja");
        }
        if (wallbox) {
            parameter.add("wallbox=ja");
        }
        return "jahresvergleich" + (parameter.isEmpty() ? "" : "?" + String.join("&amp;", parameter));
    }

    /**
     * One sentence: this year up to today against the same days of the year before, with the household and the
     * wallbox if shown
     */
    static String satz(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        int jahr = v.jahr();
        Double aenderung = v.aenderung();
        String text = "";
        Double gesamt = v.gesamtAenderung();
        if (gesamt != null) {
            text = "Vom 1. Januar bis " + DATUM.format(v.bis()) + " " + jahr + " hat das Haus " + zahl(v.gesamt(), 0)
                    + " kWh aus dem Netz bezogen, " + (Math.abs(gesamt) < 0.5 ? "genauso viel wie"
                    : zahl(Math.abs(gesamt), 1) + " % " + (gesamt > 0 ? "mehr" : "weniger") + " als")
                    + " in denselben Tagen " + (jahr - 1) + " (" + zahl(v.gesamtVorjahrGleich(), 0) + " kWh). ";
        }
        text += (gesamt != null ? "Die Heizung brauchte " + zahl(v.heizung(J - 1), 0) + " kWh"
                : "Vom 1. Januar bis " + DATUM.format(v.bis()) + " " + jahr + " hat die Heizung "
                + zahl(v.heizung(J - 1), 0) + " kWh Strom gebraucht");
        if (aenderung != null) {
            text += ", " + (Math.abs(aenderung) < 0.5 ? "genauso viel wie"
                    : zahl(Math.abs(aenderung), 1) + " % " + (aenderung > 0 ? "mehr" : "weniger") + " als")
                    + " in denselben Tagen " + (jahr - 1) + " (" + zahl(v.heizungGleich(J - 2), 0) + " kWh)";
        }
        text += ". Im ganzen Jahr waren es " + zahl(v.heizung(J - 2), 0) + " kWh (" + (jahr - 1) + ") und "
                + zahl(v.heizung(J - 3), 0) + " kWh (" + (jahr - 2) + ").";
        if (mitHaushalt) {
            text += " Der Haushalt ohne Heizung und Wallbox brauchte " + jahr + " bisher " + zahl(v.haushalt(J - 1), 0)
                    + " kWh, " + (jahr - 1) + " im ganzen Jahr " + zahl(v.haushalt(J - 2), 0) + " kWh.";
        }
        if (mitWallbox) {
            text += " An der Wallbox wurden " + jahr + " bisher " + zahl(v.wallbox(J - 1), 0) + " kWh geladen, "
                    + (jahr - 1) + " im ganzen Jahr " + zahl(v.wallbox(J - 2), 0) + " kWh.";
        }
        return text;
    }

    static List<String> kopf(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        List<String> kopf = new ArrayList<>();
        kopf.add("Monat");
        v.jahre().forEach(j -> kopf.add("Heizung " + j + " kWh"));
        kopf.add("Änderung");
        if (mitHaushalt) {
            v.jahre().forEach(j -> kopf.add("Haushalt " + j + " kWh"));
        }
        if (mitWallbox) {
            v.jahre().forEach(j -> kopf.add("Wallbox " + j + " kWh"));
        }
        v.jahre().forEach(j -> kopf.add("Außen " + j + " °C"));
        return kopf;
    }

    /**
     * Rows of the table: month, heating per year, change, household and wallbox per year if shown, outdoor
     * temperature per year
     */
    static List<List<String>> zeilen(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        List<List<String>> zeilen = new ArrayList<>();
        for (Jahresvergleich.Monat m : v.monate()) {
            List<String> zeile = new ArrayList<>();
            zeile.add(m.name());
            for (int i = 0; i < J; i++) {
                zeile.add(zahl(m.heizung().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.heizung().get(i)) : ""));
            }
            zeile.add(prozent(m.aenderung()));
            if (mitHaushalt) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.haushalt().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.haushalt().get(i)) : ""));
                }
            }
            if (mitWallbox) {
                for (int i = 0; i < J; i++) {
                    zeile.add(zahl(m.wallbox().get(i), 0) + (i == J - 1 ? bisHeute(v, m, m.wallbox().get(i)) : ""));
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
     * wallbox stacked on top if shown, a tooltip per bar
     */
    static String diagramm(Jahresvergleich.Vergleich v, boolean mitHaushalt, boolean mitWallbox) {
        double max = 0;
        for (Jahresvergleich.Monat m : v.monate()) {
            for (int i = 0; i < J; i++) {
                max = Math.max(max, wert(m.heizung().get(i)) + (mitHaushalt ? wert(m.haushalt().get(i)) : 0)
                        + (mitWallbox ? wert(m.wallbox().get(i)) : 0));
            }
        }
        double schritt = schritt(max);
        double oben = Math.max(schritt, Math.ceil(max / schritt) * schritt);
        StringBuilder svg = new StringBuilder("<div class=\"diagramm\"><svg viewBox=\"0 0 " + B + " " + H
                + "\" width=\"100%\" role=\"img\" aria-label=\"Strom der Heizung" + (mitHaushalt ? ", des Haushalts" : "")
                + (mitWallbox ? ", der Wallbox" : "") + " je Monat, " + v.jahre().get(0) + " bis " + v.jahr() + "\">");
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
                Double wallbox = mitWallbox ? m.wallbox().get(i) : null;
                String titel = m.name() + " " + jahr + (laufend ? " bis " + v.bis().getDayOfMonth() + "." : "")
                        + ": Heizung " + zahl(heizung, 0) + " kWh"
                        + (haushalt == null ? "" : ", Haushalt " + zahl(haushalt, 0) + " kWh")
                        + (wallbox == null ? "" : ", Wallbox " + zahl(wallbox, 0) + " kWh")
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
                if (wallbox != null && wallbox > 0) {
                    svg.append(balken("wallbox j" + i + (laufend ? " laufend" : ""), x, balken - 2, unten,
                            unten + wallbox, oben, titel));
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
                + "rx=\"2\"><title>%s</title></rect>", klasse, x, y, breite, hoehe, text(titel));
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

    // The color of the wallbox is the page's own, light and dark like the tokens of the status page
    private static final String CSS = """
            :root { --wallbox: #6d5bd0; }
            @media (prefers-color-scheme: dark) { :root { --wallbox: #a99bf0; } }
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 14px; }
            .legende { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 4px 0 4px; color: var(--muted);
              font-size: 13px; }
            .legende span { display: inline-flex; align-items: center; gap: 6px; }
            .umschalten { margin: 0 0 8px; font-size: 14px; }
            .ursache { margin: 4px 0 8px; font-weight: 600; }
            .cards + .kpis { margin-top: 16px; }
            .ursache.steigt { color: var(--warn); }
            .sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
            .sw.j0 { background: var(--muted); opacity: 0.3; }
            .sw.j1 { background: var(--muted); opacity: 0.65; }
            .sw.j2 { background: var(--accent); }
            .sw.haushalt { background: var(--stale); }
            .sw.wallbox { background: var(--wallbox); }
            .diagramm rect.j0 { fill: var(--muted); fill-opacity: 0.3; }
            .diagramm rect.j1 { fill: var(--muted); fill-opacity: 0.65; }
            .diagramm rect.j2 { fill: var(--accent); }
            .diagramm rect.haushalt { fill: var(--stale); }
            .diagramm rect.haushalt.j0 { fill-opacity: 0.35; }
            .diagramm rect.haushalt.j1 { fill-opacity: 0.6; }
            .diagramm rect.haushalt.j2 { fill-opacity: 0.9; }
            .diagramm rect.laufend { fill-opacity: 0.5; }
            .diagramm rect.wallbox { fill: var(--wallbox); }
            .diagramm rect.wallbox.j0 { fill-opacity: 0.35; }
            .diagramm rect.wallbox.j1 { fill-opacity: 0.6; }
            .diagramm rect.wallbox.j2 { fill-opacity: 0.9; }
            .diagramm rect.haushalt.laufend, .diagramm rect.wallbox.laufend { fill-opacity: 0.45; }
            .diagramm rect:hover { stroke: var(--text); stroke-width: 1; }
            .diagramm .gitter { stroke: var(--line); stroke-width: 1; }
            .diagramm .achslinie { stroke: var(--muted); stroke-width: 1; }
            .diagramm .achse { fill: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
            """;
}
