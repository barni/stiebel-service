package nrw.andresen.stbl.services;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frame shared by all pages: the bar with the tabs and the four signals (operation, warnings, condition, service)
 * that show the most important state on every page
 */
public final class Rahmen {

    /**
     * One signal of the strip below the tabs
     *
     * @param art  ok, achtung, warn, aktiv (heat pump runs) or neutral; decides the color
     * @param ziel page the signal leads to, relative to the base
     */
    public record Signal(String name, String wert, String art, String zeichen, String ziel) {
    }

    record Reiter(String text, String ziel) {
    }

    static final List<Reiter> REITER = List.of(new Reiter("Übersicht", "status"), new Reiter("Anlage", "anlage"),
            new Reiter("Woche", "wochenbericht"), new Reiter("Jahr", "jahresvergleich"),
            new Reiter("Zustand", "zustand"), new Reiter("CAN-Bus", "canbus"),
            new Reiter("Einstellungen", "konfiguration"));
    // These pages show stored values and are left out without InfluxDB
    static final Set<String> NUR_MIT_INFLUX = Set.of("wochenbericht", "jahresvergleich", "zustand");
    private static final Pattern HEADER = Pattern.compile("<header>(.*?)</header>", Pattern.DOTALL);
    private static final Pattern NAV = Pattern.compile("<nav>(.*?)</nav>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<a [^>]*href=\"([^\"]*)\"[^>]*>.*?</a>", Pattern.DOTALL);

    private Rahmen() {
    }

    /**
     * Bar with the tabs, the time and the signals
     *
     * @param aktiv     target of the current page, e.g. "status"
     * @param basis     prefix of the links, "" or "../" for pages one level deeper
     * @param stand     time shown at the right, e.g. "14:59"
     */
    public static String kopf(String aktiv, String basis, boolean mitInflux, List<Signal> signale, String stand) {
        StringBuilder html = new StringBuilder("<header class=\"kopf\"><a class=\"marke\" href=\"").append(basis)
                .append("status\"><svg width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" "
                        + "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" "
                        + "aria-hidden=\"true\"><path d=\"M3 21h18\"/><path d=\"M5 21V9l7-5 7 5v12\"/>"
                        + "<path d=\"M9 21v-6h6v6\"/></svg><span>Wärmepumpe</span></a>"
                        + "<nav class=\"reiter\" aria-label=\"Seiten\">");
        for (Reiter reiter : REITER) {
            if (!mitInflux && NUR_MIT_INFLUX.contains(reiter.ziel())) {
                continue;
            }
            html.append("<a href=\"").append(basis).append(reiter.ziel()).append("\"")
                    .append(reiter.ziel().equals(aktiv) ? " aria-current=\"page\"" : "").append(">")
                    .append(text(reiter.text())).append("</a>");
        }
        html.append("</nav><span class=\"live\"><span class=\"punkt\"></span>Stand ").append(text(stand))
                .append("</span></header><script src=\"/js/diagramm.js\" defer></script>");
        if (!signale.isEmpty()) {
            html.append("<section class=\"signale\" aria-label=\"Signale\">");
            for (Signal s : signale) {
                html.append("<a class=\"signal ").append(s.art()).append("\" href=\"").append(basis).append(s.ziel())
                        .append("\"><span class=\"zeichen\" aria-hidden=\"true\">").append(text(s.zeichen()))
                        .append("</span><span class=\"s-text\"><span class=\"s-name\">").append(text(s.name()))
                        .append("</span><span class=\"s-wert\">").append(text(s.wert())).append("</span></span></a>");
            }
            html.append("</section>");
        }
        return html.toString();
    }

    /**
     * Replaces the old header of a page (title, subtitle, links) by the frame and keeps title and subtitle
     */
    public static String einsetzen(String seite, String kopf) {
        Matcher m = HEADER.matcher(seite);
        if (!m.find()) {
            return seite;
        }
        Matcher nav = NAV.matcher(m.group(1));
        String titel = m.group(1);
        StringBuilder eigene = new StringBuilder();
        if (nav.find()) {
            titel = titel.substring(0, nav.start()) + titel.substring(nav.end());
            // Links to other pages are tabs now; links of the page itself, e.g. to the bus scan, stay
            Matcher link = LINK.matcher(nav.group(1));
            while (link.find()) {
                String ziel = link.group(1).replace("../", "").replaceAll("[#?].*", "");
                if (REITER.stream().noneMatch(r -> r.ziel().equals(ziel))) {
                    eigene.append(eigene.isEmpty() ? "" : " · ").append(link.group());
                }
            }
        }
        return seite.substring(0, m.start()) + kopf + "<div class=\"seitentitel\">" + titel
                + (eigene.isEmpty() ? "" : "<nav class=\"seitenlinks\">" + eigene + "</nav>") + "</div>"
                + seite.substring(m.end());
    }

    static String text(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    static final String CSS = """
            .kopf { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 24px; background: var(--card);
              border: 1px solid var(--line); border-radius: 16px; padding: 8px 12px 8px 16px; margin-bottom: 14px; }
            .marke { display: inline-flex; align-items: center; gap: 8px; font-weight: 700; font-size: 17px;
              color: var(--text); text-decoration: none; }
            .marke svg { color: var(--accent); }
            .reiter { display: flex; gap: 2px; flex: 1 1 auto; overflow-x: auto; scrollbar-width: none; }
            .reiter::-webkit-scrollbar { display: none; }
            .reiter a { padding: 9px 13px; border-radius: 10px; color: var(--muted); font-weight: 500; font-size: 14px;
              text-decoration: none; white-space: nowrap; }
            .reiter a:hover, .reiter a:focus-visible { background: var(--off-bg); color: var(--text); }
            .reiter a[aria-current="page"] { background: color-mix(in srgb, var(--accent) 14%, transparent);
              color: var(--accent); font-weight: 650; }
            .live { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; color: var(--muted);
              font-variant-numeric: tabular-nums; white-space: nowrap; }
            .live .punkt { width: 8px; height: 8px; border-radius: 50%; background: var(--on); }
            .signale { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 10px;
              margin-bottom: 18px; }
            .signal { display: flex; align-items: center; gap: 12px; padding: 10px 14px; background: var(--card);
              border: 1px solid var(--line); border-radius: 14px; color: var(--text); text-decoration: none;
              min-height: 44px; }
            .signal:hover, .signal:focus-visible { border-color: var(--accent); }
            .signal .zeichen { flex: none; width: 32px; height: 32px; border-radius: 9px; display: flex;
              align-items: center; justify-content: center; font-weight: 700; font-size: 14px;
              background: var(--off-bg); color: var(--muted); }
            .signal.ok .zeichen { background: var(--on-bg); color: var(--on); }
            .signal.aktiv .zeichen { background: color-mix(in srgb, var(--accent) 16%, transparent);
              color: var(--accent); }
            .signal.achtung .zeichen { background: color-mix(in srgb, var(--stale) 16%, transparent);
              color: var(--stale); }
            .signal.warn .zeichen { background: var(--warn-bg); color: var(--warn); }
            .signal.warn { border-color: var(--warn); }
            .s-text { display: flex; flex-direction: column; min-width: 0; }
            .s-name { font-size: 12px; color: var(--muted); font-weight: 500; }
            .s-wert { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .seitentitel { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-end;
              gap: 8px 24px; margin: 4px 0 16px; }
            .seitentitel h1 { font-size: 24px; }
            .seitenlinks { font-size: 14px; }
            .seitenlinks a { color: var(--accent); font-weight: 600; text-decoration: none; }
            .dtip { position: fixed; z-index: 30; pointer-events: none; background: var(--card); color: var(--text);
              border: 1px solid var(--line); border-radius: 10px; padding: 8px 10px; font-size: 13px;
              box-shadow: 0 4px 16px rgb(0 0 0 / 0.14); font-variant-numeric: tabular-nums; max-width: 280px; }
            .dtip div { display: flex; justify-content: space-between; gap: 14px; }
            .dtip .dtip-zeit { color: var(--muted); font-size: 12px; margin-bottom: 2px; }
            .dlinie { position: fixed; z-index: 29; width: 1px; pointer-events: none;
              background: color-mix(in srgb, var(--text) 45%, transparent); }
            [data-wert] { cursor: default; }
            .wert-art { display: inline-block; padding: 2px 10px; border-radius: 999px; font-weight: 600; font-size: 13px;
              background: var(--off-bg); color: var(--muted); }
            .wert-art.ok { background: var(--on-bg); color: var(--on); }
            .wert-art.warn { background: var(--warn-bg); color: var(--warn); }
            .wert-art.achtung { background: color-mix(in srgb, var(--stale) 16%, transparent); color: var(--stale); }
            """;
}
