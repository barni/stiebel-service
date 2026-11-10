package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PushoverServiceTest {

    @Test
    public void testFormular() {
        assertEquals("token=abc&user=u1&title=W%C3%A4rmepumpe%3A+Heizungsdruck+f%C3%A4llt&message=1%2C6+bar+%26+mehr"
                        + "&priority=-1",
                PushoverService.formular("abc", "u1", -1, "Wärmepumpe: Heizungsdruck fällt", "1,6 bar & mehr"));
        // Pushover needs a message, without text the title is sent
        assertTrue(PushoverService.formular("abc", "u1", 0, "Titel", "").contains("&message=Titel&"));
    }

    @Test
    public void testKuerzen() {
        assertEquals("kurz", PushoverService.kuerzen("kurz", 10));
        String lang = PushoverService.kuerzen("x".repeat(2000), PushoverService.TEXT_MAX);
        assertEquals(PushoverService.TEXT_MAX, lang.length());
        assertTrue(lang.endsWith("…"));
    }

    @Test
    public void testNichtEingerichtet() {
        PushoverService pushover = new PushoverService(" ", "u1", 0);
        assertFalse(pushover.isKonfiguriert());
        // Sends nothing and reports no error
        pushover.senden("Titel", "Text");
        assertNull(pushover.getLetzterFehler());
        assertTrue(new PushoverService("abc", "u1", 0).isKonfiguriert());
    }
}
