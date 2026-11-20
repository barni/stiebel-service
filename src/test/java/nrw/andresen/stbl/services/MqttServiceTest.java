package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MqttServiceTest {

    private static MqttService.Entitaet entitaet(String schluessel) {
        return MqttService.ENTITAETEN.stream().filter(e -> e.schluessel().equals(schluessel)).findFirst()
                .orElseThrow();
    }

    @Test
    public void testBeschreibung() {
        Map<String, Object> b = MqttService.beschreibung(entitaet("heizungsdruck"), "stbl", "0.2.0");
        assertEquals("Heizungsdruck", b.get("name"));
        assertEquals("stbl_heizungsdruck", b.get("unique_id"));
        assertEquals("stbl/zustand", b.get("state_topic"));
        assertEquals("{{ value_json.heizungsdruck if value_json.heizungsdruck is defined else none }}",
                b.get("value_template"));
        assertEquals("stbl/status", b.get("availability_topic"));
        assertEquals("bar", b.get("unit_of_measurement"));
        assertEquals("pressure", b.get("device_class"));
        assertEquals("measurement", b.get("state_class"));
        assertFalse(b.containsKey("payload_on"));
        @SuppressWarnings("unchecked")
        Map<String, Object> geraet = (Map<String, Object>) b.get("device");
        assertEquals(List.of("stbl-stbl"), geraet.get("identifiers"));
        assertEquals("0.2.0", geraet.get("sw_version"));
        assertEquals("homeassistant/sensor/stbl/heizungsdruck/config", MqttService.thema(entitaet("heizungsdruck")));

        // Binary sensors with ON and OFF, the energy of today for the energy dashboard
        Map<String, Object> laeuft = MqttService.beschreibung(entitaet("verdichter_laeuft"), "stbl", "x");
        assertEquals("ON", laeuft.get("payload_on"));
        assertEquals("running", laeuft.get("device_class"));
        assertEquals("homeassistant/binary_sensor/stbl/verdichter_laeuft/config",
                MqttService.thema(entitaet("verdichter_laeuft")));
        Map<String, Object> strom = MqttService.beschreibung(entitaet("strom_heute"), "stbl", "x");
        assertEquals("energy", strom.get("device_class"));
        assertEquals("total_increasing", strom.get("state_class"));
        assertEquals("kWh", strom.get("unit_of_measurement"));
        // Text sensors without unit and class
        assertNull(MqttService.beschreibung(entitaet("zustand"), "stbl", "x").get("unit_of_measurement"));

        // Every key once, valid for MQTT topics and templates
        Set<String> schluessel = new HashSet<>();
        for (MqttService.Entitaet e : MqttService.ENTITAETEN) {
            assertTrue(schluessel.add(e.schluessel()), e.schluessel());
            assertTrue(e.schluessel().matches("[a-z_]+"), e.schluessel());
        }
    }

    @Test
    public void testWerte() {
        Map<String, Object> w = new LinkedHashMap<>();
        MqttService.rund(w, "druck", 1.7849, 2);
        MqttService.rund(w, "fehlt", null, 1);
        MqttService.rund(w, "kaputt", Double.NaN, 1);
        MqttService.schalter(w, "laeuft", true);
        MqttService.schalter(w, "steht", false);
        MqttService.schalter(w, "unbekannt", null);
        // Missing values are left out, Home Assistant then shows unknown
        assertEquals(Map.of("druck", 1.78, "laeuft", "ON", "steht", "OFF"), w);
    }

    @Test
    public void testPrognose() {
        Map<String, Object> w = new LinkedHashMap<>();
        MqttService.prognose(w, null);
        assertTrue(w.isEmpty());
        Jahresvergleich.Vergleich v = new Jahresvergleich.Vergleich(List.of(2024, 2025, 2026),
                java.time.LocalDate.of(2026, 11, 20), List.of(), false, List.of(), null, null, null, List.of(),
                java.time.Instant.EPOCH, List.of());
        MqttService.prognose(w, v);
        assertFalse(w.containsKey("prognose_jahr"));
        assertTrue(MqttService.ENTITAETEN.stream().anyMatch(e -> e.schluessel().equals("prognose_jahr")));
        assertTrue(MqttService.ENTITAETEN.stream().anyMatch(e -> e.schluessel().equals("prognose_heizung")));
    }
}
