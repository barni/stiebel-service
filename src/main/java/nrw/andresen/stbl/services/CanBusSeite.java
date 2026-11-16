package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterMessage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                .append("<meta http-equiv=\"refresh\" content=\"").append(NEU_LADEN_S).append("\">")
                .append("<title>Wärmepumpe · CAN-Bus</title><style>").append(StatusPage.CSS).append(CSS)
                .append("</style></head><body><main><header><div><h1>CAN-Bus</h1><p class=\"sub\">USBtin-Adapter ")
                .append("und Nachrichten, lädt alle ").append(NEU_LADEN_S).append(" s neu</p></div>")
                .append("<nav><a class=\"nav\" href=\"status\">← Status</a> · ")
                .append("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a></nav></header>");
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
            werte.add(List.of(grau + hex(a.index()) + ende, grau + text(a.name()) + ende,
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

    static List<String> rahmenzeile(CanStatistik.Rahmen r) {
        ElsterMessage m = new ElsterMessage(r.id(), r.daten());
        String art = art(r.daten());
        boolean mitIndex = r.daten().length >= 3;
        String index = mitIndex ? hex(m.getElsterIndex().getIndex()) + " " + m.getElsterIndex().getName() : "";
        int typ = r.daten().length == 0 ? -1 : r.daten()[0] & 0x0F;
        // Answers and writes carry a value, requests do not
        String wert = typ == 0 || typ == 2 ? m.getValue() : "";
        if (wert.equals("PAYLOAD_CORRUPT")) {
            wert = "";
        }
        int an = empfaenger(r.daten());
        return List.of(UHRZEIT.format(r.zeit()), r.gesendet() ? "→" : "←", text(CanStatistik.knotenName(r.id())),
                an < 0 ? "" : text(empfaengerName(an)), art, text(index), text(wert),
                "<code>" + ElsterMessage.bytesToHex(r.daten()) + "</code>");
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
            case 6 -> "Rundruf";
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
            .nav.gewaehlt { padding: 1px 6px; border-radius: 6px; background: var(--on-bg); color: var(--text); }
            """;
}
