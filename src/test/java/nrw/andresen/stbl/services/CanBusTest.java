package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
