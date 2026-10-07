package nrw.andresen.stbl;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
