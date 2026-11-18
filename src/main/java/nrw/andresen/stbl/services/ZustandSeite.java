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
                .append("<section class=\"pkarten\" aria-label=\"Prüfungen\">")
                .append(pruefungen(ergebnis.pruefungen()))
                .append("</section><p class=\"note pnote\">Verglichen wird die neueste Saison mit genug Tagen (mindestens ")
                .append(Zustand.MIN_TAGE).append(" im Temperaturbereich) mit dem Mittel der bis zu ")
                .append(Zustand.VERGLEICH_SAISONS).append(" Saisons davor. Die Werte mit „Bezug“ werden zusätzlich ")
                .append("mit dem Mittel der ersten ").append(Zustand.BEZUG_SAISONS).append(" Saisons verglichen, ")
                .append("das nicht mitwandert: So fällt auch eine langsame Verschlechterung über Jahre auf, die ")
                .append("gegen die letzten Saisons immer nur klein aussieht; es gilt die schlechtere Bewertung. ")
                .append("Wechselt eine Prüfung auf „beobachten“, kommt eine Mail, bei „auffällig“ auch eine ")
                .append("Push-Nachricht; geprüft wird alle 6 Stunden, eine Besserung wird nicht gemeldet. ")
                .append("Eine laufende Saison ist noch ")
                .append("unvollständig, ihre Werte ändern sich bis zum Sommer. Die Druckschwankung braucht ")
                .append("mindestens ").append(Zustand.MIN_MONATE_DRUCK).append(" Wintermonate mit genug Tagen. ")
                .append("Im Verlauf jeder Karte steht ein Punkt je Saison, die gestrichelte Linie ist der Bezug.</p>")
                .append("<section class=\"cards\"><article class=\"card breit\"><h2>Je Heizsaison</h2>")
                .append(tabelle(List.of("Saison", "Heiztage", "Arbeitszahl", "AZ 0–5 °C (Tage)", "AZ 5–10 °C (Tage)",
                        "Starts/Tag 0–5 °C", "h/Start 0–5 °C", "Abtauungen/Tag 0–5 °C", "Fehlstarts je 1000 mild",
                        "Druck Winter bar", "Verdampfer K", "Verflüssiger K", "Spreizung K",
                        "Druckschwankung bar/10 K"), zeilen(ergebnis.saisons())))
                .append("<p class=\"note\">Arbeitszahl = Wärme ÷ geschätzte Wirkleistung des Verdichter-Inverters ")
                .append("(ohne Pumpe und Regelung), über alle Tage unter 15 °C bzw. die Tage im Temperaturbereich. ")
                .append("Ein Start zählt nach mindestens 3 Minuten Stillstand. Fehlstarts je 1000 Starts an milden ")
                .append("Tagen (0 bis 15 °C) aus der Auswertung der Fehlstarts. „–“: zu wenige Tage oder keine Daten. ")
                .append("Starts und Laufzeit hängen von den Einstellungen ab; im Herbst 2026 wurden Wärmebedarf, ")
                .append("Mindestlaufzeit und Betriebsart geändert. Verdampfer, Verflüssiger und Spreizung: Mediane ")
                .append("der 10-Minuten-Mittel von November bis März im ruhigen Lauf, Verdampfungs- und ")
                .append("Kondensationstemperatur aus Nieder- und Hochdruck für R410A. Druckschwankung: je Wintermonat ")
                .append("die Steigung der Tagesmittel des Drucks über die der Wassertemperatur, umgerechnet auf 1,8 bar ")
                .append("und 30 °C, Median der Monate.</p>")
                .append("</article><article class=\"card breit\"><h2>Nachfüllungen</h2>")
                .append(ergebnis.nachfuellungen().isEmpty() ? "<p class=\"note\">Keine Nachfüllung erkannt.</p>"
                        : tabelle(List.of("Datum", "Anstieg bar", "Verlust seit der davor bar/Jahr"),
                        nachfuellungen(ergebnis.nachfuellungen())))
                .append("<p class=\"note\">Erkannt am Heizungsdruck: Das Stundenmittel steigt um mindestens ")
                .append(zahl(Zustand.NACHFUELLEN_SPRUNG, 2)).append(" bar und die 24 Stunden danach liegen ")
                .append("mindestens ").append(zahl(Zustand.NACHFUELLEN_DAUERHAFT, 2))
                .append(" bar über den 24 Stunden davor.</p>")
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

    /**
     * One card per check, the conspicuous ones first
     */
    static String pruefungen(List<Zustand.Pruefung> pruefungen) {
        List<Zustand.Pruefung> sortiert = new ArrayList<>(pruefungen);
        sortiert.sort(java.util.Comparator.comparingInt((Zustand.Pruefung p) -> -Zustand.stufe(p.status())));
        StringBuilder html = new StringBuilder();
        for (Zustand.Pruefung p : sortiert) {
            String einheit = p.einheit().isEmpty() ? "" : " " + p.einheit();
            html.append("<article class=\"pkarte ").append(klasse(p.status())).append("\"><div class=\"pk-kopf\">")
                    .append("<span class=\"pname\">").append(text(p.name())).append("</span><span class=\"status ")
                    .append(klasse(p.status())).append("\">").append(text(p.status())).append("</span></div>")
                    .append("<div class=\"pk-wert\">").append(zahl(p.wert(), p.stellen()))
                    .append(p.wert() == null ? "" : "<span class=\"unit\">" + text(einheit) + "</span>")
                    .append("<span class=\"pk-saison\">").append(text(p.saison() == null ? "" : p.saison()))
                    .append("</span></div><div class=\"pk-vergleich\">Mittel davor ")
                    .append(zahl(p.vorher(), p.stellen())).append(p.vorher() == null ? "" : text(einheit))
                    .append(p.bezug() == null ? "" : " · Bezug " + zahl(p.bezug(), p.stellen()) + text(einheit))
                    .append("</div>").append(verlauf(p.verlauf(), p.bezug()))
                    .append(p.regel() == null ? "" : "<p class=\"pregel\">Meldung: " + text(p.regel()) + "</p>")
                    .append("<details class=\"mehr\"><summary>Was bedeutet das?</summary><p class=\"phint\">")
                    .append(text(p.hinweis())).append("</p></details></article>");
        }
        return html.toString();
    }

    /**
     * Small chart of a check over the seasons: a point per season, the newest bigger, the reference dashed
     */
    static String verlauf(Zustand.Verlauf v, Double bezug) {
        List<Double> werte = v.werte();
        long anzahl = werte.stream().filter(java.util.Objects::nonNull).count();
        if (anzahl < 2) {
            return "";
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Double w : werte) {
            if (w != null) {
                min = Math.min(min, w);
                max = Math.max(max, w);
            }
        }
        if (bezug != null) {
            min = Math.min(min, bezug);
            max = Math.max(max, bezug);
        }
        double rand = max > min ? (max - min) * 0.15 : Math.max(Math.abs(max) * 0.1, 0.1);
        min -= rand;
        max += rand;
        int n = werte.size();
        StringBuilder linie = new StringBuilder();
        StringBuilder punkte = new StringBuilder();
        int letzter = -1;
        for (int i = 0; i < n; i++) {
            if (werte.get(i) != null) {
                letzter = i;
            }
        }
        for (int i = 0; i < n; i++) {
            Double w = werte.get(i);
            if (w == null) {
                continue;
            }
            double x = n == 1 ? 150 : 8 + i * 284.0 / (n - 1);
            double y = 50 - (w - min) / (max - min) * 44;
            String xy = String.format(Locale.ROOT, "%.1f,%.1f", x, y);
            linie.append(linie.isEmpty() ? "" : " ").append(xy);
            punkte.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%s\"%s><title>%s: %s"
                            + "</title></circle>", x, y, i == letzter ? "4.5" : "3", i == letzter ? " class=\"neu\"" : "",
                    text(v.namen().get(i)), String.format(Locale.GERMANY, "%.3f", w)));
        }
        StringBuilder svg = new StringBuilder("<svg class=\"pk-verlauf\" viewBox=\"0 0 300 56\" "
                + "preserveAspectRatio=\"none\" role=\"img\" aria-label=\"Verlauf über ").append(n)
                .append(" Saisons\">");
        if (bezug != null) {
            double y = 50 - (bezug - min) / (max - min) * 44;
            svg.append(String.format(Locale.ROOT, "<line class=\"bezug\" x1=\"0\" x2=\"300\" y1=\"%.1f\" "
                    + "y2=\"%.1f\"/>", y, y));
        }
        svg.append("<polyline points=\"").append(linie).append("\"/>").append(punkte).append("</svg>");
        return svg + "<div class=\"pk-achse\"><span>" + text(v.namen().get(0)) + "</span><span>"
                + text(v.namen().get(n - 1)) + "</span></div>";
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
                    zahl(s.fehlstartsJe1000(), 1), zahl(s.druckWinter(), 2),
                    zahl(s.betrieb().verdampferAbstand(), 1), zahl(s.betrieb().verfluessigerAbstand(), 2),
                    zahl(s.betrieb().spreizung(), 2), zahl(s.betrieb().druckschwankung(), 3)));
        }
        return zeilen;
    }

    /**
     * Rows of the table of the refills, the newest first
     */
    static List<List<String>> nachfuellungen(List<Zustand.Nachfuellung> nachfuellungen) {
        List<List<String>> zeilen = new ArrayList<>();
        for (Zustand.Nachfuellung n : nachfuellungen.reversed()) {
            zeilen.add(List.of(Zustand.datum(n.zeit()), zahl(n.bar(), 2), zahl(n.barProJahr(), 2)));
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
            .phint { display: block; color: var(--muted); font-size: 13px; margin: 4px 0 6px; }
            .pregel { font-size: 12px; margin: 8px 0 0; color: var(--text); }
            .pkarten { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(300px, 100%), 1fr)); gap: 12px;
              margin-bottom: 8px; }
            .pkarte { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 14px 16px 8px;
              display: flex; flex-direction: column; }
            .pkarte.rot { border-color: var(--warn); }
            .pkarte.gelb { border-color: var(--stale); }
            .pk-kopf { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
            .pk-kopf .pname { font-weight: 650; }
            .pk-wert { font-size: 26px; font-weight: 700; font-variant-numeric: tabular-nums; margin-top: 6px; }
            .pk-wert .unit { margin-left: 4px; font-size: 14px; font-weight: 500; color: var(--muted); }
            .pk-saison { margin-left: 10px; font-size: 13px; font-weight: 500; color: var(--muted); }
            .pk-vergleich { font-size: 13px; color: var(--muted); font-variant-numeric: tabular-nums; }
            .pk-verlauf { width: 100%; height: 56px; margin-top: 8px; overflow: visible; }
            .pk-verlauf polyline { fill: none; stroke: var(--accent); stroke-width: 2; vector-effect: non-scaling-stroke; }
            .pk-verlauf circle { fill: var(--card); stroke: var(--accent); stroke-width: 1.5; vector-effect: non-scaling-stroke; }
            .pk-verlauf circle.neu { fill: var(--accent); }
            .pkarte.gelb .pk-verlauf circle.neu { fill: var(--stale); stroke: var(--stale); }
            .pkarte.rot .pk-verlauf circle.neu { fill: var(--warn); stroke: var(--warn); }
            .pk-verlauf .bezug { stroke: var(--muted); stroke-dasharray: 4 3; stroke-width: 1; vector-effect: non-scaling-stroke; }
            .pk-achse { display: flex; justify-content: space-between; font-size: 11px; color: var(--muted); }
            .pnote { margin: 8px 0 18px; }
            .status { display: inline-block; padding: 2px 10px; border-radius: 999px; font-weight: 600; font-size: 13px;
              background: var(--off-bg); color: var(--muted); white-space: nowrap; }
            .status.rot { background: var(--warn-bg); color: var(--warn); }
            .status.gelb { color: var(--stale); background: color-mix(in srgb, var(--stale) 15%, transparent); }
            .status.gruen { background: var(--on-bg); color: var(--on); }
            .cards + .cards { margin-top: 16px; }
            """;
}
