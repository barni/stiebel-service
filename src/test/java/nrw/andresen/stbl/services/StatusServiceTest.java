package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the whole status page once: a row outside a card fails only at runtime
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class StatusServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Waermepumpe wp;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private CanBus can;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private WaermebedarfVergleich vergleich;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Tagesuebersicht tagesuebersicht;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Ueberwachung ueberwachung;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Fehlstarts fehlstarts;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Version version;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private InfluxController influx;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private SignalService signale;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Verlauf24h verlauf24h;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Zustand zustand;
    @InjectMocks
    private StatusService status;

    @Test
    public void testSeiteBaut() {
        String html = status.getStatus();
        assertTrue(html.contains("<section class=\"jetzt"));
        assertTrue(html.contains("<section class=\"kacheln\""));
        assertTrue(html.contains("Störungen"));
        assertTrue(html.contains("Prüfungen"));
        assertFalse(html.contains("Vorlauf-Soll WPM"));
        String anlage = status.getAnlage();
        assertTrue(anlage.contains("Reserve bis Abtauung"));
        assertTrue(anlage.contains("Software der Geräte"));
        assertTrue(anlage.contains("id=\"system\""));
        assertTrue(anlage.contains("id=\"meldungen\""));
    }
}
