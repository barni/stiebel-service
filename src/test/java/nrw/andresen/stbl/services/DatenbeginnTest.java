package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DatenbeginnTest {

    @Test
    public void testAb() {
        Datenbeginn d = new Datenbeginn();
        // a set day wins; without InfluxDB the first value is not known and the substitute counts
        assertEquals(LocalDate.of(2023, 7, 1), d.ab("2023-07-01", LocalDate.of(2020, 1, 1)));
        assertEquals(LocalDate.of(2020, 1, 1), d.ab("", LocalDate.of(2020, 1, 1)));
        assertEquals(LocalDate.of(2022, 7, 1), Datenbeginn.ersterTag(" 2022-07-01 "));
        assertEquals(LocalDate.now().minusYears(2), Datenbeginn.ersterTag(""));
    }
}
