package nrw.andresen.stbl.services;

import java.util.Map;

/**
 * HTML of the configuration page: one form with the settings in groups, the value of the file in the input and the
 * value of the running service next to it if they differ
 */
public final class KonfigurationSeite {

    private KonfigurationSeite() {
    }

    /**
     * @param stand       values of the file and of the running service
     * @param eingabe     values typed into the form, shown again after an error; empty for the values of the file
     * @param fehler      message per name of a setting
     * @param meldung     result of the last request: GESPEICHERT, NEUGESTARTET, UNVERAENDERT or null
     * @param csrfName    name of the hidden form field against requests from other sites, null without protection
     */
    public static String render(Konfiguration.Stand stand, Map<String, String> eingabe, Map<String, String> fehler,
                                String meldung, String csrfName, String csrfToken) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Konfiguration</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main>")
                .append("<header><div><h1>Konfiguration</h1><p class=\"sub\">Einstellungen dieses Dienstes, nicht ")
                .append("der Wärmepumpe</p></div><nav><a class=\"nav\" href=\"status\">← Status</a></nav></header>");
        // The empty name carries a message that belongs to no setting, e.g. the file could not be written
        long ungueltig = fehler.keySet().stream().filter(name -> !name.isEmpty()).count();
        if (fehler.containsKey("")) {
            html.append("<p class=\"meldung fehler\">").append(text(fehler.get(""))).append("</p>");
        }
        if (ungueltig > 0) {
            html.append("<p class=\"meldung fehler\">Nicht gespeichert: ").append(ungueltig)
                    .append(ungueltig == 1 ? " Wert ist" : " Werte sind").append(" ungültig.</p>");
        } else if (GESPEICHERT.equals(meldung)) {
            html.append("<p class=\"meldung ok\">In die Datei geschrieben.</p>");
        } else if (NEUGESTARTET.equals(meldung)) {
            html.append("<p class=\"meldung ok\">Gespeichert, der Dienst läuft mit den neuen Werten.</p>");
        } else if (UNVERAENDERT.equals(meldung)) {
            html.append("<p class=\"meldung ok\">Gespeichert. Kein Neustart nötig, der Dienst läuft schon mit ")
                    .append("diesen Werten.</p>");
        }
        if (stand.hinweis() != null) {
            html.append("<p class=\"meldung fehler\">").append(text(stand.hinweis())).append("</p>");
        }
        if (stand.neustartNoetig()) {
            html.append("<p class=\"meldung neustart\">Neustart nötig: Die Datei weicht vom laufenden Dienst ab. ")
                    .append("Die markierten Werte gelten erst nach einem Neustart: „Speichern und neu starten“ ")
                    .append("übernimmt sie sofort.</p>");
        }
        html.append("<form method=\"post\" action=\"konfiguration\">");
        if (csrfName != null) {
            html.append("<input type=\"hidden\" name=\"").append(text(csrfName)).append("\" value=\"")
                    .append(text(csrfToken)).append("\">");
        }
        String gruppe = null;
        for (Konfiguration.Wert wert : stand.werte()) {
            Konfiguration.Feld feld = wert.feld();
            if (!feld.gruppe().equals(gruppe)) {
                html.append(gruppe == null ? "" : "</article>").append("<article class=\"card\"><h2>")
                        .append(text(feld.gruppe())).append("</h2>");
                gruppe = feld.gruppe();
            }
            html.append(feld(wert, eingabe.get(feld.name()), fehler.get(feld.name()), stand.schreibbar()));
        }
        html.append(gruppe == null ? "" : "</article>");
        String gesperrt = stand.schreibbar() ? "" : " disabled";
        html.append("<p class=\"aktionen\"><button type=\"submit\" name=\"aktion\" value=\"").append(NEUSTART)
                .append("\"").append(gesperrt).append(">Speichern und neu starten</button>")
                .append("<button type=\"submit\" class=\"zweit\" name=\"aktion\" value=\"speichern\"").append(gesperrt)
                .append(">Nur speichern</button><a class=\"nav\" href=\"status\">Zurück zum Status</a></p>")
                .append("<p class=\"note\">Der Neustart dauert etwa eine Minute, in dieser Zeit werden keine Werte ")
                .append("der Wärmepumpe gelesen und gespeichert.</p></form>")
                .append("<p class=\"note\">Datei: ").append(text(stand.datei().toString()))
                .append(". Vor jedem Speichern legt der Dienst daneben eine Kopie mit der Endung .bak an, Kommentare ")
                .append("und Reihenfolge der Datei bleiben erhalten. Der Dienst liest seine Einstellungen nur beim ")
                .append("Start. Das Login, Adresse und Port dieser Seite, die Logdatei und der Token der InfluxDB ")
                .append("stehen nur in der Datei: Ein Tippfehler dort würde die Seite aussperren.</p>")
                .append("</main></body></html>");
        return html.toString();
    }

    /**
     * Page shown while the service restarts: asks every 3 s for the start time of the service and returns to the
     * configuration page when it has changed
     *
     * @param gestartet start time of the service that is about to end, as answered by konfiguration/gestartet
     */
    public static String neustart(String gestartet) {
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<noscript><meta http-equiv=\"refresh\" content=\"90;url=konfiguration\"></noscript>"
                + "<title>Wärmepumpe · Neustart</title><style>" + StatusPage.CSS + CSS + "</style></head><body><main>"
                + "<header><div><h1>Konfiguration</h1><p class=\"sub\">Einstellungen dieses Dienstes, nicht der "
                + "Wärmepumpe</p></div></header>"
                + "<p class=\"meldung ok\">In die Datei geschrieben.</p>"
                + "<p class=\"meldung neustart\" id=\"lauf\">Der Dienst startet neu. Das dauert etwa eine Minute, "
                + "die Seite lädt sich danach von selbst.</p>"
                + "<p class=\"note\"><a class=\"nav\" href=\"konfiguration\">Zur Konfiguration</a></p></main>"
                + "<script>const alt = \"" + text(gestartet) + "\";" + NEUSTART_SCRIPT + "</script></body></html>";
    }

    private static String feld(Konfiguration.Wert wert, String eingabe, String fehler, boolean schreibbar) {
        Konfiguration.Feld feld = wert.feld();
        String id = feld.name().replace('.', '-');
        String angezeigt = eingabe != null ? eingabe : Konfiguration.anzeige(feld, wert.datei());
        StringBuilder html = new StringBuilder("<div class=\"feld").append(fehler == null ? "" : " ungueltig")
                .append("\"><label for=\"").append(id).append("\">").append(text(feld.label())).append("</label>")
                .append("<div class=\"eingabe\">");
        String attribute = " id=\"" + id + "\" name=\"" + feld.name() + "\"" + (schreibbar ? "" : " disabled");
        switch (feld.typ()) {
            case SCHALTER -> {
                boolean an = eingabe != null ? eingabe.equals("true") : Boolean.parseBoolean(wert.datei());
                html.append("<select").append(attribute).append("><option value=\"true\"").append(an ? " selected" : "")
                        .append(">an</option><option value=\"false\"").append(an ? "" : " selected")
                        .append(">aus</option></select>");
            }
            case DATUM -> html.append("<input type=\"date\"").append(attribute).append(" value=\"")
                    .append(text(angezeigt)).append("\">");
            case ZAHL, GANZZAHL -> html.append("<input type=\"text\" inputmode=\"decimal\" class=\"zahl\"")
                    .append(attribute).append(" value=\"").append(text(angezeigt)).append("\">");
            case MAIL -> html.append("<input type=\"email\"").append(attribute).append(" value=\"")
                    .append(text(angezeigt)).append("\">");
            // The value of a password never reaches the page, also not what was typed before an error
            case GEHEIM -> html.append("<input type=\"password\" autocomplete=\"new-password\"").append(attribute)
                    .append(" value=\"\" placeholder=\"").append(wert.gesetzt() ? "unverändert" : "nicht gesetzt")
                    .append("\">");
            default -> html.append("<input type=\"text\"").append(attribute).append(" value=\"")
                    .append(text(angezeigt)).append("\">");
        }
        html.append(feld.einheit().isEmpty() ? "" : "<span class=\"unit\">" + text(feld.einheit()) + "</span>")
                .append("</div><p class=\"hilfe\">").append(text(feld.erklaerung()));
        if (feld.standard() != null && (feld.typ() == Konfiguration.Typ.ZAHL
                || feld.typ() == Konfiguration.Typ.GANZZAHL || feld.typ() == Konfiguration.Typ.DATUM)) {
            html.append(" Standard: ").append(text(Konfiguration.anzeige(feld, feld.standard()))).append(".");
        }
        html.append("</p>");
        if (fehler != null) {
            html.append("<p class=\"hilfe fehlertext\">").append(text(feld.label())).append(" ").append(text(fehler))
                    .append(".</p>");
        }
        if (wert.neustartNoetig()) {
            String laufend = Konfiguration.anzeige(feld, wert.laufend());
            html.append("<p class=\"hilfe neustarttext\">Der Dienst läuft noch mit ")
                    .append(feld.typ() == Konfiguration.Typ.GEHEIM ? "dem bisherigen Passwort"
                            : laufend.isEmpty() ? "einem leeren Wert" : "„" + text(laufend) + "“").append(".</p>");
        }
        return html.append("</div>").toString();
    }

    private static String text(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static final String GESPEICHERT = "gespeichert";
    public static final String NEUGESTARTET = "neugestartet";
    public static final String UNVERAENDERT = "unveraendert";
    // Value of the button that saves and restarts
    public static final String NEUSTART = "neustart";

    private static final String NEUSTART_SCRIPT = """
            const start = Date.now();
            const timer = setInterval(async () => {
              try {
                const res = await fetch('konfiguration/gestartet', {cache: 'no-store'});
                if (res.ok && (await res.text()) !== alt) {
                  clearInterval(timer);
                  location.replace('konfiguration?meldung=neugestartet');
                }
              } catch (e) {
                // not reachable while it restarts
              }
              if (Date.now() - start > 5 * 60 * 1000) {
                clearInterval(timer);
                document.getElementById('lauf').textContent = 'Der Dienst antwortet nach 5 Minuten noch nicht. '
                  + 'Bitte das Log des Dienstes ansehen.';
              }
            }, 3000);
            """;

    private static final String CSS = """
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            form { display: grid; gap: 12px; }
            .card { padding-bottom: 14px; }
            .feld { padding: 10px 0; border-top: 1px solid var(--line); }
            .feld:first-of-type { border-top: 0; }
            .feld label { display: block; font-weight: 600; margin-bottom: 4px; }
            .eingabe { display: flex; align-items: center; gap: 8px; }
            input, select { font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--line);
              border-radius: 8px; padding: 6px 10px; width: min(100%, 320px); }
            input.zahl { width: 110px; text-align: right; font-variant-numeric: tabular-nums; }
            select { width: auto; }
            input:focus, select:focus, button:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
            input:disabled, select:disabled { color: var(--muted); }
            .unit { color: var(--muted); }
            .hilfe { margin: 4px 0 0; font-size: 12px; color: var(--muted); }
            .ungueltig input, .ungueltig select { border-color: var(--warn); }
            .fehlertext { color: var(--warn); font-weight: 600; }
            .neustarttext { color: var(--stale); font-weight: 600; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600; }
            .meldung.ok { background: var(--on-bg); color: var(--on); }
            .meldung.fehler { background: var(--warn-bg); color: var(--warn); }
            .meldung.neustart { background: var(--off-bg); color: var(--stale); }
            .aktionen { display: flex; align-items: center; gap: 18px; margin: 4px 0 0; }
            button { font: inherit; font-weight: 600; color: var(--card); background: var(--accent); border: 0;
              border-radius: 10px; padding: 8px 18px; cursor: pointer; }
            button.zweit { background: transparent; color: var(--accent); border: 1px solid var(--accent); }
            .aktionen { flex-wrap: wrap; }
            button:disabled { background: var(--off-bg); color: var(--muted); cursor: not-allowed; }
            """;
}
