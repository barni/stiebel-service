package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.ElsterMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CanBusSeiteTest {

    private static final Instant JETZT = Instant.parse("2026-11-15T12:00:00Z");

    /**
     * Answer of the heat pump 0x500 to this service for index 0x000C with the value 123
     */
    private static byte[] antwort() {
        byte[] an = ElsterMessage.convertReceiverID(CanBus.CAN_SENDER_ID);
        return new byte[]{(byte) ((an[0] & 0xF0) | 2), an[1], (byte) 0xFA, 0x00, 0x0C, 0x00, 0x7B};
    }

    private static CanBus.Verbindung verbindung(boolean verbunden) {
        return new CanBus.Verbindung(verbunden, "/dev/ttyACM0", 20000, "1.8", "1.0", "A1B2", JETZT.minusSeconds(3600),
                1, JETZT.minusSeconds(3600), JETZT.minusSeconds(2), JETZT.minusSeconds(2),
                new CanStatistik.Minute(JETZT.minusSeconds(30), 60, 60, 60, 1, 100, 4.2),
                List.of(new CanStatistik.KnotenStatus(0x500, "0x500 Wärmepumpe", 60, JETZT.minusSeconds(2))),
                1, JETZT.minusSeconds(600), "Fehler im Empfangs-Thread: <x>", true);
    }

    private static CanBus.Verbindung ohneAlle() {
        CanBus.Verbindung v = verbindung(true);
        return new CanBus.Verbindung(v.verbunden(), v.port(), v.bitrate(), v.firmware(), v.hardware(),
                v.seriennummer(), v.verbundenSeit(), v.usbNeustarts(), v.letzterNeustart(), v.letzteNachricht(),
                v.letzteAntwort(), v.letzteMinute(), v.knoten(), v.adapterFehler(), v.letzterFehler(),
                v.letzterFehlerText(), false);
    }

    @Test
    public void testMitgelesenAus() {
        List<CanBus.Abfrage> abfragen = List.of(
                new CanBus.Abfrage((short) 0x5F, "SPEICHERBEDARF", null, null, null, null, true),
                new CanBus.Abfrage((short) 0x0C, "AUSSENTEMP", "8,2", JETZT.minusSeconds(5), JETZT.minusSeconds(5)));
        String html = CanBusSeite.render(ohneAlle(), abfragen, List.of(), JETZT);
        // Grey, at the end, with the small i to the setting
        assertTrue(html.indexOf("AUSSENTEMP") < html.indexOf("SPEICHERBEDARF"));
        assertTrue(html.contains("<span class=\"aus\">aus</span> " + CanBusSeite.HINWEIS_KONFIG));
        assertTrue(html.contains("href=\"konfiguration#can-logall\""));
        assertTrue(html.contains("gerade aus."));
        // With receiving all the value is read along as before
        html = CanBusSeite.render(verbindung(true), abfragen, List.of(), JETZT);
        assertTrue(html.contains("<td>mitgelesen</td>"));
        assertFalse(html.contains(CanBusSeite.HINWEIS_KONFIG));
    }

    @Test
    public void testOffen() {
        // Answered after the last request
        assertFalse(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minusSeconds(60), JETZT.minusSeconds(59))
                .offen(JETZT));
        // The device still has time to answer
        assertFalse(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minusSeconds(10), JETZT.minusSeconds(30))
                .offen(JETZT));
        // An hourly setting answered 50 minutes ago is fine
        assertFalse(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minus(Duration.ofMinutes(50)),
                JETZT.minus(Duration.ofMinutes(50)).plusMillis(20)).offen(JETZT));
        // The last request was not answered, or never anything
        assertTrue(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minusSeconds(40), JETZT.minusSeconds(60))
                .offen(JETZT));
        assertTrue(new CanBus.Abfrage((short) 1, "A", null, JETZT.minusSeconds(40), null).offen(JETZT));
        // Requested every 20 s and only answered with 0x8000: the last request is young, the one before counts
        assertTrue(new CanBus.Abfrage((short) 1, "A", null, JETZT.minusSeconds(10), null, JETZT.minusSeconds(30),
                false).offen(JETZT));
        assertTrue(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minusSeconds(1), JETZT.minusSeconds(60),
                JETZT.minusSeconds(21), false).offen(JETZT));
        // The answer to the request before came, the one to the last request is still on its way
        assertFalse(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minusSeconds(1), JETZT.minusSeconds(20),
                JETZT.minusSeconds(21), false).offen(JETZT));
        assertFalse(new CanBus.Abfrage((short) 1, "A", "1", JETZT.minusSeconds(10), JETZT.minusSeconds(29),
                JETZT.minusSeconds(30), false).offen(JETZT));
        // A value read along is never open
        assertFalse(new CanBus.Abfrage((short) 1, "A", null, null, null, null, true).offen(JETZT));
    }

    @Test
    public void testRahmenzeile() {
        List<String> zeile = CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, false, 0x500, antwort()));
        assertEquals("←", zeile.get(1));
        assertEquals("0x500 Wärmepumpe", zeile.get(2));
        assertEquals("0x681 stbl-service", zeile.get(3));
        assertTrue(zeile.get(4).startsWith("<span class=\"calc\"") && zeile.get(4).contains(">Antwort <span"));
        assertTrue(zeile.get(5).startsWith("0x000C "));
        assertTrue(zeile.get(7).contains(ElsterMessage.bytesToHex(antwort())));

        byte[] anfrage = ElsterMessage.readRequest(CanBus.CAN_SENDER_ID, 0x500, (short) 0x000C).getMessage().getData();
        zeile = CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, true, CanBus.CAN_SENDER_ID, anfrage));
        assertEquals("→", zeile.get(1));
        assertEquals("0x500 Wärmepumpe", zeile.get(3));
        assertTrue(zeile.get(4).contains(">Anfrage <span"));
        assertEquals("", zeile.get(6));
        // Too short for an index
        assertEquals("", CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, false, 0x180, new byte[]{0x06}))
                .get(5));
    }

    @Test
    public void testSatz() {
        List<CanBus.Abfrage> abfragen = List.of(
                new CanBus.Abfrage((short) 1, "A", null, JETZT.minusSeconds(40), null),
                new CanBus.Abfrage((short) 2, "B", "5", JETZT.minusSeconds(40), JETZT.minusSeconds(39)));
        assertEquals("Adapter verbunden, letzte Antwort vor 2 s, 1 Wert ohne Antwort auf die letzte Anfrage.",
                CanBusSeite.satz(verbindung(true), abfragen, JETZT));
        assertEquals("Adapter verbunden, letzte Antwort vor 2 s, alle Anfragen beantwortet.",
                CanBusSeite.satz(verbindung(true), abfragen.subList(1, 2), JETZT));
        assertTrue(CanBusSeite.satz(verbindung(false), abfragen, JETZT).startsWith("Adapter nicht verbunden"));
    }

    @Test
    public void testRender() {
        String html = CanBusSeite.render(verbindung(true),
                List.of(new CanBus.Abfrage((short) 0x0C, "AUSSENTEMP", null, JETZT.minusSeconds(40), null)),
                List.of(new CanStatistik.Rahmen(JETZT, false, 0x500, antwort())), JETZT);
        assertTrue(html.contains("<h1>CAN-Bus</h1>"));
        assertTrue(html.contains("href=\"konfiguration\""));
        assertTrue(html.contains("http-equiv=\"refresh\""));
        // The name carries a small i with what the value stands for
        assertTrue(html.contains("<td>0x000C</td><td><span class=\"calc\" data-tip=\"Außentemperatur in °C."));
        assertTrue(html.contains(">AUSSENTEMP <span class=\"info\" aria-hidden=\"true\">i</span></span></td>"));
        assertTrue(CanBusSeite.render(verbindung(true), List.of(new CanBus.Abfrage((short) 0x5F, "SPEICHERBEDARF",
                "512", null, JETZT, null, true)), List.of(), JETZT).contains("<td>512</td><td>mitgelesen</td>"));
        assertTrue(html.contains("class=\"alt\""));
        assertTrue(html.contains("4,2 %"));
        // Error text is escaped
        assertTrue(html.contains("Fehler im Empfangs-Thread: &lt;x&gt;"));
        assertFalse(html.contains("<x>"));
    }

    @Test
    public void testSekunden() {
        assertEquals("5 s", CanBusSeite.sekunden(JETZT.minusSeconds(5), JETZT));
        assertEquals("3 min", CanBusSeite.sekunden(JETZT.minusSeconds(200), JETZT));
        assertEquals("2 h", CanBusSeite.sekunden(JETZT.minusSeconds(8000), JETZT));
        assertEquals("–", CanBusSeite.alter(null, JETZT));
    }

    @Test
    public void testKonfigurationVerlinkt() {
        assertTrue(KonfigurationSeite.render(new Konfiguration.Stand(List.of(), java.nio.file.Path.of("application.properties"), null), java.util.Map.of(),
                java.util.Map.of(), null, null, null).contains("href=\"canbus\">CAN-Bus</a>"));
    }

    private static byte[] hex(String hex) {
        return ElsterMessage.hexStringToByteArray(hex);
    }

    /**
     * Messages from the bus on 2026-11-15
     */
    @Test
    public void testSchreibenUndRundruf() {
        // The boiler sends the outdoor temperature 10.2 °C to all devices of group 0x500
        List<String> zeile = CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, false, 0x180,
                hex("A0790C00660000")));
        assertEquals("0x579 Rundruf", zeile.get(3));
        assertTrue(zeile.get(4).contains(">Schreiben <span"));
        assertTrue(zeile.get(5).startsWith("0x000C "));
        assertTrue(zeile.get(6).matches("10[,.]2"));
        // Write of the manager to the heat pump with a long index
        zeile = CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, false, 0x480, hex("A000FA02CA00F8")));
        assertEquals("0x500 Wärmepumpe", zeile.get(3));
        assertEquals("248", zeile.get(6));
        // A request has no value
        assertEquals("", CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, false, 0x480,
                hex("A100FA01D60000"))).get(6));
    }

    @Test
    public void testAuswahl() {
        List<CanStatistik.Rahmen> rahmen = List.of(
                new CanStatistik.Rahmen(JETZT, false, 0x700, hex("9200FAFE1D0002")),
                new CanStatistik.Rahmen(JETZT, false, 0x480, hex("E000FAFE1D0000")),
                new CanStatistik.Rahmen(JETZT, false, 0x180, hex("A0790C00660000")),
                new CanStatistik.Rahmen(JETZT, false, 0x480, hex("A100FA01D60000")));
        // From 0x700 and to 0x700
        assertEquals(rahmen.subList(0, 2), CanBusSeite.auswahl(rahmen, 0x700, 10));
        assertEquals(rahmen.subList(0, 3), CanBusSeite.auswahl(rahmen, null, 3));
        assertEquals(List.of(rahmen.get(2)), CanBusSeite.auswahl(rahmen, 0x579, 10));

        String html = CanBusSeite.render(verbindung(true), List.of(), rahmen, 0x700, 100, JETZT);
        assertTrue(html.contains("Letzte Nachrichten von und an 0x700"));
        assertTrue(html.contains("href=\"canbus?anzahl=100\">Filter aufheben</a>"));
        assertTrue(html.contains("Filter: 0x700 · "));
        assertTrue(html.contains("2 von 4 gespeicherten Nachrichten"));
        assertTrue(html.contains("href=\"canbus?knoten=0x500&amp;anzahl=100\">0x500 Wärmepumpe</a>"));
        // The chosen node is marked and a second click removes the filter
        html = CanBusSeite.render(verbindung(true), List.of(), rahmen, 0x500, 100, JETZT);
        assertTrue(html.contains("<a class=\"nav gewaehlt\" href=\"canbus?anzahl=100\" aria-current=\"true\">"
                + "0x500 Wärmepumpe ✕</a>"));
    }

    @Test
    public void testParameter() {
        assertEquals(0x700, CanBusSeite.knoten("0x700"));
        assertEquals(0x700, CanBusSeite.knoten(" 700 "));
        assertEquals(null, CanBusSeite.knoten(""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> CanBusSeite.knoten("<script>"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> CanBusSeite.knoten("0x1000"));
        assertEquals(1, CanBusSeite.anzahl(-5));
        assertEquals(CanStatistik.RAHMEN_MAX, CanBusSeite.anzahl(1_000_000));
    }

    @Test
    public void testMitBeschreibung() {
        assertTrue(CanBusSeite.mitBeschreibung((short) 0xFE07, "INFOBLOCK_6").contains("Verdichterdrehzahl"));
        // Fields of the fault list share one description
        assertTrue(CanBusSeite.mitBeschreibung((short) 0x0B8B, "FEHLERFELD_139").contains("Fehlerliste"));
        // Unknown: the escaped name only
        assertEquals("UNKOWN_&lt;x&gt;", CanBusSeite.mitBeschreibung((short) 0x7777, "UNKOWN_<x>"));
    }

    @Test
    public void testArtSystem() {
        // Exchange on 0xFFFE before the sync of manager and heat pump, 2026-11-16 10:41:58
        assertEquals("System", CanBusSeite.art(hex("9600FE01000000")));
        assertEquals("System-Antwort", CanBusSeite.art(hex("A700FE01000000")));
        // With an i explaining the kind, a write mentions that this service never writes
        assertTrue(CanBusSeite.artMitBeschreibung(hex("A0005F02000000")).contains("Schreiben <span class=\"info\""));
        assertTrue(CanBusSeite.artMitBeschreibung(hex("A0005F02000000")).contains("schreibt nie"));
        assertTrue(CanBusSeite.artMitBeschreibung(hex("A1005F00000000")).contains("Anfrage <span class=\"info\""));
        assertEquals("", CanBusSeite.artMitBeschreibung(new byte[0]));
        assertTrue(CanBusSeite.mitBeschreibung((short) 0x07A2, "STUETZSTELLE_HD2").contains("43,00 bar"));
    }
}
