package nrw.andresen.stbl.services.can;

import de.fischl.usbtin.CANMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CanStatistikTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");
    private static final int BITRATE = 20000;

    @Test
    public void testMinute() {
        CanStatistik statistik = new CanStatistik();
        // The first close only sets the start
        statistik.minuteAbschliessen(START, BITRATE);
        assertNull(statistik.getLetzteMinute());

        // 40 requests, 38 answers, 2 of them "not available", 20 messages of the manager to the heat pump
        for (int i = 0; i < 40; i++) {
            statistik.gesendet(7);
        }
        for (int i = 0; i < 38; i++) {
            statistik.empfangen(0x500, 7, START.plusSeconds(i));
            statistik.antwort(i < 2, START.plusSeconds(i));
        }
        for (int i = 0; i < 20; i++) {
            statistik.empfangen(0x480, 7, START.plusSeconds(30 + i));
        }
        statistik.minuteAbschliessen(START.plusSeconds(60), BITRATE);

        CanStatistik.Minute minute = statistik.getLetzteMinute();
        assertEquals(58, minute.empfangen(), 1e-9);
        assertEquals(38, minute.antworten(), 1e-9);
        assertEquals(40, minute.gesendet(), 1e-9);
        assertEquals(2, minute.nichtVerfuegbar(), 1e-9);
        assertEquals(95, minute.antwortquote(), 1e-9);
        // 98 messages with 7 data bytes: 98 * (47 + 56) bit in 60 s at 20 kbit/s
        assertEquals(100d * 98 * 103 / 60 / BITRATE, minute.buslast(), 1e-9);
        assertEquals(START.plusSeconds(37), statistik.getLetzteAntwort());
        assertEquals(START.plusSeconds(49), statistik.getLetzteNachricht());

        List<CanStatistik.KnotenStatus> knoten = statistik.getKnoten();
        assertEquals(2, knoten.size());
        assertEquals("0x480 Manager", knoten.get(0).name());
        assertEquals(20, knoten.get(0).proMinute(), 1e-9);
        assertEquals(START.plusSeconds(49), knoten.get(0).zuletzt());
        assertEquals("0x500 Wärmepumpe", knoten.get(1).name());

        // Next minute without messages
        statistik.minuteAbschliessen(START.plusSeconds(120), BITRATE);
        assertEquals(0, statistik.getLetzteMinute().empfangen(), 1e-9);
        assertEquals(0, statistik.getLetzteMinute().antwortquote(), 1e-9);
        assertEquals(0, statistik.getKnoten().get(0).proMinute(), 1e-9);
    }

    @Test
    public void testAntwortquoteHoechstens100() {
        CanStatistik statistik = new CanStatistik();
        statistik.minuteAbschliessen(START, BITRATE);
        // An answer of the previous minute arrives after the close
        statistik.gesendet(7);
        statistik.antwort(false, START);
        statistik.antwort(false, START);
        statistik.minuteAbschliessen(START.plusSeconds(60), BITRATE);
        assertEquals(100, statistik.getLetzteMinute().antwortquote(), 1e-9);
    }

    @Test
    public void testAdapter() {
        CanStatistik statistik = new CanStatistik();
        assertFalse(statistik.isVerbunden());
        statistik.verbunden("0108", "0100", "AB12", START);
        assertTrue(statistik.isVerbunden());
        assertEquals("1.8", statistik.getFirmware());
        assertEquals("1.0", statistik.getHardware());
        assertEquals(START, statistik.getVerbundenSeit());
        statistik.neustart(START.plusSeconds(600));
        statistik.getrennt();
        assertFalse(statistik.isVerbunden());
        assertEquals(1, statistik.getNeustarts());
        assertEquals(START.plusSeconds(600), statistik.getLetzterNeustart());
    }

    @Test
    public void testKnotenName() {
        assertEquals("0x180 Kessel", CanStatistik.knotenName(0x180));
        assertEquals("0x700", CanStatistik.knotenName(0x700));
    }

    @Test
    public void testGesendetWirdGezaehlt() {
        CanStatistik statistik = new CanStatistik();
        SynchronizedUSBtin usbtin = new SynchronizedUSBtin(statistik);
        statistik.minuteAbschliessen(START, BITRATE);
        // Not connected: sending fails and is not counted
        assertThrows(NullPointerException.class, () -> usbtin.send(new CANMessage(0x680, new byte[]{0x31, 0x00})));
        statistik.minuteAbschliessen(START.plusSeconds(60), BITRATE);
        assertEquals(0, statistik.getLetzteMinute().gesendet(), 1e-9);
    }

    @Test
    public void testVersion() {
        assertEquals("1.8", CanStatistik.version("0108"));
        assertEquals("1.10", CanStatistik.version("0110"));
        assertEquals("2.0", CanStatistik.version("0200"));
        assertEquals("v1.8", CanStatistik.version("v1.8"));
        assertEquals(null, CanStatistik.version(null));
    }
}
