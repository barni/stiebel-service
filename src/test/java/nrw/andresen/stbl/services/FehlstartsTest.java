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
        assertEquals(250, saison.jeTausend(), 1e-9);
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
        assertNull(new Fehlstarts.Saison("2026/27", 0, 0, true).jeTausend());
    }
}
