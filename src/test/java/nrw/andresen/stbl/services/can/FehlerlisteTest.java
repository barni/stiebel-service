package nrw.andresen.stbl.services.can;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FehlerlisteTest {

    private static final Instant ZEIT = Instant.parse("2026-10-07T12:00:00Z");

    private static void eintrag(Fehlerliste liste, int platz, int[] werte, Instant zeit) {
        for (int i = 0; i < Fehlerliste.FELDER_JE_EINTRAG; i++) {
            liste.feld((short) (Fehlerliste.ERSTES_FELD + platz * Fehlerliste.FELDER_JE_EINTRAG + i),
                    (short) werte[i], zeit);
        }
    }

    // Values of the scan on 2026-10-07: slot 14 is the newest entry, slot 13 the 8256 of 25.04.26
    private static Fehlerliste liste(Instant zeit) {
        Fehlerliste liste = new Fehlerliste();
        for (int platz = 0; platz < Fehlerliste.EINTRAEGE; platz++) {
            eintrag(liste, platz, new int[]{0, 0, 0, 0, 0, 0, 0}, zeit);
        }
        eintrag(liste, 12, new int[]{59, 9, 21, 4, 26, 0, 8116}, zeit);
        eintrag(liste, 13, new int[]{10, 7, 25, 4, 26, 0, 8256}, zeit);
        eintrag(liste, 14, new int[]{53, 18, 14, 5, 26, 0, 8116}, zeit);
        return liste;
    }

    @Test
    public void testEintraege() {
        List<Fehlerliste.Eintrag> eintraege = liste(ZEIT).eintraege();
        assertEquals(3, eintraege.size());
        assertEquals(new Fehlerliste.Eintrag(LocalDateTime.of(2026, 5, 14, 18, 53), 8116, "INV H ROTORVEKTOR", 14),
                eintraege.get(0));
        assertEquals(new Fehlerliste.Eintrag(LocalDateTime.of(2026, 4, 25, 7, 10), 8256,
                "INV H Ausgangsstrombegrenzung", 13), eintraege.get(1));
        assertEquals(12, eintraege.get(2).platz());
        assertEquals("Fehler 1234", Fehlerliste.text(1234));
    }

    @Test
    public void testUngueltigerEintrag() {
        Fehlerliste liste = new Fehlerliste();
        // Month 13 and an incomplete slot are skipped
        eintrag(liste, 0, new int[]{0, 0, 1, 13, 26, 0, 8116}, ZEIT);
        liste.feld(Fehlerliste.ERSTES_FELD, (short) 1, ZEIT);
        liste.feld((short) (Fehlerliste.ERSTES_FELD + 7), (short) 1, ZEIT);
        assertTrue(liste.eintraege().isEmpty());
    }

    @Test
    public void testVollstaendig() {
        Fehlerliste liste = new Fehlerliste();
        assertFalse(liste.vollstaendigSeit(ZEIT));
        liste = liste(ZEIT);
        assertTrue(liste.vollstaendigSeit(ZEIT));
        assertFalse(liste.vollstaendigSeit(ZEIT.plusSeconds(1)));
    }

    @Test
    public void testNeueEintraege() {
        Fehlerliste liste = liste(ZEIT);
        // The first complete read only remembers the list
        assertTrue(liste.neueEintraege().isEmpty());
        assertTrue(liste.neueEintraege().isEmpty());

        // A new fault overwrites the oldest slot of the ring buffer
        eintrag(liste, 15, new int[]{30, 3, 7, 10, 26, 0, 8116}, ZEIT);
        List<Fehlerliste.Eintrag> neu = liste.neueEintraege();
        assertEquals(1, neu.size());
        assertEquals(LocalDateTime.of(2026, 10, 7, 3, 30), neu.get(0).zeit());
        assertTrue(liste.neueEintraege().isEmpty());
    }

    @Test
    public void testIstFeld() {
        assertTrue(Fehlerliste.istFeld(0x180, (short) 0x0B00));
        assertTrue(Fehlerliste.istFeld(0x180, (short) 0x0B8B));
        assertFalse(Fehlerliste.istFeld(0x180, (short) 0x0B8C));
        assertFalse(Fehlerliste.istFeld(0x180, (short) 0x0AFF));
        assertFalse(Fehlerliste.istFeld(0x480, (short) 0x0B00));
    }
}
