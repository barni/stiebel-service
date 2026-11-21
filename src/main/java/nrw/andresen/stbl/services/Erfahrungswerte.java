package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Values this installation reached so far, from its own stored data, as comparison for the warnings: e.g. the
 * lowest low pressure at frost or the longest standstill at frost. Nothing about one house is written into the
 * code; with too few data a value is left out. Calculated once a day over the last two years; the months that are
 * over are read only once.
 */
@Component
public class Erfahrungswerte {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter MONAT = DateTimeFormatter.ofPattern("MM/yyyy");
    // How far back the values go
    static final int MONATE_ZURUECK = 24;
    // A day counts as frost below this daily mean
    static final double FROST = 0.0;
    // Fewest frost days or values for a sentence
    static final int MIN_FROSTTAGE = 3;
    static final double LAEUFT_AB_VA = 50;
    static final double LAEUFT_SICHER_VA = 300;

    // Keys of the values, also used by the warnings and the configuration page
    public static final String HOCHDRUCK = "hochdruck";
    public static final String FROST_PAUSE = "frost";
    public static final String DRUCKABFALL = "druckabfall";
    public static final String NIEDERDRUCK = "niederdruck";
    public static final String HEISSGAS = "heissgas";
    public static final String ABTAUUNG = "abtauung";
    public static final String SPANNUNG = "spannung";
    public static final String STARTS = "starts";

    private final Logger logger = LoggerFactory.getLogger(Erfahrungswerte.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    @Autowired
    private Datenbeginn datenbeginn;
    @Autowired
    private Ueberwachung ueberwachung;
    private final Map<YearMonth, Monat> fertig = new ConcurrentHashMap<>();
    private volatile Map<String, String> saetze = Map.of();

    /**
     * Values of one month: per day the longest standstill in hours, the starts per hour, the lowest low pressure
     * while running, and the durations of the defrosts in minutes
     */
    record Monat(Map<LocalDate, Double> laengsteRuhe, Map<LocalDate, List<Integer>> startsJeStunde,
                 Map<LocalDate, Double> niederdruckMin, List<Double> abtauMinuten, Instant ersteAbtauung) {
    }

    /**
     * Sentences per key, e.g. "In den eigenen Daten seit 01/2025 bei Frost mindestens 3,2 bar."; a key is missing
     * while there are too few data
     */
    public Map<String, String> getSaetze() {
        return saetze;
    }

    /**
     * Sentences for the settings of the configuration page, by the name of the setting
     */
    public Map<String, String> fuerEinstellungen() {
        Map<String, String> s = saetze;
        Map<String, String> e = new HashMap<>();
        put(e, "warnung.hochdruck.max", s.get(HOCHDRUCK));
        put(e, "warnung.frost.stunden", s.get(FROST_PAUSE));
        put(e, "warnung.heizungsdruck.abfall", s.get(DRUCKABFALL));
        put(e, "warnung.niederdruck.min", s.get(NIEDERDRUCK));
        put(e, "warnung.heissgas.max", s.get(HEISSGAS));
        put(e, "warnung.abtauung.minuten", s.get(ABTAUUNG));
        put(e, "warnung.spannung.min", s.get(SPANNUNG));
        put(e, "warnung.spannung.max", s.get(SPANNUNG));
        put(e, "warnung.starts.proStunde", s.get(STARTS));
        return e;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    /**
     * 15 minutes after the start, after the other evaluations, and then once a day
     */
    @Scheduled(initialDelay = 900000, fixedRate = 86400000)
    public void berechnen() {
        if (!influx.isAktiv()) {
            return;
        }
        try {
            ZonedDateTime jetzt = ZonedDateTime.now(ZONE);
            LocalDate ab = jetzt.toLocalDate().minusMonths(MONATE_ZURUECK).withDayOfMonth(1);
            LocalDate beginn = datenbeginn.getBeginn();
            if (beginn != null && beginn.isAfter(ab)) {
                ab = beginn;
            }
            saetze = rechnen(ab, jetzt);
            ueberwachung.erfahrungSetzen(saetze);
            logger.info("Values of this installation calculated: " + saetze.keySet());
        } catch (Exception e) {
            logger.warn("Values of this installation could not be calculated: " + e.getMessage());
        }
    }

    private Map<String, String> rechnen(LocalDate ab, ZonedDateTime jetzt) {
        Instant von = ab.atStartOfDay(ZONE).toInstant();
        Instant bis = jetzt.toInstant();
        String bucket = influx.getBucket();
        Map<LocalDate, Double> aussen = vergleich.aussentemperatur(von, bis);
        List<Monat> monate = new ArrayList<>();
        YearMonth laufend = YearMonth.from(jetzt);
        for (YearMonth m = YearMonth.from(ab); !m.isAfter(laufend); m = m.plusMonths(1)) {
            Instant mVon = m.atDay(1).atStartOfDay(ZONE).toInstant();
            Instant mBis = m.equals(laufend) ? bis : m.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
            if (mVon.isBefore(von)) {
                mVon = von;
            }
            Instant start = mVon;
            Instant ende = mBis;
            monate.add(m.equals(laufend) ? monat(bucket, start, ende)
                    : fertig.computeIfAbsent(m, x -> monat(bucket, start, ende)));
        }
        Map<String, String> s = new LinkedHashMap<>();
        String seit = MONAT.format(ab);
        // Standstill and starts at frost
        List<Double> ruhe = new ArrayList<>();
        List<Integer> startsFrost = new ArrayList<>();
        List<Double> nd = new ArrayList<>();
        int frosttage = 0;
        for (Monat m : monate) {
            for (Map.Entry<LocalDate, Double> e : m.laengsteRuhe().entrySet()) {
                Double t = aussen.get(e.getKey());
                if (t != null && t < FROST) {
                    frosttage++;
                    ruhe.add(e.getValue());
                    startsFrost.addAll(m.startsJeStunde().getOrDefault(e.getKey(), List.of()));
                    Double n = m.niederdruckMin().get(e.getKey());
                    if (n != null) {
                        nd.add(n);
                    }
                }
            }
        }
        if (frosttage >= MIN_FROSTTAGE) {
            String frost = " (" + frosttage + " Frosttage seit " + seit + ")";
            s.put(FROST_PAUSE, "In den eigenen Daten" + frost + " stand der Verdichter bei Frost höchstens "
                    + zahl(Collections.max(ruhe), 1) + " Stunden.");
            if (!startsFrost.isEmpty()) {
                s.put(STARTS, "In den eigenen Daten" + frost + " startete er bei Frost meist höchstens "
                        + quantil(startsFrost.stream().map(Integer::doubleValue).toList(), 0.95).intValue()
                        + "-mal je Stunde, Abtauungen nicht gezählt.");
            }
            if (nd.size() >= MIN_FROSTTAGE) {
                s.put(NIEDERDRUCK, "In den eigenen Daten" + frost + " lag er bei Frost im Lauf bei mindestens "
                        + zahl(Collections.min(nd), 1) + " bar.");
            }
        }
        // Defrosts: from the detection of the service, so only since it exists
        List<Double> abtau = new ArrayList<>();
        Instant ersteAbtauung = null;
        for (Monat m : monate) {
            abtau.addAll(m.abtauMinuten());
            if (ersteAbtauung == null && m.ersteAbtauung() != null) {
                ersteAbtauung = m.ersteAbtauung();
            }
        }
        if (abtau.size() >= 10) {
            s.put(ABTAUUNG, "In den eigenen Daten (" + abtau.size() + " Abtauungen seit "
                    + MONAT.format(ersteAbtauung.atZone(ZONE)) + ") dauerte sie meist " + zahl(quantil(abtau, 0.1), 0)
                    + " bis " + zahl(quantil(abtau, 0.9), 0) + " Minuten.");
        }
        // Pressure drop: daily means, a day against the day before
        Map<LocalDate, Double> druck = new TreeMap<>(vergleich.tagesmittel("WP_Heizungsdruck", von, bis));
        Double groessterAbfall = null;
        Double vorher = null;
        for (double d : druck.values()) {
            if (vorher != null && (groessterAbfall == null || vorher - d > groessterAbfall)) {
                groessterAbfall = vorher - d;
            }
            vorher = d;
        }
        if (druck.size() >= 30 && groessterAbfall != null) {
            s.put(DRUCKABFALL, "In den eigenen Daten seit " + seit + " fiel das Tagesmittel höchstens um "
                    + zahl(Math.max(0, groessterAbfall), 2) + " bar gegen den Tag davor.");
        }
        Double hd = einWert(bucket, "WP_Hochdruck", von, bis, "aggregateWindow(every: 5m, fn: mean, createEmpty: false)"
                + " |> max()");
        if (hd != null) {
            s.put(HOCHDRUCK, "In den eigenen Daten seit " + seit + " höchstens " + zahl(hd, 1)
                    + " bar im 5-Minuten-Mittel.");
        }
        Double hg = einWert(bucket, "WP_HeissgasTemp", von, bis, "aggregateWindow(every: 5m, fn: mean, "
                + "createEmpty: false) |> max()");
        Instant hgAb = ersterWert(bucket, "WP_HeissgasTemp", von, bis);
        if (hg != null && hgAb != null) {
            s.put(HEISSGAS, "In den eigenen Daten seit " + MONAT.format(hgAb.atZone(ZONE)) + " höchstens "
                    + zahl(hg, 0) + " °C im 5-Minuten-Mittel.");
        }
        Double uMin = einWert(bucket, "WP_SpannungInverter", von, bis, "aggregateWindow(every: 5m, fn: mean, "
                + "createEmpty: false) |> filter(fn: (r) => r._value > 100.0) |> quantile(q: 0.01)");
        Double uMax = einWert(bucket, "WP_SpannungInverter", von, bis, "aggregateWindow(every: 5m, fn: mean, "
                + "createEmpty: false) |> filter(fn: (r) => r._value > 100.0) |> quantile(q: 0.99)");
        if (uMin != null && uMax != null) {
            s.put(SPANNUNG, "In den eigenen Daten seit " + seit + " meist " + zahl(uMin, 0) + " bis " + zahl(uMax, 0)
                    + " V; die Anzeige des Inverters kann vom Hauszähler um einige Volt abweichen.");
        }
        return Collections.unmodifiableMap(s);
    }

    /**
     * Reads one month: power and low pressure in windows, defrost flag per minute
     */
    private Monat monat(String bucket, Instant von, Instant bis) {
        Map<Instant, Double> leistung = influx.werte(fenster(bucket, "WP_LeistungInverter", von, bis, "5m", "max"));
        Map<Instant, Double> ndMin = influx.werte(fenster(bucket, "WP_Niederdruck", von, bis, "10m", "min"));
        Map<Instant, Double> leistungMin = influx.werte(fenster(bucket, "WP_LeistungInverter", von, bis, "10m",
                "min"));
        Map<Instant, Double> abtauung = influx.werte(fenster(bucket, "WP_Abtauung", von, bis, "1m", "max"));
        return auswerten(leistung, ndMin, leistungMin, abtauung);
    }

    /**
     * Values of one month from the windows: a standstill is a row of 5-minute windows without power (a gap in the
     * data ends it); a start is a window with power after one without, so a defrost of about 2 minutes is no start
     */
    static Monat auswerten(Map<Instant, Double> leistung5m, Map<Instant, Double> niederdruck10m,
                           Map<Instant, Double> leistungMin10m, Map<Instant, Double> abtauung1m) {
        Map<LocalDate, Double> ruhe = new TreeMap<>();
        Map<LocalDate, Map<Integer, Integer>> starts = new TreeMap<>();
        Instant ruheSeit = null;
        Instant letztes = null;
        Boolean lief = null;
        for (Map.Entry<Instant, Double> e : new TreeMap<>(leistung5m).entrySet()) {
            Instant t = e.getKey();
            boolean an = e.getValue() > LAEUFT_AB_VA;
            if (letztes != null && t.getEpochSecond() - letztes.getEpochSecond() > 600) {
                // Gap in the data: neither a standstill nor a start can be judged across it
                ruheSeit = null;
                lief = null;
            }
            LocalDate tag = t.atZone(ZONE).toLocalDate();
            ruhe.putIfAbsent(tag, 0.0);
            if (an) {
                if (Boolean.FALSE.equals(lief)) {
                    starts.computeIfAbsent(tag, x -> new TreeMap<>()).merge(t.atZone(ZONE).getHour(), 1, Integer::sum);
                }
                ruheSeit = null;
            } else {
                if (ruheSeit == null) {
                    ruheSeit = t;
                }
                double h = (t.getEpochSecond() - ruheSeit.getEpochSecond()) / 3600.0 + 5 / 60.0;
                ruhe.merge(tag, h, Math::max);
            }
            lief = an;
            letztes = t;
        }
        Map<LocalDate, List<Integer>> jeStunde = new TreeMap<>();
        for (LocalDate tag : ruhe.keySet()) {
            Map<Integer, Integer> s = starts.getOrDefault(tag, Map.of());
            List<Integer> l = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                l.add(s.getOrDefault(h, 0));
            }
            jeStunde.put(tag, l);
        }
        Map<LocalDate, Double> nd = new TreeMap<>();
        for (Map.Entry<Instant, Double> e : niederdruck10m.entrySet()) {
            Double p = leistungMin10m.get(e.getKey());
            if (p != null && p > LAEUFT_SICHER_VA) {
                nd.merge(e.getKey().atZone(ZONE).toLocalDate(), e.getValue(), Math::min);
            }
        }
        // Defrosts: rows of minutes with the flag; the window ends at its stop, so a row of n minutes lasted about
        // n minutes, minus the minute the service keeps the flag after the end
        List<Double> dauer = new ArrayList<>();
        Instant erste = null;
        Instant beginn = null;
        Instant zuletzt = null;
        for (Map.Entry<Instant, Double> e : new TreeMap<>(abtauung1m).entrySet()) {
            Instant t = e.getKey();
            boolean ab = e.getValue() > 0.5;
            boolean luecke = zuletzt != null && t.getEpochSecond() - zuletzt.getEpochSecond() > 90;
            if (beginn != null && (!ab || luecke)) {
                dauer.add(Math.max(1, (zuletzt.getEpochSecond() - beginn.getEpochSecond()) / 60.0));
                beginn = null;
            }
            if (ab && beginn == null) {
                beginn = t;
                if (erste == null) {
                    erste = t;
                }
            }
            zuletzt = t;
        }
        return new Monat(ruhe, jeStunde, nd, dauer, erste);
    }

    private Double einWert(String bucket, String measurement, Instant von, Instant bis, String auswertung) {
        Map<Instant, Double> w = influx.werte("from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: "
                + bis + ") |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> group() |> " + auswertung + " |> map(fn: (r) => ({_time: " + bis + ", _value: r._value}))");
        return w.isEmpty() ? null : w.values().iterator().next();
    }

    private Instant ersterWert(String bucket, String measurement, Instant von, Instant bis) {
        Map<Instant, Double> w = influx.werte("from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: "
                + bis + ") |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> group() |> first()");
        return w.isEmpty() ? null : w.keySet().iterator().next();
    }

    static String fenster(String bucket, String measurement, Instant von, Instant bis, String fenster, String fn) {
        return "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> group() |> aggregateWindow(every: " + fenster + ", fn: " + fn + ", createEmpty: false)"
                + " |> keep(columns: [\"_time\", \"_value\"])";
    }

    static Double quantil(List<Double> werte, double q) {
        if (werte.isEmpty()) {
            return null;
        }
        List<Double> s = new ArrayList<>(werte);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(q * s.size())));
    }

    static String zahl(double wert, int stellen) {
        return String.format(Locale.GERMANY, "%." + stellen + "f", wert);
    }
}
