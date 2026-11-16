package nrw.andresen.stbl.services.can;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IndexScanTest {

    private static final Instant ZEIT = Instant.parse("2026-11-16T20:30:00Z");

    // Answer of the node to the service 0x681, long form with 0xFA like on the bus, e.g. D201FA067400B1
    private static ElsterMessage antwort(int knoten, int index, int wert) {
        return new ElsterMessage(knoten, String.format("D201FA%04X%04X", index, wert));
    }

    @Test
    public void testIndizesOhneAusgenommene() {
        IndexScan scan = new IndexScan();
        List<Short> alle = scan.indizes(Set.of());
        assertTrue(alle.size() > 3000);
        List<Short> ohne = scan.indizes(Set.of((short) 0x0176, (short) 0x01d6));
        assertEquals(alle.size() - 2, ohne.size());
        assertFalse(ohne.contains((short) 0x0176));
    }

    @Test
    public void testScan() {
        IndexScan scan = new IndexScan();
        List<ElsterMessage> anfragen = new ArrayList<>();
        // Simulated devices: the controller 0x180 knows 0x0674 (1,86 bar) and 0x0004 with 0, 0x500 knows nothing
        boolean fertig = scan.scannen(0x681, Set.of(), Duration.ZERO, Duration.ZERO, anfrage -> {
            anfragen.add(anfrage);
            int knoten = anfrage.getReceiverId();
            int index = anfrage.getElsterIndex().getIndex() & 0xffff;
            if (knoten == 0x180 && index == 0x0674) {
                assertTrue(scan.antwort(antwort(knoten, index, 186), ZEIT));
            } else if (knoten == 0x180 && index == 0x0004) {
                scan.antwort(antwort(knoten, index, 0), ZEIT);
            } else if (knoten == 0x500) {
                scan.antwort(antwort(knoten, index, 0x8000), ZEIT);
            }
        });
        assertTrue(fertig);
        assertFalse(scan.laeuft());
        int indizes = scan.indizes(Set.of()).size();
        assertEquals(4 * indizes, anfragen.size());
        // Read requests only
        assertTrue(anfragen.stream().allMatch(ElsterMessage::isRequest));

        IndexScan.Status status = scan.status();
        assertEquals(4 * indizes, status.gesamt());
        assertEquals(4 * indizes, status.gesendet());
        assertEquals(2, status.mitWert());
        assertEquals(indizes, status.nichtVerfuegbar());
        List<IndexScan.Ergebnis> ergebnisse = scan.ergebnisse();
        // Sorted by index
        assertEquals(0x0004, ergebnisse.get(0).index());
        assertEquals(0x0674, ergebnisse.get(1).index());
        assertEquals("HEIZUNGSDRUCK", ergebnisse.get(1).name());
        assertEquals("1,86", ergebnisse.get(1).wert());
        assertEquals(186, ergebnisse.get(1).roh());
        assertTrue(scan.csv().startsWith("knoten;index;name;wert;roh_hex;roh;zeit\n0x180;0x0004;"));
        assertTrue(scan.csv().contains("0x180;0x0674;HEIZUNGSDRUCK;1,86;0x00BA;186;"));
    }

    @Test
    public void testAntwortNurWaehrendDesScans() {
        IndexScan scan = new IndexScan();
        assertFalse(scan.antwort(antwort(0x180, 0x0674, 186), ZEIT));
        assertEquals(0, scan.status().mitWert());
    }

    @Test
    public void testAntwortAndererKnoten() {
        IndexScan scan = new IndexScan();
        List<Boolean> gezaehlt = new ArrayList<>();
        scan.scannen(0x681, Set.of(), Duration.ZERO, Duration.ZERO, anfrage -> {
            if (gezaehlt.isEmpty()) {
                // 0x700 is not scanned, its answer does not belong to the scan
                gezaehlt.add(scan.antwort(antwort(0x700, 0x0674, 186), ZEIT));
            }
        });
        assertFalse(gezaehlt.get(0));
        assertEquals(0, scan.status().mitWert());
    }
}
