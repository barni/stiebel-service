package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WaermebedarfVergleichTest {

    private static final LocalDate TAG1 = LocalDate.of(2026, 1, 11);
    private static final LocalDate TAG2 = LocalDate.of(2026, 1, 12);
    private static final LocalDate TAG3 = LocalDate.of(2026, 10, 1);

    @Test
    public void testBand() {
        assertEquals(0, WaermebedarfVergleich.band(-7));
        assertEquals(1, WaermebedarfVergleich.band(-5));
        assertEquals(1, WaermebedarfVergleich.band(-2.04));
        assertEquals(4, WaermebedarfVergleich.band(14.9));
        assertEquals(-1, WaermebedarfVergleich.band(15));
        assertEquals("unter -5 °C", WaermebedarfVergleich.bandName(0));
        assertEquals("-5 bis 0 °C", WaermebedarfVergleich.bandName(1));
        assertEquals("10 bis 15 °C", WaermebedarfVergleich.bandName(4));
    }

    @Test
    public void testTage() {
        // Values of 11.01.2026 from InfluxDB; 12.01. has a gap, 01.10. the new setting
        Map<LocalDate, Double> werte = Map.of(TAG1, 4320d, TAG2, 2000d, TAG3, 4320d);
        Map<LocalDate, Double> starts = Map.of(TAG1, 12d, TAG2, 16d, TAG3, 10d);
        Map<LocalDate, Double> laufzeit = Map.of(TAG1, 16.74, TAG2, 19.9, TAG3, 5d);
        Map<LocalDate, Double> waerme = Map.of(TAG1, 0.1188, TAG2, 0.101, TAG3, 0.03);
        Map<LocalDate, Double> temperatur = Map.of(TAG1, -2.04, TAG2, 5.1, TAG3, 12d);
        Map<LocalDate, Double> einstellung = Map.of(LocalDate.of(2026, 9, 22), 8.5, LocalDate.of(2026, 9, 27), 7.5);

        // 35 run-ups on 11.01., 12 of them starts
        Map<LocalDate, Double> anlaeufe = Map.of(TAG1, 35d, TAG2, 30d, TAG3, 9d);
        List<WaermebedarfVergleich.Tag> tage = WaermebedarfVergleich.tage(werte, starts, anlaeufe, laufzeit,
                waerme, temperatur, einstellung);
        assertEquals(2, tage.size());
        // Before the first stored setting the first value applies
        assertEquals(new WaermebedarfVergleich.Tag(TAG1, -2.04, 12, 16.74, 118.8, 8.5, 23).toString(),
                tage.get(0).toString());
        assertEquals(7.5, tage.get(1).waermebedarf());
        // Fewer run-ups than starts on a day (start counted the day before): no negative defrosts
        assertEquals(0, tage.get(1).abtauungen());
    }

    @Test
    public void testOhneEinstellung() {
        assertTrue(WaermebedarfVergleich.tage(Map.of(TAG1, 4320d), Map.of(), Map.of(), Map.of(), Map.of(TAG1, 0.1),
                Map.of(TAG1, 0d), Map.of()).isEmpty());
    }

    @Test
    public void testVergleich() {
        List<WaermebedarfVergleich.Tag> tage = List.of(
                new WaermebedarfVergleich.Tag(TAG3, 2, 10, 20, 100, 7.5, 0),
                new WaermebedarfVergleich.Tag(TAG1, 1, 30, 18, 110, 8.5, 0),
                new WaermebedarfVergleich.Tag(TAG2, 3, 20, 16, 90, 8.5, 0),
                new WaermebedarfVergleich.Tag(TAG2, -6, 40, 22, 150, 8.5, 0),
                new WaermebedarfVergleich.Tag(TAG2, 16, 2, 1, 5, 8.5, 0));
        List<WaermebedarfVergleich.Gruppe> gruppen = WaermebedarfVergleich.vergleich(tage);
        // The day above 15 degC is not used, coldest band first, the old setting before the new one
        assertEquals(3, gruppen.size());
        assertEquals("unter -5 °C", gruppen.get(0).band());
        WaermebedarfVergleich.Gruppe alt = gruppen.get(1);
        assertEquals("0 bis 5 °C", alt.band());
        assertEquals(8.5, alt.waermebedarf());
        assertEquals(2, alt.tage());
        assertEquals(25, alt.startsProTag(), 1e-9);
        assertEquals(17, alt.laufzeitProTag(), 1e-9);
        assertEquals(34d / 50, alt.laufzeitProStart(), 1e-9);
        assertEquals(100, alt.waermeProTag(), 1e-9);
        assertEquals(200d / 34, alt.leistungKW(), 1e-9);
        WaermebedarfVergleich.Gruppe neu = gruppen.get(2);
        assertEquals(7.5, neu.waermebedarf());
        assertEquals(2.0, neu.laufzeitProStart(), 1e-9);
    }

    @Test
    public void testFlux() {
        String flux = WaermebedarfVergleich.flux(Instant.parse("2026-01-10T23:00:00Z"),
                Instant.parse("2026-01-13T23:00:00Z"), "home_assistant", "°C", "aussen_temperatur", "mean()", "");
        assertTrue(flux.contains("r._measurement == \"°C\" and r._field == \"value\" and r.entity_id == "
                + "\"aussen_temperatur\""));
        assertTrue(flux.contains("tables |> mean(), createEmpty: false, timeSrc: \"_start\""));
        assertTrue(flux.contains("range(start: 2026-01-10T23:00:00Z, stop: 2026-01-13T23:00:00Z)"));
        assertThrows(IllegalArgumentException.class, () -> WaermebedarfVergleich.flux(Instant.EPOCH, Instant.EPOCH,
                "home\") |> drop()", "WP_Aussentemp", null, "mean()", ""));
    }

    @Test
    public void testVorherNachher() {
        List<WaermebedarfVergleich.Tag> tage = List.of(
                new WaermebedarfVergleich.Tag(TAG1, 2, 30, 18, 110, 8.5, 0),
                new WaermebedarfVergleich.Tag(TAG2, 3, 20, 16, 90, 8.5, 0),
                new WaermebedarfVergleich.Tag(TAG3, 2, 10, 20, 100, 7.5, 0),
                new WaermebedarfVergleich.Tag(TAG1, 7, 12, 14, 60, 8.5, 0));
        List<WaermebedarfVergleich.Vergleich> zeilen =
                WaermebedarfVergleich.vorherNachher(WaermebedarfVergleich.vergleich(tage));
        assertEquals(2, zeilen.size());
        // 0 to 5 degC: the old setting before the arrow, the setting used last after it
        assertEquals("0 bis 5 °C", zeilen.get(0).band());
        assertEquals(8.5, zeilen.get(0).vorher().waermebedarf());
        assertEquals(TAG2, zeilen.get(0).vorher().bis());
        assertEquals(7.5, zeilen.get(0).nachher().waermebedarf());
        // Only days with the old setting: no value before
        assertEquals("5 bis 10 °C", zeilen.get(1).band());
        assertEquals(null, zeilen.get(1).vorher());
        assertEquals(8.5, zeilen.get(1).nachher().waermebedarf());
    }

    private static WaermebedarfVergleich.Gruppe gruppe(double einstellung, int tage, double starts, double h,
                                                       double kwh) {
        return new WaermebedarfVergleich.Gruppe("0 bis 5 °C", einstellung, tage, 2, starts, h, h / starts, kwh,
                kwh / h, TAG1, 0);
    }

    @Test
    public void testBewertung() {
        WaermebedarfVergleich.Gruppe alt = gruppe(8.5, 124, 23.5, 17.5, 95);
        // Fewer starts, same heat
        assertEquals("besser", WaermebedarfVergleich.bewertung(alt, gruppe(7.5, 9, 15, 19.5, 93)));
        // Heat per day more than 10 % lower
        assertEquals("zu knapp?", WaermebedarfVergleich.bewertung(alt, gruppe(7.5, 9, 15, 19.5, 80)));
        // Nearly a full day of run time, also without days of another setting
        assertEquals("zu knapp?", WaermebedarfVergleich.bewertung(alt, gruppe(7.5, 9, 10, 23, 96)));
        assertEquals("zu knapp?", WaermebedarfVergleich.bewertung(null, gruppe(7.5, 9, 10, 23, 96)));
        assertEquals("zu wenige Tage", WaermebedarfVergleich.bewertung(alt, gruppe(7.5, 4, 15, 19.5, 93)));
        assertEquals("schlechter", WaermebedarfVergleich.bewertung(alt, gruppe(7.5, 9, 27, 17.5, 95)));
        assertEquals("kaum Unterschied", WaermebedarfVergleich.bewertung(alt, gruppe(7.5, 9, 23, 17.5, 95)));
        assertEquals("–", WaermebedarfVergleich.bewertung(null, alt));
    }
}
