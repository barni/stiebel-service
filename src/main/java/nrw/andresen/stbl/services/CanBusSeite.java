package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterMessage;
import nrw.andresen.stbl.services.can.IndexBeschreibung;
import nrw.andresen.stbl.services.can.IndexScan;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HTML of the CAN bus monitoring page: adapter, rates of the last minute, nodes, age of each requested value and the
 * last messages. Linked from the configuration page, reloads itself.
 */
public final class CanBusSeite {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZONE);
    static final int NEU_LADEN_S = 10;
    // Small i linking to the setting on the configuration page, whose fields carry the name with - for .
    static final String HINWEIS_KONFIG = "<a class=\"info\" href=\"konfiguration#can-logall\" title=\"Wird nur "
            + "mitgelesen, wenn „Alle Nachrichten empfangen“ in der Konfiguration an ist\" "
            + "aria-label=\"Zur Einstellung „Alle Nachrichten empfangen“\">i</a>";
    // Messages shown without a choice
    public static final int ANZAHL_STANDARD = 100;
    // Receiver of a message to all devices of a group, e.g. 0x579
    private static final int RUNDRUF = 0x79;

    private CanBusSeite() {
    }

    public static String render(CanBus.Verbindung v, List<CanBus.Abfrage> abfragen, List<CanStatistik.Rahmen> rahmen,
                                Instant jetzt) {
        return render(v, abfragen, rahmen, null, ANZAHL_STANDARD, jetzt);
    }

    /**
     * @param knoten only messages from or to this CAN id, null for all
     * @param anzahl number of messages shown at most
     */
    public static String render(CanBus.Verbindung v, List<CanBus.Abfrage> abfragen, List<CanStatistik.Rahmen> rahmen,
                                Integer knoten, int anzahl, Instant jetzt) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append(neuLaden())
                .append("<title>Wärmepumpe · CAN-Bus</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>CAN-Bus</h1><p class=\"sub\">USBtin-Adapter ")
                .append("und Nachrichten, lädt alle ").append(NEU_LADEN_S).append(" s neu</p></div>")
                .append("<nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a> · ")
                .append("<a class=\"nav\" href=\"canbus/scan\">Bus-Scan</a></nav></header>");
        html.append("<p class=\"meldung ").append(v.verbunden() ? "ok" : "fehler").append("\">")
                .append(text(satz(v, abfragen, jetzt))).append("</p><section class=\"cards\">");

        html.append("<article class=\"card\"><h2>Adapter</h2>").append(tabelle(null, List.of(
                List.of("Verbunden", v.verbunden() ? "ja" : "nein"),
                List.of("Port", text(v.port()) + ", " + zahl(v.bitrate() / 1000d, 0) + " kbit/s"),
                List.of("Firmware / Hardware", text(wert(v.firmware())) + " / " + text(wert(v.hardware()))),
                List.of("Seriennummer", text(wert(v.seriennummer()))),
                List.of("Verbunden seit", zeit(v.verbundenSeit())),
                List.of("USB-Neustarts", v.usbNeustarts() + (v.letzterNeustart() == null ? ""
                        : ", zuletzt " + zeit(v.letzterNeustart()))),
                List.of("Adapterfehler", v.adapterFehler() + (v.letzterFehler() == null ? ""
                        : ", zuletzt " + zeit(v.letzterFehler()))),
                List.of("Letzte Nachricht", alter(v.letzteNachricht(), jetzt)),
                List.of("Letzte Antwort", alter(v.letzteAntwort(), jetzt)))));
        if (v.letzterFehlerText() != null) {
            html.append("<p class=\"note\">Letzter Fehler: ").append(text(v.letzterFehlerText())).append("</p>");
        }
        html.append("</article>");

        CanStatistik.Minute m = v.letzteMinute();
        html.append("<article class=\"card\"><h2>Letzte Minute</h2>");
        if (m == null) {
            html.append("<p class=\"note\">Noch keine volle Minute seit dem Start.</p>");
        } else {
            html.append(tabelle(null, List.of(
                    List.of("Empfangen", zahl(m.empfangen(), 0) + " /min"),
                    List.of("Gesendet (Anfragen)", zahl(m.gesendet(), 0) + " /min"),
                    List.of("Antworten", zahl(m.antworten(), 0) + " /min"),
                    List.of("Nicht verfügbar (0x8000)", zahl(m.nichtVerfuegbar(), 0) + " /min"),
                    List.of("Antwortquote", zahl(m.antwortquote(), 0) + " %"),
                    List.of("Buslast", zahl(m.buslast(), 1) + " %"))))
                    .append("<p class=\"note\">Minute bis ").append(zeit(m.ende()))
                    .append(". Die Buslast ist eine untere Schätzung ohne Bit-Stuffing.</p>");
        }
        html.append("</article>");

        List<List<String>> geraete = new ArrayList<>();
        for (CanStatistik.KnotenStatus k : v.knoten()) {
            // A click on the chosen node again removes the filter
            boolean gewaehlt = knoten != null && k.id() == knoten;
            geraete.add(List.of("<a class=\"nav" + (gewaehlt ? " gewaehlt" : "") + "\" href=\""
                            + link(gewaehlt ? null : k.id(), anzahl) + "\"" + (gewaehlt ? " aria-current=\"true\"" : "")
                            + ">" + text(k.name()) + (gewaehlt ? " ✕" : "") + "</a>",
                    zahl(k.proMinute(), 0), alter(k.zuletzt(), jetzt)));
        }
        html.append("<article class=\"card\"><h2>Knoten</h2>");
        if (knoten != null) {
            html.append("<p class=\"auswahl\">Filter: ").append(text(CanStatistik.knotenName(knoten)))
                    .append(" · <a class=\"nav\" href=\"").append(link(null, anzahl)).append("\">Filter aufheben</a></p>");
        }
        html.append(geraete.isEmpty() ? "<p class=\"note\">Noch keine Nachricht empfangen.</p>"
                        : tabelle(List.of("Gerät", "/min", "Zuletzt"), geraete)
                        + "<p class=\"note\">Ein Klick auf ein Gerät zeigt nur dessen Nachrichten, ein zweiter Klick "
                        + "zeigt wieder alle.</p>")
                .append("</article>");

        List<List<String>> werte = new ArrayList<>();
        // Without receiving all messages nothing is read along: those values go grey to the end
        List<CanBus.Abfrage> sortiert = new ArrayList<>(abfragen);
        if (!v.alleNachrichten()) {
            sortiert.sort(java.util.Comparator.comparing(CanBus.Abfrage::mitgelesen));
        }
        for (CanBus.Abfrage a : sortiert) {
            boolean offen = a.offen(jetzt);
            boolean aus = a.mitgelesen() && !v.alleNachrichten();
            String grau = aus ? "<span class=\"aus\">" : "";
            String ende = aus ? "</span>" : "";
            werte.add(List.of(grau + hex(a.index()) + ende, grau + mitBeschreibung(a.index(), a.name()) + ende,
                    grau + text(wert(a.wert())) + ende,
                    !a.mitgelesen() ? alter(a.angefragt(), jetzt) : aus ? grau + "aus" + ende + " " + HINWEIS_KONFIG
                            : "mitgelesen",
                    (offen ? "<span class=\"alt\">" : "") + grau + alter(a.zeit(), jetzt) + ende
                            + (offen ? "</span>" : "")));
        }
        html.append("<article class=\"card breit\"><h2>Abgefragte Werte (").append(abfragen.size())
                .append(")</h2>")
                .append(werte.isEmpty() ? "<p class=\"note\">Noch nichts angefragt.</p>"
                        : tabelle(List.of("Index", "Name", "Wert", "Angefragt", "Antwort"), werte))
                .append("<p class=\"note\">Rot und oben: Auf die letzte Anfrage kam nach ")
                .append(CanBus.ANTWORTZEIT.toSeconds()).append(" s keine gültige Antwort. Antworten mit 0x8000 ")
                .append("(nicht verfügbar) zählen nicht. Die Einstellungen werden nur stündlich angefragt. ")
                .append("„mitgelesen“: Wert, den ein anderes Gerät auf den Bus schreibt, nur mit „Alle Nachrichten ")
                .append("empfangen“ in der ").append("<a class=\"nav\" href=\"konfiguration#can-logall\">")
                .append("Konfiguration</a>").append(v.alleNachrichten() ? " (an)." : ", gerade aus.").append("</p>")
                .append("</article>");

        List<CanStatistik.Rahmen> auswahl = auswahl(rahmen, knoten, anzahl);
        List<List<String>> zeilen = new ArrayList<>();
        for (CanStatistik.Rahmen r : auswahl) {
            zeilen.add(rahmenzeile(r));
        }
        html.append("<article class=\"card breit\"><h2>Letzte Nachrichten")
                .append(knoten == null ? "" : " von und an " + text(CanStatistik.knotenName(knoten)))
                .append("</h2><p class=\"auswahl\">");
        if (knoten != null) {
            html.append("<a class=\"nav\" href=\"").append(link(null, anzahl)).append("\">Filter aufheben</a> · ");
        }
        for (int n : List.of(ANZAHL_STANDARD, 300, CanStatistik.RAHMEN_MAX)) {
            html.append(n == anzahl ? "<b>" + n + "</b>" : "<a class=\"nav\" href=\"" + link(knoten, n) + "\">" + n + "</a>")
                    .append(n == CanStatistik.RAHMEN_MAX ? "" : " · ");
        }
        html.append(" Nachrichten</p>")
                .append(zeilen.isEmpty() ? "<p class=\"note\">Keine Nachricht.</p>"
                        : tabelle(List.of("Zeit", "", "Von", "An", "Art", "Index", "Wert", "Daten"), zeilen))
                .append("<p class=\"note\">").append(zeilen.size()).append(" von ").append(rahmen.size())
                .append(" gespeicherten Nachrichten")
                .append(rahmen.size() < 2 ? "" : ", die gespeicherten reichen "
                        + sekunden(rahmen.get(rahmen.size() - 1).zeit(), rahmen.get(0).zeit()) + " zurück")
                .append(". Neueste oben, → gesendet, ← empfangen. Der Wert steht bei Antworten und beim Schreiben. ")
                .append("0x…79 als Empfänger ist ein Rundruf an alle Geräte der Gruppe. Der Adapter filtert den Bus: ")
                .append("Empfangen werden nur die Antworten an diesen Dienst, außer can.logall ist an.</p></article>")
                .append("</section><p class=\"note\">Stand ").append(zeit(jetzt)).append(". Als JSON: ")
                .append("<a class=\"nav\" href=\"verbindung\">verbindung</a>.</p></main></body></html>");
        return html.toString();
    }

    /**
     * Short summary on top of the page
     */
    static String satz(CanBus.Verbindung v, List<CanBus.Abfrage> abfragen, Instant jetzt) {
        if (!v.verbunden()) {
            return "Adapter nicht verbunden, die Überwachung startet die USB-Verbindung neu.";
        }
        long alt = abfragen.stream().filter(a -> a.offen(jetzt)).count();
        String antwort = v.letzteAntwort() == null ? "noch keine Antwort"
                : "letzte Antwort vor " + sekunden(v.letzteAntwort(), jetzt);
        return "Adapter verbunden, " + antwort + (alt == 0 ? ", alle Anfragen beantwortet."
                : ", " + alt + (alt == 1 ? " Wert" : " Werte") + " ohne Antwort auf die letzte Anfrage.");
    }

    /**
     * Reloads the page every NEU_LADEN_S seconds like the status page, but not in a hidden tab and not while a
     * tooltip is shown (hovered or tapped, the i keeps the focus); then as soon as it is closed. Without script a
     * plain refresh.
     */
    static String neuLaden() {
        return "<noscript><meta http-equiv=\"refresh\" content=\"" + NEU_LADEN_S + "\"></noscript><script>(() => {"
                + "const offen = () => document.querySelector('.calc:hover, .calc:focus') !== null;"
                + "const reload = () => { if (!document.hidden && !offen()) location.reload(); };"
                + "setTimeout(() => { reload(); setInterval(reload, 1000);"
                + " document.addEventListener('visibilitychange', reload); }, " + NEU_LADEN_S * 1000 + ");"
                + "})();</script>";
    }

    /**
     * Page of the scan of all Elster indices at /canbus/scan: state, start button and every answer with a value
     *
     * @param angefragt indices the service requests anyway, they are not scanned
     * @param nullen    also show the answers with the value 0
     * @param csrfName  name of the hidden form field against requests from other sites, null without protection
     */
    public static String scan(IndexScan.Status st, List<IndexScan.Ergebnis> ergebnisse, Set<Short> angefragt,
                              boolean nullen, String csrfName, String csrfToken, Instant jetzt) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        if (st.laeuft()) {
            html.append(neuLaden());
        }
        html.append("<title>Wärmepumpe · Bus-Scan</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>Bus-Scan</h1><p class=\"sub\">Alle Indizes ")
                .append("der Elster-Tabelle einmal bei 0x180, 0x480, 0x500 und 0x514 abfragen, nur lesend</p></div>")
                .append("<nav><a class=\"nav\" href=\"../canbus\">← CAN-Bus</a> · ")
                .append("<a class=\"nav\" href=\"../status\">Status</a></nav></header><section class=\"cards\">");
        String zustand = st.laeuft() ? "läuft seit " + zeit(st.start())
                : st.ende() != null ? "fertig " + zeit(st.ende()) : "noch nicht gestartet";
        int nullwerte = (int) ergebnisse.stream().filter(e -> e.roh() == 0).count();
        html.append("<article class=\"card\"><h2>Zustand</h2>").append(tabelle(null, List.of(
                List.of("Zustand", text(zustand)),
                List.of("Fortschritt", st.gesamt() == 0 ? "–" : st.gesendet() + " von " + st.gesamt() + " Anfragen ("
                        + zahl(100d * st.gesendet() / st.gesamt(), 0) + " %)"),
                List.of("Mit Wert", st.mitWert() + (st.mitWert() == 0 ? "" : ", davon " + nullwerte + " mit 0")),
                List.of("Nicht verfügbar (0x8000)", String.valueOf(st.nichtVerfuegbar())),
                List.of("Ausgelassen", angefragt.size() + " regelmäßig abgefragte Indizes und die Fehlerliste"))));
        html.append("<p class=\"note\">Etwa 10 Leseanfragen pro Sekunde, rund 25 Minuten. Es wird nichts ")
                .append("geschrieben. Während des Scans ist die Warnung „Antwortquote niedrig“ aus, weil Geräte ")
                .append("unbekannte Indizes teils nicht beantworten. Das Ergebnis bleibt bis zum nächsten Neustart ")
                .append("des Dienstes erhalten, als CSV lässt es sich sichern.</p>");
        if (!st.laeuft()) {
            html.append("<form method=\"post\" action=\"scan\">");
            if (csrfName != null) {
                html.append("<input type=\"hidden\" name=\"").append(text(csrfName)).append("\" value=\"")
                        .append(text(csrfToken)).append("\">");
            }
            html.append("<button type=\"submit\">")
                    .append(st.ende() == null ? "Scan starten" : "Scan erneut starten").append("</button></form>");
        }
        html.append("<p class=\"note\"><a href=\"scan.csv\">Alle Werte als CSV</a> · <a href=\"scan")
                .append(nullen ? "\">Werte 0 ausblenden" : "?nullen=ja\">Auch Werte 0 zeigen").append("</a></p>")
                .append("</article>");
        List<List<String>> zeilen = new ArrayList<>();
        for (IndexScan.Ergebnis e : ergebnisse) {
            if (!nullen && e.roh() == 0) {
                continue;
            }
            zeilen.add(List.of(hex(e.index()), mitBeschreibung(e.index(), e.name()), text(CanStatistik.knotenName(
                    e.knoten())), text(e.wert()), String.format("<code>%04X</code>", e.roh()),
                    IndexBeschreibung.beschreibung(e.index()) == null ? "neu" : "beschrieben"));
        }
        html.append("<article class=\"card breit\"><h2>Antworten mit Wert (").append(zeilen.size()).append(")</h2>")
                .append(zeilen.isEmpty() ? "<p class=\"note\">Noch keine.</p>"
                        : tabelle(List.of("Index", "Name", "Gerät", "Wert", "Roh", "Bekannt"), zeilen))
                .append("<p class=\"note\">„neu“: Index ohne Beschreibung im stbl-service. Der Wert ist so ")
                .append("umgerechnet, wie die Elster-Tabelle den Index führt; bei falschem Namen kann auch die ")
                .append("Umrechnung falsch sein, dann hilft der Rohwert.</p></article>");
        return html.append("</section></main></body></html>").toString();
    }

    static List<String> rahmenzeile(CanStatistik.Rahmen r) {
        ElsterMessage m = new ElsterMessage(r.id(), r.daten());
        String art = artMitBeschreibung(r.daten());
        boolean mitIndex = r.daten().length >= 3;
        String index = mitIndex ? hex(m.getElsterIndex().getIndex()) + " "
                + mitBeschreibung(m.getElsterIndex().getIndex(), m.getElsterIndex().getName()) : "";
        int typ = r.daten().length == 0 ? -1 : r.daten()[0] & 0x0F;
        // Answers and writes carry a value, requests do not
        String wert = typ == 0 || typ == 2 ? m.getValue() : "";
        if (wert.equals("PAYLOAD_CORRUPT")) {
            wert = "";
        }
        int an = empfaenger(r.daten());
        return List.of(UHRZEIT.format(r.zeit()), r.gesendet() ? "→" : "←", text(CanStatistik.knotenName(r.id())),
                an < 0 ? "" : text(empfaengerName(an)), art, index, text(wert),
                "<code>" + ElsterMessage.bytesToHex(r.daten()) + "</code>");
    }

    /**
     * Escaped display name with a small i whose tooltip tells what the index stands for and, for an own name, the
     * Elster name; the Elster name alone if nothing is known
     */
    static String mitBeschreibung(short index, String name) {
        String anzeige = IndexBeschreibung.anzeigename(index, name);
        String beschreibung = IndexBeschreibung.beschreibung(index);
        // An own name replaces the Elster name on the page, the tooltip keeps the Elster name for looking it up
        if (!anzeige.equals(name)) {
            beschreibung = (beschreibung == null ? "" : beschreibung + " ") + "Elster-Name: " + name + ".";
        }
        return mitHinweis(anzeige, beschreibung);
    }

    /**
     * Escaped name with a small i whose tooltip shows the description, the name alone without a description
     */
    static String mitHinweis(String name, String beschreibung) {
        if (beschreibung == null) {
            return text(name);
        }
        return "<span class=\"calc\" data-tip=\"" + text(beschreibung) + "\" tabindex=\"0\" aria-label=\""
                + text(name + ": " + beschreibung) + "\">" + text(name) + " <span class=\"info\" aria-hidden=\"true\">i"
                + "</span></span>";
    }

    /**
     * CAN id of the receiver from the first two data bytes, -1 if the message is too short
     */
    static int empfaenger(byte[] daten) {
        return daten.length < 2 ? -1 : ElsterMessage.convertReceiverID(daten);
    }

    static String empfaengerName(int id) {
        return (id & 0x7F) == RUNDRUF ? String.format("0x%03X Rundruf", id) : CanStatistik.knotenName(id);
    }

    /**
     * The newest messages from or to the node, at most anzahl
     */
    static List<CanStatistik.Rahmen> auswahl(List<CanStatistik.Rahmen> rahmen, Integer knoten, int anzahl) {
        return rahmen.stream().filter(r -> knoten == null || r.id() == knoten || empfaenger(r.daten()) == knoten)
                .limit(anzahl).toList();
    }

    /**
     * CAN id from the address line, e.g. 0x700 or 700
     *
     * @throws IllegalArgumentException if it is no CAN id
     */
    public static Integer knoten(String wert) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        String hex = wert.trim().toLowerCase(Locale.ROOT).replaceFirst("^0x", "");
        if (!hex.matches("[0-9a-f]{1,3}")) {
            throw new IllegalArgumentException("Keine CAN-ID: " + wert);
        }
        return Integer.parseInt(hex, 16);
    }

    public static int anzahl(int wert) {
        return Math.max(1, Math.min(CanStatistik.RAHMEN_MAX, wert));
    }

    private static String link(Integer knoten, int anzahl) {
        return "canbus?" + (knoten == null ? "" : "knoten=" + String.format("0x%03X", knoten) + "&amp;")
                + "anzahl=" + anzahl;
    }

    /**
     * Kind of the Elster message from the low nibble of the first byte
     */
    /**
     * Kind of the message with a small i that explains it
     */
    static String artMitBeschreibung(byte[] daten) {
        if (daten.length == 0) {
            return "";
        }
        return mitHinweis(art(daten), switch (daten[0] & 0x0F) {
            case 0 -> "Ein Gerät setzt beim Empfänger einen Wert, ohne gefragt zu werden. So verteilen die Geräte "
                    + "laufend ihre Werte: Der Manager schreibt der Wärmepumpe alle 10 s Anforderung und "
                    + "Regler-Integral, der Wärmepumpenmanager schickt die Außentemperatur als Rundruf an alle. "
                    + "Meist folgt keine Antwort, manche Empfänger wie 0x700 bestätigen mit einer Antwort, die den "
                    + "übernommenen Wert enthält. Der stbl-service schreibt nie, er liest nur.";
            case 1 -> "Ein Gerät fragt beim Empfänger einen Wert ab, die Nachricht selbst enthält keinen Wert. Der "
                    + "Empfänger schickt eine Antwort zurück, 0x8000 heißt dort „nicht verfügbar“.";
            case 2 -> "Antwort auf eine Anfrage mit dem aktuellen Wert, sie geht an das fragende Gerät zurück. "
                    + "Antworten an andere Geräte sind nur mit „Alle Nachrichten empfangen“ zu sehen.";
            case 3 -> "Bestätigung eines Empfangs ohne Wert.";
            case 4 -> "Antwort auf einen Schreibbefehl, bestätigt den übernommenen Wert.";
            case 6 -> "Systemnachricht zur Teilnehmerprüfung (0xFFFE): Ein Gerät meldet sich am Bus, danach gleichen "
                    + "Manager und Wärmepumpe Stammdaten, Datum und Uhrzeit ab.";
            case 7 -> "Antwort auf eine Systemnachricht, das angesprochene Gerät bestätigt, dass es da ist.";
            default -> null;
        });
    }

    static String art(byte[] daten) {
        if (daten.length == 0) {
            return "";
        }
        return switch (daten[0] & 0x0F) {
            case 0 -> "Schreiben";
            case 1 -> "Anfrage";
            case 2 -> "Antwort";
            case 3 -> "Bestätigung";
            case 4 -> "Schreib-Antwort";
            // 6 and 7: system messages, e.g. the exchange on 0xFFFE before manager and heat pump sync their data
            case 6 -> "System";
            case 7 -> "System-Antwort";
            default -> String.format("Art %X", daten[0] & 0x0F);
        };
    }

    private static String tabelle(List<String> kopf, List<List<String>> zeilen) {
        StringBuilder html = new StringBuilder("<div class=\"tbl\"><table>");
        if (kopf != null) {
            html.append("<thead><tr>");
            kopf.forEach(k -> html.append("<th>").append(text(k)).append("</th>"));
            html.append("</tr></thead>");
        }
        html.append("<tbody>");
        for (List<String> zeile : zeilen) {
            html.append("<tr>");
            // The cells are escaped by the callers, some carry markup
            zeile.forEach(z -> html.append("<td>").append(z).append("</td>"));
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    static String alter(Instant zeit, Instant jetzt) {
        return zeit == null ? "–" : "vor " + sekunden(zeit, jetzt) + " (" + UHRZEIT.format(zeit).substring(0, 8) + ")";
    }

    static String sekunden(Instant zeit, Instant jetzt) {
        long s = Math.max(0, Duration.between(zeit, jetzt).toSeconds());
        if (s < 120) {
            return s + " s";
        }
        if (s < 7200) {
            return s / 60 + " min";
        }
        return s / 3600 + " h";
    }

    private static String hex(short index) {
        return String.format("0x%04X", index & 0xFFFF);
    }

    private static String zeit(Instant zeit) {
        return zeit == null ? "–" : ZEIT.format(zeit);
    }

    private static String wert(String wert) {
        return wert == null ? "–" : wert;
    }

    private static String zahl(double wert, int stellen) {
        return String.format(Locale.GERMANY, "%." + stellen + "f", wert);
    }

    static String text(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static final String CSS = """
            .nav { color: var(--accent); text-decoration: none; font-weight: 600; }
            .nav:hover, .nav:focus { text-decoration: underline; }
            .meldung { margin: 0 0 12px; padding: 10px 14px; border-radius: 12px; font-weight: 600;
              background: var(--card); border: 1px solid var(--line); }
            .meldung.ok { border-color: var(--on); }
            .meldung.fehler { border-color: var(--stale); color: var(--stale); }
            .tbl { overflow-x: auto; }
            .tbl table { font-size: 13px; }
            .tbl td { white-space: nowrap; }
            .tbl code { font-size: 12px; color: var(--muted); }
            .alt { color: var(--stale); font-weight: 600; }
            .auswahl { margin: 0 0 8px; font-size: 13px; color: var(--muted); }
            .aus { color: var(--muted); }
            .info { display: inline-grid; place-items: center; width: 15px; height: 15px; border-radius: 50%;
              border: 1px solid var(--accent); color: var(--accent); font-size: 10px; font-weight: 700;
              font-style: italic; text-decoration: none; vertical-align: 1px; }
            .info:hover, .info:focus-visible { background: var(--accent); color: var(--card); }
            .calc { position: relative; cursor: help; outline: none; }
            .calc:hover::after, .calc:focus::after { content: attr(data-tip); position: absolute; left: 0;
              top: calc(100% + 4px); z-index: 10; width: max-content; max-width: min(340px, 80vw);
              white-space: normal; padding: 6px 10px; border-radius: 8px; border: 1px solid var(--line);
              background: var(--card); color: var(--text); font-size: 12px; font-weight: 400; line-height: 1.4;
              box-shadow: 0 4px 14px rgb(0 0 0 / 0.15); }
            .calc:hover .info, .calc:focus .info { background: var(--accent); color: var(--card); }
            .tbl { padding-bottom: 120px; margin-bottom: -120px; }
            .nav.gewaehlt { padding: 1px 6px; border-radius: 6px; background: var(--on-bg); color: var(--text); }
            """;
}
