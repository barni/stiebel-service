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
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
        if (hinweis != null || bericht == null) {
            html.append("<p class=\"meldung\">").append(text(hinweis != null ? hinweis
                    : "Der Bericht wird berechnet, das dauert nach dem Start des Dienstes einige Minuten."))
                    .append("</p></main></body></html>");
            return html.toString();
        }
        html.append("<p class=\"meldung\">").append(text(Wochenbericht.satz(bericht))).append("</p>")
                .append("<section class=\"cards\"><article class=\"card breit\"><h2>Vergleich</h2>")
                .append(tabelle(List.of("", "Letzte 7 Tage\n" + Wochenbericht.zeitraumKurz(bericht.woche()),
                        "7 Tage davor\n" + Wochenbericht.zeitraumKurz(bericht.vorwoche()), "Üblich"),
                        Wochenbericht.zeilen(bericht)))
                .append("<p class=\"note\">Üblich: für jeden der 7 Tage das Mittel der früheren Tage, deren Tagesmittel der ")
                .append("Außentemperatur höchstens ").append(Wochenbericht.zahl(Tagesuebersicht.AEHNLICH_K, 1))
                .append(" K abweicht, aufsummiert. Strom ist die geschätzte Wirkleistung des Verdichters ohne ")
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
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 14px; }
            """;
}
