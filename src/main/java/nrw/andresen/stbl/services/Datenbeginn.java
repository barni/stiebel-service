package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Day of the first value the service stored, so the evaluations start with the data of this installation instead of
 * a fixed date. Read once from InfluxDB and kept.
 */
@Component
public class Datenbeginn {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private final Logger logger = LoggerFactory.getLogger(Datenbeginn.class);
    @Autowired
    private InfluxController influx;
    private volatile LocalDate beginn;

    /**
     * First day with a stored value of the heat pump, null without InfluxDB or if it cannot be read yet
     */
    public LocalDate getBeginn() {
        LocalDate b = beginn;
        if (b == null && influx != null && influx.isAktiv()) {
            try {
                Map<Instant, Double> erster = influx.werte(flux(influx.getBucket()));
                b = erster.keySet().stream().min(Instant::compareTo).map(t -> t.atZone(ZONE).toLocalDate())
                        .orElse(null);
                beginn = b;
            } catch (Exception e) {
                logger.warn("First stored value could not be read: " + e.getMessage());
            }
        }
        return b;
    }

    /**
     * The configured day, or the first day with data if the setting is empty
     *
     * @param eingestellt value of the setting, may be empty
     * @param ersatz      day used while the first day is not known
     */
    public LocalDate ab(String eingestellt, LocalDate ersatz) {
        if (eingestellt != null && !eingestellt.isBlank()) {
            return LocalDate.parse(eingestellt.trim());
        }
        LocalDate b = getBeginn();
        return b != null ? b : ersatz;
    }

    static String flux(String bucket) {
        return "from(bucket: \"" + bucket + "\") |> range(start: 2000-01-01T00:00:00Z)"
                + " |> filter(fn: (r) => r._measurement == \"WP_LeistungInverter\" and r._field == \"value\")"
                + " |> first() |> keep(columns: [\"_time\", \"_value\"])";
    }
}
