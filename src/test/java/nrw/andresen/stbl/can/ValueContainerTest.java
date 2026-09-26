package nrw.andresen.stbl.can;

import nrw.andresen.stbl.services.can.ValueContainer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ValueContainerTest {

    @Test
    public void testDoubleWithoutFloatingPointArtifacts() {
        assertEquals("20.458358", new ValueContainer<>(20.458357999999997d, Instant.now()).getValueString());
        assertEquals("2.6", new ValueContainer<>(2.6d, Instant.now()).getValueString());
        assertEquals("164", new ValueContainer<>(164d, Instant.now()).getValueString());
    }

    @Test
    public void testOtherTypesUnchanged() {
        assertEquals("true", new ValueContainer<>(true, Instant.now()).getValueString());
    }
}
