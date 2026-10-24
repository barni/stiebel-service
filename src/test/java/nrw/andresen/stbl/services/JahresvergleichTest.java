package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JahresvergleichTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 10, 24);

    /**
     * Heating 2024 with 90 kWh per month, 2025 with 100, 2026 up to September 110 and October so far 60 against 50
     * (2025) and 40 (2024) up to the same day. Household 300 kWh per month in 2025, 200 so far in October 2026.
     */
    private static Jahresvergleich.Vergleich vergleich(boolean mitHaushalt) {
        Map<YearMonth, Double> heizung = new TreeMap<>();
        Map<YearMonth, Double> haushalt = new TreeMap<>();
        for (int m = 1; m <= 12; m++) {
            heizung.put(YearMonth.of(2024, m), 90.0);
            heizung.put(YearMonth.of(2025, m), 100.0);
            haushalt.put(YearMonth.of(2025, m), 300.0);
        }
        for (int m = 1; m <= 9; m++) {
            heizung.put(YearMonth.of(2026, m), 110.0);
            haushalt.put(YearMonth.of(2026, m), 280.0);
        }
        heizung.put(YearMonth.of(2026, 10), 60.0);
        haushalt.put(YearMonth.of(2026, 10), 200.0);
        Map<YearMonth, Double> aussen = Map.of(YearMonth.of(2024, 1), 3.1, YearMonth.of(2025, 1), 2.9,
                YearMonth.of(2026, 1), 2.8);
        return Jahresvergleich.vergleich(2026, HEUTE, heizung, Map.of(2024, 40.0, 2025, 50.0),
                mitHaushalt ? haushalt : Map.of(), mitHaushalt, aussen, Instant.parse("2026-10-24T09:00:00Z"));
    }

    @Test
    public void testVergleich() {
        Jahresvergleich.Vergleich v = vergleich(true);
        assertEquals(java.util.List.of(2024, 2025, 2026), v.jahre());
        assertEquals(2026, v.jahr());
        assertEquals(12, v.monate().size());
        Jahresvergleich.Monat jan = v.monate().get(0);
        assertEquals("Jan", jan.name());
        assertEquals(java.util.List.of(90.0, 100.0, 110.0), jan.heizung());
        assertEquals(10.0, jan.aenderung(), 1e-9);
        assertEquals(2.8, jan.aussen().get(2));
        // The current month compares with the same days of the years before
        Jahresvergleich.Monat okt = v.monate().get(9);
        assertTrue(okt.laufend());
        assertEquals(100.0, okt.heizung().get(1));
        assertEquals(java.util.List.of(40.0, 50.0, 60.0), okt.heizungGleich());
        assertEquals(20.0, okt.aenderung(), 1e-9);
        // Months still to come have no values for this year and no values up to the same day
        Jahresvergleich.Monat nov = v.monate().get(10);
        assertNull(nov.heizung().get(2));
        assertNull(nov.heizungGleich().get(1));
        assertNull(nov.aenderung());
        assertEquals(100.0, nov.heizung().get(1));
        assertNull(nov.haushalt().get(2));
        assertFalse(nov.laufend());

        assertEquals(9 * 110 + 60, v.heizung(2), 1e-9);
        assertEquals(9 * 100 + 50, v.heizungGleich(1), 1e-9);
        assertEquals(9 * 90 + 40, v.heizungGleich(0), 1e-9);
        assertEquals(1200, v.heizung(1), 1e-9);
        assertEquals(1080, v.heizung(0), 1e-9);
        assertEquals((1050.0 / 950 - 1) * 100, v.aenderung(), 1e-9);
        assertEquals(9 * 280 + 200, v.haushalt(2), 1e-9);
        assertEquals(3600, v.haushalt(1), 1e-9);
        assertNull(v.haushalt(0));
        assertNull(vergleich(false).haushalt(1));
    }

    @Test
    public void testHaushalt() {
        YearMonth mai = YearMonth.of(2026, 5);
        YearMonth juni = YearMonth.of(2026, 6);
        YearMonth juli = YearMonth.of(2026, 7);
        Map<YearMonth, Double> bezug = new TreeMap<>();
        bezug.put(mai, 710.0);
        bezug.put(juni, 555.0);
        bezug.put(juli, null);
        Map<YearMonth, Double> haushalt = Jahresvergleich.haushalt(bezug, Map.of(mai, 94.0, juni, 25.0),
                Map.of(mai, 129.0));
        assertEquals(710 - 94 - 129, haushalt.get(mai), 1e-9);
        // No wallbox value: without wallbox
        assertEquals(555 - 25, haushalt.get(juni), 1e-9);
        // Grid import unknown
        assertFalse(haushalt.containsKey(juli));
        // Not below 0
        assertEquals(0, Jahresvergleich.haushalt(Map.of(mai, 50.0), Map.of(mai, 94.0), Map.of()).get(mai), 1e-9);
    }

    @Test
    public void testMonatsmittel() {
        Map<LocalDate, Double> tage = Map.of(LocalDate.of(2026, 1, 1), 2.0, LocalDate.of(2026, 1, 2), 4.0,
                LocalDate.of(2026, 2, 1), 7.0);
        Map<YearMonth, Double> mittel = Jahresvergleich.monatsmittel(tage);
        assertEquals(3.0, mittel.get(YearMonth.of(2026, 1)), 1e-9);
        assertEquals(7.0, mittel.get(YearMonth.of(2026, 2)), 1e-9);
    }

    @Test
    public void testFlux() {
        String flux = Jahresvergleich.flux("home", Instant.parse("2026-09-30T22:00:00Z"),
                Instant.parse("2026-10-24T08:50:00Z"));
        assertTrue(flux.contains("from(bucket: \"home\")"));
        assertTrue(flux.contains("range(start: 2026-09-30T22:00:00Z, stop: 2026-10-24T08:50:00Z)"));
        // Same line as Waermepumpe.wirkleistung, with a dot as decimal separator whatever the locale
        assertTrue(flux.contains("1.2400 - 270.0 / r._value"));
        assertTrue(flux.contains("if anteil < 0.7000 then 0.7000 else if anteil > 1.0000 then 1.0000"));
        assertTrue(flux.contains("_value: r._value / 60000.0"));
        assertThrows(IllegalArgumentException.class, () -> Jahresvergleich.flux("home\") |> drop(",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60)));

        String zaehler = Jahresvergleich.zaehlerFlux("home_assistant", "haus_gesamtverbrauch",
                Instant.parse("2026-09-30T22:00:00Z"), Instant.parse("2026-10-31T23:00:00Z"), "last");
        assertEquals("from(bucket: \"home_assistant\") |> range(start: 2026-09-30T22:00:00Z, stop: "
                + "2026-10-31T23:00:00Z) |> filter(fn: (r) => r._measurement == \"kWh\" and r._field == \"value\" "
                + "and r.entity_id == \"haus_gesamtverbrauch\") |> last()",
                zaehler);
        assertThrows(IllegalArgumentException.class, () -> Jahresvergleich.zaehlerFlux("home", "a\" or true",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "last"));
        assertThrows(IllegalArgumentException.class, () -> Jahresvergleich.zaehlerFlux("home", "a",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "drop"));
    }

    @Test
    public void testStand() {
        Instant monatsende = Instant.parse("2024-12-31T23:00:00Z");
        Map.Entry<Instant, Double> vorher = Map.entry(Instant.parse("2024-12-21T23:00:00Z"), 1000.0);
        Map.Entry<Instant, Double> nachher = Map.entry(Instant.parse("2025-01-10T23:00:00Z"), 1200.0);
        // Gap of 20 days, 10 of them before the end of the month
        assertEquals(1100.0, Jahresvergleich.stand(vorher, nachher, monatsende), 1e-9);
        assertEquals(1000.0, Jahresvergleich.stand(vorher, null, monatsende), 1e-9);
        assertNull(Jahresvergleich.stand(null, nachher, monatsende));
    }

    @Test
    public void testSeite() {
        String html = JahresvergleichSeite.render(vergleich(true), null, false);
        assertTrue(html.contains("<title>Wärmepumpe · Jahresvergleich</title>"));
        assertTrue(html.contains("<a class=\"nav\" href=\"status\">← Status</a>"));
        assertTrue(html.contains("Strom der Heizung je Monat, 2024 bis 2026"));
        assertTrue(html.contains("Vom 1. Januar bis 24. Oktober 2026 hat die Heizung 1.050 kWh Strom gebraucht, "
                + "10,5 % mehr als in denselben Tagen 2025 (950 kWh). Im ganzen Jahr waren es 1.200 kWh (2025) und "
                + "1.080 kWh (2024)."));
        assertTrue(html.contains("<td>Okt</td><td>90</td><td>100</td><td>60 (bis 24.)</td><td>+20,0 %</td>"));
        assertTrue(html.contains("<td>Nov</td><td>90</td><td>100</td><td>–</td><td>–</td>"));
        // 12 bars each for 2024 and 2025, 10 for 2026 with the current month lighter, no household
        assertEquals(12, html.split("<rect class=\"j0\"").length - 1);
        assertEquals(12, html.split("<rect class=\"j1\"").length - 1);
        assertEquals(9, html.split("<rect class=\"j2\"").length - 1);
        assertEquals(1, html.split("<rect class=\"j2 laufend\"").length - 1);
        assertFalse(html.contains("<rect class=\"haushalt"));
        assertTrue(html.contains("<title>Okt 2025: Heizung 100 kWh; bis zum gleichen Tag 50 kWh</title>"));
        assertTrue(html.contains("href=\"jahresvergleich?haushalt=ja\">Haushaltsstrom einblenden</a>"));

        String mit = JahresvergleichSeite.render(vergleich(true), null, true);
        assertTrue(mit.contains("href=\"jahresvergleich\">Haushaltsstrom ausblenden</a>"));
        assertTrue(mit.contains("Der Haushalt ohne Heizung und Wallbox brauchte 2026 bisher 2.720 kWh, 2025 im "
                + "ganzen Jahr 3.600 kWh."));
        assertTrue(mit.contains("<th>Haushalt 2026 kWh</th>"));
        assertTrue(mit.contains("<td>+20,0 %</td><td>–</td><td>300</td><td>200 (bis 24.)</td>"));
        assertEquals(12, mit.split("<rect class=\"haushalt j1\"").length - 1);
        assertEquals(1, mit.split("<rect class=\"haushalt j2 laufend\"").length - 1);
        assertTrue(mit.contains("<title>Okt 2026 bis 24.: Heizung 60 kWh, Haushalt 200 kWh</title>"));

        // Without the house meter there is nothing to show in, even if asked for
        String ohne = JahresvergleichSeite.render(vergleich(false), null, true);
        assertFalse(ohne.contains("Haushaltsstrom einblenden"));
        assertFalse(ohne.contains("<rect class=\"haushalt"));
        assertTrue(ohne.contains("wenn in der Konfiguration der Hauszähler eingetragen ist"));
        // Not calculated yet, or InfluxDB switched off
        assertTrue(JahresvergleichSeite.render(null, null, false).contains("wird berechnet"));
        assertTrue(JahresvergleichSeite.render(null, "InfluxDB ist aus", false).contains("InfluxDB ist aus"));
    }

    @Test
    public void testSchritt() {
        assertEquals(200, JahresvergleichSeite.schritt(773), 1e-9);
        assertEquals(20, JahresvergleichSeite.schritt(90), 1e-9);
        assertEquals(1, JahresvergleichSeite.schritt(0), 1e-9);
    }
}
