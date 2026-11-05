package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZustandTest {

    /**
     * n days at the outdoor temperature with the same values
     */
    private static List<WaermebedarfVergleich.Tag> tage(LocalDate von, int n, double aussen, int starts,
                                                        double laufzeitH, double waermeKWh, int abtauungen,
                                                        double stromKWh) {
        List<WaermebedarfVergleich.Tag> tage = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tage.add(new WaermebedarfVergleich.Tag(von.plusDays(i), aussen, starts, laufzeitH, waermeKWh, 7.5,
                    abtauungen, stromKWh));
        }
        return tage;
    }

    private static Zustand.Saison saison(String name, double azKalt, double abtauKalt, Double fehlstarts,
                                         Double druck) {
        Zustand.Band kalt = new Zustand.Band(30, azKalt, 7.0, 2.4, abtauKalt);
        Zustand.Band mild = new Zustand.Band(40, azKalt + 1, 6.0, 2.2, 5.0);
        return new Zustand.Saison(name, false, 150, azKalt + 0.5, kalt, mild, fehlstarts, druck);
    }

    @Test
    public void testSaison() {
        List<WaermebedarfVergleich.Tag> tage = new ArrayList<>();
        tage.addAll(tage(LocalDate.of(2025, 12, 1), 12, 2.0, 8, 18, 100, 16, 25));
        tage.addAll(tage(LocalDate.of(2025, 11, 1), 5, 7.0, 6, 12, 60, 5, 12));
        tage.addAll(tage(LocalDate.of(2025, 8, 1), 20, 20.0, 0, 0, 0, 0, 0));
        Zustand.Saison s = Zustand.saison("2025/26", false, tage, 12.5,
                Map.of(LocalDate.of(2025, 12, 1), 1.8, LocalDate.of(2026, 1, 1), 1.7));
        assertEquals(17, s.heiztage());
        assertEquals((12 * 100.0 + 5 * 60) / (12 * 25 + 5 * 12), s.arbeitszahl(), 1e-9);
        assertEquals(12, s.kalt().tage());
        assertEquals(4.0, s.kalt().arbeitszahl(), 1e-9);
        assertEquals(8.0, s.kalt().startsProTag(), 1e-9);
        assertEquals(18.0 / 8, s.kalt().laufzeitProStart(), 1e-9);
        assertEquals(16.0, s.kalt().abtauungenProTag(), 1e-9);
        // Below 10 days in a band no means
        assertEquals(5, s.mild().tage());
        assertNull(s.mild().arbeitszahl());
        assertEquals(12.5, s.fehlstartsJe1000());
        assertEquals(1.75, s.druckWinter(), 1e-9);
    }

    @Test
    public void testPlausibel() {
        // A day without heat while the compressor ran, and a day with the heat of several days, do not count
        List<WaermebedarfVergleich.Tag> tage = new ArrayList<>(tage(LocalDate.of(2022, 11, 1), 10, 7, 8, 12, 50, 5,
                10));
        tage.add(new WaermebedarfVergleich.Tag(LocalDate.of(2022, 11, 20), 7, 8, 12, 0, 7.5, 5, 10));
        tage.add(new WaermebedarfVergleich.Tag(LocalDate.of(2022, 11, 21), 7, 8, 12, 300, 7.5, 5, 10));
        assertEquals(5.0, Zustand.arbeitszahl(tage), 1e-9);
        assertTrue(Zustand.plausibel(tage.get(0)));
    }

    @Test
    public void testPruefungen() {
        List<Zustand.Saison> saisons = List.of(saison("2023/24", 4.0, 15, 10.0, 1.85),
                saison("2024/25", 4.2, 17, 12.0, 1.75), saison("2025/26", 3.6, 21, 25.0, 1.45));
        List<Zustand.Pruefung> p = Zustand.pruefungen(saisons);
        Zustand.Pruefung az = p.get(0);
        assertEquals("Arbeitszahl bei 0 bis 5 °C", az.name());
        assertEquals("2025/26", az.saison());
        assertEquals(4.1, az.vorher(), 1e-9);
        // 3.6 is 12 % below 4.1
        assertEquals("auffällig", az.status());
        // 4.6 against 5.1: just under 10 % below
        assertEquals("beobachten", p.get(1).status());
        // 21 defrosts against 16: 31 % more
        assertEquals("auffällig", p.get(2).status());
        assertEquals("unauffällig", p.get(3).status());
        // Failed starts more than doubled
        assertEquals("auffällig", p.get(4).status());
        // Pressure 1.45 bar: time to refill
        assertEquals("beobachten", p.get(5).status());
        assertEquals(1.75, p.get(5).vorher(), 1e-9);

        Zustand.Pruefung gut = Zustand.pruefung("x", List.of(saison("2024/25", 4.0, 15, 10.0, 1.8),
                saison("2025/26", 3.75, 15, 10.0, 1.8)), s -> s.kalt().arbeitszahl(), "", 2, false, 0.95, 0.90, "");
        assertEquals("beobachten", gut.status());
        assertEquals("zu wenige Daten", Zustand.pruefung("x", List.of(saison("2025/26", 4.0, 15, 10.0, 1.8)),
                s -> s.kalt().arbeitszahl(), "", 2, false, 0.95, 0.90, "").status());
        assertEquals("auffällig", Zustand.druck(List.of(saison("2025/26", 4.0, 15, 10.0, 1.35))).status());
    }

    @Test
    public void testSaisonName() {
        assertEquals(LocalDate.of(2025, 7, 1), Zustand.saisonStart(LocalDate.of(2026, 3, 1)));
        assertEquals(LocalDate.of(2026, 7, 1), Zustand.saisonStart(LocalDate.of(2026, 11, 5)));
        assertEquals("2025/26", Zustand.name(LocalDate.of(2025, 7, 1)));
        assertEquals("2099/00", Zustand.name(LocalDate.of(2099, 7, 1)));
    }

    @Test
    public void testSeite() {
        List<Zustand.Saison> saisons = List.of(saison("2023/24", 4.0, 15, 10.0, 1.85),
                saison("2024/25", 4.1, 15.5, 11.0, 1.8));
        Zustand.Ergebnis e = new Zustand.Ergebnis(saisons, Zustand.pruefungen(saisons), false,
                Instant.parse("2026-11-05T09:00:00Z"));
        String html = ZustandSeite.render(e, null);
        assertTrue(html.contains("<title>Wärmepumpe · Zustand</title>"));
        assertTrue(html.contains("<p class=\"meldung gruen\">Keine Anzeichen von Alterung oder Problemen"));
        // The newest season first
        assertTrue(html.indexOf("<td>2024/25</td>") < html.indexOf("<td>2023/24</td>"));
        assertTrue(html.contains("<td>4,10 (30)</td>"));
        assertTrue(html.contains("<span class=\"status gruen\">unauffällig</span>"));

        List<Zustand.Saison> schlecht = List.of(saison("2023/24", 4.0, 15, 10.0, 1.85),
                saison("2024/25", 3.5, 15, 10.0, 1.8));
        String rot = ZustandSeite.render(new Zustand.Ergebnis(schlecht, Zustand.pruefungen(schlecht), true,
                Instant.EPOCH), null);
        assertTrue(rot.contains("<p class=\"meldung rot\">Auffällig: Arbeitszahl bei 0 bis 5 °C, "
                + "Arbeitszahl bei 5 bis 10 °C"));
        assertTrue(rot.contains("Eine Saison fehlt noch"));
        assertTrue(ZustandSeite.render(null, null).contains("wird berechnet"));
        assertTrue(ZustandSeite.render(null, "InfluxDB ist aus").contains("InfluxDB ist aus"));
    }
}
