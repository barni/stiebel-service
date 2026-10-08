package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TagesuebersichtTest {

    private static final Instant START = Instant.parse("2026-01-11T06:00:00Z");
    private static final LocalDate DATUM = LocalDate.of(2026, 1, 11);

    private static Map<Instant, Double> werte(double... werte) {
        Map<Instant, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < werte.length; i++) {
            map.put(START.plusSeconds(20L * i), werte[i]);
        }
        return map;
    }

    @Test
    public void testTag() {
        // Two runs with 1500 VA after a standstill; the first value already running is no start
        Map<Instant, Double> leistung = werte(1500, 0, 1500, 1500, 1500, 0, 0, 1500, 1500, 0);
        Map<Instant, Double> abtauung = werte(0, 1, 1, 0, 0, 1, 0);
        Map<Instant, Double> waerme = werte(111.9, 111.95, 112.0);
        Tagesuebersicht.Tag tag = Tagesuebersicht.tag(DATUM, -2.0, leistung, abtauung, waerme);
        assertEquals(2, tag.starts());
        // 6 running values with a following value, 20 s each
        assertEquals(120 / 3600d, tag.laufzeitH(), 1e-9);
        assertEquals(100, tag.waermeKWh(), 1e-6);
        // Real power at 1500 VA equals the apparent power
        assertEquals(1500 * 120 / 3600d / 1000, tag.stromKWh(), 1e-9);
        assertEquals(2, tag.abtauungen());
        assertEquals(tag.waermeKWh() / tag.stromKWh(), tag.arbeitszahl(), 1e-9);
    }

    @Test
    public void testLueckeUndLeererTag() {
        // A gap of one hour counts only 60 s
        Map<Instant, Double> leistung = new LinkedHashMap<>();
        leistung.put(START, 1000d);
        leistung.put(START.plusSeconds(3600), 1000d);
        Tagesuebersicht.Tag tag = Tagesuebersicht.tag(DATUM, null, leistung, Map.of(), Map.of());
        assertEquals(60 / 3600d, tag.laufzeitH(), 1e-9);
        assertEquals(0, tag.starts());
        assertNull(tag.laufzeitProStart());

        Tagesuebersicht.Tag leer = Tagesuebersicht.tag(DATUM, null, Map.of(), Map.of(), Map.of());
        assertEquals(0, leer.waermeKWh());
        assertNull(leer.arbeitszahl());
    }

    @Test
    public void testAehnlicheTage() {
        List<WaermebedarfVergleich.Tag> tage = List.of(
                new WaermebedarfVergleich.Tag(DATUM, -2.0, 35, 16.7, 119, 8.5),
                new WaermebedarfVergleich.Tag(DATUM.minusDays(1), -1.0, 30, 17, 110, 8.5),
                new WaermebedarfVergleich.Tag(DATUM.minusDays(2), -3.4, 20, 18, 130, 8.5),
                new WaermebedarfVergleich.Tag(DATUM.minusDays(3), 1.0, 10, 10, 60, 8.5));
        // Yesterday itself is not compared with itself
        Tagesuebersicht.Vergleichstage aehnlich = Tagesuebersicht.aehnlicheTage(tage, DATUM, -2.0);
        assertEquals(2, aehnlich.tage());
        assertEquals(25, aehnlich.starts(), 1e-9);
        assertEquals(17.5, aehnlich.laufzeitH(), 1e-9);
        assertEquals(120, aehnlich.waermeKWh(), 1e-9);
        assertEquals(-2.2, aehnlich.aussentemp(), 1e-9);
        assertNull(Tagesuebersicht.aehnlicheTage(tage, DATUM, 20));
    }
}
