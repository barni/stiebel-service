package nrw.andresen.stbl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ApplicationTest {

    /**
     * Without these JavaMail waits without limit for a mail server that does not answer
     */
    @Test
    public void testZeitlimitsMail() {
        assertEquals("10000", Application.STANDARDWERTE.get("spring.mail.properties.mail.smtp.connectiontimeout"));
        assertEquals("20000", Application.STANDARDWERTE.get("spring.mail.properties.mail.smtp.timeout"));
        assertEquals("20000", Application.STANDARDWERTE.get("spring.mail.properties.mail.smtp.writetimeout"));
    }
}
