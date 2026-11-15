package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EmailServiceTest {

    @SuppressWarnings("unchecked")
    private static EmailService service(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new EmailService(provider, new PushoverService("", "", 0), "an@example.org");
    }

    /**
     * The caller holds the locks of the requests to the heat pump: an alert returns at once although the mail
     * server does not answer, and is sent as soon as it does
     */
    @Test
    public void testAlertWartetNichtAufMailserver() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        CountDownLatch begonnen = new CountDownLatch(1);
        CountDownLatch serverAntwortet = new CountDownLatch(1);
        doAnswer(aufruf -> {
            begonnen.countDown();
            serverAntwortet.await();
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        EmailService service = service(sender);

        long start = System.nanoTime();
        service.sendAlert("Betreff", "Text");
        service.sendAlert("Betreff 2", "Text");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
        assertTrue(begonnen.await(5, TimeUnit.SECONDS));

        serverAntwortet.countDown();
        service.beenden();
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
    }

    /**
     * More waiting alerts than the queue holds are dropped, the caller is neither stopped nor does it see an error
     */
    @Test
    public void testVolleWarteschlange() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        CountDownLatch serverAntwortet = new CountDownLatch(1);
        doAnswer(aufruf -> {
            serverAntwortet.await();
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        EmailService service = service(sender);

        for (int i = 0; i < EmailService.WARTESCHLANGE + 20; i++) {
            service.sendAlert("Betreff " + i, "Text");
        }
        serverAntwortet.countDown();
        service.beenden();
        // one being sent and the full queue; whether the first was already taken from the queue is not fixed
        verify(sender, org.mockito.Mockito.atMost(EmailService.WARTESCHLANGE + 1)).send(any(SimpleMailMessage.class));
        verify(sender, org.mockito.Mockito.atLeast(EmailService.WARTESCHLANGE)).send(any(SimpleMailMessage.class));
    }

    /**
     * The test message of the configuration page waits for the result
     */
    @Test
    public void testSofort() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailService service = service(sender);
        service.sendAlertSofort("Betreff", "Text");
        verify(sender).send(any(SimpleMailMessage.class));
        assertNull(service.getLetzterFehler());

        doThrow(new IllegalStateException("kein Server")).when(sender).send(any(SimpleMailMessage.class));
        service.sendAlertSofort("Betreff", "Text");
        assertEquals("kein Server", service.getLetzterFehler());
    }
}
