package nrw.andresen.stbl;

import nrw.andresen.stbl.services.StatusPage;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatusPageTest {

    private static final Instant NOW = Instant.parse("2026-09-13T17:48:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Berlin"));

    private static ValueContainer<Double> value(double value) {
        return new ValueContainer<>(value, NOW.minusSeconds(15));
    }

    private static ValueContainer<Boolean> state(boolean value) {
        return new ValueContainer<>(value, NOW.minusSeconds(15));
    }

    private String render() throws Exception {
        String html = new StatusPage(clock)
                .badge("Verdichter läuft", "Verdichter aus", () -> state(true))
                .kpi("Außentemperatur", "°C", 1, () -> value(18.2))
                .kpi("Vorlauf", "°C", 1, "VorlaufIstTemp", () -> value(29.8))
                .kpi("Rücklauf", "°C", 1, () -> value(23.1))
                .kpi("Verdichter", "Hz", 0, () -> value(24))
                .kpi("Inverter", "VA", 0, () -> value(595.89))
                .card("Betrieb")
                .pill("Verdichter", () -> state(true))
                .pill("Pufferladepumpe", () -> state(true))
                .pill("Warmwasserladepumpe", () -> state(false))
                .pill("DHC 1", () -> state(false))
                .pill("DHC 2", () -> state(false))
                .pill("EVU-Sperre", () -> state(false))
                .pill("Abtauung (berechnet)", () -> state(false))
                .formula("EXV & Verdampfer")
                .row("Laufzeit DHC 1", "h", 0, () -> value(0))
                .row("Laufzeit DHC 2", "h", 0, () -> value(0))
                .row("Laufzeit DHC 1+2", "h", 0, () -> value(2))
                .card("Heizkreis")
                .row("Vorlauf", "°C", 1, () -> value(29.8))
                .row("Rücklauf", "°C", 1, () -> value(23.1))
                .row("Spreizung", "K", 1, () -> value(6.7))
                .formula("Vorlauf − Rücklauf")
                .row("Volumenstrom", "l/min", 1, () -> value(9.0))
                .row("Wärmeleistung (berechnet)", "kW", 2, "WaermeleistungBerechnet", () -> value(3.39))
                .formula("Volumenstrom × Spreizung")
                .row("Heizungsdruck", "bar", 2, () -> value(1.56))
                .card("Kältekreis")
                .row("Verdichter-Drehzahl", "Hz", 0, () -> value(24))
                .row("Hochdruck", "bar", 2, "Hochdruck", () -> value(18.44))
                .row("Niederdruck", "bar", 2, () -> value(11.37))
                .row("Heißgas", "°C", 1, () -> value(42.7))
                .row("Verdichter-Eintritt", "°C", 1, () -> value(18.0))
                .row("Verdampfer", "°C", 1, () -> value(12.4))
                .row("Ölsumpf", "°C", 1, () -> new ValueContainer<>(37.8, NOW.minusSeconds(600)))
                .card("Inverter")
                .row("Spannung", "V", 1, () -> value(220.7))
                .row("Strom", "A", 1, () -> value(2.7))
                .row("Scheinleistung", "VA", 0, () -> value(595.89))
                .note("Spannung × Strom ohne Leistungsfaktor. Die Wirkleistung liegt laut Smartmeter bei etwa "
                        + "80 % bei 600 VA und erreicht ab 1000 VA die Scheinleistung.")
                .card("Energie Heizen")
                .row("Stromaufnahme", "MWh", 3, () -> value(20.458419))
                .row("Wärmeerzeugung", "MWh", 3, () -> value(111.871129))
                .row("Zusatzheizung", "MWh", 3, () -> value(0.041))
                .row("Effizienz gesamt (Zähler)", "", 2, () -> value(5.46822))
                .row("Effizienz gesamt (korrigiert)", "", 2, () -> value(4.56))
                .row("Arbeitszahl aktuell", "", 1, () -> value(6.6))
                .note("Zähler der Wärmepumpe seit Inbetriebnahme. Die aktuelle Arbeitszahl ist geschätzt: "
                        + "berechnete Wärmeleistung geteilt durch die aus der Scheinleistung geschätzte Wirkleistung.")
                .render();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "status-preview.html"), html);
        return html;
    }

    @Test
    public void testGermanNumberFormat() throws Exception {
        String html = render();
        assertTrue(html.contains(">111,871<"));
        assertTrue(html.contains(">596<"));
        assertTrue(html.contains(">1,56<"));
    }

    @Test
    public void testOutdatedValues() throws Exception {
        String html = render();
        // Oelsumpf is 10 minutes old, the other values are current
        assertTrue(html.contains("seit 19:38"));
        assertFalse(html.contains("seit 19:47"));
    }

    @Test
    public void testOutdatedValuesWithOwnMaxAge() {
        // Settings are requested every hour, 30 minutes old is still current
        String html = new StatusPage(clock)
                .card("Einstellungen")
                .row("Wärmebedarf", "kW", 1, () -> new ValueContainer<>(8.5, NOW.minusSeconds(1800)),
                        Duration.ofMinutes(130))
                .row("Soll-Spreizung", "K", 1, () -> new ValueContainer<>(5.0, NOW.minusSeconds(3 * 3600)),
                        Duration.ofMinutes(130))
                .render();
        assertFalse(html.contains("seit 19:18"));
        assertTrue(html.contains("seit 16:48"));
    }

    @Test
    public void testMissingValues() {
        String html = new StatusPage(clock)
                .card("Heizkreis")
                .row("Volumenstrom", "l/min", 1, () -> {
                    throw new Exception("NO_VALUES_RECEIVED");
                })
                .render();
        assertTrue(html.contains("keine Daten"));
        assertFalse(html.contains("l/min"));
    }

    @Test
    public void testHeader() throws Exception {
        String html = render();
        assertTrue(html.contains("Stand 13.09.26, 19:48:00"));
        assertTrue(html.contains("badge on"));
    }

    @Test
    public void testHistoryLinks() throws Exception {
        String html = render();
        assertTrue(html.contains("data-series=\"VorlaufIstTemp\" data-label=\"Vorlauf\" data-unit=\"°C\" "
                + "data-decimals=\"1\""));
        assertTrue(html.contains("data-series=\"Hochdruck\""));
        // Rows without a stored series are not clickable
        assertFalse(html.contains("data-series=\"Spreizung\""));
        assertTrue(html.contains("<dialog id=\"verlauf\""));
        assertTrue(html.contains("fetch('history/'"));
        // Range selection in the dialog, the same ranges as in StblController
        for (String range : new String[]{"6h", "24h", "7d", "30d", "1y"}) {
            assertTrue(html.contains("data-range=\"" + range + "\""));
        }
        // The page is reloaded by the script, not while the dialog is open
        assertTrue(html.contains("<noscript><meta http-equiv=\"refresh\" content=\"20\"></noscript>"));
    }

    @Test
    public void testWithoutHistory() {
        String html = new StatusPage(clock)
                .card("Heizkreis")
                .row("Spreizung", "K", 1, () -> value(6.7))
                .render();
        assertFalse(html.contains("data-series"));
        assertFalse(html.contains("<dialog"));
        assertTrue(html.contains("<meta http-equiv=\"refresh\" content=\"20\">"));
    }

    @Test
    public void testFormula() throws Exception {
        String html = render();
        // Row without course: tooltip on the label, focusable for a tap on a phone
        assertTrue(html.contains("<span class=\"calc\" data-tip=\"Berechnung: Vorlauf − Rücklauf\" tabindex=\"0\">"
                + "Spreizung<span class=\"info\" aria-hidden=\"true\">ⓘ</span></span>"));
        // Row with course: the dialog shows the formula, the label is not focusable on its own
        assertTrue(html.contains("data-formula=\"Volumenstrom × Spreizung\" role=\"button\""));
        assertTrue(html.contains("<span class=\"calc\" data-tip=\"Berechnung: Volumenstrom × Spreizung\">"));
        // Pill, the formula is escaped
        assertTrue(html.contains("data-tip=\"Berechnung: EXV &amp; Verdampfer\" tabindex=\"0\">Abtauung (berechnet)"));
        assertTrue(html.contains("id=\"v-formula\""));
        assertTrue(html.contains(".calc:hover::after"));
    }

    @Test
    public void testInfo() {
        String html = new StatusPage(clock)
                .kpi("Vorlauf", "°C", 1, "VorlaufIstTemp", () -> value(29.8))
                .info("Temperatur zum Heizkreis")
                .card("Heizkreis")
                .row("Spreizung (berechnet)", "K", 1, () -> value(6.7))
                .info("Unterschied Vor- und Rücklauf")
                .formula("Vorlauf − Rücklauf")
                .text("Seriennummer", "A1")
                .info("Des USBtin")
                .render();
        // Explanation only
        assertTrue(html.contains("<span class=\"calc\" data-tip=\"Temperatur zum Heizkreis\">Vorlauf"));
        assertTrue(html.contains("data-info=\"Temperatur zum Heizkreis\" role=\"button\""));
        // Explanation and formula in two lines
        assertTrue(html.contains("data-tip=\"Unterschied Vor- und Rücklauf&#10;Berechnung: Vorlauf − Rücklauf\""));
        assertTrue(html.contains("<span class=\"calc\" data-tip=\"Des USBtin\" tabindex=\"0\">Seriennummer"));
        assertTrue(html.contains("id=\"v-info\""));
        assertTrue(html.contains("white-space: pre-line"));
    }

    @Test
    public void testFormulaWithoutValue() {
        assertThrows(IllegalStateException.class, () -> new StatusPage(clock).formula("x"));
    }

    @Test
    public void testText() {
        String html = new StatusPage(clock)
                .card("USB-Adapter")
                .text("Firmware / Hardware", "v1.9 / <v1.0>")
                .text("Seriennummer", null)
                .render();
        assertTrue(html.contains("<span class=\"label\">Firmware / Hardware</span>"
                + "<span class=\"value\">v1.9 / &lt;v1.0&gt;</span>"));
        assertTrue(html.contains("<span class=\"label\">Seriennummer</span><span class=\"value\">–</span>"));
    }

    @Test
    public void testTable() {
        String html = new StatusPage()
                .card("Vergleich")
                .table(java.util.List.of("Außen", "kW"), java.util.List.of(java.util.List.of("0 bis 5 °C", "<1>")))
                .render();
        assertTrue(html.contains("<table><thead><tr><th>Außen</th><th>kW</th></tr></thead>"));
        assertTrue(html.contains("<td data-label=\"Außen\">0 bis 5 °C</td><td data-label=\"kW\">&lt;1&gt;</td>"));
        assertTrue(html.contains("<div class=\"tbl\">"));

        String breit = new StatusPage()
                .card("Vergleich")
                .breit()
                .table(java.util.List.of("Außen"), java.util.List.of(java.util.List.of("0 bis 5 °C")), true)
                .render();
        assertTrue(breit.contains("<article class=\"card breit\">"));
        assertTrue(breit.contains("<div class=\"tbl stapeln\">"));

        String zwei = new StatusPage().bereich("Auswertung").zweiSpalten().card("Energie").render();
        assertTrue(zwei.contains("<section class=\"cards zwei\">"));
    }

    @Test
    public void testBereiche() {
        String html = new StatusPage()
                .bereich("Übersicht")
                .card("Fehlerliste")
                .text("Letzter Eintrag", "14.05.26 18:53")
                .details("Alle 20 Einträge")
                .text("14.05.26 18:53", "INV H ROTORVEKTOR")
                .detailsEnde()
                .bereichKlappbar("Anlage", "Betrieb · Kältekreis", false)
                .card("Kältekreis")
                .bereichKlappbar("System", "Fehler: InfluxDB lesen", true)
                .card("Dienst")
                .render();
        assertTrue(html.contains("<h2 class=\"bereich-titel\">Übersicht</h2>"));
        assertTrue(html.contains("<details class=\"mehr\"><summary>Alle 20 Einträge</summary>"));
        assertTrue(html.contains("<details class=\"bereich\" data-id=\"Anlage\"><summary><span>Anlage</span>"
                + "<span class=\"zusammenfassung\">Betrieb · Kältekreis</span></summary>"));
        // An area with a problem is opened and stays open
        assertTrue(html.contains("<details class=\"bereich\" data-id=\"System\" open data-erzwingen>"));
        assertTrue(html.contains("localStorage"));
    }

    @Test
    public void testHinweiseUndErsatz() throws Exception {
        Instant jetzt = Instant.now();
        String html = new StatusPage()
                .hinweis("Heizungsdruck niedrig", true)
                .hinweis("keine Warnung", false)
                .badgeWennAn("Abtauung", () -> new ValueContainer<>(true, jetzt))
                .badgeWennAn("EVU-Sperre", () -> new ValueContainer<>(false, jetzt))
                .badgeWennAn("Heizstab", () -> {
                    throw new Exception("NO_VALUES_RECEIVED");
                })
                .kpi("Arbeitszahl", "", 1, () -> {
                    throw new Exception("VERDICHTER_AUS");
                })
                .ersatz("aus")
                .render();
        assertTrue(html.contains("<span class=\"badge warn\"><span class=\"dot\"></span>Heizungsdruck niedrig"));
        assertTrue(html.contains("<span class=\"badge ok\"><span class=\"dot\"></span>keine Warnung"));
        assertTrue(html.contains(">Abtauung</span>"));
        assertFalse(html.contains("EVU-Sperre"));
        assertFalse(html.contains("Heizstab"));
        assertTrue(html.contains("<span class=\"missing\">aus</span>"));
    }
}
