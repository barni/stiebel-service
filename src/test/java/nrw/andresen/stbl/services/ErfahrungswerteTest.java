package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErfahrungswerteTest {

    // 2026-01-10 00:00 in Berlin
    private static final Instant TAG = Instant.parse("2026-01-09T23:00:00Z");

    @Test
    public void testAuswerten() {
        Map<Instant, Double> leistung = new TreeMap<>();
        // 0:00–2:00 running, 2:00–5:00 off (3 h), 5:00–6:00 running, a 5-min gap (defrost) is no start, 6:00–7:00 off
        for (int m = 0; m < 7 * 60; m += 5) {
            Instant t = TAG.plusSeconds(m * 60L);
            boolean an = m < 120 || (m >= 300 && m < 360);
            leistung.put(t, an ? 1200.0 : 0.0);
        }
        Map<Instant, Double> nd = new TreeMap<>();
        Map<Instant, Double> leistungMin = new TreeMap<>();
        nd.put(TAG.plusSeconds(600), 4.1);
        leistungMin.put(TAG.plusSeconds(600), 900.0);
        // a lower value while not running does not count
        nd.put(TAG.plusSeconds(3 * 3600), 2.0);
        leistungMin.put(TAG.plusSeconds(3 * 3600), 0.0);
        Map<Instant, Double> abtau = new TreeMap<>();
        for (int m = 0; m < 30; m++) {
            // flagged minutes 10–13 (a defrost of about 3 minutes)
            abtau.put(TAG.plusSeconds(m * 60L), m >= 10 && m <= 13 ? 1.0 : 0.0);
        }
        Erfahrungswerte.Monat monat = Erfahrungswerte.auswerten(leistung, nd, leistungMin, abtau);
        LocalDate tag = LocalDate.of(2026, 1, 10);
        assertEquals(3.0, monat.laengsteRuhe().get(tag), 0.1);
        // one start at 5:00 after the standstill; the start of the data is no start
        assertEquals(1, monat.startsJeStunde().get(tag).get(5));
        assertEquals(1, monat.startsJeStunde().get(tag).stream().mapToInt(Integer::intValue).sum());
        assertEquals(4.1, monat.niederdruckMin().get(tag), 1e-9);
        assertEquals(List.of(3.0), monat.abtauMinuten());
        assertEquals(TAG.plusSeconds(600), monat.ersteAbtauung());
    }

    @Test
    public void testLueckeIstKeinStillstand() {
        Map<Instant, Double> leistung = new TreeMap<>();
        leistung.put(TAG, 0.0);
        leistung.put(TAG.plusSeconds(300), 0.0);
        // 5 hours without data, then off again: the gap does not count as standstill
        leistung.put(TAG.plusSeconds(5 * 3600), 0.0);
        leistung.put(TAG.plusSeconds(5 * 3600 + 300), 1000.0);
        Erfahrungswerte.Monat monat = Erfahrungswerte.auswerten(leistung, Map.of(), Map.of(), Map.of());
        assertTrue(monat.laengsteRuhe().get(LocalDate.of(2026, 1, 10)) < 0.5);
        // after the gap a window without and then one with power is a start
        assertEquals(1, monat.startsJeStunde().get(LocalDate.of(2026, 1, 10)).get(5));
    }

    @Test
    public void testQuantil() {
        assertNull(Erfahrungswerte.quantil(List.of(), 0.5));
        assertEquals(9.0, Erfahrungswerte.quantil(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 0.85),
                1e-9);
    }
}
