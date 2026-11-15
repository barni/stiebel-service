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
                1, JETZT.minusSeconds(600), "Fehler im Empfangs-Thread: <x>");
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
    }

    @Test
    public void testRahmenzeile() {
        List<String> zeile = CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, false, 0x500, antwort()));
        assertEquals("←", zeile.get(1));
        assertEquals("0x500 Wärmepumpe", zeile.get(2));
        assertEquals("0x681", zeile.get(3));
        assertEquals("Antwort", zeile.get(4));
        assertTrue(zeile.get(5).startsWith("0x000C "));
        assertTrue(zeile.get(7).contains(ElsterMessage.bytesToHex(antwort())));

        byte[] anfrage = ElsterMessage.readRequest(CanBus.CAN_SENDER_ID, 0x500, (short) 0x000C).getMessage().getData();
        zeile = CanBusSeite.rahmenzeile(new CanStatistik.Rahmen(JETZT, true, CanBus.CAN_SENDER_ID, anfrage));
        assertEquals("→", zeile.get(1));
        assertEquals("0x500 Wärmepumpe", zeile.get(3));
        assertEquals("Anfrage", zeile.get(4));
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
        assertTrue(html.contains("<td>0x000C</td><td>AUSSENTEMP</td>"));
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
}
