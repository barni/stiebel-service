package nrw.andresen.stbl.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends the alerts also as push message over Pushover (pushover.net), nothing if pushover.token or pushover.user is
 * not configured
 */
@Component
public class PushoverService {

    private static final Logger logger = LoggerFactory.getLogger(PushoverService.class);

    static final URI API = URI.create("https://api.pushover.net/1/messages.json");
    // Limits of the Pushover API, longer texts are cut
    static final int TITEL_MAX = 250;
    static final int TEXT_MAX = 1024;

    private final String token;
    private final String user;
    private final int prioritaet;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String letzterFehler;

    public PushoverService(@Value("${pushover.token:}") String token, @Value("${pushover.user:}") String user,
                           @Value("${pushover.prioritaet:0}") int prioritaet) {
        this.token = token.trim();
        this.user = user.trim();
        this.prioritaet = prioritaet;
    }

    /**
     * Never throws, a failing message must not stop the caller
     */
    public void senden(String titel, String text) {
        if (!isKonfiguriert()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(API).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formular(token, user, prioritaet, titel, text)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }
            letzterFehler = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            letzterFehler = "unterbrochen";
        } catch (Exception e) {
            letzterFehler = e.getMessage();
            logger.warn("Sending Pushover message failed: " + e.getMessage());
        }
    }

    /**
     * Form for the Pushover API, title and text cut to its limits
     */
    static String formular(String token, String user, int prioritaet, String titel, String text) {
        Map<String, String> felder = new LinkedHashMap<>();
        felder.put("token", token);
        felder.put("user", user);
        felder.put("title", kuerzen(titel, TITEL_MAX));
        felder.put("message", kuerzen(text.isBlank() ? titel : text, TEXT_MAX));
        felder.put("priority", Integer.toString(prioritaet));
        return felder.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static String kuerzen(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    public boolean isKonfiguriert() {
        return !token.isEmpty() && !user.isEmpty();
    }

    /**
     * Error of the last message, null if it was sent
     */
    public String getLetzterFehler() {
        return letzterFehler;
    }
}
