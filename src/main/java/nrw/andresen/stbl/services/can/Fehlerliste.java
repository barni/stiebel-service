package nrw.andresen.stbl.services.can;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fault list of the WPM3 (DIAGNOSE / FEHLERLISTE), found with a scan of all Elster indices on 2026-10-07: 20 entries
 * of 7 values in FEHLERFELD_0..139 (0x0B00-0x0B8B) at 0x180, the same at 0x480 and 0x514. Each entry is minute,
 * hour, day, month, year (2 digits), a value that was always 0 and the fault code. The list is a ring buffer, the
 * entries are sorted by their time.
 */
public class Fehlerliste {

    public static final int KNOTEN = 0x180;
    public static final short ERSTES_FELD = 0x0B00;
    public static final int EINTRAEGE = 20;
    public static final int FELDER_JE_EINTRAG = 7;
    public static final int FELDER = EINTRAEGE * FELDER_JE_EINTRAG;

    // Codes seen in the list and their text on the WPM display; 8255-8258 from the WPM 3 manual
    private static final Map<Integer, String> TEXTE = Map.of(
            8116, "INV H ROTORVEKTOR",
            8255, "INV H Eingangsstrombegrenzung",
            8256, "INV H Ausgangsstrombegrenzung",
            8257, "INV H Phasenverlust",
            8258, "INV H Powermodul");

    /**
     * Entry of the fault list, platz is its position 0..19 in the ring buffer
     */
    public record Eintrag(LocalDateTime zeit, int code, String text, int platz) {
    }

    private record Feld(int wert, Instant zeit) {
    }

    private final Map<Integer, Feld> felder = new ConcurrentHashMap<>();
    // Entries already known, null until the first complete read
    private Set<Eintrag> bekannt;

    public static boolean istFeld(int knoten, short index) {
        return knoten == KNOTEN && index >= ERSTES_FELD && index < ERSTES_FELD + FELDER;
    }

    public static String text(int code) {
        return TEXTE.getOrDefault(code, "Fehler " + code);
    }

    /**
     * Answer of the WPM for one field
     */
    public void feld(short index, short rohwert, Instant zeit) {
        felder.put(index - ERSTES_FELD, new Feld(rohwert & 0xffff, zeit));
    }

    /**
     * True if all fields were received after the given time, e.g. the last request
     */
    public boolean vollstaendigSeit(Instant zeit) {
        if (felder.size() < FELDER) {
            return false;
        }
        return felder.values().stream().noneMatch(feld -> feld.zeit().isBefore(zeit));
    }

    /**
     * Entries with a valid time, the newest first, empty slots are skipped
     */
    public List<Eintrag> eintraege() {
        List<Eintrag> liste = new ArrayList<>();
        for (int platz = 0; platz < EINTRAEGE; platz++) {
            int[] werte = new int[FELDER_JE_EINTRAG];
            boolean vorhanden = true;
            for (int i = 0; i < FELDER_JE_EINTRAG; i++) {
                Feld feld = felder.get(platz * FELDER_JE_EINTRAG + i);
                if (feld == null) {
                    vorhanden = false;
                    break;
                }
                werte[i] = feld.wert();
            }
            if (!vorhanden || werte[6] == 0) {
                continue;
            }
            try {
                LocalDateTime zeit = LocalDateTime.of(2000 + werte[4], werte[3], werte[2], werte[1], werte[0]);
                liste.add(new Eintrag(zeit, werte[6], text(werte[6]), platz));
            } catch (DateTimeException e) {
                // Empty or partly written slot
            }
        }
        liste.sort(Comparator.comparing(Eintrag::zeit).reversed());
        return liste;
    }

    /**
     * Entries that were not in the list at the previous call; the first call only remembers the list. Call it only
     * after a complete read.
     */
    public synchronized List<Eintrag> neueEintraege() {
        List<Eintrag> aktuell = eintraege();
        if (bekannt == null) {
            bekannt = new HashSet<>(aktuell);
            return List.of();
        }
        List<Eintrag> neu = aktuell.stream().filter(e -> !bekannt.contains(e)).toList();
        bekannt = new HashSet<>(aktuell);
        return neu;
    }
}
