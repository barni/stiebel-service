package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Failed compressor starts per heating season (July to June), found in the stored values: after a standstill the
 * high pressure drops by the start attempt, the compressor does not run, and the heat pump starts again only after
 * its lock of about 23 minutes. The fault list of the heat pump shows these as INV H ROTORVEKTOR (8116); it keeps
 * only 20 entries, so the history comes from this pattern. Checked against the fault list 02-05/2026: 15 of 17
 * failed starts found, no false ones; faults while running cannot be seen this way.
 */
@Component
public class Fehlstarts {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    // Inverter power per minute: above 50 VA the compressor runs at all, a minute mean above 300 VA is a real run
    private static final double LAEUFT_AB_VA = 50;
    private static final double LAUF_AB_VA = 300;
    // Drop of the high pressure between two minutes when the start attempt opens the valves
    private static final double DRUCKABFALL_BAR = 1.0;
    // A start counts after this standstill, shorter breaks (e.g. the defrost) belong to the same run; with it the
    // count matches the start counter of the heat pump
    static final Duration MIN_STILLSTAND_START = Duration.ofMinutes(3);
    // A start attempt only after a longer standstill, a run within the first minutes is a normal start
    static final Duration MIN_STILLSTAND_VERSUCH = Duration.ofMinutes(10);
    static final Duration NORMALER_START = Duration.ofMinutes(10);
    // After a failed start the heat pump waits 22-23 minutes
    static final Duration SPERRE_MIN = Duration.ofMinutes(18);
    static final Duration SPERRE_MAX = Duration.ofMinutes(30);
    private static final int TAGE_JE_ABFRAGE = 90;
    // Failed starts happen on days with a mean outdoor temperature in this range, hardly below 0 degC (2022-2026: none
    // in 507 starts) and seldom above 15 degC; the rate is compared only within it, so a different mix of cold and
    // mild days or the number of starts does not change it
    static final double MILD_VON = 0;
    static final double MILD_BIS = 15;
    // A day counts as covered with 80 % of the values every 30 s; until 2021 the values were stored about every 25 s,
    // since then every 20 s
    private static final int MIN_WERTE_PRO_TAG = 2400;
    // The rate on mild days only if the outdoor temperature is known for most covered days of the season
    private static final double MIN_ANTEIL_TEMPERATUR = 0.8;

    private final Logger logger = LoggerFactory.getLogger(Fehlstarts.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    private final LocalDate start;
    // Seasons before the current one do not change and are calculated once
    private volatile List<Saison> abgeschlossen;
    private volatile Saison laufend;
    private volatile List<Instant> letzte = List.of();
    // Defrosts of the seasons before the current one and of the current one, null until calculated
    private volatile Abtauungen abtauungenAbgeschlossen;
    private volatile Abtauungen abtauungenLaufend;
    private volatile Instant berechnet;

    /**
     * Failed starts of one heating season, e.g. "2025/26"; waermeMWh only of the covered days, startsMild and
     * fehlstarteMild only of days with a known outdoor temperature between 0 and 15 degC
     */
    public record Saison(String name, int starts, int fehlstarts, boolean laufend, double waermeMWh, int tageMitDaten,
                         int startsMild, int fehlstartsMild, int tageMitTemperatur) {

        public Double jeMWh() {
            return waermeMWh >= 0.5 ? fehlstarts / waermeMWh : null;
        }

        /**
         * Failed starts per 1000 starts on mild days, null without outdoor temperature or starts
         */
        public Double jeTausendMild() {
            return tageMitTemperatur >= MIN_ANTEIL_TEMPERATUR * tageMitDaten && tageMitDaten > 0 && startsMild > 0
                    ? 1000d * fehlstartsMild / startsMild : null;
        }
    }

    /**
     * Defrosts counted in the stored values and the first day with values, null without values
     */
    public record Abtauungen(int anzahl, LocalDate seit) {
    }

    /**
     * Events from the stored values: pressure drops, changes standstill/running (+1 start, -1 stop) per minute and
     * starts of real runs
     */
    record Ereignisse(List<Instant> druckabfaelle, NavigableMap<Instant, Double> anAus, List<Instant> laeufe,
                      Map<LocalDate, Double> werteProTag, Map<LocalDate, Double> waermeProTag,
                      Map<LocalDate, Double> temperatur) {

        Ereignisse(List<Instant> druckabfaelle, NavigableMap<Instant, Double> anAus, List<Instant> laeufe) {
            this(druckabfaelle, anAus, laeufe, Map.of(), Map.of(), Map.of());
        }
    }

    public Fehlstarts(@Value("${auswertung.fehlstarts.start:2019-01-01}") String start) {
        this.start = LocalDate.parse(start);
    }

    /**
     * Calculates all seasons two minutes after the start, about a minute; afterwards every hour only the current one
     */
    @Scheduled(initialDelay = 120000, fixedRate = 3600000)
    public void berechnen() {
        try {
            LocalDate saisonStart = saisonStart(LocalDate.now(ZONE));
            Instant jetzt = Instant.now();
            if (abgeschlossen == null) {
                List<Saison> saisons = saisons(ereignisse(start.atStartOfDay(ZONE).toInstant(),
                        saisonStart.atStartOfDay(ZONE).toInstant()), null);
                abgeschlossen = saisons;
                abtauungenAbgeschlossen = abtauungen(start.atStartOfDay(ZONE).toInstant(),
                        saisonStart.atStartOfDay(ZONE).toInstant());
            }
            Ereignisse aktuell = ereignisse(saisonStart.atStartOfDay(ZONE).toInstant(), jetzt);
            List<Saison> saisons = saisons(aktuell, saisonName(saisonStart));
            laufend = saisons.isEmpty() ? new Saison(saisonName(saisonStart), 0, 0, true, 0, 0, 0, 0, 0)
                    : saisons.get(0);
            List<Instant> fehlstarts = erkennen(aktuell);
            letzte = fehlstarts.subList(Math.max(0, fehlstarts.size() - 5), fehlstarts.size());
            abtauungenLaufend = abtauungen(saisonStart.atStartOfDay(ZONE).toInstant(), jetzt);
            berechnet = jetzt;
        } catch (Exception e) {
            logger.warn("Failed starts could not be calculated: " + e.getMessage());
        }
    }

    /**
     * All seasons, the newest first; empty until the first calculation
     */
    public List<Saison> getSaisons() {
        List<Saison> liste = new ArrayList<>();
        if (laufend != null) {
            liste.add(laufend);
        }
        if (abgeschlossen != null) {
            List<Saison> alt = new ArrayList<>(abgeschlossen);
            Collections.reverse(alt);
            liste.addAll(alt);
        }
        return liste;
    }

    /**
     * All defrosts found in the stored values, null until the first calculation. The heat pump's own counter
     * (0x0806, STARTS ABTAUEN in the menu) stopped at 9999.
     */
    public Abtauungen getAbtauungen() {
        Abtauungen alt = abtauungenAbgeschlossen;
        Abtauungen neu = abtauungenLaufend;
        return alt == null || neu == null ? null : summe(alt, neu);
    }

    static Abtauungen summe(Abtauungen alt, Abtauungen neu) {
        return new Abtauungen(alt.anzahl() + neu.anzahl(), alt.seit() != null ? alt.seit() : neu.seit());
    }

    private Abtauungen abtauungen(Instant von, Instant bis) {
        String bucket = influx.getBucket();
        return abtauungen(tageswerte(von, bis, bucket, "WP_LeistungInverter", "count()",
                        WaermebedarfVergleich.FILTER_ANLAEUFE),
                tageswerte(von, bis, bucket, "WP_LeistungInverter", "count()", WaermebedarfVergleich.FILTER_STARTS));
    }

    /**
     * Defrosts are the run-ups of the compressor that are no start, i.e. follow a break below 3 minutes. Around
     * midnight the two can be counted on different days, so a day never counts below 0.
     *
     * @param anlaeufe changes from standstill to running per day
     * @param starts   starts after at least 3 minutes standstill per day
     */
    static Abtauungen abtauungen(Map<LocalDate, Double> anlaeufe, Map<LocalDate, Double> starts) {
        int anzahl = 0;
        LocalDate seit = null;
        for (Map.Entry<LocalDate, Double> tag : new TreeMap<>(anlaeufe).entrySet()) {
            if (seit == null) {
                seit = tag.getKey();
            }
            anzahl += Math.max(0, tag.getValue().intValue() - starts.getOrDefault(tag.getKey(), 0d).intValue());
        }
        return new Abtauungen(anzahl, seit);
    }

    /**
     * Up to five failed starts of the current season, the oldest first
     */
    public List<Instant> getLetzte() {
        return letzte;
    }

    public Instant getBerechnet() {
        return berechnet;
    }

    private Ereignisse ereignisse(Instant von, Instant bis) {
        String bucket = influx.getBucket();
        List<Instant> druckabfaelle = new ArrayList<>();
        NavigableMap<Instant, Double> anAus = new TreeMap<>();
        List<Instant> laeufe = new ArrayList<>();
        for (Instant teil = von; teil.isBefore(bis); teil = teil.plus(Duration.ofDays(TAGE_JE_ABFRAGE))) {
            Instant teilBis = teil.plus(Duration.ofDays(TAGE_JE_ABFRAGE));
            if (teilBis.isAfter(bis)) {
                teilBis = bis;
            }
            druckabfaelle.addAll(influx.werte(flux(bucket, "WP_Hochdruck", teil, teilBis, "last",
                    " |> difference() |> filter(fn: (r) => r._value <= -" + DRUCKABFALL_BAR + ")")).keySet());
            anAus.putAll(influx.werte(flux(bucket, "WP_LeistungInverter", teil, teilBis, "max",
                    " |> map(fn: (r) => ({r with _value: if r._value > " + LAEUFT_AB_VA + " then 1.0 else 0.0}))"
                            + " |> difference() |> filter(fn: (r) => r._value != 0.0)")));
            laeufe.addAll(influx.werte(flux(bucket, "WP_LeistungInverter", teil, teilBis, "mean",
                    " |> map(fn: (r) => ({r with _value: if r._value > " + LAUF_AB_VA + " then 1.0 else 0.0}))"
                            + " |> difference() |> filter(fn: (r) => r._value == 1.0)")).keySet());
        }
        Map<LocalDate, Double> werteProTag = tageswerte(von, bis, bucket, "WP_LeistungInverter", "count()");
        Map<LocalDate, Double> waermeProTag = waermeProTag(tageswerte(von.minus(Duration.ofDays(1)), bis, bucket,
                "WP_AbgabeWaerme", "last()"));
        Map<LocalDate, Double> temperatur = vergleich.aussentemperatur(von, bis);
        return new Ereignisse(druckabfaelle, anAus, laeufe, werteProTag, waermeProTag, temperatur);
    }

    /**
     * Heat per day in MWh from the counter at the end of the day and of the day before; a day without the day before
     * or with a falling counter is left out
     */
    static Map<LocalDate, Double> waermeProTag(Map<LocalDate, Double> zaehlerAmTagesende) {
        Map<LocalDate, Double> waerme = new TreeMap<>();
        zaehlerAmTagesende.forEach((tag, stand) -> {
            Double vorher = zaehlerAmTagesende.get(tag.minusDays(1));
            if (vorher != null && stand >= vorher) {
                waerme.put(tag, stand - vorher);
            }
        });
        return waerme;
    }

    private Map<LocalDate, Double> tageswerte(Instant von, Instant bis, String bucket, String measurement,
                                              String funktion) {
        return tageswerte(von, bis, bucket, measurement, funktion, "");
    }

    private Map<LocalDate, Double> tageswerte(Instant von, Instant bis, String bucket, String measurement,
                                              String funktion, String filter) {
        Map<LocalDate, Double> tage = new TreeMap<>();
        for (Instant teil = von; teil.isBefore(bis); teil = teil.plus(Duration.ofDays(TAGE_JE_ABFRAGE))) {
            Instant teilBis = teil.plus(Duration.ofDays(TAGE_JE_ABFRAGE));
            influx.werte(WaermebedarfVergleich.flux(teil, teilBis.isAfter(bis) ? bis : teilBis, bucket, measurement,
                    null, funktion, filter)).forEach((zeit, wert) -> tage.put(zeit.atZone(ZONE).toLocalDate(), wert));
        }
        return tage;
    }

    static String flux(String bucket, String measurement, Instant von, Instant bis, String funktion, String rest) {
        return "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> aggregateWindow(every: 1m, fn: " + funktion + ", createEmpty: false)"
                + rest + " |> keep(columns: [\"_time\", \"_value\"])";
    }

    /**
     * Failed starts: a pressure drop after at least 10 minutes standstill, no run within 10 minutes and the next
     * run 18 to 30 minutes later
     */
    static List<Instant> erkennen(Ereignisse e) {
        List<Instant> laeufe = new ArrayList<>(e.laeufe());
        Collections.sort(laeufe);
        List<Instant> fehlstarts = new ArrayList<>();
        Instant letzterVersuch = null;
        for (Instant abfall : e.druckabfaelle().stream().sorted().toList()) {
            if (letzterVersuch != null && Duration.between(letzterVersuch, abfall).compareTo(SPERRE_MIN) < 0) {
                continue;
            }
            Instant stillstandSeit = stillstandSeit(e.anAus(), abfall.minus(Duration.ofMinutes(1)));
            if (stillstandSeit == null
                    || Duration.between(stillstandSeit, abfall).compareTo(MIN_STILLSTAND_VERSUCH) < 0) {
                continue;
            }
            int i = Collections.binarySearch(laeufe, abfall.minus(Duration.ofMinutes(1)));
            i = i < 0 ? -i - 1 : i;
            if (i >= laeufe.size()) {
                continue;
            }
            letzterVersuch = abfall;
            Duration bisLauf = Duration.between(abfall, laeufe.get(i));
            if (bisLauf.compareTo(NORMALER_START) > 0 && bisLauf.compareTo(SPERRE_MIN) >= 0
                    && bisLauf.compareTo(SPERRE_MAX) <= 0) {
                fehlstarts.add(abfall);
            }
        }
        return fehlstarts;
    }

    /**
     * Time of the last stop before the given time, null if the compressor runs then or no stop is known
     */
    static Instant stillstandSeit(NavigableMap<Instant, Double> anAus, Instant zeit) {
        Map.Entry<Instant, Double> letzte = anAus.floorEntry(zeit);
        return letzte != null && letzte.getValue() < 0 ? letzte.getKey() : null;
    }

    /**
     * Starts after at least 3 minutes standstill, failed starts, heat and covered days per season, the oldest first
     *
     * @param nurSaison only this season, all if null
     */
    static List<Saison> saisons(Ereignisse e, String nurSaison) {
        // starts, failed starts, starts mild, failed starts mild, covered days, days with temperature
        Map<String, int[]> zaehler = new TreeMap<>();
        Map<String, Double> waerme = new TreeMap<>();
        Instant gestoppt = null;
        for (Map.Entry<Instant, Double> wechsel : e.anAus().entrySet()) {
            if (wechsel.getValue() < 0) {
                gestoppt = wechsel.getKey();
            } else if (gestoppt == null
                    || Duration.between(gestoppt, wechsel.getKey()).compareTo(MIN_STILLSTAND_START) >= 0) {
                int[] z = zaehler.computeIfAbsent(saisonName(wechsel.getKey()), k -> new int[6]);
                z[0]++;
                if (mild(e.temperatur(), wechsel.getKey())) {
                    z[2]++;
                }
            }
        }
        for (Instant fehlstart : erkennen(e)) {
            int[] z = zaehler.computeIfAbsent(saisonName(fehlstart), k -> new int[6]);
            z[1]++;
            if (mild(e.temperatur(), fehlstart)) {
                z[3]++;
            }
        }
        e.werteProTag().forEach((tag, werte) -> {
            if (werte >= MIN_WERTE_PRO_TAG) {
                String name = saisonName(saisonStart(tag));
                zaehler.computeIfAbsent(name, k -> new int[6])[4]++;
                waerme.merge(name, e.waermeProTag().getOrDefault(tag, 0d), Double::sum);
                if (e.temperatur().containsKey(tag)) {
                    zaehler.get(name)[5]++;
                }
            }
        });
        List<Saison> saisons = new ArrayList<>();
        zaehler.forEach((name, z) -> {
            if ((nurSaison == null && (z[0] > 0 || z[1] > 0)) || name.equals(nurSaison)) {
                saisons.add(new Saison(name, z[0], z[1], nurSaison != null, waerme.getOrDefault(name, 0d), z[4],
                        z[2], z[3], z[5]));
            }
        });
        return saisons;
    }

    /**
     * True if the daily mean outdoor temperature of the day is known and between 0 and 15 degC
     */
    static boolean mild(Map<LocalDate, Double> temperatur, Instant zeit) {
        Double wert = temperatur.get(zeit.atZone(ZONE).toLocalDate());
        return wert != null && wert >= MILD_VON && wert < MILD_BIS;
    }

    static LocalDate saisonStart(LocalDate tag) {
        int jahr = tag.getMonthValue() >= 7 ? tag.getYear() : tag.getYear() - 1;
        return LocalDate.of(jahr, 7, 1);
    }

    static String saisonName(LocalDate saisonStart) {
        return saisonStart.getYear() + "/" + String.format("%02d", (saisonStart.getYear() + 1) % 100);
    }

    static String saisonName(Instant zeit) {
        return saisonName(saisonStart(zeit.atZone(ZONE).toLocalDate()));
    }
}
