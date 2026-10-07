package nrw.andresen.stbl.services.can;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FehlerDiagnoseTest {

    private static final Instant ZEIT = Instant.parse("2026-10-07T20:00:00Z");

    @Test
    public void testIstKandidat() {
        assertTrue(FehlerDiagnose.istKandidat(0x180, FehlerDiagnose.FEHLERAUSGANG));
        assertTrue(FehlerDiagnose.istKandidat(0x500, FehlerDiagnose.FEHLERMELDUNG));
        // An unexpected node answering a trial index is kept as well
        assertTrue(FehlerDiagnose.istKandidat(0x700, FehlerDiagnose.FEHLERMELDUNG));
        // Only the Betriebsstatus of the manager belongs to the trial, the one of 0x180 is used as before
        assertTrue(FehlerDiagnose.istKandidat(0x480, FehlerDiagnose.BETRIEBS_STATUS));
        assertFalse(FehlerDiagnose.istKandidat(0x180, FehlerDiagnose.BETRIEBS_STATUS));
        assertFalse(FehlerDiagnose.istKandidat(0x500, (short) 0x07a6));
    }

    @Test
    public void testZeilen() {
        FehlerDiagnose diagnose = new FehlerDiagnose();
        diagnose.antwort(0x180, FehlerDiagnose.FEHLERAUSGANG, (short) 0, ZEIT);
        // The same index of two nodes does not overwrite each other
        diagnose.antwort(0x480, FehlerDiagnose.FEHLERMELDUNG, (short) 0x8000, ZEIT);
        diagnose.antwort(0x500, FehlerDiagnose.FEHLERMELDUNG, (short) 4, ZEIT);
        diagnose.antwort(0x700, FehlerDiagnose.FEHLERMELDUNG, (short) 0x5145, ZEIT);

        List<FehlerDiagnose.Zeile> zeilen = diagnose.zeilen();
        assertEquals(FehlerDiagnose.KANDIDATEN.size() + 1, zeilen.size());
        assertEquals(0, zeilen.get(0).antwort().wert());
        assertNull(zeilen.get(1).antwort());
        assertTrue(zeilen.get(2).antwort().nichtVerfuegbar());
        assertEquals(4, zeilen.get(3).antwort().wert());
        // Answer of a node that was not asked comes last
        FehlerDiagnose.Zeile fremd = zeilen.get(zeilen.size() - 1);
        assertEquals(0x700, fremd.knoten());
        assertEquals(0x5145, fremd.antwort().wert());
        assertEquals("Index 0x0001", fremd.name());
        assertEquals(4, diagnose.getAntworten().size());
    }

    @Test
    public void testBeschreibung() {
        assertEquals("keine Antwort", FehlerDiagnose.beschreibung(null));
        assertEquals("nicht verfügbar", FehlerDiagnose.beschreibung(antwort(FehlerDiagnose.FEHLERMELDUNG, 0x8000)));
        assertEquals("0x0000 (0)", FehlerDiagnose.beschreibung(antwort(FehlerDiagnose.FEHLERAUSGANG, 0)));
        // Known fault number from ErrorIndex
        assertEquals("0x0004 (4), Hochdruck", FehlerDiagnose.beschreibung(antwort(FehlerDiagnose.FEHLERMELDUNG, 4)));
        assertEquals("0x5145 (20805)", FehlerDiagnose.beschreibung(antwort(FehlerDiagnose.FEHLERMELDUNG, 0x5145)));
        // Bit 4 of the Betriebsstatus of the manager
        assertEquals("0x0014 (20), Bit 4: an",
                FehlerDiagnose.beschreibung(antwort(FehlerDiagnose.BETRIEBS_STATUS, 0x0014)));
        assertEquals("0x0004 (4), Bit 4: aus",
                FehlerDiagnose.beschreibung(antwort(FehlerDiagnose.BETRIEBS_STATUS, 0x0004)));
    }

    private static FehlerDiagnose.Antwort antwort(short index, int wert) {
        return new FehlerDiagnose.Antwort(0x480, index, wert, ZEIT);
    }

    @Test
    public void testAntwortDesManagersWirdErkannt() {
        // Request of the service 0x681 to the manager 0x480 for FEHLERMELDUNG
        ElsterMessage anfrage = ElsterMessage.readRequest(0x681, 0x480, FehlerDiagnose.FEHLERMELDUNG);
        assertEquals("91 00 FA 00 01 00 00", hex(anfrage.getMessage().getData()));
        // Answer of the manager to 0x681: type 2, receiver 0x681 = 0xd0 0x01, value 0x0000
        ElsterMessage antwort = new ElsterMessage(0x480, new byte[]{(byte) 0xd2, 0x01, (byte) 0xfa, 0x00, 0x01, 0x00,
                0x00});
        assertTrue(antwort.isResponse());
        assertEquals(0x681, antwort.getReceiverId());
        assertEquals(0x480, antwort.getId());
        assertEquals(FehlerDiagnose.FEHLERMELDUNG, antwort.getElsterIndex().getIndex());
        assertEquals((short) 0, antwort.getRawValue());
        assertTrue(FehlerDiagnose.istKandidat(antwort.getId(), antwort.getElsterIndex().getIndex()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(text.length() == 0 ? "" : " ").append(String.format("%02X", b));
        }
        return text.toString();
    }
}
