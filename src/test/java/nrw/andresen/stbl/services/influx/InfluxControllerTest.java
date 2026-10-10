package nrw.andresen.stbl.services.influx;

import nrw.andresen.stbl.services.can.ValueContainer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    public void testAusgeschaltet() {
        // Without InfluxDB the other settings may be empty and nothing is contacted
        InfluxController influx = new InfluxController(false, "", "", "", "");
        assertFalse(influx.isAktiv());
        influx.storePoints(List.of(influx.createPoint("Test", new ValueContainer<>(1d, Instant.now()))));
        assertEquals(InfluxController.AUSGESCHALTET,
                assertThrows(IllegalStateException.class, () -> influx.history("VorlaufIstTemp", DAY, WINDOW))
                        .getMessage());
        assertThrows(IllegalStateException.class, influx::erreichbar);
        assertThrows(IllegalStateException.class, () -> influx.letzterWert("Einstellung_Heizkurve"));
    }
}
