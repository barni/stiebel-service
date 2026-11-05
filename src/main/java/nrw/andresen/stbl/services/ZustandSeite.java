package nrw.andresen.stbl.services;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML of the page with the condition of the heat pump over the heating seasons, linked with the other pages
 */
public final class ZustandSeite {

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Berlin"));

    private ZustandSeite() {
    }

    /**
     * @param ergebnis null while it is not calculated yet
     * @param hinweis  shown instead of the result, e.g. that InfluxDB is switched off; null for the result
     */
    public static String render(Zustand.Ergebnis ergebnis, String hinweis) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Zustand</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>Zustand und Alterung</h1><p class=\"sub\">")
                .append("Je Heizsaison (Juli bis Juni) bei gleicher Außentemperatur</p></div>")
                .append("<nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"jahresvergleich\">Jahresvergleich</a> · ")
                .append("<a class=\"nav\" href=\"wochenbericht\">Wochenbericht</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
        if (hinweis != null || ergebnis == null) {
            html.append("<p class=\"meldung\">").append(text(hinweis != null ? hinweis
                    : "Der Zustand wird berechnet, das dauert nach dem Start des Dienstes etwa 10 Minuten."))
                    .append("</p></main></body></html>");
            return html.toString();
        }
        html.append("<p class=\"meldung ").append(klasse(gesamt(ergebnis.pruefungen()))).append("\">")
                .append(text(satz(ergebnis.pruefungen()))).append("</p>")
                .append("<section class=\"cards\"><article class=\"card breit\"><h2>Prüfungen</h2>")
                .append(pruefungen(ergebnis.pruefungen()))
                .append("<p class=\"note\">Verglichen wird die neueste Saison mit genug Tagen (mindestens ")
                .append(Zustand.MIN_TAGE).append(" im Temperaturbereich) mit dem Mittel der Saisons davor. ")
                .append("Eine laufende Saison ist noch unvollständig, ihre Werte ändern sich bis zum Sommer.</p>")
                .append("</article><article class=\"card breit\"><h2>Je Heizsaison</h2>")
                .append(tabelle(List.of("Saison", "Heiztage", "Arbeitszahl", "AZ 0–5 °C (Tage)", "AZ 5–10 °C (Tage)",
                        "Starts/Tag 0–5 °C", "h/Start 0–5 °C", "Abtauungen/Tag 0–5 °C", "Fehlstarts je 1000",
                        "Druck Winter bar"), zeilen(ergebnis.saisons())))
                .append("<p class=\"note\">Arbeitszahl = Wärme ÷ geschätzte Wirkleistung des Verdichter-Inverters ")
                .append("(ohne Pumpe und Regelung), über alle Tage unter 15 °C bzw. die Tage im Temperaturbereich. ")
                .append("Ein Start zählt nach mindestens 3 Minuten Stillstand. Fehlstarts je 1000 Starts an milden ")
                .append("Tagen (0 bis 15 °C) aus der Auswertung der Fehlstarts. „–“: zu wenige Tage oder keine Daten. ")
                .append("Starts und Laufzeit hängen von den Einstellungen ab; im Herbst 2026 wurden Wärmebedarf, ")
                .append("Mindestlaufzeit und Betriebsart geändert.</p>")
                .append("</article></section><p class=\"note\">Stand ").append(ZEIT.format(ergebnis.berechnet()))
                .append(", alle 6 Stunden neu berechnet.")
                .append(ergebnis.unvollstaendig() ? " Eine Saison fehlt noch, weil die InfluxDB zu langsam "
                        + "antwortete; sie wird in 30 Minuten erneut versucht." : "")
                .append("</p></main></body></html>");
        return html.toString();
    }

    /**
     * Worst status of the checks
     */
    static String gesamt(List<Zustand.Pruefung> pruefungen) {
        for (String status : List.of("auffällig", "beobachten", "unauffällig")) {
            if (pruefungen.stream().anyMatch(p -> p.status().equals(status))) {
                return status;
            }
        }
        return "zu wenige Daten";
    }

    /**
     * One sentence: nothing found, or which checks to watch or are conspicuous
     */
    static String satz(List<Zustand.Pruefung> pruefungen) {
        List<String> auffaellig = pruefungen.stream().filter(p -> p.status().equals("auffällig"))
                .map(Zustand.Pruefung::name).toList();
        List<String> beobachten = pruefungen.stream().filter(p -> p.status().equals("beobachten"))
                .map(Zustand.Pruefung::name).toList();
        if (auffaellig.isEmpty() && beobachten.isEmpty()) {
            return pruefungen.stream().anyMatch(p -> p.status().equals("unauffällig"))
                    ? "Keine Anzeichen von Alterung oder Problemen: alle Werte wie in den Saisons davor."
                    : "Noch zu wenige Daten für einen Vergleich.";
        }
        List<String> teile = new ArrayList<>();
        if (!auffaellig.isEmpty()) {
            teile.add("Auffällig: " + String.join(", ", auffaellig));
        }
        if (!beobachten.isEmpty()) {
            teile.add("beobachten: " + String.join(", ", beobachten));
        }
        String text = String.join("; ", teile) + ".";
        return text.substring(0, 1).toUpperCase(Locale.GERMANY) + text.substring(1);
    }

    private static String pruefungen(List<Zustand.Pruefung> pruefungen) {
        StringBuilder html = new StringBuilder("<div class=\"tbl\"><table><thead><tr><th>Wert</th><th>Saison</th>"
                + "<th>Wert</th><th>Mittel davor</th><th>Status</th></tr></thead><tbody>");
        for (Zustand.Pruefung p : pruefungen) {
            String einheit = p.einheit().isEmpty() ? "" : " " + p.einheit();
            html.append("<tr><td><span class=\"pname\">").append(text(p.name())).append("</span><span class=\"phint\">")
                    .append(text(p.hinweis())).append("</span></td><td>").append(text(p.saison() == null ? "–"
                            : p.saison())).append("</td><td>").append(zahl(p.wert(), p.stellen()))
                    .append(p.wert() == null ? "" : text(einheit)).append("</td><td>")
                    .append(zahl(p.vorher(), p.stellen())).append(p.vorher() == null ? "" : text(einheit))
                    .append("</td><td><span class=\"status ").append(klasse(p.status())).append("\">")
                    .append(text(p.status())).append("</span></td></tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    /**
     * Rows of the table of the seasons, the newest first
     */
    static List<List<String>> zeilen(List<Zustand.Saison> saisons) {
        List<List<String>> zeilen = new ArrayList<>();
        for (Zustand.Saison s : saisons.reversed()) {
            zeilen.add(List.of(s.name() + (s.laufend() ? " (laufend)" : ""), String.valueOf(s.heiztage()),
                    zahl(s.arbeitszahl(), 2), mitTagen(s.kalt().arbeitszahl(), s.kalt().tage()),
                    mitTagen(s.mild().arbeitszahl(), s.mild().tage()), zahl(s.kalt().startsProTag(), 1),
                    zahl(s.kalt().laufzeitProStart(), 2), zahl(s.kalt().abtauungenProTag(), 1),
                    zahl(s.fehlstartsJe1000(), 1), zahl(s.druckWinter(), 2)));
        }
        return zeilen;
    }

    private static String mitTagen(Double wert, int tage) {
        return zahl(wert, 2) + " (" + tage + ")";
    }

    private static String klasse(String status) {
        return switch (status) {
            case "auffällig" -> "rot";
            case "beobachten" -> "gelb";
            case "unauffällig" -> "gruen";
            default -> "grau";
        };
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

    private static String text(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String CSS = """
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .meldung.rot { background: var(--warn-bg); color: var(--warn); }
            .meldung.gelb { border-color: var(--stale); color: var(--stale); }
            .meldung.gruen { background: var(--on-bg); color: var(--on); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 14px; }
            .pname { display: block; font-weight: 600; }
            .phint { display: block; color: var(--muted); font-size: 12px; max-width: 46ch; white-space: normal; }
            .status { display: inline-block; padding: 2px 10px; border-radius: 999px; font-weight: 600; font-size: 13px;
              background: var(--off-bg); color: var(--muted); white-space: nowrap; }
            .status.rot { background: var(--warn-bg); color: var(--warn); }
            .status.gelb { color: var(--stale); background: color-mix(in srgb, var(--stale) 15%, transparent); }
            .status.gruen { background: var(--on-bg); color: var(--on); }
            .cards + .cards { margin-top: 16px; }
            """;
}
