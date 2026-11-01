package nrw.andresen.stbl.services;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends alert mails and the weekly report to stbl.mail.to, nothing if it or spring.mail.host is not configured
 */
@Component
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> emailSender;
    private final String to;
    private volatile String letzterFehler;

    public EmailService(ObjectProvider<JavaMailSender> emailSender, @Value("${stbl.mail.to:}") String to) {
        this.emailSender = emailSender;
        this.to = to;
    }

    /**
     * Never throws, a failing mail must not stop the caller (e.g. the USB restart)
     */
    public void sendAlert(String subject, String text) {
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
     * Mail with an HTML part and the same content as plain text for mail programs without HTML. Never throws.
     */
    public void sendHtml(String subject, String text, String html) {
        JavaMailSender sender = emailSender.getIfAvailable();
        if (to.isBlank() || sender == null) {
            logger.info("No mail configured, not sending: " + subject);
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            sender.send(message);
            letzterFehler = null;
        } catch (Exception e) {
            letzterFehler = e.getMessage();
            logger.warn("Sending mail failed: " + e.getMessage());
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
