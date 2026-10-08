package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.ValueContainer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UeberwachungTest {

    private static final Instant START = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    public void testWarnungMitVerzoegerung() {
        Warnung warnung = new Warnung("Antwortquote niedrig", "Regel", Duration.ofMinutes(10));
        assertFalse(warnung.pruefen(true, "80 %", START));
        assertFalse(warnung.pruefen(true, "80 %", START.plusSeconds(540)));
        assertFalse(warnung.isAktiv());
        // Active after 10 minutes, reported only once
        assertTrue(warnung.pruefen(true, "70 %", START.plusSeconds(600)));
        assertFalse(warnung.pruefen(true, "70 %", START.plusSeconds(660)));
        assertTrue(warnung.isAktiv());
        assertEquals("70 %", warnung.getText());
        assertEquals(START.plusSeconds(600), warnung.getAktivSeit());
        // Ends at once, a new problem is reported again
        assertFalse(warnung.pruefen(false, "100 %", START.plusSeconds(720)));
        assertFalse(warnung.isAktiv());
        assertNull(warnung.getText());
        assertFalse(warnung.pruefen(true, "80 %", START.plusSeconds(780)));
    }

    @Test
    public void testWarnungSofort() {
        Warnung warnung = new Warnung("Heizstab läuft", "Regel", Duration.ZERO);
        assertTrue(warnung.pruefen(true, "Heizstab an", START));
        assertFalse(warnung.pruefen(true, "Heizstab an", START.plusSeconds(60)));
    }

    @Test
    public void testStartsInDerLetztenStunde() {
        Deque<ValueContainer<Double>> verlauf = new ArrayDeque<>();
        // Counter every minute, one start every 10 minutes
        Integer starts = null;
        for (int minute = 0; minute <= 70; minute++) {
            Instant zeit = START.plusSeconds(60L * minute);
            starts = Ueberwachung.startsInDerLetztenStunde(verlauf,
                    new ValueContainer<>(17569d + minute / 10, zeit), zeit);
            if (minute < 55) {
                assertNull(starts, "minute " + minute);
            }
        }
        // At 70 min the counter is compared with the value of 15 min: starts at 20, 30 ... 70 min
        assertEquals(6, starts);
        assertTrue(verlauf.size() <= 67);
    }

    @Test
    public void testStartsGleicherZeitstempel() {
        Deque<ValueContainer<Double>> verlauf = new ArrayDeque<>();
        ValueContainer<Double> wert = new ValueContainer<>(100d, START);
        Ueberwachung.startsInDerLetztenStunde(verlauf, wert, START);
        Ueberwachung.startsInDerLetztenStunde(verlauf, wert, START.plusSeconds(60));
        // The same answer is kept only once
        assertEquals(1, verlauf.size());
    }
}
