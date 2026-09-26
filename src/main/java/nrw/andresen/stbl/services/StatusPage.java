package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.ValueContainer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * HTML status page, values are grouped in cards
 */
public class StatusPage {

    // Values older than this are marked as outdated
    private static final Duration MAX_AGE = Duration.ofSeconds(150);
    private static final Locale GERMAN = Locale.GERMANY;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm:ss", GERMAN);
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN);

    private final Clock clock;
    private final ZoneId zone;
    private final List<String> headerBadges = new ArrayList<>();
    private final List<String> kpis = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();

    public StatusPage() {
        this(Clock.systemDefaultZone());
    }

    public StatusPage(Clock clock) {
        this.clock = clock;
        this.zone = clock.getZone();
    }

    private static class Card {
        final String title;
        final StringBuilder content = new StringBuilder();
        final StringBuilder pills = new StringBuilder();

        Card(String title) {
            this.title = title;
        }
    }

    private Card current() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("card() has to be called first");
        }
        return cards.get(cards.size() - 1);
    }

    /**
     * Badge in the header, e.g. the compressor state
     */
    public StatusPage badge(String onText, String offText, Callable<ValueContainer<Boolean>> value) {
        ValueContainer<Boolean> container = call(value);
        if (container == null) {
            headerBadges.add("<span class=\"badge unknown\">" + offText + " unbekannt</span>");
        } else {
            boolean on = container.getValue();
            headerBadges.add("<span class=\"badge " + (on ? "on" : "off") + "\"><span class=\"dot\"></span>"
                    + (on ? onText : offText) + "</span>");
        }
        return this;
    }

    public StatusPage kpi(String label, String unit, int decimals, Callable<ValueContainer<Double>> value) {
        ValueContainer<Double> container = call(value);
        kpis.add("<div class=\"kpi\"><div class=\"label\">" + label + "</div>"
                + "<div class=\"value\">" + number(container, decimals)
                + unit(container, unit) + "</div>"
                + age(container, MAX_AGE) + "</div>");
        return this;
    }

    public StatusPage card(String title) {
        cards.add(new Card(title));
        return this;
    }

    public StatusPage row(String label, String unit, int decimals, Callable<ValueContainer<Double>> value) {
        return row(label, unit, decimals, value, MAX_AGE);
    }

    /**
     * Row for a value which is requested less often, it is marked as outdated only after maxAge
     */
    public StatusPage row(String label, String unit, int decimals, Callable<ValueContainer<Double>> value,
                          Duration maxAge) {
        ValueContainer<Double> container = call(value);
        current().content.append("<div class=\"row\"><span class=\"label\">").append(label).append("</span>")
                .append("<span class=\"value\">").append(number(container, decimals))
                .append(unit(container, unit))
                .append(age(container, maxAge)).append("</span></div>");
        return this;
    }

    public StatusPage pill(String label, Callable<ValueContainer<Boolean>> value) {
        ValueContainer<Boolean> container = call(value);
        String state = container == null ? "unknown" : (container.getValue() ? "on" : "off");
        String text = container == null ? "–" : (container.getValue() ? "an" : "aus");
        current().pills.append("<span class=\"pill ").append(state).append("\"><span class=\"dot\"></span>")
                .append(label).append(" <b>").append(text).append("</b></span>");
        return this;
    }

    public StatusPage note(String text) {
        current().content.append("<p class=\"note\">").append(text).append("</p>");
        return this;
    }

    private static <T> T call(Callable<T> value) {
        try {
            return value.call();
        } catch (Exception e) {
            return null;
        }
    }

    private String number(ValueContainer<Double> container, int decimals) {
        if (container == null) {
            return "<span class=\"missing\">keine Daten</span>";
        }
        DecimalFormat format = new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals),
                DecimalFormatSymbols.getInstance(GERMAN));
        return format.format(container.getValue());
    }

    private static String unit(ValueContainer<?> container, String unit) {
        return container == null || unit.isEmpty() ? "" : "<span class=\"unit\">" + unit + "</span>";
    }

    private String age(ValueContainer<?> container, Duration maxAge) {
        if (container == null) {
            return "";
        }
        Duration age = Duration.between(container.getTimestamp(), clock.instant());
        if (age.compareTo(maxAge) <= 0) {
            return "";
        }
        return "<span class=\"stale\" title=\"Zuletzt empfangen " + TIME.format(container.getTimestamp().atZone(zone))
                + "\">seit " + SHORT_TIME.format(container.getTimestamp().atZone(zone)) + "</span>";
    }

    public String render() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta http-equiv=\"refresh\" content=\"20\">")
                .append("<title>Wärmepumpe</title><style>").append(CSS).append("</style></head><body><main>");

        html.append("<header><div><h1>Wärmepumpe</h1><p class=\"sub\">Stand ")
                .append(TIME.format(Instant.now(clock).atZone(zone)))
                .append(" · aktualisiert alle 20 s</p></div><div class=\"badges\">");
        headerBadges.forEach(html::append);
        html.append("</div></header>");

        html.append("<section class=\"kpis\">");
        kpis.forEach(html::append);
        html.append("</section><section class=\"cards\">");
        for (Card card : cards) {
            html.append("<article class=\"card\"><h2>").append(card.title).append("</h2>");
            if (card.pills.length() > 0) {
                html.append("<div class=\"pills\">").append(card.pills).append("</div>");
            }
            html.append(card.content).append("</article>");
        }
        html.append("</section></main></body></html>");
        return html.toString();
    }

    private static final String CSS = """
            :root {
              --bg: #f4f5f2; --card: #ffffff; --text: #1d2321; --muted: #69726e; --line: #e4e7e2;
              --accent: #0f766e; --on: #15803d; --on-bg: #dcfce7; --off-bg: #eceeea; --stale: #b45309;
              color-scheme: light;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #111514; --card: #1a201e; --text: #e6ebe8; --muted: #93a09a; --line: #2a3330;
                --accent: #5eead4; --on: #4ade80; --on-bg: #14532d; --off-bg: #252d2a; --stale: #fbbf24;
                color-scheme: dark;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text);
              font: 15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
            main { max-width: 1120px; margin: 0 auto; padding: 28px 16px 40px; }
            header { display: flex; flex-wrap: wrap; gap: 12px 24px; align-items: flex-end;
              justify-content: space-between; margin-bottom: 20px; }
            h1 { margin: 0; font-size: 26px; letter-spacing: -0.01em; }
            .sub { margin: 2px 0 0; color: var(--muted); font-size: 13px; }
            .badges { display: flex; flex-wrap: wrap; gap: 8px; }
            .badge { display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: 999px;
              font-weight: 600; font-size: 14px; background: var(--off-bg); color: var(--muted); }
            .badge.on { background: var(--on-bg); color: var(--on); }
            .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; flex: none; }
            .badge.on .dot { box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 25%, transparent); }
            .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(150px, 100%), 1fr)); gap: 12px;
              margin-bottom: 16px; }
            .kpi { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 14px 16px; }
            .kpi .label { color: var(--muted); font-size: 13px; }
            .kpi .value { font-size: 28px; font-weight: 650; letter-spacing: -0.02em;
              font-variant-numeric: tabular-nums; margin-top: 2px; }
            .kpi .unit { font-size: 15px; font-weight: 500; color: var(--muted); margin-left: 4px; }
            .kpi .missing { font-size: 15px; }
            .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr)); gap: 12px; }
            .card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 14px 16px 8px; }
            h2 { margin: 0 0 6px; font-size: 13px; text-transform: uppercase; letter-spacing: 0.06em;
              color: var(--accent); }
            .row { display: flex; justify-content: space-between; gap: 12px; padding: 7px 0;
              border-top: 1px solid var(--line); }
            .row:first-of-type { border-top: 0; }
            .row .label { color: var(--muted); }
            .row .value { font-weight: 600; font-variant-numeric: tabular-nums; text-align: right; }
            .row .unit { font-weight: 400; color: var(--muted); margin-left: 4px; }
            .missing { color: var(--muted); font-weight: 400; font-style: italic; }
            .stale { display: block; font-size: 12px; font-weight: 500; color: var(--stale); }
            .pills { display: flex; flex-wrap: wrap; gap: 6px; padding: 4px 0 10px; }
            .pill { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 999px;
              font-size: 13px; background: var(--off-bg); color: var(--muted); }
            .pill b { font-weight: 600; }
            .pill.on { background: var(--on-bg); color: var(--on); }
            .note { margin: 4px 0 8px; font-size: 12px; color: var(--muted); }
            """;
}
