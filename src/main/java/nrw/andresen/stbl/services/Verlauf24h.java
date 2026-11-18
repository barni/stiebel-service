package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stored values of the last 24 hours as 10 minute means for the overview. Read at most once a minute and only when
 * the overview is opened: the page updates itself every 20 s.
 */
@Component
public class Verlauf24h {

    static final Duration SPANNE = Duration.ofHours(24);
    static final Duration FENSTER = Duration.ofMinutes(10);
    private static final Duration GUELTIG = Duration.ofMinutes(1);
    static final List<String> REIHEN = List.of("LeistungInverter", "Abtauung", "VorlaufFein", "RuecklaufFein",
            "VorlaufIstTemp", "RuecklaufIstTemp", "AussentempWPM", "Aussentemp", "Heizungsdruck",
            "VerdichterDrehzahlHz");

    /**
     * @param reihen the courses by name without WP_, a value missing if it could not be read
     */
    public record Daten(Instant geholt, Map<String, InfluxController.History> reihen) {

        public InfluxController.History reihe(String name) {
            InfluxController.History h = reihen.get(name);
            return h == null ? new InfluxController.History(name, new long[0], new double[0]) : h;
        }
    }

    private final Logger logger = LoggerFactory.getLogger(Verlauf24h.class);
    @Autowired
    private InfluxController influx;
    private volatile Daten daten;

    public synchronized Daten get() {
        Daten d = daten;
        if (d != null && d.geholt().isAfter(Instant.now().minus(GUELTIG))) {
            return d;
        }
        Map<String, InfluxController.History> reihen = new HashMap<>();
        for (String name : REIHEN) {
            try {
                reihen.put(name, influx.history(name, SPANNE, FENSTER));
            } catch (Exception e) {
                logger.debug("Course of " + name + " could not be read: " + e.getMessage());
            }
        }
        daten = new Daten(Instant.now(), reihen);
        return daten;
    }
}
