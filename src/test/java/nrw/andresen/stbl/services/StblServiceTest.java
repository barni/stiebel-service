package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StblServiceTest {

    @Test
    public void testWaermeleistung() {
        // 9 l/min and 5,4 K, measured on 21.09.2026
        assertEquals(3.39, StblService.waermeleistung(9.0, 5.4), 0.01);
        // 12 l/min and 7 K
        assertEquals(5.87, StblService.waermeleistung(12.0, 7.0), 0.01);
    }

    @Test
    public void testWaermeleistungOhneDurchfluss() {
        assertEquals(0.0, StblService.waermeleistung(0.0, 5.4), 0.0001);
    }

    @Test
    public void testWirkleistung() {
        // Fit from the house meter 2024 and 2025: about 0.8 at 600 VA, 0.9 at 800 VA
        assertEquals(474, StblService.wirkleistung(600), 1);
        assertEquals(722, StblService.wirkleistung(800), 1);
        // Not more than the apparent power at high load
        assertEquals(2000, StblService.wirkleistung(2000), 0.001);
        // At least 70 % below the measured range
        assertEquals(210, StblService.wirkleistung(300), 0.001);
        assertEquals(0, StblService.wirkleistung(0), 0.001);
    }

    @Test
    public void testNachAnlauf() {
        Instant start = Instant.parse("2026-09-23T04:45:52Z");
        assertFalse(StblService.nachAnlauf(null, start));
        // 05:01:32 / 06:45:32: pump runs, compressor starting
        assertFalse(StblService.nachAnlauf(start, start.plusSeconds(40)));
        assertTrue(StblService.nachAnlauf(start, start.plusSeconds(60)));
        assertTrue(StblService.nachAnlauf(start, start.plusSeconds(3000)));
    }

    @Test
    public void testAbtauung() {
        Duration lange = Duration.ofMinutes(90);
        // Heating on 2026-09-25 02:40: EXV 20 %, evaporator 9 degC, flow 31.9 degC, 20 Hz
        assertFalse(StblService.abtauung(20.1, 9.0, 31.9, 20, lange));
        // Defrost 02:43:00: compressor stopped 1.2 min before, EXV 100 %
        assertTrue(StblService.abtauung(100.0, 19.3, 27.5, 0, Duration.ofSeconds(72)));
        // Defrost 05:11:20 if the EXV value is not yet updated: evaporator 44.1 degC above flow 37.8 degC
        assertTrue(StblService.abtauung(20.3, 44.1, 37.8, 20, Duration.ofSeconds(120)));
        // Standstill: evaporator warms up above the flow, but the compressor does not run
        assertFalse(StblService.abtauung(0.0, 24.0, 22.0, 0, lange));
        // Start 2026-09-25 06:41:40 after 1.5 h standstill: the EXV opens to 100 % before the compressor runs
        assertFalse(StblService.abtauung(100.0, 22.8, 28.5, 0, lange));
        assertFalse(StblService.abtauung(100.0, 14.4, 31.9, 40, lange));
        // Start 2026-09-26 06:00:40: evaporator 21.9 degC close to the flow 25.2 degC
        assertFalse(StblService.abtauung(20.8, 21.9, 25.2, 40, lange));
        assertFalse(StblService.abtauung(20.8, 25.0, 22.0, 40, lange));
        // Service just started, no stop seen yet
        assertFalse(StblService.abtauung(100.0, 19.3, 27.5, 0, null));
    }

    @Test
    public void testStillstand() {
        Instant stopp = Instant.parse("2026-09-25T02:41:48Z");
        assertNull(StblService.stillstand(null, null, stopp));
        // Compressor stands still: up to now
        assertEquals(Duration.ofSeconds(72), StblService.stillstand(null, stopp, stopp.plusSeconds(72)));
        // Compressor runs again: up to the start of the run
        assertEquals(Duration.ofSeconds(132),
                StblService.stillstand(stopp.plusSeconds(132), stopp, stopp.plusSeconds(160)));
    }

    @Test
    public void testInAbtauung() {
        Instant abtauung = Instant.parse("2026-09-25T05:11:29Z");
        assertFalse(StblService.inAbtauung(null, abtauung));
        assertTrue(StblService.inAbtauung(abtauung, abtauung.plusSeconds(40)));
        assertFalse(StblService.inAbtauung(abtauung, abtauung.plusSeconds(60)));
    }

    @Test
    public void testEnergie() {
        // Heat counters 2026-09-23 22:01 UTC: 111 MWh, 931 kWh, day 7 kWh 458 Wh
        assertEquals(111.938458, StblService.energie(111, 931, 7, 458), 1e-9);
        // 22:02 UTC after midnight: the day is added to the sum without the 458 Wh
        assertEquals(111.938, StblService.energie(111, 938, 0, 0), 1e-9);
    }

    @Test
    public void testEnergieFaelltNicht() {
        // Same merge as in getEnergie
        Map<Short, Double> hoechsteEnergie = new HashMap<>();
        short index = 0x0931;
        assertEquals(111.938458, hoechsteEnergie.merge(index, StblService.energie(111, 931, 7, 458), Math::max), 1e-9);
        assertEquals(111.938458, hoechsteEnergie.merge(index, StblService.energie(111, 938, 0, 0), Math::max), 1e-9);
        assertEquals(111.938501, hoechsteEnergie.merge(index, StblService.energie(111, 938, 0, 501), Math::max), 1e-9);
    }

    @Test
    public void testGleichzeitig() {
        Instant summe = Instant.parse("2026-09-23T22:02:09Z");
        // Answers of one request cycle
        assertTrue(StblService.gleichzeitig(List.of(summe, summe.plusMillis(20), summe.plusMillis(40), summe.plusMillis(60))));
        // New sum with the day value of the previous minute
        assertFalse(StblService.gleichzeitig(List.of(summe, summe, summe.minusSeconds(60), summe.minusSeconds(60))));
    }

    @Test
    public void testFormeln() {
        assertEquals("0,7", StblService.zahl(0.7));
        assertEquals("1,24", StblService.zahl(1.24));
        assertEquals("1,0", StblService.zahl(1.0));
        assertTrue(StblService.formelWaermeleistung().startsWith("Volumenstrom [l/min] ÷ 60 × 4,19 kJ/(kg·K)"));
        assertTrue(StblService.formelWaermeleistung().contains("ersten 60 s"));
        assertTrue(StblService.formelArbeitszahl().contains("(1,24 − 270 W ÷ Scheinleistung)"));
        assertTrue(StblService.formelArbeitszahl().contains("0,7 bis 1,0"));
        assertTrue(StblService.formelAbtauung().contains("innerhalb von 5 min"));
        assertTrue(StblService.formelAbtauung().contains("mindestens 90 %"));
        assertTrue(StblService.formelAbtauung().contains("mehr als 5 K"));
    }
}
