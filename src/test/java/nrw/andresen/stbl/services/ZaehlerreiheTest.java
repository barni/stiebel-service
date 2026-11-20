package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ZaehlerreiheTest {

    private static Instant t(String tag) {
        return Instant.parse(tag + "T00:00:00Z");
    }

    @Test
    public void testHaendischUndZaehler() {
        Map<Instant, Double> davor = new TreeMap<>(Map.of(t("2025-01-01"), 100.0, t("2025-01-11"), 120.0,
                t("2025-01-31"), 140.0));
        // The Shelly starts at 0 and goes on, once it starts again after a reset
        Map<Instant, Double> shelly = new TreeMap<>(Map.of(t("2025-02-02"), 0.0, t("2025-02-03"), 2.0,
                t("2025-02-04"), 3.0, t("2025-02-05"), 1.0, t("2025-02-06"), 2.5));
        Zaehlerreihe r = new Zaehlerreihe(davor, shelly);
        assertEquals(t("2025-01-01"), r.getAnfang());
        // Interpolated between manual readings: 2 kWh per day
        assertEquals(110.0 - 100, r.stand(t("2025-01-06")), 1e-9);
        assertEquals(40.0, r.verbrauch(t("2025-01-01"), t("2025-01-31"), true), 1e-9);
        // Between the last manual reading and the first of the Shelly unknown
        assertNull(r.stand(t("2025-02-01")));
        assertNull(r.verbrauch(t("2025-01-15"), t("2025-02-04"), true));
        // Shelly: +2, +1, reset to 1 counts +1, +1.5
        assertEquals(5.5, r.verbrauch(t("2025-02-02"), t("2025-02-06"), true), 1e-9);
        // Shortly after the last reading the last value, later unknown
        assertEquals(45.5, r.stand(t("2025-02-07")), 1e-9);
        assertNull(r.stand(t("2025-02-10")));
    }

    @Test
    public void testVorherDa() {
        Zaehlerreihe wallbox = new Zaehlerreihe(Map.of(), new TreeMap<>(Map.of(t("2026-05-01"), 10.0,
                t("2026-06-01"), 110.0)));
        // A device that came new counts 0 before its first reading, one that was there before is unknown
        assertEquals(0.0, wallbox.verbrauch(t("2026-01-01"), t("2026-04-01"), false), 1e-9);
        assertEquals(100.0, wallbox.verbrauch(t("2026-01-01"), t("2026-06-01"), false), 1e-9);
        assertNull(wallbox.verbrauch(t("2026-01-01"), t("2026-06-01"), true));
        assertEquals(100.0, wallbox.verbrauch(t("2026-05-01"), t("2026-06-01"), true), 1e-9);
        Zaehlerreihe leer = new Zaehlerreihe(Map.of(), Map.of());
        assertNull(leer.verbrauch(t("2026-01-01"), t("2026-02-01"), true));
        assertEquals(0.0, leer.verbrauch(t("2026-01-01"), t("2026-02-01"), false), 1e-9);
    }

    @Test
    public void testHochrechnen() {
        // Monthly readings, 2 kWh per day in the last 30 days
        Map<Instant, Double> davor = new TreeMap<>(Map.of(t("2026-09-01"), 8181.0, t("2026-10-02"), 8233.0,
                t("2026-11-01"), 8293.0));
        Zaehlerreihe r = new Zaehlerreihe(davor, Map.of());
        assertEquals(true, r.isHochgerechnet());
        // 18 days after the last reading at the rate of October: 60 kWh in 30 days
        assertEquals(36.0, r.verbrauch(t("2026-11-01"), t("2026-11-19"), true), 1e-9);
        // Not longer than two months
        assertNull(r.stand(t("2027-01-15")));
        // With a counter after the readings nothing is estimated, the gap stays unknown
        Zaehlerreihe mitShelly = new Zaehlerreihe(davor, Map.of(t("2026-11-20"), 0.5, t("2026-11-21"), 2.5));
        assertEquals(false, mitShelly.isHochgerechnet());
        assertNull(mitShelly.verbrauch(t("2026-11-01"), t("2026-11-21"), true));
        assertEquals(2.0, mitShelly.verbrauch(t("2026-11-20"), t("2026-11-21"), true), 1e-9);
    }
}
