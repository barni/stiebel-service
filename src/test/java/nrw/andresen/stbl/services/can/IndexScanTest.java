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

    private static final Instant ZEIT = Instant.parse("2026-10-08T02:00:00Z");

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
        // Simulated devices: the manager keeps 0x2040 at 0x0300 and 69 at five indices, 0x500 knows nothing
        boolean fertig = scan.scannen(0x681, Set.of(), Duration.ZERO, Duration.ZERO, anfrage -> {
            anfragen.add(anfrage);
            int knoten = anfrage.getReceiverId();
            short index = anfrage.getElsterIndex().getIndex();
            if (knoten == 0x480 && index == 0x0300) {
                scan.antwort(knoten, index, (short) 0x2040, ZEIT);
            } else if (knoten == 0x480 && index >= 0x0310 && index < 0x0315) {
                scan.antwort(knoten, index, (short) 69, ZEIT);
            } else if (knoten == 0x500) {
                scan.antwort(knoten, index, (short) 0x8000, ZEIT);
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
        assertEquals(6, status.mitWert());
        assertEquals(indizes, status.nichtVerfuegbar());
        assertEquals(6, status.treffer().size());
        assertEquals("8256 INV H Ausgangsstrombegrenzung", status.treffer().get(0).grund());
        assertEquals(1, status.haeufungen().size());
        assertEquals(69, status.haeufungen().get(0).wert());
        assertEquals(5, status.haeufungen().get(0).anzahl());
        assertTrue(scan.csv().startsWith("knoten;index;name;wert_hex;wert;zeit\n0x480;0x0300;"));
    }

    @Test
    public void testAntwortNurWaehrendDesScans() {
        IndexScan scan = new IndexScan();
        scan.antwort(0x480, (short) 0x0300, (short) 0x2040, ZEIT);
        assertEquals(0, scan.status().mitWert());
    }
}
