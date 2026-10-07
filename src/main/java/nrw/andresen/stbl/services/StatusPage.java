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
    private final List<Item> kpis = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    // Last added KPI, row or pill, formula() refers to it
    private Item last;
    private boolean hasHistory;
    private boolean hasFormula;

    public StatusPage() {
        this(Clock.systemDefaultZone());
    }

    public StatusPage(Clock clock) {
        this.clock = clock;
        this.zone = clock.getZone();
    }

    private static class Card {
        final String title;
        // Rows and notes in their order
        final List<Object> content = new ArrayList<>();
        final List<Item> pills = new ArrayList<>();

        Card(String title) {
            this.title = title;
        }
    }

    /**
     * KPI, row or pill, rendered at the end so a formula can still be added
     */
    private static class Item {
        String label;
        String unit;
        int decimals;
        String series;
        ValueContainer<Double> value;
        ValueContainer<Boolean> state;
        Duration maxAge;
        String formula;
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
        return kpi(label, unit, decimals, null, value);
    }

    /**
     * KPI whose value is stored in InfluxDB as WP_series, a click shows its course
     */
    public StatusPage kpi(String label, String unit, int decimals, String series,
                          Callable<ValueContainer<Double>> value) {
        kpis.add(item(label, unit, decimals, series, call(value), MAX_AGE));
        return this;
    }

    public StatusPage card(String title) {
        cards.add(new Card(title));
        return this;
    }

    public StatusPage row(String label, String unit, int decimals, Callable<ValueContainer<Double>> value) {
        return row(label, unit, decimals, null, value, MAX_AGE);
    }

    /**
     * Row for a value which is requested less often, it is marked as outdated only after maxAge
     */
    public StatusPage row(String label, String unit, int decimals, Callable<ValueContainer<Double>> value,
                          Duration maxAge) {
        return row(label, unit, decimals, null, value, maxAge);
    }

    /**
     * Row whose value is stored in InfluxDB as WP_series, a click shows its course
     */
    public StatusPage row(String label, String unit, int decimals, String series,
                          Callable<ValueContainer<Double>> value) {
        return row(label, unit, decimals, series, value, MAX_AGE);
    }

    public StatusPage row(String label, String unit, int decimals, String series,
                          Callable<ValueContainer<Double>> value, Duration maxAge) {
        current().content.add(item(label, unit, decimals, series, call(value), maxAge));
        return this;
    }

    public StatusPage pill(String label, Callable<ValueContainer<Boolean>> value) {
        Item pill = new Item();
        pill.label = label;
        pill.state = call(value);
        current().pills.add(pill);
        last = pill;
        return this;
    }

    /**
     * Formula of the value calculated by the service, shown as tooltip of the last added KPI, row or pill and in
     * the course dialog
     */
    public StatusPage formula(String text) {
        if (last == null) {
            throw new IllegalStateException("kpi(), row() or pill() has to be called first");
        }
        last.formula = text;
        hasFormula = true;
        return this;
    }

    public StatusPage note(String text) {
        current().content.add("<p class=\"note\">" + text + "</p>");
        return this;
    }

    private Item item(String label, String unit, int decimals, String series, ValueContainer<Double> value,
                      Duration maxAge) {
        Item item = new Item();
        item.label = label;
        item.unit = unit;
        item.decimals = decimals;
        item.series = series;
        item.value = value;
        item.maxAge = maxAge;
        hasHistory |= series != null;
        last = item;
        return item;
    }

    private String renderKpi(Item kpi) {
        return "<div class=\"kpi\"" + history(kpi) + "><div class=\"label\">" + label(kpi) + "</div>"
                + "<div class=\"value\"><span class=\"num\">" + number(kpi.value, kpi.decimals)
                + unit(kpi.value, kpi.unit) + "</span></div>" + age(kpi.value, kpi.maxAge) + "</div>";
    }

    private String renderRow(Item row) {
        return "<div class=\"row\"" + history(row) + "><span class=\"label\">" + label(row) + "</span>"
                + "<span class=\"value\"><span class=\"num\">" + number(row.value, row.decimals)
                + unit(row.value, row.unit) + "</span>" + age(row.value, row.maxAge) + "</span></div>";
    }

    private String renderPill(Item pill) {
        String state = pill.state == null ? "unknown" : (pill.state.getValue() ? "on" : "off");
        String text = pill.state == null ? "–" : (pill.state.getValue() ? "an" : "aus");
        return "<span class=\"pill " + state + "\"><span class=\"dot\"></span>" + label(pill) + " <b>" + text
                + "</b></span>";
    }

    /**
     * Label with the formula as tooltip. Without course the label can be focused, so a tap shows it on a phone.
     */
    private static String label(Item item) {
        if (item.formula == null) {
            return item.label;
        }
        return "<span class=\"calc\" data-formula=\"" + attr(item.formula) + "\""
                + (item.series == null ? " tabindex=\"0\"" : "") + ">" + item.label
                + "<span class=\"info\" aria-hidden=\"true\">ⓘ</span></span>";
    }

    /**
     * Attributes which make a row or KPI clickable, empty without series
     */
    private static String history(Item item) {
        if (item.series == null) {
            return "";
        }
        return " data-series=\"" + attr(item.series) + "\" data-label=\"" + attr(item.label) + "\" data-unit=\""
                + attr(item.unit) + "\" data-decimals=\"" + item.decimals + "\""
                + (item.formula == null ? "" : " data-formula=\"" + attr(item.formula) + "\"")
                + " role=\"button\" tabindex=\"0\"";
    }

    private static String attr(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
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
                // With the course dialog the script reloads the page, but not while the dialog is open
                .append(hasHistory ? "<noscript><meta http-equiv=\"refresh\" content=\"20\"></noscript>"
                        : "<meta http-equiv=\"refresh\" content=\"20\">")
                .append("<title>Wärmepumpe</title><style>").append(CSS)
                .append(hasFormula ? FORMULA_CSS : "").append(hasHistory ? HISTORY_CSS : "")
                .append("</style></head><body><main>");

        html.append("<header><div><h1>Wärmepumpe</h1><p class=\"sub\">Stand ")
                .append(TIME.format(Instant.now(clock).atZone(zone)))
                .append(" · aktualisiert alle 20 s</p></div><div class=\"badges\">");
        headerBadges.forEach(html::append);
        html.append("</div></header>");

        html.append("<section class=\"kpis\">");
        kpis.forEach(kpi -> html.append(renderKpi(kpi)));
        html.append("</section><section class=\"cards\">");
        for (Card card : cards) {
            html.append("<article class=\"card\"><h2>").append(card.title).append("</h2>");
            if (!card.pills.isEmpty()) {
                html.append("<div class=\"pills\">");
                card.pills.forEach(pill -> html.append(renderPill(pill)));
                html.append("</div>");
            }
            for (Object content : card.content) {
                html.append(content instanceof Item row ? renderRow(row) : content);
            }
            html.append("</article>");
        }
        html.append("</section></main>");
        if (hasHistory) {
            html.append(HISTORY_DIALOG).append("<script>").append(HISTORY_SCRIPT).append("</script>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private static final String FORMULA_CSS = """
            .calc { position: relative; cursor: help; outline: none; }
            .calc .info { margin-left: 4px; font-size: 0.85em; color: var(--accent); opacity: 0.8; }
            .calc:hover::after, .calc:focus::after { content: attr(data-formula); position: absolute; left: 0;
              top: calc(100% + 4px); z-index: 10; width: max-content; max-width: min(340px, 80vw);
              white-space: normal; padding: 6px 10px; border-radius: 8px; border: 1px solid var(--line);
              background: var(--card); color: var(--text); font-size: 12px; font-weight: 400; line-height: 1.4;
              text-transform: none; letter-spacing: 0; box-shadow: 0 4px 14px rgb(0 0 0 / 0.15); }
            .pill .calc:hover::after, .pill .calc:focus::after { top: calc(100% + 8px); }
            """;

    private static final String HISTORY_DIALOG = """
            <dialog id="verlauf" aria-labelledby="v-title">
              <div class="v-head"><div><h3 id="v-title"></h3><p class="sub" id="v-sub"></p><p class="v-formula" id="v-formula" hidden></p></div>
              <button type="button" class="v-close" aria-label="Schließen">×</button></div>
              <div class="v-ranges" role="group" aria-label="Zeitbereich">
                <button type="button" data-range="6h">6 h</button><button type="button" data-range="24h">24 h</button>
                <button type="button" data-range="7d">7 Tage</button><button type="button" data-range="30d">30 Tage</button>
                <button type="button" data-range="1y">1 Jahr</button>
              </div>
              <div id="v-chart" class="v-chart"></div>
              <div id="v-stats" class="v-stats"></div>
            </dialog>""";

    private static final String HISTORY_CSS = """
            [data-series] { cursor: pointer; }
            [data-series] .num { text-decoration: underline dotted var(--muted); text-underline-offset: 4px; }
            .row[data-series]:hover .num, .kpi[data-series]:hover .num, [data-series]:focus-visible .num {
              color: var(--accent); text-decoration-color: var(--accent); }
            [data-series]:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 6px; }
            dialog#verlauf { width: min(760px, calc(100vw - 32px)); max-width: none; padding: 16px 16px 12px;
              border: 1px solid var(--line); border-radius: 14px; background: var(--card); color: var(--text); }
            dialog#verlauf::backdrop { background: rgb(0 0 0 / 0.45); }
            .v-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
            .v-head h3 { margin: 0; font-size: 18px; }
            .v-formula { margin: 4px 0 0; font-size: 12px; color: var(--text); }
            .v-formula::before { content: "Berechnung: "; color: var(--muted); }
            .v-close { border: 0; background: var(--off-bg); color: var(--text); width: 32px; height: 32px;
              border-radius: 50%; font-size: 20px; line-height: 1; cursor: pointer; flex: none; }
            .v-ranges { display: inline-flex; flex-wrap: wrap; gap: 2px; margin-top: 10px; padding: 3px;
              border-radius: 10px; background: var(--off-bg); }
            .v-ranges button { border: 0; background: none; color: var(--muted); font: inherit; font-size: 13px;
              padding: 5px 12px; border-radius: 8px; cursor: pointer; }
            .v-ranges button:hover { color: var(--text); }
            .v-ranges button[aria-pressed="true"] { background: var(--card); color: var(--text); font-weight: 600;
              box-shadow: 0 1px 3px rgb(0 0 0 / 0.15); }
            .v-ranges button:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
            .v-chart { position: relative; margin-top: 10px; min-height: 260px; }
            .v-chart svg { display: block; width: 100%; height: 260px; touch-action: none; }
            .v-chart .msg { display: grid; place-items: center; height: 260px; color: var(--muted); }
            .v-chart .grid { stroke: var(--line); stroke-width: 1; }
            .v-chart .axis { fill: var(--muted); font-size: 11px; font-variant-numeric: tabular-nums; }
            .v-chart .line { fill: none; stroke: var(--accent); stroke-width: 2; stroke-linejoin: round;
              stroke-linecap: round; }
            .v-chart .cursor { stroke: var(--muted); stroke-width: 1; }
            .v-chart .dot { fill: var(--accent); stroke: var(--card); stroke-width: 2; }
            .v-tip { position: absolute; top: 4px; pointer-events: none; background: var(--card);
              border: 1px solid var(--line); border-radius: 8px; padding: 3px 8px; font-size: 13px;
              white-space: nowrap; font-variant-numeric: tabular-nums; box-shadow: 0 2px 8px rgb(0 0 0 / 0.12); }
            .v-stats { display: flex; flex-wrap: wrap; gap: 6px 18px; padding-top: 8px; font-size: 13px;
              color: var(--muted); font-variant-numeric: tabular-nums; }
            .v-stats b { color: var(--text); font-weight: 600; }
            """;

    private static final String HISTORY_SCRIPT = """
            (() => {
              const dlg = document.getElementById('verlauf');
              const chart = document.getElementById('v-chart');
              const stats = document.getElementById('v-stats');
              const NS = 'http://www.w3.org/2000/svg', H_MS = 3600e3, D_MS = 24 * H_MS;
              // Same ranges and mean windows as StblController.HISTORY_RANGES
              const RANGES = {
                '6h':  { ms: 6 * H_MS,   text: 'Letzte 6 Stunden, Mittel über 1 min',  axis: 'hours', step: 1, narrow: 2 },
                '24h': { ms: D_MS,       text: 'Letzte 24 Stunden, Mittel über 2 min', axis: 'hours', step: 3, narrow: 6 },
                '7d':  { ms: 7 * D_MS,   text: 'Letzte 7 Tage, Mittel über 15 min',    axis: 'days', step: 1, narrow: 2 },
                '30d': { ms: 30 * D_MS,  text: 'Letzte 30 Tage, Stundenmittel',         axis: 'days', step: 5, narrow: 10 },
                '1y':  { ms: 365 * D_MS, text: 'Letztes Jahr, Mittel über 12 h',        axis: 'months', step: 1, narrow: 2 }
              };
              let current = null, range = '24h', request = 0;
              try { if (RANGES[localStorage.getItem('verlaufBereich')]) range = localStorage.getItem('verlaufBereich'); }
              catch (e) { }

              let reloadDue = false;
              setTimeout(() => { reloadDue = true; if (!dlg.open) location.reload(); }, 20000);
              dlg.addEventListener('close', () => { if (reloadDue) location.reload(); });
              dlg.querySelector('.v-close').addEventListener('click', () => dlg.close());
              dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); });
              dlg.querySelectorAll('.v-ranges button').forEach(b => b.addEventListener('click', () => {
                range = b.dataset.range;
                try { localStorage.setItem('verlaufBereich', range); } catch (e) { }
                load();
              }));

              const fmt = d => new Intl.NumberFormat('de-DE', { minimumFractionDigits: d, maximumFractionDigits: d });
              const pad = n => String(n).padStart(2, '0');
              const hhmm = d => pad(d.getHours()) + ':' + pad(d.getMinutes());
              const ddmm = d => pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + '.';
              const MONTHS = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];
              const tipTime = (ms, r) => {
                const d = new Date(ms);
                if (r.axis === 'hours') return hhmm(d) + ' Uhr';
                if (r.axis === 'days') return ddmm(d) + ' ' + hhmm(d);
                return ddmm(d) + String(d.getFullYear()).slice(2);
              };
              const el = (name, attrs, parent) => {
                const e = document.createElementNS(NS, name);
                for (const k in attrs) e.setAttribute(k, attrs[k]);
                if (parent) parent.appendChild(e);
                return e;
              };
              const message = text => { chart.innerHTML = '<div class="msg"></div>'; chart.firstChild.textContent = text; };

              function niceStep(span) {
                const raw = span / 4, mag = Math.pow(10, Math.floor(Math.log10(raw))), n = raw / mag;
                return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
              }

              // Time axis: full hours, local midnights or first days of a month
              function ticks(r, start, end, narrow) {
                const step = narrow ? r.narrow : r.step, out = [], d = new Date(start);
                if (r.axis === 'hours') {
                  d.setMinutes(0, 0, 0);
                  while (d.getTime() < start || d.getHours() % step) d.setHours(d.getHours() + 1);
                  for (; d.getTime() <= end; d.setHours(d.getHours() + step)) out.push([d.getTime(), hhmm(d)]);
                } else if (r.axis === 'days') {
                  d.setHours(0, 0, 0, 0);
                  if (d.getTime() < start) d.setDate(d.getDate() + 1);
                  // Count back from today so the newest day is always labelled
                  const days = Math.floor((new Date(end).setHours(0, 0, 0, 0) - d.getTime()) / D_MS + 0.5);
                  d.setDate(d.getDate() + days % step);
                  for (; d.getTime() <= end; d.setDate(d.getDate() + step)) out.push([d.getTime(), ddmm(d)]);
                } else {
                  d.setHours(0, 0, 0, 0); d.setDate(1);
                  if (d.getTime() < start) d.setMonth(d.getMonth() + 1);
                  for (; d.getTime() <= end; d.setMonth(d.getMonth() + step)) out.push([d.getTime(), MONTHS[d.getMonth()]]);
                }
                return out;
              }

              function draw(data, unit, decimals, r) {
                const t = data.time, v = data.value, n = t.length;
                if (!n) { message('Keine Daten in diesem Zeitraum.'); stats.textContent = ''; return; }
                const W = Math.max(chart.clientWidth, 280), H = 260, L = 52, R = 12, T = 12, B = 26;
                const end = Date.now(), start = end - r.ms;
                let lo = Math.min(...v), hi = Math.max(...v);
                if (hi - lo < 1e-9) { lo -= 1; hi += 1; }
                const step = niceStep(hi - lo);
                lo = Math.floor(lo / step) * step; hi = Math.ceil(hi / step) * step;
                const x = ms => L + (ms - start) / (end - start) * (W - L - R);
                const y = val => T + (hi - val) / (hi - lo) * (H - T - B);
                chart.innerHTML = '';
                const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img' }, chart);
                const tickDec = Math.max(0, Math.min(3, -Math.floor(Math.log10(step) + 1e-9)));
                for (let val = lo; val <= hi + step / 2; val += step) {
                  el('line', { x1: L, x2: W - R, y1: y(val), y2: y(val), class: 'grid' }, svg);
                  el('text', { x: L - 6, y: y(val) + 4, 'text-anchor': 'end', class: 'axis' }, svg)
                    .textContent = fmt(tickDec).format(val);
                }
                for (const [ms, label] of ticks(r, start, end, W < 480)) {
                  const tx = x(ms);
                  el('line', { x1: tx, x2: tx, y1: T, y2: H - B, class: 'grid' }, svg);
                  el('text', { x: tx, y: H - 8, 'text-anchor': 'middle', class: 'axis' }, svg).textContent = label;
                }
                // Gap in the line where values are missing, settings are stored only once per hour
                const gaps = [];
                for (let i = 1; i < n; i++) gaps.push(t[i] - t[i - 1]);
                gaps.sort((a, b) => a - b);
                const maxGap = Math.max(r.ms / 144, 3 * (gaps.length ? gaps[gaps.length >> 1] : 0));
                let d = '';
                for (let i = 0; i < n; i++) {
                  d += (i === 0 || t[i] - t[i - 1] > maxGap ? 'M' : 'L') + x(t[i]).toFixed(1) + ' ' + y(v[i]).toFixed(1);
                }
                el('path', { d, class: 'line' }, svg);
                const cursor = el('line', { y1: T, y2: H - B, class: 'cursor', visibility: 'hidden' }, svg);
                const dot = el('circle', { r: 4, class: 'dot', visibility: 'hidden' }, svg);
                const tip = document.createElement('div');
                tip.className = 'v-tip'; tip.hidden = true; chart.appendChild(tip);
                const u = unit ? '\\u202f' + unit : '';
                svg.addEventListener('pointermove', e => {
                  const box = svg.getBoundingClientRect(), px = (e.clientX - box.left) * W / box.width;
                  const ms = start + (px - L) / (W - L - R) * (end - start);
                  let a = 0, b = n - 1;
                  while (b - a > 1) { const m = (a + b) >> 1; if (t[m] < ms) a = m; else b = m; }
                  const i = Math.abs(t[a] - ms) <= Math.abs(t[b] - ms) ? a : b;
                  const cx = x(t[i]), cy = y(v[i]);
                  cursor.setAttribute('x1', cx); cursor.setAttribute('x2', cx); cursor.setAttribute('visibility', 'visible');
                  dot.setAttribute('cx', cx); dot.setAttribute('cy', cy); dot.setAttribute('visibility', 'visible');
                  tip.textContent = tipTime(t[i], r) + ' · ' + fmt(decimals).format(v[i]) + u;
                  tip.hidden = false;
                  const left = cx / W * box.width;
                  tip.style.left = Math.min(Math.max(left - tip.offsetWidth / 2, 0), box.width - tip.offsetWidth) + 'px';
                });
                svg.addEventListener('pointerleave', () => {
                  tip.hidden = true; cursor.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden');
                });
                const mean = v.reduce((s, a) => s + a, 0) / n;
                stats.innerHTML = '';
                [['Min', Math.min(...v)], ['Max', Math.max(...v)], ['Mittel', mean], ['Letzter Wert', v[n - 1]]]
                  .forEach(([k, val]) => {
                    const s = document.createElement('span'), b = document.createElement('b');
                    s.textContent = k + ' '; b.textContent = fmt(decimals).format(val) + u;
                    s.appendChild(b); stats.appendChild(s);
                  });
              }

              async function load() {
                const { series, unit } = current.dataset, decimals = +current.dataset.decimals, r = RANGES[range];
                const id = ++request;
                document.getElementById('v-sub').textContent = r.text;
                dlg.querySelectorAll('.v-ranges button')
                  .forEach(b => b.setAttribute('aria-pressed', String(b.dataset.range === range)));
                stats.textContent = '';
                message('Lade Verlauf …');
                try {
                  const res = await fetch('history/' + encodeURIComponent(series) + '?range=' + range,
                    { headers: { Accept: 'application/json' } });
                  if (!res.ok) throw new Error((await res.text()) || ('HTTP ' + res.status));
                  const data = await res.json();
                  // Ignore an older answer if the range was changed in the meantime
                  if (id === request) draw(data, unit, decimals, r);
                } catch (e) {
                  if (id === request) message('Verlauf nicht verfügbar: ' + e.message);
                }
              }

              function open(row) {
                current = row;
                document.getElementById('v-title').textContent = row.dataset.label;
                const formula = document.getElementById('v-formula');
                formula.textContent = row.dataset.formula || '';
                formula.hidden = !row.dataset.formula;
                if (!dlg.open) dlg.showModal();
                load();
              }

              document.querySelectorAll('[data-series]').forEach(row => {
                row.addEventListener('click', () => open(row));
                row.addEventListener('keydown', e => {
                  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(row); }
                });
              });
            })();
            """;

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
