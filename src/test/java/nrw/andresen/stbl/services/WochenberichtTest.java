package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.Fehlerliste;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WochenberichtTest {

    private static final LocalDate SONNTAG = LocalDate.of(2026, 10, 4);

    /**
     * Seven days from the given day with the same values
     */
    private static List<Tagesuebersicht.Tag> tage(LocalDate von, Double aussentemp, int starts, double laufzeitH,
                                                  double waermeKWh, double stromKWh, int abtauungen) {
        List<Tagesuebersicht.Tag> tage = new ArrayList<>();
        for (int i = 0; i < Wochenbericht.TAGE; i++) {
            tage.add(new Tagesuebersicht.Tag(von.plusDays(i), aussentemp, starts, laufzeitH, waermeKWh, stromKWh,
                    abtauungen));
        }
        return tage;
    }

    private static Wochenbericht.Bericht bericht(Wochenbericht.Ueblich ueblich, int neueFehler) {
        Wochenbericht.Woche woche = Wochenbericht.woche(tage(SONNTAG, 15.0, 4, 6, 20, 4, 1),
                Map.of(SONNTAG, 1.54, SONNTAG.plusDays(6), 1.56), Map.of(SONNTAG.plusDays(2), 35.5));
        Wochenbericht.Woche vorwoche = Wochenbericht.woche(tage(SONNTAG.minusDays(7), 20.4, 1, 1.5, 5, 1, 0),
                Map.of(SONNTAG.minusDays(7), 1.54, SONNTAG.minusDays(1), 1.47), Map.of());
        return new Wochenbericht.Bericht(woche, vorwoche, ueblich, neueFehler, Instant.parse("2026-10-11T18:00:00Z"));
    }

    @Test
    public void testWoche() {
        Wochenbericht.Woche woche = bericht(null, 0).woche();
        assertEquals(SONNTAG, woche.von());
        assertEquals(SONNTAG.plusDays(6), woche.bis());
        assertEquals(15.0, woche.aussentemp(), 1e-9);
        assertEquals(140, woche.waermeKWh(), 1e-9);
        assertEquals(28, woche.starts());
        assertEquals(7, woche.abtauungen());
        assertEquals(5.0, woche.arbeitszahl(), 1e-9);
        assertEquals(1.5, woche.laufzeitProStart(), 1e-9);
        assertEquals(35.5, woche.vorlaufMax(), 1e-9);
        assertEquals("04.10.–10.10.2026", Wochenbericht.zeitraum(woche));
    }

    @Test
    public void testWocheOhneWerte() {
        Wochenbericht.Woche woche = Wochenbericht.woche(tage(SONNTAG, null, 0, 0, 0, 0, 0), Map.of(), Map.of());
        assertNull(woche.aussentemp());
        assertNull(woche.arbeitszahl());
        assertNull(woche.laufzeitProStart());
        assertNull(woche.vorlaufMax());
        assertNull(woche.druckAnfang());
    }

    @Test
    public void testUeblich() {
        List<WaermebedarfVergleich.Tag> frueher = List.of(
                new WaermebedarfVergleich.Tag(LocalDate.of(2025, 10, 1), 14.5, 6, 8, 30, 8.5, 1),
                new WaermebedarfVergleich.Tag(LocalDate.of(2025, 10, 2), 15.5, 4, 6, 20, 8.5, 0),
                new WaermebedarfVergleich.Tag(LocalDate.of(2025, 1, 2), -2, 12, 18, 110, 8.5, 20));
        // For each of the 7 days the mean of the two similar days: 25 kWh, 5 starts, 7 h, 0.5 defrosts
        Wochenbericht.Ueblich ueblich = Wochenbericht.ueblich(tage(SONNTAG, 15.0, 4, 6, 20, 4, 1), frueher);
        assertEquals(175, ueblich.waermeKWh(), 1e-9);
        assertEquals(35, ueblich.starts(), 1e-9);
        assertEquals(49, ueblich.laufzeitH(), 1e-9);
        assertEquals(3.5, ueblich.abtauungen(), 1e-9);
        // A day without outdoor temperature or without similar days: no comparison
        assertNull(Wochenbericht.ueblich(tage(SONNTAG, null, 4, 6, 20, 4, 1), frueher));
        assertNull(Wochenbericht.ueblich(tage(SONNTAG, 30.0, 4, 6, 20, 4, 1), frueher));
    }

    @Test
    public void testNeueFehler() {
        List<Fehlerliste.Eintrag> eintraege = List.of(
                new Fehlerliste.Eintrag(LocalDateTime.of(2026, 10, 3, 23, 59), 20, "alt", 1),
                new Fehlerliste.Eintrag(LocalDateTime.of(2026, 10, 4, 0, 0), 20, "erster Tag", 2),
                new Fehlerliste.Eintrag(LocalDateTime.of(2026, 10, 10, 23, 0), 20, "letzter Tag", 3),
                new Fehlerliste.Eintrag(LocalDateTime.of(2026, 10, 11, 0, 0), 20, "danach", 4));
        assertEquals(2, Wochenbericht.neueFehler(eintraege, SONNTAG, SONNTAG.plusDays(7)));
    }

    @Test
    public void testSatz() {
        assertEquals("5,4 K kälter als die Vorwoche, weniger Starts als bei dieser Temperatur üblich (28 statt 40), "
                        + "Heizungsdruck stabil, keine neuen Einträge in der Fehlerliste.",
                Wochenbericht.satz(bericht(new Wochenbericht.Ueblich(150, 40, 45, 5), 0)));
        // Within 20 % of the usual number of starts; without usual values the part is left out
        assertTrue(Wochenbericht.satz(bericht(new Wochenbericht.Ueblich(150, 30, 45, 5), 2))
                .contains("Starts wie bei dieser Temperatur üblich, Heizungsdruck stabil, 2 neue Einträge"));
        assertFalse(Wochenbericht.satz(bericht(null, 1)).contains("Starts"));
        assertTrue(Wochenbericht.satz(bericht(null, 1)).endsWith("1 neuer Eintrag in der Fehlerliste."));
    }

    @Test
    public void testTextUndSeite() {
        Wochenbericht.Bericht bericht = bericht(new Wochenbericht.Ueblich(150, 40, 45, 5), 0);
        String text = Wochenbericht.text(bericht);
        assertTrue(text.startsWith("Woche 04.10.–10.10.2026\n\n5,4 K kälter"));
        assertTrue(text.contains("- Wärme kWh: 140 (35 / 150)\n"));
        assertTrue(text.contains("- Starts: 28 (7 / 40)\n"));
        assertTrue(text.contains("- Heizungsdruck bar: 1,54 → 1,56 (1,54 → 1,47 / –)\n"));
        assertTrue(text.contains("- So. 04.10. | 15,0 | 20 | 4,0 | 4 | 1,5 | 1\n"));

        String html = WochenberichtSeite.render(bericht, null);
        assertTrue(html.contains("<a class=\"nav\" href=\"status\">← Status</a>"));
        assertTrue(html.contains("<a class=\"nav\" href=\"konfiguration\">Konfiguration</a>"));
        assertTrue(html.contains("<td>Wärme kWh</td><td>140</td><td>35</td><td>150</td>"));
        assertTrue(html.contains("Woche 04.10.–10.10.2026"));
        // Not calculated yet, or InfluxDB switched off
        assertTrue(WochenberichtSeite.render(null, null).contains("wird berechnet"));
        assertTrue(WochenberichtSeite.render(null, "InfluxDB ist aus").contains("InfluxDB ist aus"));
    }
}
