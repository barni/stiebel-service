package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VersionTest {

    @Test
    public void testText() {
        // 15:20 UTC is 17:20 in Berlin in summer
        assertEquals("0.2.0-SNAPSHOT, Commit 2036a8b, gebaut 10.10.2026 17:20",
                Version.text("0.2.0-SNAPSHOT", "2036a8b", false, Instant.parse("2026-10-10T15:20:00Z")));
        assertEquals("0.2.0-SNAPSHOT", Version.text("0.2.0-SNAPSHOT", null, false, null));
        assertEquals("Version unbekannt, Commit 2036a8b mit nicht eingecheckten Änderungen",
                Version.text(null, "2036a8b", true, null));
    }

    @Test
    public void testOhneBuildInformationen() {
        assertEquals("unbekannt (ohne Build-Informationen gestartet)", Version.text(null, null, false, null));
    }
}
