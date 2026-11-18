package nrw.andresen.stbl;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SicherheitTest {

    private static MockHttpServletRequest anfrage(String methode, String pfad, String accept) {
        MockHttpServletRequest r = new MockHttpServletRequest(methode, pfad);
        if (accept != null) {
            r.addHeader("Accept", accept);
        }
        return r;
    }

    @Test
    public void testSeite() {
        String html = "text/html,application/xhtml+xml,*/*;q=0.8";
        // Only pages opened in the browser are opened again after the login
        assertTrue(Sicherheit.seite(anfrage("GET", "/anlage", html)));
        assertFalse(Sicherheit.seite(anfrage("GET", "/apple-touch-icon.png", html)));
        assertFalse(Sicherheit.seite(anfrage("GET", "/js/diagramm.js", html)));
        assertFalse(Sicherheit.seite(anfrage("GET", "/error", html)));
        assertFalse(Sicherheit.seite(anfrage("GET", "/status", "*/*")));
        assertFalse(Sicherheit.seite(anfrage("POST", "/konfiguration", html)));
        MockHttpServletRequest skript = anfrage("GET", "/status", html);
        skript.addHeader("Sec-Fetch-Mode", "cors");
        assertFalse(Sicherheit.seite(skript));
    }
}
