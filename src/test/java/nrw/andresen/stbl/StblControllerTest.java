package nrw.andresen.stbl;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StblControllerTest {

    @Test
    public void testKeinWert() {
        ResponseEntity<String> response = new StblController().keinWert(new Exception("VERDICHTER_AUS"));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("VERDICHTER_AUS", response.getBody());
    }

    @Test
    public void testUngueltigerName() {
        ResponseEntity<String> response = new StblController()
                .ungueltig(new IllegalArgumentException("Unbekannter Wert: x y"));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void testHistoryRange() {
        assertEquals(Duration.ofHours(24), StblController.historyRange("24h").range());
        assertEquals(Duration.ofMinutes(2), StblController.historyRange("24h").window());
        assertEquals(Duration.ofDays(365), StblController.historyRange("1y").range());
        assertThrows(IllegalArgumentException.class, () -> StblController.historyRange("2h"));
        assertThrows(IllegalArgumentException.class, () -> StblController.historyRange("-30d"));
    }
}
