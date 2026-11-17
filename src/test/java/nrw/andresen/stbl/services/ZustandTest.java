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
        // Run time per start depends on the settings, shown without a status
        assertEquals("nur Info", p.get(3).status());
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
        // Without values of the steady run and without refills
        assertEquals("Abstand am Verdampfer bei 0 bis 5 °C", p.get(6).name());
        assertEquals("zu wenige Daten", p.get(6).status());
        assertEquals("Druckverlust je Jahr", p.getLast().name());
        assertEquals("zu wenige Daten", p.getLast().status());
    }

    private static Zustand.Saison mitBetrieb(String name, double verdampfer, double spreizung, double schwankung) {
        Zustand.Saison s = saison(name, 4.0, 15, 10.0, 1.8);
        return new Zustand.Saison(s.name(), false, s.heiztage(), s.arbeitszahl(), s.kalt(), s.mild(),
                s.fehlstartsJe1000(), s.druckWinter(), new Zustand.Betrieb(verdampfer, -0.2, spreizung, schwankung));
    }

    @Test
    public void testVergleichMitDenLetztenSaisons() {
        // Only the three seasons before count: the old 10 K of 2020/21 would hide the rise
        List<Zustand.Saison> saisons = List.of(mitBetrieb("2020/21", 10.0, 4.9, 0.10),
                mitBetrieb("2022/23", 8.0, 4.9, 0.10), mitBetrieb("2023/24", 8.0, 4.9, 0.10),
                mitBetrieb("2024/25", 8.0, 4.9, 0.10), mitBetrieb("2025/26", 9.2, 5.6, 0.16));
        List<Zustand.Pruefung> p = Zustand.pruefungen(saisons);
        Zustand.Pruefung verdampfer = p.get(6);
        assertEquals(8.0, verdampfer.vorher(), 1e-9);
        // 1.2 K more: watch
        assertEquals("beobachten", verdampfer.status());
        assertEquals("unauffällig", p.get(7).status());
        // Spread 0.7 K more: watch
        assertEquals("beobachten", p.get(8).status());
        // Pressure swing 60 % more: conspicuous
        assertEquals("auffällig", p.get(9).status());
    }

    @Test
    public void testMeldung() {
        assertEquals("ArbeitszahlBei0Bis5C", Zustand.schluessel("Arbeitszahl bei 0 bis 5 °C"));
        assertEquals("AbstandAmVerfluessiger", Zustand.schluessel("Abstand am Verflüssiger"));
        assertEquals(2, Zustand.stufe("auffällig"));
        assertEquals(1, Zustand.stufe("beobachten"));
        assertEquals(0, Zustand.stufe("unauffällig"));
        assertEquals(-1, Zustand.stufe("nur Info"));
        assertEquals(-1, Zustand.stufe("zu wenige Daten"));
        String text = Zustand.meldung(List.of(new Zustand.Pruefung("Druckschwankung je 10 K", "2026/27 (laufend)",
                0.172, 0.127, "bar", 3, "beobachten", "Vordruck prüfen lassen.")));
        assertTrue(text.contains("Druckschwankung je 10 K: beobachten\n2026/27 (laufend) 0,172 bar, Mittel davor "
                + "0,127 bar\nVordruck prüfen lassen."));
    }

    @Test
    public void testLangsameVeraenderung() {
        // 0.3 K worse every winter: only 0.6 K above the three seasons before, but 1.05 K above the first two
        List<Zustand.Saison> saisons = List.of(mitBetrieb("2022/23", 8.0, 4.9, 0.10),
                mitBetrieb("2023/24", 8.3, 4.9, 0.10), mitBetrieb("2024/25", 8.6, 4.9, 0.10),
                mitBetrieb("2025/26", 8.9, 4.9, 0.10), mitBetrieb("2026/27", 9.2, 4.9, 0.10));
        Zustand.Pruefung verdampfer = Zustand.pruefungen(saisons).get(6);
        assertEquals(8.6, verdampfer.vorher(), 1e-9);
        assertEquals(8.15, verdampfer.bezug(), 1e-9);
        assertEquals("beobachten", verdampfer.status());
        // Without enough seasons there is no fixed reference
        assertNull(Zustand.pruefungen(saisons.subList(0, 2)).get(6).bezug());
        // The efficiency depends on the settings and has no reference
        assertNull(Zustand.pruefungen(saisons).get(0).bezug());
        assertTrue(Zustand.meldung(List.of(verdampfer)).contains("Mittel davor 8,6 K, Bezug 8,2 K\nGrenze: "
                + "beobachten ab +1,0 K, auffällig ab +2,0 K gegen das Mittel davor oder den Bezug"));
    }

    @Test
    public void testRegeln() {
        List<Zustand.Saison> saisons = List.of(mitBetrieb("2024/25", 8.0, 4.9, 0.10),
                mitBetrieb("2025/26", 8.0, 4.9, 0.10));
        List<Zustand.Pruefung> p = Zustand.pruefungen(saisons);
        // Every check says when it reports
        p.forEach(x -> assertTrue(x.regel() != null, x.name()));
        assertEquals("beobachten ab −5 %, auffällig ab −10 % gegen das Mittel davor", p.get(0).regel());
        assertEquals("beobachten ab +15 %, auffällig ab +30 % gegen das Mittel davor", p.get(2).regel());
        assertEquals("keine Meldung, hängt von den Einstellungen ab", p.get(3).regel());
        assertEquals("beobachten ab +0,5 K, auffällig ab +1,0 K gegen das Mittel davor oder den Bezug",
                p.get(8).regel());
        assertEquals("beobachten ab +25 %, auffällig ab +50 % gegen das Mittel davor oder den Bezug",
                p.get(9).regel());
        String html = ZustandSeite.render(new Zustand.Ergebnis(saisons, p, false, Instant.EPOCH), null);
        assertTrue(html.contains("<span class=\"pregel\">Meldung: beobachten unter 1,5 bar"));
        assertTrue(html.contains("bei „auffällig“ auch eine Push-Nachricht"));
    }

    @Test
    public void testSaettigung() {
        assertEquals(0.0, Zustand.saettigung(7.99), 1e-9);
        // 20.5 bar high pressure: about 33 °C condensation
        assertEquals(33.2, Zustand.saettigung(20.5), 1e-9);
        assertNull(Zustand.saettigung(1.0));
    }

    @Test
    public void testBetrieb() {
        Map<Instant, Double> leistung = new java.util.TreeMap<>();
        Map<Instant, Double> nd = new java.util.TreeMap<>();
        Map<Instant, Double> hd = new java.util.TreeMap<>();
        Map<Instant, Double> vl = new java.util.TreeMap<>();
        Map<Instant, Double> rl = new java.util.TreeMap<>();
        Map<Instant, Double> aussen = new java.util.TreeMap<>();
        Map<Instant, Double> druck = new java.util.TreeMap<>();
        Instant start = Instant.parse("2025-12-01T00:00:00Z");
        for (int i = 0; i < 6 * 144; i++) {
            Instant t = start.plusSeconds(600L * i);
            double vorlauf = 32 + (i % 144) / 36.0;
            leistung.put(t, 800.0);
            nd.put(t, 5.73);
            hd.put(t, 21.4);
            vl.put(t, vorlauf);
            rl.put(t, vorlauf - 5);
            aussen.put(t, 2.0);
            druck.put(t, 1.5 + 0.01 * (vorlauf - 2.5));
        }
        Zustand.Betrieb b = Zustand.betrieb(leistung, nd, hd, vl, rl, aussen, druck, java.util.Set.of());
        // 2 °C outside, −10 °C evaporation at 5.73 bar
        assertEquals(12.0, b.verdampferAbstand(), 1e-9);
        // 35 °C condensation at 21.4 bar, flow 32 to 36 °C
        assertEquals(1.0, b.verfluessigerAbstand(), 0.05);
        assertEquals(5.0, b.spreizung(), 1e-9);
        // Every day has the same mean water temperature, so there is no pressure swing across the days
        assertNull(b.druckschwankung());
        // At standstill nothing counts
        leistung.replaceAll((t, w) -> 0.0);
        assertEquals(Zustand.Betrieb.LEER, Zustand.betrieb(leistung, nd, hd, vl, rl, aussen, druck,
                java.util.Set.of()));
    }

    @Test
    public void testDruckschwankungJeMonat() {
        Map<Instant, Double> leistung = new java.util.TreeMap<>();
        Map<Instant, Double> vl = new java.util.TreeMap<>();
        Map<Instant, Double> rl = new java.util.TreeMap<>();
        Map<Instant, Double> druck = new java.util.TreeMap<>();
        Map<Instant, Double> leer = Map.of();
        // December and January: every day 8 h running at a daily water temperature of 27 to 33 °C, the pressure
        // 0.03 bar per K around 1.8 bar at 30 °C; within the day the pressure jumps by the pump
        Instant start = Instant.parse("2025-11-30T23:00:00Z");
        for (int tag = 0; tag < 62; tag++) {
            double wasser = 27 + tag % 7;
            for (int i = 0; i < 48; i++) {
                Instant t = start.plusSeconds(86400L * tag + 600L * i);
                leistung.put(t, 800.0);
                vl.put(t, wasser + 2.5);
                rl.put(t, wasser - 2.5);
                druck.put(t, 1.8 + 0.03 * (wasser - 30) + (i % 2 == 0 ? 0.05 : -0.05));
            }
        }
        Zustand.Betrieb b = Zustand.betrieb(leistung, leer, leer, vl, rl, leer, druck, java.util.Set.of());
        // 0.3 bar per 10 K at 1.8 bar and 30 °C, the factor for other pressures and temperatures is close to 1
        assertEquals(0.3, b.druckschwankung(), 0.01);
        // A month with a refill does not count, one month is too little
        assertNull(Zustand.betrieb(leistung, leer, leer, vl, rl, leer, druck,
                java.util.Set.of(java.time.YearMonth.of(2025, 12))).druckschwankung());
        // The swing grows with the square of the absolute pressure and with the expansion of water
        assertEquals(1.0, Zustand.normierung(1.8, 30), 1e-9);
        assertEquals(Math.pow(3.013 / 2.813, 2), Zustand.normierung(2.0, 30), 1e-9);
        assertEquals(3.45 / 3.03, Zustand.normierung(1.8, 35), 1e-9);
    }

    @Test
    public void testNachfuellungen() {
        Map<Instant, Double> stuendlich = new java.util.TreeMap<>();
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        int stunden = 24 * 400;
        for (int i = 0; i < stunden; i++) {
            double p = 1.5 + (i % 24 == 6 ? 0.14 : 0) + (i >= 100 ? 0.25 : 0) + (i >= 100 + 24 * 365 ? 0.2 : 0);
            stuendlich.put(start.plusSeconds(3600L * i), p);
        }
        List<Zustand.Nachfuellung> n = Zustand.nachfuellungen(stuendlich);
        // The daily rise by heating is no refill
        assertEquals(2, n.size());
        assertEquals(start.plusSeconds(3600L * 100), n.get(0).zeit());
        assertEquals(0.25, n.get(0).bar(), 0.02);
        assertNull(n.get(0).barProJahr());
        assertEquals(0.2, n.get(1).barProJahr(), 0.01);

        Zustand.Nachfuellung a = new Zustand.Nachfuellung(Instant.parse("2024-02-15T16:00:00Z"), 0.28, 0.15);
        Zustand.Nachfuellung b = new Zustand.Nachfuellung(Instant.parse("2025-04-28T14:00:00Z"), 0.29, 0.24);
        assertEquals("unauffällig", Zustand.druckverlust(List.of(a, b, new Zustand.Nachfuellung(
                Instant.parse("2026-10-13T13:00:00Z"), 0.23, 0.16))).status());
        Zustand.Pruefung schlecht = Zustand.druckverlust(List.of(a, b, new Zustand.Nachfuellung(
                Instant.parse("2026-10-13T13:00:00Z"), 0.4, 0.35)));
        assertEquals("auffällig", schlecht.status());
        assertEquals("13.10.2026", schlecht.saison());
        assertEquals(0.195, schlecht.vorher(), 1e-9);
        assertEquals(0.195, schlecht.bezug(), 1e-9);
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
        assertTrue(html.contains("Keine Nachfüllung erkannt."));
        assertTrue(html.contains("<th>Mittel davor</th><th>Bezug</th>"));
        String mitNachfuellung = ZustandSeite.render(new Zustand.Ergebnis(saisons, Zustand.pruefungen(saisons), false,
                Instant.EPOCH, List.of(new Zustand.Nachfuellung(Instant.parse("2026-10-13T13:00:00Z"), 0.23, 0.16))),
                null);
        assertTrue(mitNachfuellung.contains("<td>13.10.2026</td><td>0,23</td><td>0,16</td>"));
        assertTrue(ZustandSeite.render(null, null).contains("wird berechnet"));
        assertTrue(ZustandSeite.render(null, "InfluxDB ist aus").contains("InfluxDB ist aus"));
    }
}
