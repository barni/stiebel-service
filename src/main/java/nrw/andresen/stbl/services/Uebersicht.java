package nrw.andresen.stbl.services;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blocks of the overview page: what the heat pump does right now, tiles with the course of the last 24 hours and
 * a timeline of the runs, the electricity and the temperatures. Pure functions from data to HTML and SVG.
 */
public final class Uebersicht {

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY);

    /**
     * Bar under the state, e.g. the margin until the next defrost
     *
     * @param anteil 0 to 1
     * @param art    aktiv, achtung or neutral, decides the color
     */
    public record Balken(String links, String mitte, String rechts, double anteil, String art, String hinweis) {
    }

    /**
     * Number of today next to the state, e.g. run time
     */
    public record Zahl(String name, String wert, String einheit, String vergleich) {
    }

    /**
     * State of the heat pump right now
     *
     * @param art   aktiv (runs), abtauen or neutral
     * @param zeile the line next to the state, e.g. "seit 42 min · Strom ≈ 0,89 kW"
     */
    public record Jetzt(String zustand, String art, String zeile, String text, Balken balken, List<Zahl> heute) {
    }

    /**
     * Tile with a value and its course
     *
     * @param serie name of the stored value for the dialog with the course, null without dialog
     */
    public record Kachel(String name, String wert, String einheit, String sub, long[] zeit, double[] werte,
                         double min, double max, String linie, String serie, int stellen) {
    }

    /**
     * Line of the temperature chart
     *
     * @param klasse vorlauf, ruecklauf or aussen, decides color and dashes
     */
    public record Linie(String name, String klasse, long[] zeit, double[] wert) {
    }

    /**
     * Timeline of the last 24 hours, times in epoch milliseconds
     *
     * @param stromZeit end of each window of the electricity
     * @param abtauungen times of the defrosts
     */
    public record Zeitleiste(long start, long ende, long[] stromZeit, double[] stromW, List<Long> abtauungen,
                             List<Linie> linien, String zusammenfassung) {
    }

    private Uebersicht() {
    }

    /**
     * Series for the values under the pointer (diagramm.js)
     */
    public record Reihe(String name, String einheit, int stellen, long[] zeit, double[] wert) {
    }

    /**
     * JSON for data-verlauf, NaN as null
     */
    static String verlaufJson(long von, long bis, List<Reihe> reihen) {
        StringBuilder json = new StringBuilder("{\"von\":").append(von).append(",\"bis\":").append(bis)
                .append(",\"reihen\":[");
        for (int r = 0; r < reihen.size(); r++) {
            Reihe reihe = reihen.get(r);
            json.append(r == 0 ? "" : ",").append("{\"n\":\"").append(reihe.name().replace("\"", "")).append("\",\"e\":\"")
                    .append(reihe.einheit().replace("\"", "")).append("\",\"s\":").append(reihe.stellen()).append(",\"t\":[");
            for (int i = 0; i < reihe.zeit().length; i++) {
                json.append(i == 0 ? "" : ",").append(reihe.zeit()[i]);
            }
            json.append("],\"w\":[");
            for (int i = 0; i < reihe.wert().length; i++) {
                double w = reihe.wert()[i];
                json.append(i == 0 ? "" : ",").append(Double.isNaN(w) ? "null"
                        : String.format(Locale.ROOT, "%." + reihe.stellen() + "f", w));
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    public static String jetzt(Jetzt j) {
        StringBuilder html = new StringBuilder("<section class=\"jetzt ").append(j.art())
                .append("\" aria-label=\"Zustand der Wärmepumpe\"><div class=\"jetzt-links\">")
                .append("<span class=\"jetzt-label\">Jetzt</span><div class=\"jetzt-kopf\"><span class=\"jetzt-zustand\">")
                .append(t(j.zustand())).append("</span><span class=\"jetzt-zeile\">").append(t(j.zeile()))
                .append("</span></div>");
        if (j.text() != null) {
            html.append("<p class=\"jetzt-text\">").append(t(j.text())).append("</p>");
        }
        Balken b = j.balken();
        if (b != null) {
            double anteil = Math.max(0, Math.min(1, b.anteil()));
            html.append("<div class=\"balken ").append(b.art()).append("\"><div class=\"balken-skala\"><span>")
                    .append(t(b.links())).append("</span><span>").append(t(b.mitte())).append("</span><span>")
                    .append(t(b.rechts())).append("</span></div><div class=\"balken-bahn\"><div class=\"balken-wert\" "
                            + "style=\"width:").append(String.format(Locale.ROOT, "%.1f", anteil * 100))
                    .append("%\"></div></div>");
            if (b.hinweis() != null) {
                html.append("<span class=\"balken-hinweis\">").append(t(b.hinweis())).append("</span>");
            }
            html.append("</div>");
        }
        html.append("</div><div class=\"jetzt-heute\" aria-label=\"Heute\">");
        for (Zahl z : j.heute()) {
            html.append("<div class=\"zahl\"><span class=\"zahl-name\">").append(t(z.name()))
                    .append("</span><span class=\"zahl-wert\">").append(t(z.wert()))
                    .append(z.einheit().isEmpty() ? "" : "<span class=\"unit\">" + t(z.einheit()) + "</span>")
                    .append("</span><span class=\"zahl-vergleich\">").append(t(z.vergleich())).append("</span></div>");
        }
        return html.append("</div></section>").toString();
    }

    public static String kacheln(List<Kachel> kacheln) {
        StringBuilder html = new StringBuilder("<section class=\"kacheln\" aria-label=\"Messwerte\">");
        for (Kachel k : kacheln) {
            html.append("<div class=\"kachel").append(k.serie() != null ? " klickbar" : "").append("\"");
            if (k.serie() != null) {
                html.append(" data-series=\"").append(t(k.serie())).append("\" data-label=\"").append(t(k.name()))
                        .append("\" data-unit=\"").append(t(k.einheit())).append("\" data-decimals=\"")
                        .append(k.stellen()).append("\" tabindex=\"0\" role=\"button\" "
                                + "aria-label=\"Verlauf von ").append(t(k.name())).append(" öffnen\"");
            }
            html.append("><span class=\"kachel-name\">").append(t(k.name())).append("</span><span class=\"kachel-wert\">")
                    .append(t(k.wert())).append(k.einheit().isEmpty() ? "" : "<span class=\"unit\">" + t(k.einheit())
                            + "</span>").append("</span><span class=\"kachel-sub\">").append(t(k.sub())).append("</span>");
            String punkte = punkte(k.zeit(), k.werte(), k.min(), k.max());
            if (!punkte.isEmpty()) {
                html.append("<svg class=\"spark\" viewBox=\"0 0 100 30\" preserveAspectRatio=\"none\" aria-hidden=\"true\" "
                        + "data-verlauf=\"").append(t(verlaufJson(k.zeit()[0], k.zeit()[k.zeit().length - 1],
                                List.of(new Reihe(k.name().replace(" jetzt", ""), k.einheit(), k.stellen(), k.zeit(),
                                        k.werte()))))).append("\"><polyline class=\"").append(k.linie()).append("\" points=\"").append(punkte)
                        .append("\"/></svg><span class=\"kachel-fuss\">letzte 24 h</span>");
            }
            html.append("</div>");
        }
        return html.append("</section>").toString();
    }

    /**
     * Points of a sparkline in a box of 100 × 30, the time from the first to the last value
     */
    static String punkte(long[] zeit, double[] werte, double min, double max) {
        if (zeit == null || zeit.length < 2 || max <= min) {
            return "";
        }
        long von = zeit[0];
        long bis = zeit[zeit.length - 1];
        if (bis <= von) {
            return "";
        }
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < zeit.length; i++) {
            if (Double.isNaN(werte[i])) {
                continue;
            }
            double x = (zeit[i] - von) * 100.0 / (bis - von);
            double y = 28 - (Math.max(min, Math.min(max, werte[i])) - min) / (max - min) * 26;
            p.append(p.isEmpty() ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
        }
        return p.toString();
    }

    public static String zeitleiste(Zeitleiste z, ZoneId zone) {
        double spanne = z.ende() - z.start();
        StringBuilder html = new StringBuilder("<section class=\"card zeitleiste\" aria-label=\"Letzte 24 Stunden\">"
                + "<div class=\"zl-kopf\"><h2>Letzte 24 Stunden</h2><div class=\"zl-legende\">"
                + "<span><i class=\"lg strom\"></i>Strom ≈ (Wirkleistung des Verdichters)</span>"
                + "<span><i class=\"lg abtau\"></i>Abtauung</span>");
        for (Linie l : z.linien()) {
            html.append("<span><i class=\"lg ").append(l.klasse()).append("\"></i>").append(t(l.name())).append("</span>");
        }
        List<Reihe> reihen = new ArrayList<>();
        reihen.add(new Reihe("Strom ≈", "W", 0, z.stromZeit(), z.stromW()));
        for (Linie l : z.linien()) {
            reihen.add(new Reihe(l.name(), "°C", 1, l.zeit(), l.wert()));
        }
        html.append("</div></div><div class=\"zl-raster\" data-verlauf=\"")
                .append(t(verlaufJson(z.start(), z.ende(), reihen))).append("\"><span class=\"zl-achse-name\">Strom kW ≈</span>"
                + "<div class=\"zl-strom\" data-plot><svg viewBox=\"0 0 1000 60\" preserveAspectRatio=\"none\" aria-hidden=\"true\">"
                + "<line x1=\"0\" x2=\"1000\" y1=\"31\" y2=\"31\"/><line x1=\"0\" x2=\"1000\" y1=\"4\" y2=\"4\"/>");
        if (z.stromW().length > 0) {
            StringBuilder flaeche = new StringBuilder("0,58");
            for (int i = 0; i < z.stromW().length; i++) {
                double x = (z.stromZeit()[i] - z.start()) * 1000 / spanne;
                double y = 58 - Math.min(1100, Math.max(0, z.stromW()[i])) / 1000 * 54;
                flaeche.append(String.format(Locale.ROOT, " %.1f,%.1f", Math.max(0, Math.min(1000, x)), y));
            }
            flaeche.append(" 1000,58");
            html.append("<polygon points=\"").append(flaeche).append("\"/>");
        }
        html.append("</svg><span class=\"zl-mark\" style=\"top:0\">1 kW</span><span class=\"zl-mark\" "
                + "style=\"top:44%\">0,5</span>");
        for (long a : z.abtauungen()) {
            html.append("<span class=\"zl-abtau\" data-wert=\"Abtauung ").append(UHR.format(Instant.ofEpochMilli(a).atZone(zone)))
                    .append("\" style=\"left:").append(String.format(Locale.ROOT, "%.2f", (a - z.start()) * 100 / spanne))
                    .append("%\"></span>");
        }
        html.append("</div>");
        // Water and outdoor temperature each with their own scale: on one axis from 0 to 40 degC the outdoor
        // temperature, a few K over the day, was a straight line
        List<Linie> wasser = z.linien().stream().filter(l -> !l.klasse().equals("aussen")).toList();
        List<Linie> aussen = z.linien().stream().filter(l -> l.klasse().equals("aussen")).toList();
        temperaturBand(html, "Wasser °C", "zl-temp", wasser, 10, z.start(), spanne);
        temperaturBand(html, "Außen °C", "zl-aussen", aussen, 4, z.start(), spanne);
        html.append("</div><span></span><div class=\"zl-zeit\">");
        ZonedDateTime erste = Instant.ofEpochMilli(z.start()).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        while (erste.getHour() % 3 != 0) {
            erste = erste.plusHours(1);
        }
        // Hour marks too close to "jetzt" at the right edge are left out, they would overlap it
        for (ZonedDateTime s = erste; s.toInstant().toEpochMilli() < z.ende() - spanne * 0.08; s = s.plusHours(3)) {
            html.append("<span style=\"left:").append(String.format(Locale.ROOT, "%.2f",
                    (s.toInstant().toEpochMilli() - z.start()) * 100 / spanne)).append("%\">")
                    .append(s.getHour()).append(" Uhr</span>");
        }
        html.append("<span style=\"left:100%\">jetzt</span></div></div>");
        if (z.zusammenfassung() != null) {
            html.append("<p class=\"zl-satz\">").append(t(z.zusammenfassung())).append("</p>");
        }
        return html.append("</section>").toString();
    }

    /**
     * One band of the timeline with the lines on their own scale: from the lowest to the highest value, at least
     * minSpanne K, on whole steps of the grid
     */
    static void temperaturBand(StringBuilder html, String name, String klasse, List<Linie> linien, double minSpanne,
                               long start, double spanne) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Linie l : linien) {
            for (double w : l.wert()) {
                if (!Double.isNaN(w)) {
                    min = Math.min(min, w);
                    max = Math.max(max, w);
                }
            }
        }
        if (min > max) {
            return;
        }
        double[] skala = skala(min, max, minSpanne);
        double lo = skala[0];
        double hi = skala[1];
        double schritt = skala[2];
        html.append("<span class=\"zl-achse-name\">").append(t(name)).append("</span><div class=\"").append(klasse)
                .append("\" data-plot><svg viewBox=\"0 0 1000 100\" preserveAspectRatio=\"none\" aria-hidden=\"true\">");
        List<Double> marken = new ArrayList<>();
        for (double g = Math.ceil(lo / schritt) * schritt; g < hi - 1e-9; g += schritt) {
            marken.add(g);
            String y = String.format(Locale.ROOT, "%.1f", y(g, lo, hi));
            html.append("<line class=\"gitter\" x1=\"0\" x2=\"1000\" y1=\"").append(y).append("\" y2=\"").append(y)
                    .append("\"/>");
        }
        for (Linie l : linien) {
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < l.zeit().length; i++) {
                if (Double.isNaN(l.wert()[i])) {
                    continue;
                }
                p.append(p.isEmpty() ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f",
                        (l.zeit()[i] - start) * 1000 / spanne, y(l.wert()[i], lo, hi)));
            }
            html.append("<polyline class=\"").append(l.klasse()).append("\" points=\"").append(p).append("\"/>");
        }
        html.append("</svg>");
        for (double g : marken) {
            // A label at the edge would be cut off, the grid line is enough there
            if (y(g, lo, hi) < 12 || y(g, lo, hi) > 86) {
                continue;
            }
            html.append("<span class=\"zl-mark\" style=\"top:").append(String.format(Locale.ROOT, "%.1f",
                    y(g, lo, hi) - 7)).append("%\">").append(String.format(Locale.GERMANY, "%.0f", g)).append("</span>");
        }
        html.append("</div>");
    }

    /**
     * Lower and upper end and step of the grid for values from min to max, at least minSpanne wide
     */
    static double[] skala(double min, double max, double minSpanne) {
        double mitte = (min + max) / 2;
        double breite = Math.max(max - min, minSpanne);
        // A little room above and below the lines; the grid on whole steps inside, at most three lines
        double lo = mitte - breite * 0.6;
        double hi = mitte + breite * 0.6;
        double schritt = 1;
        for (double s : new double[]{1, 2, 5, 10, 20}) {
            schritt = s;
            if ((hi - lo) / s <= 3.5) {
                break;
            }
        }
        return new double[]{lo, hi, schritt};
    }

    private static double y(double wert, double lo, double hi) {
        return 96 - (Math.max(lo, Math.min(hi, wert)) - lo) / (hi - lo) * 92;
    }

    /**
     * Runs of the compressor from the electricity: windows above the threshold one after another
     *
     * @return start and end (end of the last window) of each run
     */
    static List<long[]> laeufe(long[] zeit, double[] watt, double schwelle, long fenster) {
        List<long[]> laeufe = new ArrayList<>();
        long[] lauf = null;
        for (int i = 0; i < zeit.length; i++) {
            if (watt[i] > schwelle) {
                if (lauf == null) {
                    lauf = new long[]{zeit[i] - fenster, zeit[i]};
                    laeufe.add(lauf);
                } else {
                    lauf[1] = zeit[i];
                }
            } else {
                lauf = null;
            }
        }
        return laeufe;
    }

    /**
     * Sentence under the timeline, e.g. "9 Läufe, zusammen 15,2 h · Strom ≈ 9,1 kWh · längster Lauf 00:00–04:10
     * (4:10 h) · 1 Abtauung"
     */
    static String zusammenfassung(List<long[]> laeufe, double kWh, int abtauungen, ZoneId zone) {
        if (laeufe.isEmpty()) {
            return "Kein Lauf in den letzten 24 Stunden" + (abtauungen > 0 ? " · " + abtauungen(abtauungen) : "");
        }
        long summe = 0;
        long[] laengster = laeufe.get(0);
        for (long[] l : laeufe) {
            summe += l[1] - l[0];
            if (l[1] - l[0] > laengster[1] - laengster[0]) {
                laengster = l;
            }
        }
        long min = (laengster[1] - laengster[0]) / 60000;
        return laeufe.size() + (laeufe.size() == 1 ? " Lauf" : " Läufe") + ", zusammen "
                + String.format(Locale.GERMANY, "%.1f", summe / 3_600_000.0) + " h · Strom ≈ "
                + String.format(Locale.GERMANY, "%.1f", kWh) + " kWh · längster Lauf "
                + UHR.format(Instant.ofEpochMilli(laengster[0]).atZone(zone)) + "–"
                + UHR.format(Instant.ofEpochMilli(laengster[1]).atZone(zone)) + " (" + min / 60 + ":"
                + String.format("%02d", min % 60) + " h) · " + abtauungen(abtauungen) + " · aus 10-Minuten-Mitteln";
    }

    private static String abtauungen(int n) {
        return n == 0 ? "keine Abtauung" : n == 1 ? "1 Abtauung" : n + " Abtauungen";
    }

    private static String t(String text) {
        return Rahmen.text(text);
    }

    static final String CSS = """
            :root { --vorlauf: #c2410c; --ruecklauf: #2563eb; --strom: #0f766e; --abtau: #c2650c; --aussen: #a21caf; }
            @media (prefers-color-scheme: dark) {
              :root { --vorlauf: #fb923c; --ruecklauf: #60a5fa; --strom: #2dd4bf; --abtau: #fbbf24; --aussen: #e879f9; }
            }
            .jetzt { display: flex; flex-wrap: wrap; gap: 20px 40px; background: var(--card); border: 1px solid var(--line);
              border-radius: 18px; padding: 22px 24px; margin-bottom: 16px; }
            .jetzt-links { flex: 999 1 480px; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
            .jetzt-label { font-size: 12px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase;
              color: var(--accent); }
            .jetzt-kopf { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 14px; }
            .jetzt-zustand { font-size: 38px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; }
            .jetzt.aktiv .jetzt-zustand { color: var(--accent); }
            .jetzt.abtauen .jetzt-zustand { color: var(--abtau); }
            .jetzt-zeile { font-size: 17px; color: var(--muted); font-variant-numeric: tabular-nums; }
            .jetzt-text { margin: 0; color: var(--muted); max-width: 70ch; }
            .balken { display: flex; flex-direction: column; gap: 6px; max-width: 560px; }
            .balken-skala { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--muted);
              font-variant-numeric: tabular-nums; }
            .balken-bahn { height: 10px; border-radius: 999px; background: var(--off-bg); overflow: hidden; }
            .balken-wert { height: 100%; border-radius: 999px; background: var(--accent); }
            .balken.achtung .balken-wert { background: var(--abtau); }
            .balken.neutral .balken-wert { background: var(--muted); }
            .balken-hinweis { font-size: 13px; color: var(--muted); }
            .jetzt-heute { flex: 1 1 300px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px;
              align-content: start; }
            .zahl { background: var(--bg); border-radius: 12px; padding: 10px 12px; display: flex; flex-direction: column; }
            .zahl-name { font-size: 12px; color: var(--muted); }
            .zahl-wert { font-size: 21px; font-weight: 700; font-variant-numeric: tabular-nums; }
            .zahl-wert .unit, .kachel-wert .unit { margin-left: 4px; font-size: 14px; font-weight: 500;
              color: var(--muted); }
            .zahl-vergleich { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
            .kacheln { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 12px;
              margin-bottom: 16px; }
            .kachel { display: flex; flex-direction: column; gap: 2px; background: var(--card); border: 1px solid var(--line);
              border-radius: 16px; padding: 14px 16px 10px; }
            .kachel.klickbar { cursor: pointer; }
            .kachel.klickbar:hover, .kachel.klickbar:focus-visible { border-color: var(--accent); }
            .kachel-name { font-size: 13px; color: var(--muted); }
            .kachel-wert { font-size: 30px; font-weight: 700; letter-spacing: -0.01em; font-variant-numeric: tabular-nums; }
            .kachel-sub, .kachel-fuss { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
            .spark { width: 100%; height: 40px; margin-top: 6px; }
            .spark polyline, .zl-temp polyline, .zl-aussen polyline { fill: none; stroke-width: 2; vector-effect: non-scaling-stroke;
              stroke-linejoin: round; }
            polyline.vorlauf { stroke: var(--vorlauf); }
            polyline.ruecklauf { stroke: var(--ruecklauf); }
            polyline.aussen { stroke: var(--aussen); }
            .spark polyline.aussen { stroke: var(--accent); }
            polyline.strom { stroke: var(--strom); }
            polyline.druck, polyline.drehzahl { stroke: var(--accent); }
            .zeitleiste { margin-bottom: 16px; padding: 16px 20px 14px; }
            .zl-kopf { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 6px 20px;
              margin-bottom: 12px; }
            .zl-kopf h2 { margin: 0; }
            .zl-legende { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 12px; color: var(--muted); }
            .zl-legende span { display: inline-flex; align-items: center; gap: 6px; }
            .lg { display: inline-block; width: 16px; height: 2px; background: var(--muted); }
            .lg.strom { height: 10px; width: 14px; border-radius: 3px; background: var(--strom); }
            .lg.abtau { width: 9px; height: 9px; transform: rotate(45deg); background: var(--abtau); }
            .lg.vorlauf { background: var(--vorlauf); }
            .lg.ruecklauf { background: var(--ruecklauf); }
            .lg.aussen { background: var(--aussen); }
            .zl-raster { display: grid; grid-template-columns: 74px minmax(0, 1fr); gap: 8px 10px; align-items: center; }
            .zl-achse-name { font-size: 12px; color: var(--muted); }
            .zl-strom, .zl-temp, .zl-aussen { position: relative; border-radius: 8px; background: var(--bg); overflow: hidden; }
            .zl-strom { height: 84px; }
            .zl-temp { height: 130px; }
            .zl-aussen { height: 70px; }
            .zl-strom svg, .zl-temp svg, .zl-aussen svg { position: absolute; inset: 0; width: 100%; height: 100%; }
            .zl-strom line, .zl-temp line, .zl-aussen line { stroke: var(--line); stroke-width: 1; vector-effect: non-scaling-stroke; }
            .zl-strom polygon { fill: var(--strom); fill-opacity: 0.85; }
            .zl-mark { position: absolute; left: 6px; font-size: 10px; color: var(--muted); font-variant-numeric: tabular-nums; }
            .zl-abtau { position: absolute; top: 4px; width: 12px; height: 12px; margin-left: -6px; background: var(--abtau);
              transform: rotate(45deg); border: 2px solid var(--card); }
            .zl-zeit { position: relative; height: 16px; font-size: 11px; color: var(--muted); }
            .zl-zeit span { position: absolute; transform: translateX(-50%); white-space: nowrap; }
            .zl-zeit span:last-child { transform: translateX(-100%); }
            .zl-satz { margin: 12px 0 0; font-size: 13px; color: var(--muted); font-variant-numeric: tabular-nums; }
            @media (max-width: 600px) {
              .jetzt { padding: 18px; }
              .jetzt-zustand { font-size: 30px; }
              .zl-raster { grid-template-columns: 1fr; }
              .zl-achse-name { display: none; }
            }
            """;
}
