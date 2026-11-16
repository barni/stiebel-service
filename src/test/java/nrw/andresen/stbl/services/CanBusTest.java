package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CanBusTest {

    private static final Instant JETZT = Instant.parse("2026-11-15T12:00:00Z");

    /**
     * A silent adapter is restarted every 2 minutes, the alert is sent for the first restart and then once an hour
     */
    @Test
    public void testMeldungFaellig() {
        assertTrue(CanBus.meldungFaellig(null, JETZT));
        assertFalse(CanBus.meldungFaellig(JETZT.minus(Duration.ofMinutes(2)), JETZT));
        assertFalse(CanBus.meldungFaellig(JETZT.minus(Duration.ofMinutes(59)), JETZT));
        assertTrue(CanBus.meldungFaellig(JETZT.minus(CanBus.MELDUNG_ABSTAND), JETZT));
        assertTrue(CanBus.meldungFaellig(JETZT.minus(Duration.ofHours(5)), JETZT));
    }

    @Test
    public void testMeldungstext() {
        assertEquals("Restarting USB, no message received for: 125 s", CanBus.meldungstext(125, 1));
        assertEquals("Restarting USB, no message received for: 125 s (30 restarts since the last message)",
                CanBus.meldungstext(125, 30));
    }

    @Test
    public void testFilter() {
        // Receiving all: no filter at all
        assertNull(CanBus.filter(true));
        assertEquals(1, CanBus.filter(false).length);
    }

    @Test
    public void testMitlesen() {
        // The manager writes SPEICHERBEDARF 512 to the heat pump, recorded on 2026-11-15
        assertTrue(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "A0005F02000000")));
        // Not from another device, not the request and not the answer 0x8000 of the heat pump
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x180, "A0005F02000000")));
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x681, "A1005F00000000")));
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x500, "D2015F80000000")));
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "A0005F80000000")));
        // Another index
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "A0002C00000000")));
        // 0x700 answers the manager the fine flow 33.9 degC, 2026-11-15 22:53
        assertTrue(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x700, "9200FAFDF30153")));
        // but not its request, and not the same index as write
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "E100FAFDF30000")));
        assertFalse(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x700, "9000FAFDF30153")));
        // The manager writes the pump PWM 72 to 0x700 and the controller integral -327 to the heat pump
        assertTrue(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "E000FAFE1B0048")));
        assertTrue(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "A000FA0917FEB9")));
        // and 0x02CA = 257 to the heat pump
        assertTrue(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x480, "A000FA02CA0101")));
        // Raw values: answer of the heat pump to the manager
        assertTrue(CanBus.mitlesen(new nrw.andresen.stbl.services.can.ElsterMessage(0x500, "9200FA07A80018")));
    }
}
