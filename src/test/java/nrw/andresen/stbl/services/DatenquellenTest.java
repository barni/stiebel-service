package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DatenquellenTest {

    private static final Instant JETZT = Instant.parse("2026-11-21T17:00:00Z");

    @Test
    public void testZuletzt() {
        Datenquellen.Zuletzt frisch = Datenquellen.zuletzt(JETZT.minusSeconds(180), JETZT, Duration.ofHours(6));
        assertTrue(frisch.ok());
        assertEquals("letzter Wert 21.11. 17:57 (vor 3 min)", frisch.text());
        Datenquellen.Zuletzt alt = Datenquellen.zuletzt(JETZT.minus(Duration.ofDays(12)), JETZT, Duration.ofDays(10));
        assertFalse(alt.ok());
        assertEquals("seit 12 Tagen kein Wert, zuletzt 09.11. 18:00 (vor 12 Tagen)", alt.text());
        // readings by hand are never overdue
        assertTrue(Datenquellen.zuletzt(JETZT.minus(Duration.ofDays(40)), JETZT, null).ok());
        assertFalse(Datenquellen.zuletzt(null, JETZT, Duration.ofDays(10)).ok());
    }

    @Test
    public void testMaxAlter() {
        assertEquals(Duration.ofHours(6), Datenquellen.maxAlter("auswertung.haushalt.zaehler"));
        assertEquals(Duration.ofDays(10), Datenquellen.maxAlter("auswertung.geraet.2.zaehler"));
        assertNull(Datenquellen.maxAlter("auswertung.geraet.2.davor"));
        assertNull(Datenquellen.maxAlter("auswertung.pv.einspeisung"));
        // the readings before the counter get no line
        assertTrue(Datenquellen.quellen().contains("auswertung.geraet.5.zaehler"));
        assertFalse(Datenquellen.quellen().contains("auswertung.geraet.5.davor"));
    }

    @Test
    public void testAnzeige() {
        assertEquals("", KonfigurationSeite.zuletzt(null));
        assertEquals("<p class=\"hilfe zuletzt\">letzter Wert 21.11. 17:57 (vor 3 min)</p>",
                KonfigurationSeite.zuletzt(new Datenquellen.Zuletzt("letzter Wert 21.11. 17:57 (vor 3 min)", true)));
        assertTrue(KonfigurationSeite.zuletzt(new Datenquellen.Zuletzt("seit 12 Tagen kein Wert", false))
                .startsWith("<p class=\"hilfe zuletzt fehlertext\">⚠ "));
    }
}
