package nrw.andresen.stbl.services.influx;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class InfluxControllerTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration WINDOW = Duration.ofMinutes(2);

    @Test
    public void testFlux() {
        assertEquals("from(bucket: \"home\") |> range(start: -86400s)"
                        + " |> filter(fn: (r) => r._measurement == \"WP_VorlaufIstTemp\" and r._field == \"value\")"
                        + " |> aggregateWindow(every: 120s, fn: mean, createEmpty: false)"
                        + " |> keep(columns: [\"_time\", \"_value\"])",
                InfluxController.flux("home", "VorlaufIstTemp", DAY, WINDOW));
    }

    @Test
    public void testFluxRejectsOtherNames() {
        // Nothing but a plain name may end up in the query
        assertThrows(IllegalArgumentException.class,
                () -> InfluxController.flux("home", "Vorlauf\") |> drop(columns: [\"x\"]) //", DAY, WINDOW));
        assertThrows(IllegalArgumentException.class, () -> InfluxController.flux("home", "../x", DAY, WINDOW));
        assertThrows(IllegalArgumentException.class, () -> InfluxController.flux("home", "", DAY, WINDOW));
        assertThrows(IllegalArgumentException.class, () -> InfluxController.flux("home", null, DAY, WINDOW));
    }
}
