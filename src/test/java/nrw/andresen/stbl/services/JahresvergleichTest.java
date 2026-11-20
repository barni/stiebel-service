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
    // The wallbox came new in May 2026
    private static final Geraete.Geraet WALLBOX = new Geraete.Geraet("Wallbox", "wallbox_energy", "", false);

    /**
     * Heating 2024 with 90 kWh per month, 2025 with 100, 2026 up to September 110 and October so far 60 against 50
     * (2025) and 40 (2024) up to the same day. Household 300 kWh per month in 2025, 200 so far in October 2026.
     * Wallbox 150 kWh per month from May 2026, 80 so far in October.
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
        Map<YearMonth, Double> wallbox = new TreeMap<>();
        for (int m = 5; m <= 9; m++) {
            wallbox.put(YearMonth.of(2026, m), 150.0);
        }
        wallbox.put(YearMonth.of(2026, 10), 80.0);
        Map<YearMonth, Double> aussen = Map.of(YearMonth.of(2024, 1), 3.1, YearMonth.of(2025, 1), 2.9,
                YearMonth.of(2026, 1), 2.8);
        return Jahresvergleich.vergleich(2026, HEUTE, heizung, Map.of(2024, 40.0, 2025, 50.0),
                mitHaushalt ? haushalt : Map.of(), mitHaushalt,
                mitHaushalt ? java.util.List.of(WALLBOX) : java.util.List.of(),
                mitHaushalt ? java.util.List.of(wallbox) : java.util.List.of(), aussen,
                mitHaushalt ? 7800.0 : null, mitHaushalt ? 8000.0 : null, null,
                mitHaushalt ? java.util.List.of(new Jahresvergleich.GleicheTage(830.0, 0.0, null)) : java.util.List.of(),
                Instant.parse("2026-10-24T09:00:00Z"), java.util.List.of());
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
        assertEquals(-2.5, v.gesamtAenderung(), 1e-9);
        assertNull(vergleich(false).gesamtAenderung());
        assertEquals(5 * 150 + 80, v.geraet(0, 2), 1e-9);
        assertNull(v.geraet(0, 1));
        assertTrue(vergleich(false).geraete().isEmpty());
        assertEquals(80.0, okt.geraet(0, 2));
    }

    @Test
    public void testAufteilung() {
        java.util.List<Jahresvergleich.Anteil> anteile = vergleich(true).aufteilung();
        // Same order as the forecast: total, heating, rest, devices
        assertEquals(java.util.List.of("Gesamt", "Heizung", "Rest (Haushalt)", "Wallbox"),
                anteile.stream().map(Jahresvergleich.Anteil::name).toList());
        assertEquals(-200, anteile.get(0).differenz(), 1e-9);
        assertEquals(830, anteile.get(3).differenz(), 1e-9);
        assertNull(anteile.get(3).prozent());
        assertEquals(100, anteile.get(1).differenz(), 1e-9);
        // Rest = grid import minus heating minus wallbox
        assertEquals(8000 - 950, anteile.get(2).vorher(), 1e-9);
        assertEquals(7800 - 1050 - 830, anteile.get(2).jetzt(), 1e-9);
        assertTrue(vergleich(false).aufteilung().isEmpty());

        assertEquals("Der Stromverbrauch ist gegenüber denselben Tagen 2025 um 200 kWh (2,5 %) gesunken, vor allem "
                + "beim übrigen Haushalt (−1.130 kWh).", JahresvergleichSeite.ursache(anteile, 2026));
        java.util.List<Jahresvergleich.Anteil> mehr = java.util.List.of(new Jahresvergleich.Anteil("Gesamt", 7135, 7814),
                new Jahresvergleich.Anteil("Wallbox", 0, 651), new Jahresvergleich.Anteil("Heizung", 1982, 2014),
                new Jahresvergleich.Anteil("Rest (Haushalt)", 5153, 5149));
        assertEquals("Der Stromverbrauch ist gegenüber denselben Tagen 2025 um 679 kWh (9,5 %) gestiegen, vor allem "
                + "bei Wallbox (+651 kWh).", JahresvergleichSeite.ursache(mehr, 2026));
        assertEquals("Der Stromverbrauch ist etwa gleich geblieben wie in denselben Tagen 2025.",
                JahresvergleichSeite.ursache(java.util.List.of(new Jahresvergleich.Anteil("Gesamt", 7135, 7160)), 2026));

        String karte = JahresvergleichSeite.ursachen(new Jahresvergleich.Vergleich(java.util.List.of(2024, 2025, 2026),
                HEUTE, java.util.List.of(), true, java.util.List.of(WALLBOX), 7814.0, 7135.0, null,
                java.util.List.of(new Jahresvergleich.GleicheTage(651.0, 0.0, null)), Instant.EPOCH, java.util.List.of()));
        assertTrue(karte.contains("<h2>Woran liegt es?</h2>"));
        // A rise above 5 % is marked
        assertTrue(karte.contains("<p class=\"ursache steigt\">"));
        assertTrue(karte.contains("<td>Gesamt</td><td>7.135</td><td>7.814</td><td>+679 kWh (+9,5 %)</td><td>–</td>"));
        assertTrue(karte.contains("<td>Wallbox</td><td>0</td><td>651</td><td>+651 kWh</td><td>96 %</td>"));
        assertFalse(karte.contains("-0 %"));
        // Two years at the same days only without the grid import of two years before
        assertFalse(karte.contains("2024 kWh"));
        // The tiles of the heating only without the house meter, otherwise they repeat the card and the table
        assertFalse(JahresvergleichSeite.render(vergleich(true), null, false, false).contains("ganzes Jahr</"));
        assertTrue(JahresvergleichSeite.render(vergleich(false), null, false, false).contains("Heizung ≈ 2025 ganzes Jahr"));
    }

    @Test
    public void testAufteilungDreiJahre() {
        Jahresvergleich.Vergleich zwei = vergleich(true);
        Jahresvergleich.Vergleich v = new Jahresvergleich.Vergleich(zwei.jahre(), zwei.bis(), zwei.monate(), true,
                zwei.geraete(), zwei.gesamt(), zwei.gesamtVorjahrGleich(), 7935.0,
                java.util.List.of(new Jahresvergleich.GleicheTage(830.0, 0.0, 0.0)), zwei.berechnet(), java.util.List.of());
        java.util.List<Jahresvergleich.Anteil> anteile = v.aufteilung();
        Jahresvergleich.Anteil gesamt = anteile.get(0);
        assertEquals(7935.0, gesamt.vorvorher(), 1e-9);
        // The heating of two years before up to the same day, no wallbox then, the rest from the grid import
        Jahresvergleich.Anteil heizung = anteile.stream().filter(a -> a.name().equals("Heizung")).findFirst().orElseThrow();
        assertEquals(v.heizungGleich(0), heizung.vorvorher(), 1e-9);
        Jahresvergleich.Anteil wallbox = anteile.stream().filter(a -> a.name().equals("Wallbox")).findFirst().orElseThrow();
        assertEquals(0.0, wallbox.vorvorher(), 1e-9);
        Jahresvergleich.Anteil rest = anteile.get(Jahresvergleich.Vergleich.REST);
        assertEquals(7935.0 - v.heizungGleich(0), rest.vorvorher(), 1e-9);

        String karte = JahresvergleichSeite.ursachen(v);
        // 2024 only for information, every change against 2025
        assertTrue(karte.contains("<div class=\"tbl vorvorjahr\"><table><thead><tr><th>1. Januar bis 24. Oktober</th>"
                + "<th>2024 kWh</th><th>2025 kWh</th><th>2026 kWh</th><th>Änderung zu 2025</th>"
                + "<th>Anteil an der Änderung</th>"));
        assertFalse(karte.contains("Änderung zu 2024"));
        assertTrue(karte.contains("<td>Gesamt</td><td>7.935</td>"));
        assertEquals(new Jahresvergleich.Anteil("x", 1, 2), new Jahresvergleich.Anteil("x", 1, 2, null));
    }

    @Test
    public void testPrognose() {
        Jahresvergleich.Vergleich v = vergleich(true);
        java.util.List<Jahresvergleich.Prognose> p = v.prognose();
        assertEquals(java.util.List.of("Gesamt", "Heizung", "Rest (Haushalt)", "Wallbox"),
                p.stream().map(Jahresvergleich.Prognose::name).toList());
        // Heating: rest of 2025 (1200 - 950) and of 2024 (1080 - 850), mean 240
        Jahresvergleich.Prognose heizung = p.get(1);
        assertEquals(1050, heizung.bisher(), 1e-9);
        assertEquals(240, heizung.erwartet(), 1e-9);
        assertEquals(1290, heizung.jahr(), 1e-9);
        assertEquals((1290.0 / 1200 - 1) * 100, heizung.aenderung(), 1e-9);
        // Household: so far 7800 - 1050 - 830, the rest of 2025 is 3600 - (8000 - 950)... not below 0
        Jahresvergleich.Prognose rest = p.get(2);
        assertEquals(5920, rest.bisher(), 1e-9);
        assertEquals(0, rest.erwartet(), 1e-9);
        // Wallbox: July to September 450 kWh in 92 days, 69 days left including today
        Jahresvergleich.Prognose wallbox = p.get(3);
        assertEquals(450.0 / 92, v.jeTag(0), 1e-9);
        assertEquals(830 + 450.0 / 92 * 69, wallbox.jahr(), 1e-9);
        assertNull(wallbox.aenderung());
        // The total is the sum of the parts
        assertEquals(heizung.jahr() + rest.jahr() + wallbox.jahr(), p.get(0).jahr(), 1e-9);
        assertEquals(1200 + 3600, p.get(0).vorjahr(), 1e-9);

        // Without the house meter only the heating
        assertEquals(java.util.List.of("Heizung"), vergleich(false).prognose().stream()
                .map(Jahresvergleich.Prognose::name).toList());
        String karte = JahresvergleichSeite.prognose(vergleich(false));
        assertTrue(karte.contains("<h2>Prognose bis Jahresende</h2>"));
        assertTrue(karte.contains("Die Heizung wird 2026 voraussichtlich 1.290 kWh brauchen, 7,5 % mehr als 2025 "
                + "(1.200 kWh)."));
        assertTrue(karte.contains("<td>Heizung ≈</td><td>1.050</td><td>240</td><td>1.290</td><td>1.200</td>"
                + "<td>+7,5 %</td>"));
        assertTrue(JahresvergleichSeite.render(vergleich(true), null, false, false).contains("Das Haus wird 2026 "
                + "voraussichtlich"));
        assertTrue(JahresvergleichSeite.render(vergleich(false), null, false, false).contains("Prognose bis Jahresende"));
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
                java.util.List.of(Map.of(mai, 129.0)));
        assertEquals(710 - 94 - 129, haushalt.get(mai), 1e-9);
        // No wallbox value: without wallbox
        assertEquals(555 - 25, haushalt.get(juni), 1e-9);
        // Grid import unknown
        assertFalse(haushalt.containsKey(juli));
        // Not below 0
        assertEquals(0, Jahresvergleich.haushalt(Map.of(mai, 50.0), Map.of(mai, 94.0), java.util.List.of()).get(mai), 1e-9);
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
        // Household and wallbox switched off
        String html = JahresvergleichSeite.render(vergleich(true), null, false, false);
        assertTrue(html.contains("<title>Wärmepumpe · Jahresvergleich</title>"));
        assertTrue(html.contains("<a class=\"nav\" href=\"status\">← Status</a>"));
        assertTrue(html.contains("Netzbezug des Hauses und Strom der Heizung, 2024 bis 2026"));
        // With the house meter no sentence at the top, the tiles say the same
        assertFalse(html.contains("<p class=\"meldung\">"));
        assertTrue(html.contains("<div class=\"label\">Gesamt Veränderung gleiche Tage</div><div class=\"value\">−2,5 %</div>"));
        assertTrue(html.contains("<h2>Netzbezug je Jahr</h2>"));
        assertTrue(html.contains("2026 Prognose"));
        // Each year after the first with its change against the year before
        assertEquals(2, html.split("<tspan class=\"aenderung\"").length - 1);
        assertTrue(html.contains("<h2>Woran liegt es?</h2><p class=\"ursache\">"));
        assertTrue(html.contains("<polyline class=\"abstand gesamt\""));
        assertTrue(html.contains("<polyline class=\"abstand g0\""));
        assertFalse(html.contains("Haushalt 2025 ganzes Jahr"));
        // The table: grid import per year first, then the heating
        assertTrue(html.contains("<th>Gesamt 2026 kWh</th><th>Änderung</th><th>Heizung ≈ 2024 kWh</th>"));
        assertTrue(html.contains("<td>60 (bis 24.)</td>"));
        // 12 bars each for 2024 and 2025, 10 for 2026 with the current month lighter, no household
        assertEquals(12, html.split("<rect class=\"j0\"").length - 1);
        assertEquals(12, html.split("<rect class=\"j1\"").length - 1);
        assertEquals(9, html.split("<rect class=\"j2\"").length - 1);
        assertEquals(1, html.split("<rect class=\"j2 laufend\"").length - 1);
        assertFalse(html.contains("<rect class=\"haushalt"));
        assertTrue(html.contains("data-wert=\"Okt 2025: Heizung 100 kWh; bis zum gleichen Tag 50 kWh\""));
        assertTrue(html.contains("href=\"jahresvergleich?geraete=nein\">Haushaltsstrom einblenden</a>"));
        assertTrue(html.contains("href=\"jahresvergleich?haushalt=nein\">Geräte einblenden</a>"));
        assertFalse(html.contains("<rect class=\"geraet"));

        String mit = JahresvergleichSeite.render(vergleich(true), null, true, false);
        assertTrue(mit.contains("href=\"jahresvergleich?haushalt=nein&amp;geraete=nein\">Haushaltsstrom ausblenden</a>"));
        // The other switch keeps the household shown
        assertTrue(mit.contains("href=\"jahresvergleich\">Geräte einblenden</a>"));
        assertEquals(12, mit.split("<rect class=\"haushalt j1\"").length - 1);
        assertEquals(1, mit.split("<rect class=\"haushalt j2 laufend\"").length - 1);
        assertTrue(mit.contains("data-wert=\"Okt 2026 bis 24.: Heizung 60 kWh, Haushalt 200 kWh\""));

        // Shown by default
        String beide = JahresvergleichSeite.render(vergleich(true), null, true, true);
        assertTrue(beide.contains("href=\"jahresvergleich?haushalt=nein\">Haushaltsstrom ausblenden</a>"));
        assertTrue(beide.contains("href=\"jahresvergleich?geraete=nein\">Geräte ausblenden</a>"));
        // The wallbox sits on top of the household: October 2026 from 60 + 200 to 340 kWh
        assertEquals(5, beide.split("<rect class=\"geraet g0 j2\"").length - 1);
        assertEquals(1, beide.split("<rect class=\"geraet g0 j2 laufend\"").length - 1);
        assertTrue(beide.contains("data-wert=\"Okt 2026 bis 24.: Heizung 60 kWh, Haushalt 200 kWh, Wallbox 80 kWh\""));

        // Without the house meter only the heating, as before
        String ohne = JahresvergleichSeite.render(vergleich(false), null, true, true);
        assertFalse(ohne.contains("Haushaltsstrom einblenden"));
        assertFalse(ohne.contains("Geräte einblenden"));
        assertFalse(ohne.contains("<rect class=\"geraet"));
        assertFalse(ohne.contains("<rect class=\"haushalt"));
        assertTrue(ohne.contains("wenn in der Konfiguration der Hauszähler eingetragen ist"));
        assertFalse(ohne.contains("Gesamt Veränderung"));
        assertFalse(ohne.contains("Woran liegt es?"));
        assertFalse(ohne.contains("Netzbezug je Jahr"));
        assertTrue(ohne.contains("<th>Heizung ≈ 2026 kWh</th><th>Änderung</th>"));
        assertTrue(ohne.contains("Vom 1. Januar bis 24. Oktober 2026 hat die Heizung 1.050 kWh Strom gebraucht, "
                + "10,5 % mehr als in denselben Tagen 2025 (950 kWh)."));
        // Not calculated yet, or InfluxDB switched off
        assertTrue(JahresvergleichSeite.render(null, null, false, false).contains("wird berechnet"));
        assertTrue(JahresvergleichSeite.render(null, "InfluxDB ist aus", false, false).contains("InfluxDB ist aus"));
    }

    @Test
    public void testSchritt() {
        assertEquals(200, JahresvergleichSeite.schritt(773), 1e-9);
        assertEquals(20, JahresvergleichSeite.schritt(90), 1e-9);
        assertEquals(1, JahresvergleichSeite.schritt(0), 1e-9);
    }

    @Test
    public void testAlleJahreUndZeitraum() {
        Jahresvergleich.Vergleich zwei = vergleich(true);
        // Four years that are over: the bars show all of them and the current one, none drops out on the 1st of January
        java.util.List<Jahresvergleich.Jahressumme> summen = java.util.List.of(
                new Jahresvergleich.Jahressumme(2022, 9000, 2500, java.util.List.of(0.0)),
                new Jahresvergleich.Jahressumme(2023, 9200, 2600, java.util.List.of(0.0)),
                new Jahresvergleich.Jahressumme(2024, 9667, 2806, java.util.List.of(0.0)),
                new Jahresvergleich.Jahressumme(2025, 9635, 3016, java.util.List.of(0.0)));
        assertEquals(6861, summen.get(2).haushalt(), 1e-9);
        Jahresvergleich.Vergleich v = new Jahresvergleich.Vergleich(zwei.jahre(), zwei.bis(), zwei.monate(), true,
                zwei.geraete(), zwei.gesamt(), zwei.gesamtVorjahrGleich(), null, zwei.geraeteGleich(), zwei.berechnet(),
                summen);
        String balken = JahresvergleichSeite.jahresbalken(v);
        for (String jahr : java.util.List.of(">2022<", ">2023<", ">2024<", ">2025<", ">2026 Prognose<")) {
            assertTrue(balken.contains(jahr), jahr);
        }
        assertEquals(4, balken.split("<tspan class=\"aenderung\"").length - 1);

        // The period between two readings
        java.time.LocalDate von = java.time.LocalDate.of(2025, 12, 1);
        java.time.LocalDate bis = java.time.LocalDate.of(2026, 11, 30);
        Jahresvergleich.Zeitraum z = new Jahresvergleich.Zeitraum(von, bis,
                bis.plusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), 58000.0, 67500.0, 3100,
                java.util.List.of("Wallbox", "Warmwasser"), java.util.Arrays.asList(900.0, null));
        assertEquals(9500, z.gesamt(), 1e-9);
        assertEquals(5500, z.haushalt(), 1e-9);
        assertEquals(365, z.tage());
        String karte = JahresvergleichSeite.zeitraumKarte(v, z, null, null);
        assertTrue(karte.contains("<input type=\"date\" name=\"von\" required value=\"2025-12-01\">"));
        assertTrue(karte.contains("<td>Zählerstand 01.12.2025 0:00</td><td>58.000,0</td>"));
        assertTrue(karte.contains("<td>Zählerstand 30.11.2026 24:00</td><td>67.500,0</td>"));
        assertTrue(karte.contains("<td>Netzbezug (365 Tage)</td><td>9.500 · 26,0 je Tag</td>"));
        assertTrue(karte.contains("<td>davon Wallbox</td><td>900</td>"));
        // A device without values in the period counts to the household
        assertTrue(karte.contains("<td>davon Warmwasser</td><td>nicht gemessen</td>"));
        assertTrue(karte.contains("<td>davon Haushalt mit Warmwasser</td><td>5.500</td>"));
        // Without a period only the form, without the house meter nothing
        assertTrue(JahresvergleichSeite.zeitraumKarte(v, null, von, bis).contains("value=\"2026-11-30\""));
        assertFalse(JahresvergleichSeite.zeitraumKarte(v, null, von, bis).contains("Zählerstand"));
        assertEquals("", JahresvergleichSeite.zeitraumKarte(vergleich(false), null, von, bis));
        assertTrue(JahresvergleichSeite.render(v, null, true, true, z).contains("id=\"zeitraum\""));
    }

    @Test
    public void testGeraetErstSeitDiesemJahr() {
        Jahresvergleich.Vergleich zwei = vergleich(true);
        // Air conditioning measured only since this year, it ran before: counted in the rest for the comparison
        Geraete.Geraet klima = new Geraete.Geraet("Klimaanlage", "klima_energy", "", true);
        Jahresvergleich.Vergleich v = new Jahresvergleich.Vergleich(zwei.jahre(), zwei.bis(), zwei.monate(), true,
                java.util.List.of(WALLBOX, klima), zwei.gesamt(), zwei.gesamtVorjahrGleich(), null,
                java.util.List.of(new Jahresvergleich.GleicheTage(830.0, 0.0, null),
                        new Jahresvergleich.GleicheTage(300.0, null, null)), zwei.berechnet(), java.util.List.of());
        assertEquals(java.util.List.of(0), v.gezeigt());
        assertEquals(java.util.List.of("Klimaanlage"), v.imRest());
        java.util.List<Jahresvergleich.Anteil> anteile = v.aufteilung();
        assertEquals(java.util.List.of("Gesamt", "Heizung", "Rest (Haushalt mit Klimaanlage)", "Wallbox"),
                anteile.stream().map(Jahresvergleich.Anteil::name).toList());
        // The rest keeps the air conditioning in both years, so it compares like with like
        assertEquals(7800 - 1050 - 830, anteile.get(2).jetzt(), 1e-9);
        String karte = JahresvergleichSeite.ursachen(v);
        assertTrue(karte.contains("Klimaanlage zählt zum Rest, bis es für beide Jahre Messwerte gibt."));
        // In the bar of the current year it has its own part all the same, taken out of the household
        String balken = JahresvergleichSeite.jahresbalken(v);
        assertTrue(balken.contains("2026 (Prognose): Klimaanlage 300 kWh von"));
        // In the tables below the rest as "davon", without a change against the year before
        assertTrue(karte.contains("<tr class=\"davon\"><td>davon Klimaanlage</td><td>nicht gemessen</td><td>300</td>"
                + "<td>–</td><td>–</td></tr>"));
        assertTrue(JahresvergleichSeite.prognose(v).contains("<tr class=\"davon\"><td>davon Klimaanlage</td><td>300</td>"));
        // A value estimated after the last reading by hand is named
        Jahresvergleich.Vergleich ww = new Jahresvergleich.Vergleich(zwei.jahre(), zwei.bis(), zwei.monate(), true,
                java.util.List.of(new Geraete.Geraet("Warmwasser", "", "warmwasser_manuell", true)), zwei.gesamt(),
                zwei.gesamtVorjahrGleich(), null, java.util.List.of(new Jahresvergleich.GleicheTage(560.0, 650.0,
                null, true)), zwei.berechnet(), java.util.List.of());
        assertTrue(JahresvergleichSeite.ursachen(ww).contains("Warmwasser: nach der letzten Ablesung mit dem Verbrauch "
                + "der Wochen davor hochgerechnet"));
    }

    @Test
    public void testGeraeteLesen() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("auswertung.haushalt.wallbox", "wallbox_energy")
                .withProperty("auswertung.geraet.1.name", "Warmwasser")
                .withProperty("auswertung.geraet.1.zaehler", "warmwasser_energy")
                .withProperty("auswertung.geraet.1.davor", "warmwasser_manuell")
                .withProperty("auswertung.geraet.2.name", "Klimaanlage")
                .withProperty("auswertung.geraet.2.zaehler", "klima_energy")
                .withProperty("auswertung.geraet.2.vorher", "false")
                .withProperty("auswertung.geraet.3.name", "ohne Zähler");
        java.util.List<Geraete.Geraet> liste = Geraete.lesen(env);
        assertEquals(java.util.List.of(new Geraete.Geraet("Wallbox", "wallbox_energy", "", false),
                new Geraete.Geraet("Warmwasser", "warmwasser_energy", "warmwasser_manuell", true),
                new Geraete.Geraet("Klimaanlage", "klima_energy", "", false)), liste);
        // The former setting of the wallbox only while it is not in the table
        assertEquals(2, Geraete.lesen(new org.springframework.mock.env.MockEnvironment()
                .withProperty("auswertung.haushalt.wallbox", "wallbox_energy")
                .withProperty("auswertung.geraet.5.name", "Wallbox")
                .withProperty("auswertung.geraet.5.zaehler", "wallbox_energy")
                .withProperty("auswertung.geraet.5.vorher", "false")
                .withProperty("auswertung.geraet.1.name", "Warmwasser")
                .withProperty("auswertung.geraet.1.davor", "warmwasser_manuell")).size());
    }

    @Test
    public void testReiheFlux() {
        String flux = Jahresvergleich.reiheFlux("home_assistant", "warmwasser_energy", true);
        assertTrue(flux.contains("r.entity_id == \"warmwasser_energy\""));
        assertTrue(flux.contains("aggregateWindow(every: 1d, fn: last"));
        assertTrue(flux.contains("Europe/Berlin"));
        assertFalse(Jahresvergleich.reiheFlux("home_assistant", "warmwasser_manuell", false).contains("aggregateWindow"));
        assertThrows(IllegalArgumentException.class, () -> Jahresvergleich.reiheFlux("x", "a\" or true", false));
    }

    private static Jahresvergleich.Vergleich mit(Jahresvergleich.Vergleich v, java.util.List<Double> gradtage,
                                                 Double preis, java.util.List<Jahresvergleich.GleicheTage> gleich) {
        return new Jahresvergleich.Vergleich(v.jahre(), v.bis(), v.monate(), v.mitHaushalt(), v.geraete(), v.gesamt(),
                v.gesamtVorjahrGleich(), v.gesamtVorvorjahrGleich(), gleich, v.berechnet(), v.jahressummen(),
                gradtage, preis);
    }

    @Test
    public void testGradtage() {
        Map<LocalDate, Double> tage = new TreeMap<>();
        // 10 days: 5 at 0 °C (20 each), 4 at 16 °C (none), one missing
        for (int d = 1; d <= 5; d++) {
            tage.put(LocalDate.of(2026, 1, d), 0.0);
        }
        for (int d = 6; d <= 9; d++) {
            tage.put(LocalDate.of(2026, 1, d), 16.0);
        }
        // The missing day counts as the mean of the others: 100 / 9 * 10
        assertEquals(100.0 / 9 * 10, Jahresvergleich.gradtage(tage, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 11)),
                1e-9);
        // The day bis is not over and does not count
        assertEquals(100.0, Jahresvergleich.gradtage(tage, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6)), 1e-9);
        // Too many days missing
        assertNull(Jahresvergleich.gradtage(tage, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20)));
        assertNull(Jahresvergleich.gradtage(tage, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    public void testWetter() {
        Jahresvergleich.Vergleich ohne = vergleich(true);
        assertNull(ohne.wetter());
        assertFalse(JahresvergleichSeite.ursachen(ohne).contains("davon Wetter"));
        // Heating on the same days: 2025 950 kWh, 2026 1050 kWh; 10 % more degree days explain 95 kWh
        Jahresvergleich.Vergleich v = mit(ohne, java.util.List.of(2100.0, 2000.0, 2200.0), null, ohne.geraeteGleich());
        assertEquals(95, v.wetter(), 1e-9);
        assertEquals(5, v.bereinigt(), 1e-9);
        String karte = JahresvergleichSeite.ursachen(v);
        assertTrue(karte.contains("<td>davon Wetter</td><td></td><td></td><td>+95 kWh</td><td>–</td>"), karte);
        assertTrue(karte.contains("<td>davon wetterbereinigt</td><td></td><td></td><td>+5 kWh</td>"));
        assertTrue(karte.contains("Das Wetter war kälter als 2025: 2.200 statt 2.000 Heizgradtage (+10,0 %). Das "
                + "erklärt +95 kWh der Heizung; wetterbereinigt braucht sie 5 kWh mehr."), karte);
        // The weather rows follow the heating
        assertTrue(karte.indexOf("<td>Heizung ≈</td>") < karte.indexOf("davon Wetter"));
        assertTrue(karte.indexOf("davon Wetter") < karte.indexOf("<td>Rest (Haushalt)</td>"));
    }

    @Test
    public void testKosten() {
        Jahresvergleich.Vergleich ohne = vergleich(true);
        assertFalse(JahresvergleichSeite.prognose(ohne).contains("€"));
        Jahresvergleich.Vergleich v = mit(ohne, java.util.List.of(), 0.30, ohne.geraeteGleich());
        String prognose = JahresvergleichSeite.prognose(v);
        assertTrue(prognose.contains("<th>Prognose €</th>"));
        assertTrue(prognose.contains("Kosten mit 30,0 ct/kWh, ohne Grundpreis."));
        String ursachen = JahresvergleichSeite.ursachen(v);
        // Wallbox +830 kWh at 0.30 €
        assertTrue(ursachen.contains("+830 kWh · +249 €"), ursachen);
        assertEquals(" · −30 €", JahresvergleichSeite.euro(v, -100.0, true));
        assertEquals("", JahresvergleichSeite.euro(ohne, 100.0, true));
    }

    @Test
    public void testErwartetWieImRestDesVorjahres() {
        Jahresvergleich.Vergleich ohne = vergleich(true);
        // Without the rest of the year before: the rate of the last three months
        assertEquals(ohne.jeTag(0) * (LocalDate.of(2027, 1, 1).toEpochDay() - HEUTE.toEpochDay()), ohne.erwartet(0),
                1e-9);
        // Measured and used in the rest of the year before: that, also for a device that is new
        Jahresvergleich.Vergleich v = mit(ohne, java.util.List.of(), null,
                java.util.List.of(new Jahresvergleich.GleicheTage(830.0, 63.0, null, false, 12.0)));
        assertEquals(12.0, v.erwartet(0), 1e-9);
        // A counter that ran the year before without use (wallbox before the electric car): the rate of the months
        Jahresvergleich.Vergleich still = mit(ohne, java.util.List.of(), null,
                java.util.List.of(new Jahresvergleich.GleicheTage(830.0, 0.0, null, false, 0.0)));
        assertEquals(ohne.erwartet(0), still.erwartet(0), 1e-9);
        assertTrue(v.prognose().stream().anyMatch(p -> p.name().equals("Wallbox") && Math.abs(p.erwartet() - 12) < 1e-9));
    }
}
