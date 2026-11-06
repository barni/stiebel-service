package nrw.andresen.stbl.services.influx;

import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
    public void testFehlerPoint() {
        // The WPM shows local time: 14.05.26 18:53 is 16:53 UTC in summer time, 21.01.26 09:59 is 08:59 UTC
        assertEquals("WP_Fehler,code=8116 code=8116i,text=\"INV H ROTORVEKTOR\" 1778777580",
                InfluxController.createFehlerPoint(new Fehlerliste.Eintrag(LocalDateTime.of(2026, 5, 14, 18, 53),
                        8116, "INV H ROTORVEKTOR", 14)).toLineProtocol());
        assertEquals(Instant.parse("2026-01-21T08:59:00Z").getEpochSecond() + "",
                InfluxController.createFehlerPoint(new Fehlerliste.Eintrag(LocalDateTime.of(2026, 1, 21, 9, 59),
                        8256, "INV H Ausgangsstrombegrenzung", 3)).toLineProtocol().replaceAll(".* ", ""));
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
