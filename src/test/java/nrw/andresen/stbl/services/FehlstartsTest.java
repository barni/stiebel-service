package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class FehlstartsTest {

    private static Instant t(String zeit) {
        return Instant.parse("2026-05-14T" + zeit + ":00Z");
    }

    /**
     * 14.05.2026 (UTC): run until 15:40, failed start 16:53 with the next run 23 minutes later, then a stop and a
     * normal start 17:40 with a run one minute after the pressure drop, a defrost break of one minute at 18:10
     */
    private static Fehlstarts.Ereignisse ereignisse() {
        NavigableMap<Instant, Double> anAus = new TreeMap<>();
        anAus.put(t("15:00"), 1d);
        anAus.put(t("15:40"), -1d);
        anAus.put(t("16:54"), 1d);
        anAus.put(t("16:55"), -1d);
        anAus.put(t("17:16"), 1d);
        anAus.put(t("17:25"), -1d);
        anAus.put(t("17:41"), 1d);
        anAus.put(t("18:10"), -1d);
        anAus.put(t("18:11"), 1d);
        List<Instant> druckabfaelle = new ArrayList<>(List.of(t("16:54"), t("17:41")));
        List<Instant> laeufe = List.of(t("15:00"), t("17:16"), t("17:42"));
        return new Fehlstarts.Ereignisse(druckabfaelle, anAus, laeufe);
    }

    @Test
    public void testErkennen() {
        assertEquals(List.of(t("16:54")), Fehlstarts.erkennen(ereignisse()));
    }

    @Test
    public void testSaisons() {
        List<Fehlstarts.Saison> saisons = Fehlstarts.saisons(ereignisse(), null);
        assertEquals(1, saisons.size());
        Fehlstarts.Saison saison = saisons.get(0);
        assertEquals("2025/26", saison.name());
        // 15:00, 16:54 (blip after the long standstill), 17:16, 17:41; the defrost break 18:11 is no start
        assertEquals(4, saison.starts());
        assertEquals(1, saison.fehlstarts());
        // Without daily values no heat, no covered days and no rate on mild days
        assertNull(saison.jeMWh());
        assertNull(saison.jeTausendMild());
    }

    @Test
    public void testStillstand() {
        assertEquals(t("15:40"), Fehlstarts.stillstandSeit(ereignisse().anAus(), t("16:53")));
        assertNull(Fehlstarts.stillstandSeit(ereignisse().anAus(), t("15:20")));
    }

    @Test
    public void testSaison() {
        assertEquals(LocalDate.of(2025, 7, 1), Fehlstarts.saisonStart(LocalDate.of(2026, 6, 30)));
        assertEquals(LocalDate.of(2026, 7, 1), Fehlstarts.saisonStart(LocalDate.of(2026, 7, 1)));
        assertEquals("2026/27", Fehlstarts.saisonName(LocalDate.of(2026, 7, 1)));
        assertEquals("2099/00", Fehlstarts.saisonName(LocalDate.of(2099, 7, 1)));
        assertNull(new Fehlstarts.Saison("2026/27", 0, 0, true, 0.3, 99, 3, 0, 99).jeMWh());
    }

    @Test
    public void testKennzahlen() {
        Fehlstarts.Ereignisse ohne = ereignisse();
        LocalDate tag = LocalDate.of(2026, 5, 14);
        // 14.05.: covered, 12 degC (mild), 0.1 MWh; 13.05.: gap
        Fehlstarts.Ereignisse e = new Fehlstarts.Ereignisse(ohne.druckabfaelle(), ohne.anAus(), ohne.laeufe(),
                java.util.Map.of(tag, 4320d, tag.minusDays(1), 1000d), java.util.Map.of(tag, 0.1, tag.minusDays(1),
                0.5), java.util.Map.of(tag, 12d));
        Fehlstarts.Saison saison = Fehlstarts.saisons(e, null).get(0);
        assertEquals(1, saison.tageMitDaten());
        assertEquals(0.1, saison.waermeMWh(), 1e-9);
        // Heat below 0.5 MWh gives no rate per MWh
        assertNull(saison.jeMWh());
        assertEquals(4, saison.startsMild());
        assertEquals(1, saison.fehlstartsMild());
        assertEquals(250, saison.jeTausendMild(), 1e-9);
        assertEquals(10, new Fehlstarts.Saison("2025/26", 1608, 24, false, 2.4, 363, 0, 0, 0).jeMWh(), 1e-9);
        // Outdoor temperature only for part of the season: no rate on mild days
        assertNull(new Fehlstarts.Saison("2021/22", 2753, 20, false, 13.9, 363, 556, 4, 106).jeTausendMild());
    }

    @Test
    public void testWaermeProTag() {
        LocalDate tag = LocalDate.of(2026, 1, 11);
        java.util.Map<LocalDate, Double> zaehler = new java.util.TreeMap<>(java.util.Map.of(
                tag.minusDays(1), 111.80, tag, 111.92, tag.plusDays(1), 111.91, tag.plusDays(3), 112.20));
        java.util.Map<LocalDate, Double> waerme = Fehlstarts.waermeProTag(zaehler);
        assertEquals(0.12, waerme.get(tag), 1e-9);
        // Falling counter and a day without the day before are left out
        assertEquals(java.util.Set.of(tag), waerme.keySet());
    }

    @Test
    public void testMild() {
        java.util.Map<LocalDate, Double> temperatur = java.util.Map.of(LocalDate.of(2026, 5, 14), 12d,
                LocalDate.of(2026, 1, 11), -2d, LocalDate.of(2026, 7, 1), 15d);
        assertEquals(true, Fehlstarts.mild(temperatur, t("16:54")));
        assertEquals(false, Fehlstarts.mild(temperatur, Instant.parse("2026-01-11T12:00:00Z")));
        assertEquals(false, Fehlstarts.mild(temperatur, Instant.parse("2026-07-01T12:00:00Z")));
        assertEquals(false, Fehlstarts.mild(temperatur, Instant.parse("2026-03-01T12:00:00Z")));
    }
}
