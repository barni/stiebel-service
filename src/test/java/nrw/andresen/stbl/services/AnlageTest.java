package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnlageTest {

    @Test
    public void testModell() {
        Anlage a = new Anlage();
        a.setModell("");
        assertNull(a.getModell());
        // without a model no numbers of a data sheet
        assertEquals("", a.waermeleistung());
        assertEquals("", a.arbeitszahl());
        assertFalse(a.volumenstrom().matches(".*\\d.*"));
        a.setModell("WPL 17 ACS classic");
        assertEquals(" Laut Anleitung kann die WPL 17 ACS classic bei A7/W35 2,6–8,5 kW und bei A−7/W35 3–7,8 kW.",
                a.waermeleistung());
        assertTrue(a.arbeitszahl().contains("A2/W35 3,97") && a.arbeitszahl().contains("SCOP 4,48"));
        assertTrue(a.volumenstrom().contains("mindestens 10 l/min"));
        a.setModell("WPL 07 ACS classic");
        assertTrue(a.silent().contains("2,2 kW"));
        a.setModell("unbekannt");
        assertNull(a.getModell());
    }

    @Test
    public void testAuswahlInDerKonfiguration() {
        Map<String, String> werte = new LinkedHashMap<>();
        assertTrue(Konfiguration.pruefen(Map.of("waermepumpe.modell", "WPL 13 ACS classic"), werte).isEmpty());
        assertEquals("WPL 13 ACS classic", werte.get("waermepumpe.modell"));
        assertTrue(Konfiguration.pruefen(Map.of("waermepumpe.modell", ""), werte).isEmpty());
        assertEquals("ist keiner der möglichen Werte",
                Konfiguration.pruefen(Map.of("waermepumpe.modell", "WPL 99"), werte).get("waermepumpe.modell"));
        assertTrue(Konfiguration.SOFORT.contains("waermepumpe.modell"));
    }
}
