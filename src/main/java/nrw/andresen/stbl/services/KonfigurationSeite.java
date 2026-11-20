package nrw.andresen.stbl.services;

import java.util.ArrayList;
import java.util.List;
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
    // Fetches the system area every 20 s; after the session ran out it stops, the next click leads to the login
    static final String SYSTEM_LIVE = """
            <script>
            (() => {
              const ziel = document.getElementById('system-live');
              const id = setInterval(async () => {
                try {
                  const antwort = await fetch('konfiguration/system', {cache: 'no-store', credentials: 'same-origin'});
                  if (antwort.status === 401) { clearInterval(id); return; }
                  if (antwort.ok) ziel.innerHTML = await antwort.text();
                } catch (e) { /* next time */ }
              }, 20000);
            })();
            </script>""";

    public static String render(Konfiguration.Stand stand, Map<String, String> eingabe, Map<String, String> fehler,
                                String meldung, String csrfName, String csrfToken) {
        return render(stand, eingabe, fehler, meldung, csrfName, csrfToken, null);
    }

    /**
     * @param testErgebnis result of the last test message, shown with the message TEST
     */
    public static String render(Konfiguration.Stand stand, Map<String, String> eingabe, Map<String, String> fehler,
                                String meldung, String csrfName, String csrfToken, String testErgebnis) {
        return render(stand, eingabe, fehler, meldung, csrfName, csrfToken, testErgebnis, "");
    }

    /**
     * @param system state of the service, the devices and the USB adapter, shown above the settings
     */
    public static String render(Konfiguration.Stand stand, Map<String, String> eingabe, Map<String, String> fehler,
                                String meldung, String csrfName, String csrfToken, String testErgebnis,
                                String system) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Wärmepumpe · Einstellungen</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main>")
                .append("<header><div><h1>Einstellungen</h1><p class=\"sub\">Einstellungen dieses Dienstes, nicht ")
                .append("der Wärmepumpe</p></div><nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"jahresvergleich\">Jahresvergleich</a> · ")
                .append("<a class=\"nav\" href=\"zustand\">Zustand</a> · ")
                .append("<a class=\"nav\" href=\"canbus\">CAN-Bus</a></nav></header>")
                // The system area is fetched again every 20 s on its own, the forms below stay as they are
                .append("<div id=\"system-live\">").append(system).append("</div>")
                .append(system.isEmpty() ? "" : SYSTEM_LIVE);
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
        } else if (TEST.equals(meldung) && testErgebnis != null) {
            html.append("<p class=\"meldung ").append(testErgebnis.contains("fehlgeschlagen") ? "fehler" : "ok")
                    .append("\">").append(text(testErgebnis)).append("</p>");
        } else if (SOFORT.equals(meldung)) {
            html.append("<p class=\"meldung ok\">Gespeichert und sofort übernommen, ohne Neustart.</p>");
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
        Map<String, Konfiguration.Wert> nachName = new java.util.LinkedHashMap<>();
        stand.werte().forEach(w -> nachName.put(w.feld().name(), w));
        html.append("<nav class=\"abschnitte\" aria-label=\"Abschnitte\">");
        for (Abschnitt a : ABSCHNITTE) {
            html.append("<a class=\"nav\" href=\"#").append(a.id()).append("\">").append(text(a.titel())).append("</a>");
        }
        html.append("</nav><form method=\"post\" action=\"konfiguration\">");
        if (csrfName != null) {
            html.append("<input type=\"hidden\" name=\"").append(text(csrfName)).append("\" value=\"")
                    .append(text(csrfToken)).append("\">");
        }
        java.util.Set<String> gezeigt = new java.util.HashSet<>();
        for (Abschnitt a : ABSCHNITTE) {
            html.append("<section class=\"card abschnitt\" id=\"").append(a.id()).append("\"><h2>")
                    .append(text(a.titel())).append("</h2><p class=\"hilfe einleitung\">").append(text(a.text()))
                    .append("</p>");
            for (Teil t : a.teile()) {
                html.append(t.titel().isEmpty() ? "" : "<h3>" + text(t.titel()) + "</h3>");
                if (t.felder().equals(GERAETE)) {
                    html.append(geraete(nachName, eingabe, fehler, stand.schreibbar()));
                    for (int n = 1; n <= Geraete.ANZAHL; n++) {
                        for (String teil : GERAETE_TEILE) {
                            gezeigt.add("auswertung.geraet." + n + "." + teil);
                        }
                    }
                    continue;
                }
                html.append("<div class=\"felder\">");
                for (String name : t.felder()) {
                    Konfiguration.Wert wert = nachName.get(name);
                    if (wert != null) {
                        html.append(feld(wert, eingabe.get(name), fehler.get(name), stand.schreibbar()));
                        gezeigt.add(name);
                    }
                }
                html.append("</div>");
            }
            if (a.id().equals("benachrichtigungen")) {
                // Sends with the values of the running service, uses the token of this form
                html.append("<p class=\"aktionen\"><button type=\"submit\" class=\"zweit\" ")
                        .append("formaction=\"konfiguration/test\" formnovalidate>Testnachricht senden</button>")
                        .append("<span class=\"hilfe\">Schickt eine Warnung zur Probe per Mail und, falls eingerichtet, ")
                        .append("per Pushover, mit den Werten des laufenden Dienstes.</span></p>");
            }
            html.append("</section>");
        }
        // Settings without a place in the sections, so none is ever lost
        StringBuilder rest = new StringBuilder();
        for (Konfiguration.Wert wert : stand.werte()) {
            if (!gezeigt.contains(wert.feld().name())) {
                rest.append(feld(wert, eingabe.get(wert.feld().name()), fehler.get(wert.feld().name()),
                        stand.schreibbar()));
            }
        }
        if (!rest.isEmpty()) {
            html.append("<section class=\"card abschnitt\"><h2>Weitere</h2><div class=\"felder\">").append(rest)
                    .append("</div></section>");
        }
        String gesperrt = stand.schreibbar() ? "" : " disabled";
        html.append("<div class=\"aktionen fest\"><button type=\"submit\" name=\"aktion\" value=\"").append(NEUSTART)
                .append("\"").append(gesperrt).append(">Speichern und neu starten</button>")
                .append("<button type=\"submit\" class=\"zweit\" name=\"aktion\" value=\"speichern\"").append(gesperrt)
                .append(">Nur speichern</button><span class=\"hilfe\">Der Neustart dauert etwa eine Minute, ohne ")
                .append("Werte der Wärmepumpe. Mit <span class=\"sofort\">sofort</span> markierte Werte gelten schon ")
                .append("beim Speichern.</span></div>")
                .append("</form>")
                .append("<p class=\"note\">Datei: ").append(text(stand.datei().toString()))
                .append(". Vor jedem Speichern legt der Dienst daneben eine Kopie mit der Endung .bak an, Kommentare ")
                .append("und Reihenfolge der Datei bleiben erhalten. Die übrigen Einstellungen liest der Dienst nur ")
                .append("beim Start. Das Login, Adresse und Port dieser Seite, die Logdatei und der Token der InfluxDB ")
                .append("stehen nur in der Datei: Ein Tippfehler dort würde die Seite aussperren.</p>")
                .append("</main></body></html>");
        return html.toString();
    }

    /**
     * One section of the page with its parts; a part shows its settings in columns
     */
    record Abschnitt(String id, String titel, String text, List<Teil> teile) {
    }

    record Teil(String titel, List<String> felder) {
    }

    // Marks the part shown as table of the devices
    static final List<String> GERAETE = List.of("geraete");
    static final List<String> GERAETE_TEILE = List.of("name", "zaehler", "davor", "vorher");

    // In the order of setting up the service: connection, notification, warnings, Home Assistant, evaluation
    static final List<Abschnitt> ABSCHNITTE = List.of(
            new Abschnitt("verbindung", "Wärmepumpe und Datenbank",
                    "Wie der Dienst die Wärmepumpe liest und wo er die Werte speichert.", List.of(
                    new Teil("CAN-Adapter", List.of("USBtin.port", "USBtin.speed", "can.logall")),
                    new Teil("InfluxDB", List.of("influx.enabled", "influx.url", "influx.org", "influx.bucket")))),
            new Abschnitt("benachrichtigungen", "Benachrichtigungen", "Wohin die Warnungen gehen.", List.of(
                    new Teil("Mail", List.of("stbl.mail.to", "spring.mail.host", "spring.mail.port",
                            "spring.mail.username", "spring.mail.password", "spring.mail.properties.mail.smtp.auth",
                            "spring.mail.properties.mail.smtp.starttls.enable")),
                    new Teil("Pushover", List.of("pushover.user", "pushover.token", "pushover.prioritaet")))),
            new Abschnitt("warnungen", "Warnungen", "Ab wann der Dienst warnt.", List.of(
                    new Teil("Heizwasser", List.of("warnung.heizungsdruck.min", "warnung.heizungsdruck.max",
                            "warnung.heizungsdruck.abfall", "warnung.spreizung.max")),
                    new Teil("Kältekreis", List.of("warnung.hochdruck.max", "warnung.niederdruck.min",
                            "warnung.heissgas.max")),
                    new Teil("Betrieb", List.of("warnung.starts.proStunde", "warnung.abtauung.minuten",
                            "warnung.frost.aussentemp", "warnung.frost.stunden")),
                    new Teil("Strom und Bus", List.of("warnung.spannung.min", "warnung.spannung.max",
                            "warnung.antwortquote.min")))),
            new Abschnitt("homeassistant", "Home Assistant", "Die Werte als Gerät „Wärmepumpe“ in Home Assistant, "
                    + "über MQTT und nur lesend.", List.of(
                    new Teil("", List.of("mqtt.host", "mqtt.port", "mqtt.benutzer", "mqtt.passwort", "mqtt.praefix")))),
            new Abschnitt("auswertung", "Auswertung", "Was die Seiten Jahr, Zustand und die Vergleiche lesen.", List.of(
                    new Teil("Ab wann ausgewertet wird", List.of("auswertung.start", "auswertung.fehlstarts.start",
                            "auswertung.zustand.start")),
                    new Teil("Außentemperatur", List.of("auswertung.aussentemp.bucket", "auswertung.aussentemp.entity")),
                    new Teil("Hauszähler", List.of("auswertung.haushalt.bucket", "auswertung.haushalt.zaehler")),
                    new Teil("Geräte mit eigenem Zähler", GERAETE))));

    /**
     * The devices as table, one row each: name, counter, readings before the counter, ran before the first value
     */
    private static String geraete(Map<String, Konfiguration.Wert> werte, Map<String, String> eingabe,
                                  Map<String, String> fehler, boolean schreibbar) {
        StringBuilder html = new StringBuilder("<p class=\"hilfe\">Zum Beispiel ein Shelly an Klimaanlage oder "
                + "Warmwasser: eigener Teil im Jahresvergleich, vom Haushaltsstrom abgezogen. Beide Spalten mit "
                + "Werten sind Home-Assistant-Entities mit dem Zählerstand in kWh. „Werte vor dem Zähler“: Stände aus "
                + "der Zeit, bevor es den Zähler gab, etwa händisch abgelesen; der Zähler setzt danach fort. „Vor dem "
                + "ersten Wert“: „lief schon“, wenn das Gerät vor seinem ersten Wert (von Hand oder vom Zähler) "
                + "ungemessen im Haushalt steckte; „gab es noch nicht“ etwa für eine neue Wallbox, vorher zählt sie "
                + "0.</p><div class=\"tbl\"><table class=\"geraete\"><thead><tr><th></th><th>Name</th>"
                + "<th>Zähler</th><th>Werte vor dem Zähler</th><th>Vor dem ersten Wert</th></tr></thead><tbody>");
        List<String> meldungen = new ArrayList<>();
        for (int n = 1; n <= Geraete.ANZAHL; n++) {
            html.append("<tr><td>").append(n).append("</td>");
            for (String teil : GERAETE_TEILE) {
                String name = "auswertung.geraet." + n + "." + teil;
                Konfiguration.Wert wert = werte.get(name);
                if (wert == null) {
                    html.append("<td></td>");
                    continue;
                }
                String id = name.replace('.', '-');
                String angezeigt = eingabe.get(name) != null ? eingabe.get(name)
                        : Konfiguration.anzeige(wert.feld(), wert.datei());
                String attribute = " id=\"" + id + "\" name=\"" + name + "\" aria-label=\"" + text(wert.feld().label())
                        + "\"" + (schreibbar ? "" : " disabled");
                html.append("<td").append(fehler.containsKey(name) ? " class=\"ungueltig\"" : "").append(">");
                if (wert.feld().typ() == Konfiguration.Typ.SCHALTER) {
                    boolean an = eingabe.get(name) != null ? eingabe.get(name).equals("true")
                            : Boolean.parseBoolean(wert.datei());
                    html.append("<select").append(attribute).append("><option value=\"true\"")
                            .append(an ? " selected" : "").append(">lief schon</option><option value=\"false\"")
                            .append(an ? "" : " selected").append(">gab es noch nicht</option></select>");
                } else {
                    html.append("<input type=\"text\"").append(attribute).append(" value=\"").append(text(angezeigt))
                            .append("\">");
                }
                html.append("</td>");
                if (fehler.containsKey(name)) {
                    meldungen.add(wert.feld().label() + " " + fehler.get(name) + ".");
                }
                if (wert.neustartNoetig()) {
                    meldungen.add(wert.feld().label() + ": gilt erst nach dem Neustart.");
                }
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></div>");
        for (String m : meldungen) {
            html.append("<p class=\"hilfe ").append(m.endsWith("Neustart.") ? "neustarttext" : "fehlertext").append("\">")
                    .append(text(m)).append("</p>");
        }
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
                + "<header><div><h1>Einstellungen</h1><p class=\"sub\">Einstellungen dieses Dienstes, nicht der "
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
                .append("\"><label for=\"").append(id).append("\">").append(text(feld.label()))
                .append(Konfiguration.SOFORT.contains(feld.name())
                        ? " <span class=\"sofort\" title=\"Gilt schon beim Speichern, ohne Neustart\">sofort</span>" : "")
                .append("</label>")
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
    public static final String TEST = "test";
    public static final String SOFORT = "sofort";
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
            form { display: grid; gap: 14px; }
            form > * { min-width: 0; }
            .abschnitte { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 0 0 12px; font-size: 14px; }
            .abschnitt { padding-bottom: 14px; scroll-margin-top: 12px; }
            .abschnitt h3 { margin: 14px 0 0; padding-top: 10px; border-top: 1px solid var(--line); font-size: 13px;
              font-weight: 650; color: var(--text); }
            .einleitung { margin: 2px 0 0; }
            .felder { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr));
              gap: 0 28px; }
            .feld { padding: 10px 0 2px; }
            .feld label { display: block; font-weight: 600; font-size: 14px; margin-bottom: 4px; }
            table.geraete { width: 100%; margin-top: 8px; }
            table.geraete th, table.geraete td { text-align: left; }
            table.geraete td { padding: 4px 8px 4px 0; vertical-align: middle; }
            table.geraete td:first-child { color: var(--muted); width: 1.5em; }
            table.geraete input { width: 100%; min-width: 120px; }
            .ungueltig input, .ungueltig select { border-color: var(--warn); }
            .aktionen.fest { position: sticky; bottom: 0; z-index: 5; padding: 12px 16px; margin: 0;
              background: color-mix(in srgb, var(--card) 94%, transparent); border: 1px solid var(--line);
              border-radius: 14px; box-shadow: 0 -4px 16px rgb(0 0 0 / 0.06); }
            .aktionen .hilfe { margin: 0; }
            .eingabe { display: flex; align-items: center; gap: 8px; }
            input, select { font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--line);
              border-radius: 8px; padding: 6px 10px; width: min(100%, 320px); }
            input.zahl { width: 110px; text-align: right; font-variant-numeric: tabular-nums; }
            select { width: auto; }
            input:focus, select:focus, button:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
            input:disabled, select:disabled { color: var(--muted); }
            .unit { color: var(--muted); }
            .hilfe { margin: 4px 0 0; font-size: 12px; color: var(--muted); }
            .sofort { display: inline-block; margin-left: 4px; padding: 0 6px; border-radius: 999px; font-size: 11px;
              font-weight: 600; line-height: 16px; vertical-align: 1px; color: var(--accent);
              border: 1px solid var(--accent); }
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
