package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.ValueContainer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Sends the values to Home Assistant over MQTT. With MQTT discovery Home Assistant creates the device "Wärmepumpe"
 * with its sensors on its own; nothing can be switched from there, the CAN bus stays read only.
 * <ul>
 * <li>{praefix}/status: online, or offline as last will when the service ends or the connection breaks</li>
 * <li>{praefix}/zustand: all values as one JSON object every 30 s, kept by the broker (retained)</li>
 * <li>homeassistant/{sensor|binary_sensor}/stbl/{name}/config: one description per entity, sent after every
 * connection</li>
 * </ul>
 * Switched off while mqtt.host is empty.
 */
@Component
public class MqttService {

    static final String DISCOVERY = "homeassistant";
    // Values older than this are sent as unknown, e.g. while the CAN bus does not answer
    static final Duration MAX_ALTER = Duration.ofMinutes(3);

    /**
     * One entity in Home Assistant
     *
     * @param komponente  sensor or binary_sensor
     * @param geraeteklasse device_class of Home Assistant, null for none
     * @param zustandsklasse state_class (measurement, total_increasing), null for none
     */
    record Entitaet(String schluessel, String name, String komponente, String einheit, String geraeteklasse,
                    String zustandsklasse, String icon, Integer stellen) {
    }

    static final List<Entitaet> ENTITAETEN = List.of(
            new Entitaet("betrieb", "Betrieb", "sensor", null, null, null, "mdi:heat-pump", null),
            new Entitaet("aussentemperatur", "Außentemperatur", "sensor", "°C", "temperature", "measurement", null, 1),
            new Entitaet("vorlauf", "Vorlauf", "sensor", "°C", "temperature", "measurement", null, 1),
            new Entitaet("ruecklauf", "Rücklauf", "sensor", "°C", "temperature", "measurement", null, 1),
            new Entitaet("heizungsdruck", "Heizungsdruck", "sensor", "bar", "pressure", "measurement", null, 2),
            new Entitaet("leistung", "Leistungsaufnahme (geschätzt)", "sensor", "W", "power", "measurement", null, 0),
            new Entitaet("waermeleistung", "Wärmeleistung", "sensor", "kW", "power", "measurement", null, 2),
            new Entitaet("arbeitszahl", "Arbeitszahl (geschätzt)", "sensor", null, null, "measurement",
                    "mdi:gauge", 1),
            new Entitaet("drehzahl", "Verdichter", "sensor", "Hz", "frequency", "measurement", null, 0),
            new Entitaet("volumenstrom", "Volumenstrom", "sensor", "L/min", "volume_flow_rate", "measurement",
                    null, 1),
            new Entitaet("abtau_reserve", "Reserve bis Abtauung", "sensor", "K", null, "measurement",
                    "mdi:snowflake-melt", 1),
            new Entitaet("hochdruck", "Hochdruck", "sensor", "bar", "pressure", "measurement", null, 1),
            new Entitaet("niederdruck", "Niederdruck", "sensor", "bar", "pressure", "measurement", null, 1),
            new Entitaet("regler_integral", "Regler-Integral", "sensor", null, null, "measurement",
                    "mdi:sigma", 0),
            // Totals of today for the energy dashboard; they start at 0 at midnight, which Home Assistant counts as
            // a new cycle of the meter
            new Entitaet("strom_heute", "Stromverbrauch heute (geschätzt)", "sensor", "kWh", "energy", "total_increasing",
                    null, 2),
            new Entitaet("waerme_heute", "Wärme heute", "sensor", "kWh", "energy", "total_increasing", null, 1),
            new Entitaet("starts_heute", "Starts heute", "sensor", null, null, "total_increasing",
                    "mdi:restart", 0),
            // Forecast of the year from the year comparison, calculated every hour; prognose_jahr is the whole
            // house, named so because it sits under the device of the heat pump (the key stays for the entity ID)
            new Entitaet("prognose_jahr", "Haus gesamt Strom Prognose Jahr", "sensor", "kWh", null, null,
                    "mdi:chart-line", 0),
            new Entitaet("prognose_heizung", "Heizung Prognose Jahr (geschätzt)", "sensor", "kWh", null, null,
                    "mdi:chart-line", 0),
            new Entitaet("warnungen", "Warnungen", "sensor", null, null, null, "mdi:alert-outline", null),
            new Entitaet("zustand", "Zustand", "sensor", null, null, null, "mdi:stethoscope", null),
            new Entitaet("dienst", "Dienst", "sensor", null, null, null, "mdi:server", null),
            new Entitaet("verdichter_laeuft", "Verdichter läuft", "binary_sensor", null, "running", null, null,
                    null),
            new Entitaet("abtauung", "Abtauung", "binary_sensor", null, null, null, "mdi:snowflake-melt", null),
            new Entitaet("evu_sperre", "EVU-Sperre", "binary_sensor", null, null, null, "mdi:transmission-tower-off",
                    null),
            new Entitaet("warnung", "Warnung", "binary_sensor", null, "problem", null, null, null));

    private final Logger logger = LoggerFactory.getLogger(MqttService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final String host;
    private final int port;
    private final String benutzer;
    private final String passwort;
    private final String praefix;
    @Autowired
    private Waermepumpe wp;
    @Autowired
    private SignalService signale;
    @Autowired
    private Ueberwachung ueberwachung;
    @Autowired
    private Tagesuebersicht tagesuebersicht;
    @Autowired
    private Version version;
    @Autowired
    private Jahresvergleich jahresvergleich;
    private MqttClient client;
    private volatile String letzterFehler;
    private volatile Instant letzteNachricht;
    // Set by every (re)connection, the descriptions are then sent by the next run; publishing with QoS 1 from the
    // callback blocked Paho after 10 messages, the acknowledgements are handled by the same thread
    private volatile boolean anmelden;

    public MqttService(@Value("${mqtt.host:}") String host, @Value("${mqtt.port:1883}") int port,
                       @Value("${mqtt.benutzer:}") String benutzer, @Value("${mqtt.passwort:}") String passwort,
                       @Value("${mqtt.praefix:stbl}") String praefix) {
        this.host = host.trim();
        this.port = port;
        this.benutzer = benutzer.trim();
        this.passwort = passwort;
        this.praefix = praefix.trim().isEmpty() ? "stbl" : praefix.trim();
    }

    public boolean isKonfiguriert() {
        return !host.isEmpty();
    }

    /**
     * Error of the last attempt to connect or send, null if it worked
     */
    public String getLetzterFehler() {
        return letzterFehler;
    }

    public Instant getLetzteNachricht() {
        return letzteNachricht;
    }

    /**
     * Every 30 s, starting 10 s after the start: the connection is checked at once, missing values are sent once
     * the CAN bus has answered
     */
    @Scheduled(initialDelay = 10000, fixedRate = 30000)
    public synchronized void senden() {
        if (!isKonfiguriert()) {
            return;
        }
        try {
            verbinden();
            if (client.isConnected()) {
                if (anmelden) {
                    anmelden = false;
                    anmelden();
                }
                senden(praefix + "/zustand", json.writeValueAsString(werte()), 0, true);
                letzteNachricht = Instant.now();
                letzterFehler = null;
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).equals(letzterFehler)) {
                logger.warn("Sending to MQTT failed: " + e.getMessage());
            }
            letzterFehler = String.valueOf(e.getMessage());
        }
    }

    private void verbinden() throws MqttException {
        if (client == null) {
            client = new MqttClient("tcp://" + host + ":" + port, "stbl-service-" + praefix, new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean wieder, String adresse) {
                    // Also after an automatic reconnect: the broker may have lost the retained descriptions
                    anmelden = true;
                }

                @Override
                public void connectionLost(Throwable grund) {
                    letzterFehler = "Verbindung verloren: " + grund.getMessage();
                }

                @Override
                public void messageArrived(String thema, MqttMessage nachricht) {
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
        }
        if (!client.isConnected()) {
            MqttConnectOptions optionen = new MqttConnectOptions();
            optionen.setAutomaticReconnect(true);
            optionen.setCleanSession(true);
            optionen.setConnectionTimeout(10);
            optionen.setKeepAliveInterval(60);
            optionen.setMaxInflight(ENTITAETEN.size() + 10);
            optionen.setWill(praefix + "/status", "offline".getBytes(StandardCharsets.UTF_8), 1, true);
            if (!benutzer.isEmpty()) {
                optionen.setUserName(benutzer);
                optionen.setPassword(passwort.toCharArray());
            }
            client.connect(optionen);
            logger.info("Connected to MQTT broker " + host + ":" + port);
        }
    }

    /**
     * Status online and the description of every entity, both kept by the broker
     */
    private void anmelden() throws Exception {
        senden(praefix + "/status", "online", 1, true);
        for (Entitaet e : ENTITAETEN) {
            senden(thema(e), json.writeValueAsString(beschreibung(e, praefix, version.getText())), 1, true);
        }
    }

    private void senden(String thema, String text, int qos, boolean behalten) throws MqttException {
        MqttMessage nachricht = new MqttMessage(text.getBytes(StandardCharsets.UTF_8));
        nachricht.setQos(qos);
        nachricht.setRetained(behalten);
        client.publish(thema, nachricht);
    }

    static String thema(Entitaet e) {
        return DISCOVERY + "/" + e.komponente() + "/stbl/" + e.schluessel() + "/config";
    }

    /**
     * Discovery message of one entity: name, unit and classes, where its value is and to which device it belongs
     */
    static Map<String, Object> beschreibung(Entitaet e, String praefix, String version) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", e.name());
        b.put("unique_id", "stbl_" + e.schluessel());
        b.put("object_id", "waermepumpe_" + e.schluessel());
        b.put("state_topic", praefix + "/zustand");
        // A value missing in the object is shown as unknown
        b.put("value_template", "{{ value_json." + e.schluessel() + " if value_json." + e.schluessel()
                + " is defined else none }}");
        b.put("availability_topic", praefix + "/status");
        if (e.komponente().equals("binary_sensor")) {
            b.put("payload_on", "ON");
            b.put("payload_off", "OFF");
        }
        if (e.einheit() != null) {
            b.put("unit_of_measurement", e.einheit());
        }
        if (e.geraeteklasse() != null) {
            b.put("device_class", e.geraeteklasse());
        }
        if (e.zustandsklasse() != null) {
            b.put("state_class", e.zustandsklasse());
        }
        if (e.stellen() != null) {
            b.put("suggested_display_precision", e.stellen());
        }
        if (e.icon() != null) {
            b.put("icon", e.icon());
        }
        Map<String, Object> geraet = new LinkedHashMap<>();
        geraet.put("identifiers", List.of("stbl-" + praefix));
        geraet.put("name", "Wärmepumpe");
        geraet.put("manufacturer", "Stiebel Eltron");
        geraet.put("model", "WPL ACS classic mit WPM 3");
        geraet.put("sw_version", version);
        b.put("device", geraet);
        return b;
    }

    /**
     * Current values by the key of the entity; values that are missing or too old are left out
     */
    Map<String, Object> werte() {
        Map<String, Object> w = new LinkedHashMap<>();
        Instant jetzt = Instant.now();
        for (Rahmen.Signal s : signale.signale()) {
            switch (s.name()) {
                case "Betrieb" -> w.put("betrieb", s.wert());
                case "Warnungen" -> w.put("warnungen", s.wert());
                case "Zustand" -> w.put("zustand", s.wert());
                case "Dienst" -> w.put("dienst", s.wert());
                default -> {
                }
            }
        }
        Double aussen = wert(wp::getAussentempWpm, jetzt);
        rund(w, "aussentemperatur", aussen != null ? aussen : wert(wp::getAussentemp, jetzt), 1);
        Double vorlauf = wert(wp::getVorlaufFein, jetzt);
        rund(w, "vorlauf", vorlauf != null ? vorlauf : wert(wp::getVorlaufIstTemp, jetzt), 1);
        Double ruecklauf = wert(wp::getRuecklaufFein, jetzt);
        rund(w, "ruecklauf", ruecklauf != null ? ruecklauf : wert(wp::getRuecklaufIstTemp, jetzt), 1);
        rund(w, "heizungsdruck", wert(wp::getHeizungsdruck, jetzt), 2);
        Double schein = wert(wp::getLeistungInverter, jetzt);
        rund(w, "leistung", schein == null ? null : Waermepumpe.wirkleistung(schein), 0);
        rund(w, "waermeleistung", wert(wp::getWaermeleistung, jetzt), 2);
        rund(w, "arbeitszahl", wert(wp::getArbeitszahl, jetzt), 1);
        rund(w, "drehzahl", wert(wp::getVerdichterDrehzahl, jetzt), 0);
        rund(w, "volumenstrom", wert(wp::getVolumenstrom, jetzt), 1);
        rund(w, "abtau_reserve", wert(wp::getAbtauReserve, jetzt), 1);
        rund(w, "hochdruck", wert(wp::getHochdruck, jetzt), 1);
        rund(w, "niederdruck", wert(wp::getNiederdruck, jetzt), 1);
        rund(w, "regler_integral", wert(wp::getRegelIntegral, jetzt), 0);
        Tagesuebersicht.Tag heute = tagesuebersicht.getHeute();
        if (heute != null) {
            rund(w, "strom_heute", heute.stromKWh(), 2);
            rund(w, "waerme_heute", heute.waermeKWh(), 1);
            w.put("starts_heute", heute.starts());
        }
        prognose(w, jahresvergleich == null ? null : jahresvergleich.getVergleich());
        schalter(w, "verdichter_laeuft", wert(wp::isVerdichterOn, jetzt));
        schalter(w, "abtauung", wert(wp::isAbtauung, jetzt));
        schalter(w, "evu_sperre", wert(wp::isEvuSperre, jetzt));
        w.put("warnung", ueberwachung.getWarnungen().stream().anyMatch(Warnung::isAktiv) ? "ON" : "OFF");
        return w;
    }

    /**
     * Forecast of the whole year: the grid import (only with the house meter) and the heating
     */
    static void prognose(Map<String, Object> w, Jahresvergleich.Vergleich v) {
        if (v == null) {
            return;
        }
        for (Jahresvergleich.Prognose p : v.prognose()) {
            switch (p.name()) {
                case "Gesamt" -> rund(w, "prognose_jahr", p.jahr(), 0);
                case "Heizung" -> rund(w, "prognose_heizung", p.jahr(), 0);
                default -> {
                }
            }
        }
    }

    static void rund(Map<String, Object> w, String schluessel, Double wert, int stellen) {
        if (wert != null && !wert.isNaN() && !wert.isInfinite()) {
            double f = Math.pow(10, stellen);
            w.put(schluessel, Math.round(wert * f) / f);
        }
    }

    static void schalter(Map<String, Object> w, String schluessel, Boolean wert) {
        if (wert != null) {
            w.put(schluessel, wert ? "ON" : "OFF");
        }
    }

    private static <T extends java.io.Serializable> T wert(Callable<ValueContainer<T>> quelle, Instant jetzt) {
        try {
            ValueContainer<T> v = quelle.call();
            return v.getTimestamp() != null && v.getTimestamp().isBefore(jetzt.minus(MAX_ALTER)) ? null : v.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Says goodbye with offline, so Home Assistant shows the entities as unavailable at once
     */
    @PreDestroy
    public synchronized void beenden() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                senden(praefix + "/status", "offline", 1, true);
                client.disconnect(2000);
            }
            client.close();
        } catch (MqttException e) {
            logger.debug("Closing MQTT: " + e.getMessage());
        }
    }
}
