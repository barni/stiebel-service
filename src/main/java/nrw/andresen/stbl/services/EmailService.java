package nrw.andresen.stbl.services;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends alert mails to stbl.mail.to, nothing if it or spring.mail.host is not configured.
 * Alerts also go to Pushover if it is configured.
 */
@Component
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> emailSender;
    private final PushoverService pushover;
    private final String to;
    private volatile String letzterFehler;
    // Alerts are sent by an own thread: the callers hold the locks of the requests to the heat pump and of the USB
    // watchdog, a slow mail server must not stop them. More waiting alerts than this are dropped.
    static final int WARTESCHLANGE = 50;
    private final ExecutorService versand = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WARTESCHLANGE), r -> {
        Thread thread = new Thread(r, "meldung");
        thread.setDaemon(true);
        return thread;
    });

    public EmailService(ObjectProvider<JavaMailSender> emailSender, PushoverService pushover,
                        @Value("${stbl.mail.to:}") String to) {
        this.emailSender = emailSender;
        this.pushover = pushover;
        this.to = to;
    }

    /**
     * Sends the alert in the background and returns at once. Never throws, a failing or slow mail must not stop the
     * caller (e.g. the USB restart).
     */
    public void sendAlert(String subject, String text) {
        try {
            versand.execute(() -> sendAlertSofort(subject, text));
        } catch (RejectedExecutionException e) {
            logger.warn("Too many alerts are waiting or the service ends, not sending: " + subject);
        }
    }

    /**
     * Sends a mail without Pushover in the background, for things that are not urgent. Never throws.
     */
    public void sendMail(String subject, String text) {
        try {
            versand.execute(() -> mailSenden(subject, text));
        } catch (RejectedExecutionException e) {
            logger.warn("Too many alerts are waiting or the service ends, not sending: " + subject);
        }
    }

    /**
     * Sends the alert and waits for the result, see getLetzterFehler. Never throws.
     */
    public void sendAlertSofort(String subject, String text) {
        pushover.senden(subject, text);
        mailSenden(subject, text);
    }

    private void mailSenden(String subject, String text) {
        JavaMailSender sender = emailSender.getIfAvailable();
        if (to.isBlank() || sender == null) {
            logger.info("No mail configured, not sending: " + subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            sender.send(message);
            letzterFehler = null;
        } catch (Exception e) {
            letzterFehler = e.getMessage();
            logger.warn("Sending mail failed: " + e.getMessage());
        }
    }

    /**
     * Gives the waiting alerts a moment to leave before the service ends or restarts
     */
    @PreDestroy
    public void beenden() {
        versand.shutdown();
        try {
            if (!versand.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warn("Alerts were still waiting when the service ended");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isKonfiguriert() {
        return !to.isBlank() && emailSender.getIfAvailable() != null;
    }

    /**
     * Error of the last mail, null if it was sent
     */
    public String getLetzterFehler() {
        return letzterFehler;
    }
}
