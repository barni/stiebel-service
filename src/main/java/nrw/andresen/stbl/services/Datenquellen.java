package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * When each source of data last delivered a value: the entities from Home Assistant set on the configuration page
 * (house meter, PV, outdoor temperature, device counters and their readings before) and the heat pump on the CAN
 * bus. Read every two minutes in the background, the page only shows the result.
 */
@Component
public class Datenquellen {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    // How far back a last value is searched
    static final Duration SUCHEN = Duration.ofDays(90);
    static final Duration MESSWERT = Duration.ofHours(6);
    static final Duration ZAEHLER = Duration.ofDays(10);
    static final Duration CAN = Duration.ofMinutes(2);

    /**
     * Last value of one source
     *
     * @param ok false if the source is overdue; readings by hand are never overdue
     */
    public record Zuletzt(String text, boolean ok) {
    }

    private final Logger logger = LoggerFactory.getLogger(Datenquellen.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private Konfiguration konfiguration;
    @Autowired
    private CanBus canBus;
    private volatile Map<String, Instant> letzte = Map.of();
    private volatile Map<String, String> entities = Map.of();
    // Hours a device counter may be silent, by setting name; 0 means no warning
    private volatile Map<String, Integer> alarm = Map.of();

    /**
     * Two minutes after the start and then every two minutes
     */
    @Scheduled(initialDelay = 120000, fixedRate = 120000)
    public void lesen() {
        if (!influx.isAktiv()) {
            return;
        }
        Map<String, String> werte = new HashMap<>();
        for (Konfiguration.Wert w : konfiguration.stand().werte()) {
            werte.put(w.feld().name(), w.datei() == null ? "" : w.datei().trim());
        }
        String standard = influx.getBucket();
        String haushalt = werte.getOrDefault("auswertung.haushalt.bucket", "").isEmpty() ? standard
                : werte.get("auswertung.haushalt.bucket");
        String aussen = werte.getOrDefault("auswertung.aussentemp.bucket", "").isEmpty() ? standard
                : werte.get("auswertung.aussentemp.bucket");
        Map<String, String> neu = new LinkedHashMap<>();
        Map<String, Instant> zeiten = new HashMap<>();
        for (String name : quellen()) {
            String entity = werte.getOrDefault(name, "");
            if (entity.isEmpty() || !NAME.matcher(entity).matches()) {
                continue;
            }
            String bucket = name.startsWith("auswertung.aussentemp") ? aussen : haushalt;
            if (!NAME.matcher(bucket).matches()) {
                continue;
            }
            neu.put(name, entity);
            try {
                Map<Instant, Double> w = influx.werte("from(bucket: \"" + bucket + "\") |> range(start: -"
                        + SUCHEN.toDays() + "d) |> filter(fn: (r) => r._field == \"value\" and r.entity_id == \""
                        + entity + "\") |> group() |> last()");
                w.keySet().stream().max(Instant::compareTo).ifPresent(t -> zeiten.put(name, t));
            } catch (Exception e) {
                logger.debug("Last value of " + entity + " could not be read: " + e.getMessage());
            }
        }
        Map<String, Integer> tage = new HashMap<>();
        for (int n = 1; n <= Geraete.ANZAHL; n++) {
            String t = werte.getOrDefault("auswertung.geraet." + n + ".alarm", "");
            try {
                tage.put("auswertung.geraet." + n + ".zaehler", t.isEmpty() ? 240 : Integer.parseInt(t));
            } catch (NumberFormatException e) {
                tage.put("auswertung.geraet." + n + ".zaehler", 240);
            }
        }
        alarm = tage;
        entities = neu;
        letzte = zeiten;
    }

    /**
     * The settings that name an entity, in the order of the page
     */
    static java.util.List<String> quellen() {
        java.util.List<String> q = new java.util.ArrayList<>(java.util.List.of("auswertung.aussentemp.entity",
                "auswertung.haushalt.zaehler", "auswertung.pv.ertrag", "auswertung.pv.einspeisung"));
        for (int n = 1; n <= Geraete.ANZAHL; n++) {
            q.add("auswertung.geraet." + n + ".zaehler");
        }
        return q;
    }

    /**
     * Per setting the last value, for the configuration page; the heat pump under the port of the CAN adapter
     */
    public Map<String, Zuletzt> fuerEinstellungen() {
        Instant jetzt = Instant.now();
        Map<String, Zuletzt> m = new HashMap<>();
        Map<String, Instant> l = letzte;
        for (String name : entities.keySet()) {
            Integer tage = alarm.get(name);
            Duration max = tage == null ? maxAlter(name) : tage <= 0 ? null : Duration.ofHours(tage);
            m.put(name, zuletzt(l.get(name), jetzt, max));
        }
        Instant antwort = canBus.getLetzteAntwort();
        if (antwort != null) {
            Zuletzt z = zuletzt(antwort, jetzt, CAN);
            m.put("USBtin.port", new Zuletzt("Wärmepumpe: " + z.text(), z.ok()));
        }
        return m;
    }

    /**
     * How old a value may be before the source counts as overdue, null for readings by hand and the feed-in, which
     * change only now and then
     */
    static Duration maxAlter(String name) {
        if (name.endsWith(".davor") || name.equals("auswertung.pv.einspeisung")) {
            return null;
        }
        return name.equals("auswertung.haushalt.zaehler") || name.equals("auswertung.aussentemp.entity") ? MESSWERT
                : ZAEHLER;
    }

    static Zuletzt zuletzt(Instant zeit, Instant jetzt, Duration maxAlter) {
        if (zeit == null) {
            return new Zuletzt("kein Wert in den letzten " + SUCHEN.toDays() + " Tagen", maxAlter == null);
        }
        Duration alter = Duration.between(zeit, jetzt);
        String wann = ZEIT.format(zeit.atZone(ZONE)) + " (vor " + dauer(alter) + ")";
        boolean ok = maxAlter == null || alter.compareTo(maxAlter) <= 0;
        return new Zuletzt(ok ? "letzter Wert " + wann : "seit " + dauer(alter) + " kein Wert, zuletzt " + wann, ok);
    }

    static String dauer(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 120) {
            return s + " s";
        }
        if (s < 7200) {
            return s / 60 + " min";
        }
        if (s < 172800) {
            return s / 3600 + " h";
        }
        return s / 86400 + " Tagen";
    }
}
