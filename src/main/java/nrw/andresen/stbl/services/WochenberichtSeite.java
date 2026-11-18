package nrw.andresen.stbl.services;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * HTML of the page with the weekly report, linked with the status page and the configuration page
 */
public final class WochenberichtSeite {

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Berlin"));

    private WochenberichtSeite() {
    }

    /**
     * @param bericht null while it is not calculated yet
     * @param hinweis shown instead of the report, e.g. that InfluxDB is switched off; null for the report
     */
    public static String render(Wochenbericht.Bericht bericht, String hinweis) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Wochenbericht</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>Wochenbericht</h1><p class=\"sub\">")
                .append(bericht == null ? "Die letzten 7 vollen Tage" : "Letzte 7 Tage: " + Wochenbericht.zeitraum(bericht.woche()))
                .append("</p></div><nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"jahresvergleich\">Jahresvergleich</a> · ")
                .append("<a class=\"nav\" href=\"zustand\">Zustand</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
        if (hinweis != null || bericht == null) {
            html.append("<p class=\"meldung\">").append(text(hinweis != null ? hinweis
                    : "Der Bericht wird berechnet, das dauert nach dem Start des Dienstes einige Minuten."))
                    .append("</p></main></body></html>");
            return html.toString();
        }
        html.append("<p class=\"meldung\">").append(text(Wochenbericht.satz(bericht))).append("</p>")
                .append("<section class=\"cards\"><article class=\"card breit\"><h2>Wärme und Strom je Tag</h2>")
                .append(diagramm(bericht.woche().tage())).append("</article>")
                .append("<article class=\"card breit\"><h2>Vergleich</h2>")
                .append(tabelle(List.of("", "Letzte 7 Tage\n" + Wochenbericht.zeitraumKurz(bericht.woche()),
                        "7 Tage davor\n" + Wochenbericht.zeitraumKurz(bericht.vorwoche()), "Üblich"),
                        Wochenbericht.zeilen(bericht)))
                .append("<p class=\"note\">Üblich: für jeden der 7 Tage das Mittel der früheren Tage, deren Tagesmittel der ")
                .append("Außentemperatur höchstens ").append(Wochenbericht.zahl(Tagesuebersicht.AEHNLICH_K, 1))
                .append(" K abweicht, aufsummiert. Die übliche Arbeitszahl stammt nur aus den Tagen mit Stromwert. Strom ist die geschätzte Wirkleistung des Verdichters ohne ")
                .append("Pumpe und Regelung, die Arbeitszahl daraus liegt deshalb etwas zu hoch. Ein Start zählt ")
                .append("nach mindestens ").append(Fehlstarts.MIN_STILLSTAND_START.toMinutes())
                .append(" Minuten Stillstand, also ohne die Wiederanläufe nach einer Abtauung. Heizungsdruck: ")
                .append("Tagesmittel des ersten und des letzten Tages.</p></article>")
                .append("<article class=\"card breit\"><h2>Die Tage</h2>")
                .append(tabelle(List.of("Tag", "Außen °C", "Wärme kWh", "Strom kWh", "Starts", "h je Start",
                        "Abtauungen"), Wochenbericht.tageszeilen(bericht.woche())))
                .append("</article></section><p class=\"note\">Stand ").append(zeit(bericht.berechnet()))
                .append(", stündlich neu berechnet. Per Mail kommt der Bericht sonntags um 20 Uhr, abschaltbar in ")
                .append("der Konfiguration.</p></main></body></html>");
        return html.toString();
    }

    private static String tabelle(List<String> kopf, List<List<String>> zeilen) {
        StringBuilder html = new StringBuilder("<div class=\"tbl\"><table><thead><tr>");
        kopf.forEach(k -> html.append("<th>").append(text(k).replace("\n", "<br>")).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (List<String> zeile : zeilen) {
            html.append("<tr>");
            zeile.forEach(z -> html.append("<td>").append(text(z)).append("</td>"));
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    private static String zeit(Instant zeit) {
        return ZEIT.format(zeit);
    }

    private static String text(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String CSS = """
            .wochendiagramm { width: 100%; height: auto; max-height: 300px; display: block; }
            .wd-gitter { stroke: var(--line); stroke-width: 1; }
            .wd-waerme { fill: color-mix(in srgb, var(--accent) 45%, var(--card)); }
            .wd-strom { fill: var(--accent); }
            .wd-achse { fill: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
            .wd-tag { fill: var(--text); font-size: 13px; font-weight: 600; }
            .wd-wert { fill: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
            .wd-legende { display: flex; gap: 18px; font-size: 12px; color: var(--muted); margin-bottom: 4px; }
            .wd-legende span { display: inline-flex; align-items: center; gap: 6px; }
            .wd-l { display: inline-block; width: 12px; height: 10px; border-radius: 3px; }
            .wd-l.waerme { background: color-mix(in srgb, var(--accent) 45%, var(--card)); }
            .wd-l.strom { background: var(--accent); }
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 14px; }
            """;

    private static final String[] WOCHENTAG = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};

    /**
     * Bars of heat and electricity per day on one axis in kWh, the outdoor temperature as label under each day
     */
    static String diagramm(List<Tagesuebersicht.Tag> tage) {
        if (tage.isEmpty()) {
            return "";
        }
        double max = 1;
        for (Tagesuebersicht.Tag t : tage) {
            max = Math.max(max, Math.max(t.waermeKWh(), t.stromKWh()));
        }
        max = Math.ceil(max / 20) * 20;
        int n = tage.size();
        double breite = 700.0 / n;
        StringBuilder svg = new StringBuilder("<div class=\"wd-legende\"><span><i class=\"wd-l waerme\"></i>Wärme kWh"
                + "</span><span><i class=\"wd-l strom\"></i>Strom kWh ≈</span></div><svg class=\"wochendiagramm\" "
                + "viewBox=\"0 0 760 230\" role=\"img\" aria-label=\"Wärme und Strom der letzten 7 Tage\">");
        for (int g = 0; g <= 2; g++) {
            double wert = max * g / 2;
            double y = 180 - wert / max * 160;
            svg.append(String.format(java.util.Locale.ROOT, "<line class=\"wd-gitter\" x1=\"50\" x2=\"760\" "
                    + "y1=\"%.1f\" y2=\"%.1f\"/><text class=\"wd-achse\" x=\"42\" y=\"%.1f\" text-anchor=\"end\">"
                    + "%s</text>", y, y, y + 4, Wochenbericht.zahl(wert, 0)));
        }
        for (int i = 0; i < n; i++) {
            Tagesuebersicht.Tag t = tage.get(i);
            double mitte = 50 + breite * i + breite / 2;
            double hw = t.waermeKWh() / max * 160;
            double hs = t.stromKWh() / max * 160;
            svg.append(String.format(java.util.Locale.ROOT, "<rect class=\"wd-waerme\" x=\"%.1f\" y=\"%.1f\" "
                            + "width=\"26\" height=\"%.1f\" rx=\"4\"><title>%s</title></rect>", mitte - 28,
                    180 - hw, Math.max(0, hw), text(Wochenbericht.zahl(t.waermeKWh(), 1) + " kWh Wärme")));
            svg.append(String.format(java.util.Locale.ROOT, "<rect class=\"wd-strom\" x=\"%.1f\" y=\"%.1f\" "
                            + "width=\"26\" height=\"%.1f\" rx=\"4\"><title>%s</title></rect>", mitte + 2,
                    180 - hs, Math.max(0, hs), text(Wochenbericht.zahl(t.stromKWh(), 1) + " kWh Strom")));
            svg.append(String.format(java.util.Locale.ROOT, "<text class=\"wd-wert\" x=\"%.1f\" y=\"%.1f\" "
                    + "text-anchor=\"middle\">%s</text>", mitte - 15, 174 - hw, Wochenbericht.zahl(t.waermeKWh(), 0)));
            svg.append(String.format(java.util.Locale.ROOT, "<text class=\"wd-tag\" x=\"%.1f\" y=\"200\" "
                            + "text-anchor=\"middle\">%s %s</text><text class=\"wd-achse\" x=\"%.1f\" y=\"218\" "
                            + "text-anchor=\"middle\">%s</text>", mitte, WOCHENTAG[t.datum().getDayOfWeek().getValue() - 1],
                    t.datum().format(DateTimeFormatter.ofPattern("dd.MM.")), mitte,
                    t.aussentemp() == null ? "" : text(Wochenbericht.zahl(t.aussentemp(), 1) + " °C")));
        }
        return svg.append("</svg>").toString();
    }
}
