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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Electricity of the heating per month in the current year next to the two years before, optionally with the
 * household electricity and the wallbox. The real power of the heating is estimated from the apparent power of the inverter as on
 * the status page; pumps and control are not included. The household electricity is the grid import of the house
 * meter minus the heating and minus the wallbox, both counters from Home Assistant. Uses the stored values, so it
 * needs InfluxDB.
 */
@Component
public class Jahresvergleich {

    static final int JAHRE = 3;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final double LAEUFT_AB_W = 50;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    // How far before or after the end of a month a counter reading is searched
    private static final Duration SUCHEN = Duration.ofDays(60);
    private static final String[] MONATE = {"Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt",
            "Nov", "Dez"};

    private final Logger logger = LoggerFactory.getLogger(Jahresvergleich.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    private final String haushaltBucket;
    private final String hauszaehler;
    private final String wallboxZaehler;
    // Months that are over do not change any more and are read only once
    private final Map<YearMonth, Double> heizungFertig = new ConcurrentHashMap<>();
    private final Map<String, Optional<Double>> zaehlerFertig = new ConcurrentHashMap<>();
    private final Map<YearMonth, Double> aussenFertig = new ConcurrentHashMap<>();
    private volatile Vergleich ergebnis;

    /**
     * One month; the lists hold one value per year, the oldest first, null where there is no data (the months still
     * to come)
     *
     * @param heizungGleich the years before up to the same day and hour, differs from heizung only in the current
     *                      month
     * @param haushalt      household electricity, all null without the house meter
     * @param wallbox       electricity of the wallbox, all null without its counter
     * @param laufend       the month is not over yet
     */
    public record Monat(int monat, String name, List<Double> heizung, List<Double> heizungGleich,
                        List<Double> haushalt, List<Double> wallbox, List<Double> aussen, boolean laufend) {

        /**
         * Change of the current year against the same days of the year before in percent, null below 5 kWh in the
         * year before
         */
        public Double aenderung() {
            Double jetzt = heizung.get(JAHRE - 1);
            Double vorher = heizungGleich.get(JAHRE - 2);
            return jetzt == null || vorher == null || vorher < 5 ? null : (jetzt / vorher - 1) * 100;
        }
    }

    /**
     * @param jahre       the years, the oldest first, the last is the current year
     * @param bis         the last day with data in the current year
     * @param mitHaushalt the house meter is configured
     * @param mitWallbox  the counter of the wallbox is configured
     * @param gesamt      grid import of the house meter in the current year up to now, null without the meter
     * @param gesamtVorjahrGleich grid import in the year before up to the same day and hour
     * @param wallboxBisJetzt     wallbox in the current year up to now, null without its counter
     * @param wallboxVorjahrGleich wallbox in the year before up to the same day and hour
     */
    public record Vergleich(List<Integer> jahre, LocalDate bis, List<Monat> monate, boolean mitHaushalt,
                            boolean mitWallbox, Double gesamt, Double gesamtVorjahrGleich, Double wallboxBisJetzt,
                            Double wallboxVorjahrGleich, Instant berechnet) {

        /**
         * What the grid import from the 1st of January up to now consists of, against the same days of the year
         * before: total, wallbox (if its counter is set), heating and the rest. Empty without the house meter.
         */
        public List<Anteil> aufteilung() {
            return aufteilungBisJetzt();
        }

        /**
         * Forecast up to the end of the year per part: heating as in the rest of the years before (mean of the two),
         * the household as in the rest of the year before, the wallbox at the rate of the last three months that are
         * over. The total is the sum of the parts. Without the house meter only the heating.
         */
        public List<Prognose> prognose() {
            List<Prognose> teile = new ArrayList<>();
            double summe = 0;
            int n = 0;
            for (int i = 0; i < JAHRE - 1; i++) {
                if (heizung(i) > 0) {
                    summe += heizung(i) - heizungGleich(i);
                    n++;
                }
            }
            if (n == 0) {
                return teile;
            }
            teile.add(new Prognose("Heizung", heizung(JAHRE - 1), summe / n, heizung(JAHRE - 2)));
            List<Anteil> anteile = aufteilungBisJetzt();
            if (!anteile.isEmpty() && haushalt(JAHRE - 2) != null) {
                Anteil rest = anteile.get(anteile.size() - 1);
                teile.add(new Prognose(rest.name(), rest.jetzt(), Math.max(0, haushalt(JAHRE - 2) - rest.vorher()),
                        haushalt(JAHRE - 2)));
            }
            if (!anteile.isEmpty() && mitWallbox) {
                Double vorjahr = wallbox(JAHRE - 2);
                // Days up to the end of the year, today included
                long restTage = LocalDate.of(jahr() + 1, 1, 1).toEpochDay() - bis.toEpochDay();
                teile.add(new Prognose("Wallbox", wallboxBisJetzt == null ? 0 : wallboxBisJetzt,
                        wallboxJeTag() * restTage, vorjahr == null ? 0 : vorjahr));
            }
            if (teile.size() > 1) {
                teile.addFirst(new Prognose("Gesamt", teile.stream().mapToDouble(Prognose::bisher).sum(),
                        teile.stream().mapToDouble(Prognose::erwartet).sum(),
                        teile.stream().mapToDouble(p -> p.vorjahr() == null ? 0 : p.vorjahr()).sum()));
            }
            return teile;
        }

        /**
         * Mean electricity of the wallbox per day in the last three months that are over
         */
        double wallboxJeTag() {
            double kwh = 0;
            int tage = 0;
            for (int k = 1; k <= 3; k++) {
                YearMonth monat = YearMonth.from(bis).minusMonths(k);
                int i = jahre.indexOf(monat.getYear());
                Double wert = i < 0 ? null : monate.get(monat.getMonthValue() - 1).wallbox().get(i);
                if (wert != null) {
                    kwh += wert;
                    tage += monat.lengthOfMonth();
                }
            }
            return tage == 0 ? 0 : kwh / tage;
        }

        private List<Anteil> aufteilungBisJetzt() {
            if (gesamt == null || gesamtVorjahrGleich == null) {
                return List.of();
            }
            double heizungJetzt = heizungGleich(JAHRE - 1);
            double heizungVorher = heizungGleich(JAHRE - 2);
            double wallboxJetzt = wallboxBisJetzt == null ? 0 : wallboxBisJetzt;
            double wallboxVorher = wallboxVorjahrGleich == null ? 0 : wallboxVorjahrGleich;
            List<Anteil> anteile = new ArrayList<>();
            anteile.add(new Anteil("Gesamt", gesamtVorjahrGleich, gesamt));
            if (mitWallbox) {
                anteile.add(new Anteil("Wallbox", wallboxVorher, wallboxJetzt));
            }
            anteile.add(new Anteil("Heizung", heizungVorher, heizungJetzt));
            anteile.add(new Anteil(mitWallbox ? "Rest (Haushalt)" : "Rest (Haushalt und Wallbox)",
                    gesamtVorjahrGleich - heizungVorher - wallboxVorher, gesamt - heizungJetzt - wallboxJetzt));
            return anteile;
        }

        /**
         * Change of the grid import against the same days of the year before in percent
         */
        public Double gesamtAenderung() {
            return gesamt == null || gesamtVorjahrGleich == null || gesamtVorjahrGleich <= 0 ? null
                    : (gesamt / gesamtVorjahrGleich - 1) * 100;
        }

        public int jahr() {
            return jahre.get(JAHRE - 1);
        }

        /**
         * Heating of the year with the given index (0 the oldest)
         */
        public double heizung(int i) {
            return summe(monate.stream().map(m -> m.heizung().get(i)).toList());
        }

        /**
         * Heating of the year with the given index up to the same day as in the current year
         */
        public double heizungGleich(int i) {
            return summe(monate.stream().map(m -> m.heizungGleich().get(i)).toList());
        }

        /**
         * Household electricity of the year with the given index, null without the house meter or without values
         */
        public Double haushalt(int i) {
            List<Double> werte = monate.stream().map(m -> m.haushalt().get(i)).toList();
            return mitHaushalt && werte.stream().anyMatch(w -> w != null) ? summe(werte) : null;
        }

        /**
         * Electricity of the wallbox in the year with the given index, null without its counter or without values
         */
        public Double wallbox(int i) {
            List<Double> werte = monate.stream().map(m -> m.wallbox().get(i)).toList();
            return mitWallbox && werte.stream().anyMatch(w -> w != null) ? summe(werte) : null;
        }

        /**
         * Change of the current year against the same days of the year before in percent
         */
        public Double aenderung() {
            double vorher = heizungGleich(JAHRE - 2);
            return vorher > 0 ? (heizungGleich(JAHRE - 1) / vorher - 1) * 100 : null;
        }

        private static double summe(List<Double> werte) {
            return werte.stream().filter(w -> w != null).mapToDouble(Double::doubleValue).sum();
        }
    }

    /**
     * Forecast of one part for the whole current year
     *
     * @param bisher   from the 1st of January up to now
     * @param erwartet expected from now up to the end of the year
     * @param vorjahr  the whole year before
     */
    public record Prognose(String name, double bisher, double erwartet, Double vorjahr) {

        public double jahr() {
            return bisher + erwartet;
        }

        /**
         * Change against the whole year before in percent
         */
        public Double aenderung() {
            return vorjahr == null || vorjahr <= 0 ? null : (jahr() / vorjahr - 1) * 100;
        }
    }

    /**
     * One part of the grid import, the same days in the year before and in the current year
     */
    public record Anteil(String name, double vorher, double jetzt) {

        public double differenz() {
            return jetzt - vorher;
        }

        public Double prozent() {
            return vorher > 0 ? (jetzt / vorher - 1) * 100 : null;
        }
    }

    public Jahresvergleich(@Value("${auswertung.haushalt.bucket:}") String haushaltBucket,
                           @Value("${auswertung.haushalt.zaehler:}") String hauszaehler,
                           @Value("${auswertung.haushalt.wallbox:}") String wallboxZaehler) {
        this.haushaltBucket = haushaltBucket.trim();
        this.hauszaehler = hauszaehler.trim();
        this.wallboxZaehler = wallboxZaehler.trim();
    }

    /**
     * Calculated 4 minutes after the start and then every hour; the months that are over are read only once
     */
    @Scheduled(initialDelay = 240000, fixedRate = 3600000)
    public void berechnen() {
        if (!influx.isAktiv()) {
            return;
        }
        try {
            ergebnis = rechnen(ZonedDateTime.now(ZONE));
        } catch (Exception e) {
            logger.warn("Year comparison could not be calculated: " + e.getMessage());
        }
    }

    /**
     * Last calculated comparison, null before the first calculation or without InfluxDB
     */
    public Vergleich getVergleich() {
        return ergebnis;
    }

    private Vergleich rechnen(ZonedDateTime jetzt) {
        YearMonth laufend = YearMonth.from(jetzt);
        int jahr = jetzt.getYear();
        YearMonth erster = YearMonth.of(jahr - JAHRE + 1, 1);
        Map<YearMonth, Double> heizung = new TreeMap<>();
        for (YearMonth monat = erster; monat.isBefore(laufend); monat = monat.plusMonths(1)) {
            heizung.put(monat, heizungFertig.computeIfAbsent(monat, m -> strom(start(m), start(m.plusMonths(1)))));
        }
        heizung.put(laufend, strom(start(laufend), jetzt.toInstant()));
        // The current month in the years before, up to the same day and hour
        Map<Integer, Double> gleich = new TreeMap<>();
        for (int k = 1; k < JAHRE; k++) {
            gleich.put(jahr - k, strom(start(laufend.minusYears(k)), jetzt.minusYears(k).toInstant()));
        }
        Map<YearMonth, Double> bezug = verbrauch(hauszaehler, erster, laufend, jetzt);
        Map<YearMonth, Double> wallbox = verbrauch(wallboxZaehler, erster, laufend, jetzt);
        // The outdoor temperature of the months that are over is read once too
        YearMonth ab = aussenFertig.isEmpty() || !aussenFertig.containsKey(laufend.minusMonths(1)) ? erster : laufend;
        Map<YearMonth, Double> aussen = new TreeMap<>(aussenFertig);
        monatsmittel(vergleich.aussentemperatur(start(ab), jetzt.toInstant())).forEach((monat, wert) -> {
            aussen.put(monat, wert);
            if (monat.isBefore(laufend)) {
                aussenFertig.put(monat, wert);
            }
        });
        // Grid import from the 1st of January up to now and up to the same hour a year ago
        Double[] gesamt = bisJetzt(hauszaehler, jetzt);
        Double[] wallboxBisJetzt = bisJetzt(wallboxZaehler, jetzt);
        return vergleich(jahr, jetzt.toLocalDate(), heizung, gleich, haushalt(bezug, heizung, wallbox),
                !hauszaehler.isEmpty(), wallbox, !wallboxZaehler.isEmpty(), aussen, gesamt[0], gesamt[1],
                wallboxBisJetzt[0], wallboxBisJetzt[1], Instant.now());
    }

    /**
     * Consumption of a counter from the 1st of January up to now, and in the year before up to the same hour; both
     * null without the counter
     */
    private Double[] bisJetzt(String entity, ZonedDateTime jetzt) {
        if (entity.isEmpty()) {
            return new Double[2];
        }
        int jahr = jetzt.getYear();
        Instant vorJahr = jetzt.minusYears(1).toInstant();
        return new Double[]{differenz(stand(entity, start(YearMonth.of(jahr, 1))), letzter(entity, jetzt.toInstant())),
                differenz(stand(entity, start(YearMonth.of(jahr - 1, 1))),
                        stand(zaehler(entity, vorJahr, false), zaehler(entity, vorJahr, true), vorJahr))};
    }

    /**
     * Comparison from the values per month: the current month counts up to today, for it the years before are given
     * up to the same day in gleich; gesamt is the grid import of this year up to now, gesamtVorjahrGleich the same
     * days of the year before
     */
    static Vergleich vergleich(int jahr, LocalDate heute, Map<YearMonth, Double> heizung, Map<Integer, Double> gleich,
                               Map<YearMonth, Double> haushalt, boolean mitHaushalt, Map<YearMonth, Double> wallbox,
                               boolean mitWallbox, Map<YearMonth, Double> aussen, Double gesamt,
                               Double gesamtVorjahrGleich, Double wallboxBisJetzt, Double wallboxVorjahrGleich,
                               Instant berechnet) {
        YearMonth laufend = YearMonth.from(heute);
        List<Integer> jahre = jahre(jahr);
        List<Monat> monate = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            boolean istLaufend = m == laufend.getMonthValue();
            boolean kommt = m > laufend.getMonthValue();
            List<Double> h = new ArrayList<>();
            List<Double> hg = new ArrayList<>();
            List<Double> hh = new ArrayList<>();
            List<Double> w = new ArrayList<>();
            List<Double> a = new ArrayList<>();
            for (int j : jahre) {
                YearMonth monat = YearMonth.of(j, m);
                boolean zukunft = monat.isAfter(laufend);
                h.add(zukunft ? null : heizung.get(monat));
                hg.add(kommt ? null : istLaufend && j < jahr ? gleich.get(j) : zukunft ? null : heizung.get(monat));
                hh.add(zukunft ? null : haushalt.get(monat));
                w.add(zukunft ? null : wallbox.get(monat));
                a.add(zukunft ? null : aussen.get(monat));
            }
            monate.add(new Monat(m, MONATE[m - 1], Collections.unmodifiableList(h), Collections.unmodifiableList(hg),
                    Collections.unmodifiableList(hh), Collections.unmodifiableList(w), Collections.unmodifiableList(a),
                    istLaufend));
        }
        return new Vergleich(List.copyOf(jahre), heute, monate, mitHaushalt, mitWallbox, gesamt, gesamtVorjahrGleich,
                wallboxBisJetzt, wallboxVorjahrGleich, berechnet);
    }

    private static List<Integer> jahre(int jahr) {
        List<Integer> jahre = new ArrayList<>();
        for (int j = jahr - JAHRE + 1; j <= jahr; j++) {
            jahre.add(j);
        }
        return jahre;
    }

    private static Double differenz(Double anfang, Double ende) {
        return anfang == null || ende == null ? null : ende - anfang;
    }

    /**
     * Household electricity per month: grid import minus heating minus wallbox, not below 0. Null where the grid
     * import is not known; a month without a wallbox value counts without wallbox.
     */
    static Map<YearMonth, Double> haushalt(Map<YearMonth, Double> bezug, Map<YearMonth, Double> heizung,
                                           Map<YearMonth, Double> wallbox) {
        Map<YearMonth, Double> haushalt = new TreeMap<>();
        bezug.forEach((monat, kwh) -> {
            if (kwh != null) {
                double rest = kwh - heizung.getOrDefault(monat, 0d) - Optional.ofNullable(wallbox.get(monat)).orElse(0d);
                haushalt.put(monat, Math.max(0, rest));
            }
        });
        return haushalt;
    }

    /**
     * Consumption per month from a counter in kWh: the reading at the end of the month minus the reading at its
     * start. Empty without a counter, null where a reading is missing.
     */
    private Map<YearMonth, Double> verbrauch(String entity, YearMonth erster, YearMonth laufend, ZonedDateTime jetzt) {
        Map<YearMonth, Double> verbrauch = new TreeMap<>();
        if (entity.isEmpty()) {
            return verbrauch;
        }
        Double anfang = stand(entity, start(erster));
        for (YearMonth monat = erster; !monat.isAfter(laufend); monat = monat.plusMonths(1)) {
            Double ende = monat.equals(laufend) ? letzter(entity, jetzt.toInstant()) : stand(entity, start(monat.plusMonths(1)));
            verbrauch.put(monat, anfang == null || ende == null ? null : ende - anfang);
            anfang = ende;
        }
        return verbrauch;
    }

    /**
     * Reading of the counter at a point in time that is over, read once
     */
    private Double stand(String entity, Instant zeit) {
        return zaehlerFertig.computeIfAbsent(entity + "/" + zeit, k -> Optional.ofNullable(
                stand(zaehler(entity, zeit, false), zaehler(entity, zeit, true), zeit))).orElse(null);
    }

    /**
     * Reading at a point in time from the last reading before and the first after it, interpolated over the time.
     * If the readings stop for a while, e.g. at the end of December, the consumption of the gap is split between
     * the months by its length instead of counting all of it in one month. Null without a reading before.
     */
    static Double stand(Map.Entry<Instant, Double> vorher, Map.Entry<Instant, Double> nachher, Instant zeit) {
        if (vorher == null) {
            return null;
        }
        if (nachher == null || !nachher.getKey().isAfter(vorher.getKey())) {
            return vorher.getValue();
        }
        double anteil = (double) (zeit.toEpochMilli() - vorher.getKey().toEpochMilli())
                / (nachher.getKey().toEpochMilli() - vorher.getKey().toEpochMilli());
        return vorher.getValue() + anteil * (nachher.getValue() - vorher.getValue());
    }

    private Double letzter(String entity, Instant zeit) {
        Map.Entry<Instant, Double> wert = zaehler(entity, zeit, false);
        return wert == null ? null : wert.getValue();
    }

    /**
     * Last reading before the point in time or first reading after it, within SUCHEN
     */
    private Map.Entry<Instant, Double> zaehler(String entity, Instant zeit, boolean nachher) {
        Map<Instant, Double> werte = influx.werte(zaehlerFlux(haushaltBucket.isEmpty() ? influx.getBucket()
                : haushaltBucket, entity, nachher ? zeit : zeit.minus(SUCHEN), nachher ? zeit.plus(SUCHEN) : zeit,
                nachher ? "first" : "last"));
        // One reading per table, e.g. after Home Assistant changed a tag; sorted by time
        List<Map.Entry<Instant, Double>> liste = new ArrayList<>(werte.entrySet());
        return liste.isEmpty() ? null : liste.get(nachher ? 0 : liste.size() - 1);
    }

    /**
     * Mean of the daily values per month
     */
    static Map<YearMonth, Double> monatsmittel(Map<LocalDate, Double> tage) {
        Map<YearMonth, double[]> summen = new TreeMap<>();
        tage.forEach((tag, wert) -> {
            double[] s = summen.computeIfAbsent(YearMonth.from(tag), x -> new double[2]);
            s[0] += wert;
            s[1]++;
        });
        Map<YearMonth, Double> mittel = new TreeMap<>();
        summen.forEach((monat, s) -> mittel.put(monat, s[0] / s[1]));
        return mittel;
    }

    private static Instant start(YearMonth monat) {
        return monat.atDay(1).atStartOfDay(ZONE).toInstant();
    }

    /**
     * Estimated real energy of the heating in kWh from von to bis; 0 if there are no values
     */
    private double strom(Instant von, Instant bis) {
        if (!bis.isAfter(von)) {
            return 0;
        }
        return influx.werte(flux(influx.getBucket(), von, bis)).values().stream().mapToDouble(Double::doubleValue)
                .sum();
    }

    /**
     * Sum of the estimated real power: each stored value of the apparent power is turned into real power with the
     * same line as Waermepumpe.wirkleistung, then one mean per minute and the sum of the minutes in kWh
     */
    static String flux(String bucket, Instant von, Instant bis) {
        pruefen(bucket);
        return String.format(Locale.ROOT, """
                        from(bucket: "%s")
                          |> range(start: %s, stop: %s)
                          |> filter(fn: (r) => r._measurement == "WP_LeistungInverter" and r._field == "value")
                          |> map(fn: (r) => {
                              anteil = if r._value <= %.1f then 0.0 else %.4f - %.1f / r._value
                              begrenzt = if r._value <= %.1f then 0.0 else if anteil < %.4f then %.4f else if anteil > %.4f then %.4f else anteil
                              return {r with _value: r._value * begrenzt}
                            })
                          |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
                          |> sum()
                          |> map(fn: (r) => ({r with _value: r._value / 60000.0, _time: r._start}))
                        """, bucket, von, bis, LAEUFT_AB_W, Waermepumpe.WIRKLEISTUNG_STEIGUNG,
                Waermepumpe.WIRKLEISTUNG_OFFSET_W, LAEUFT_AB_W, Waermepumpe.WIRKLEISTUNGSANTEIL_MIN,
                Waermepumpe.WIRKLEISTUNGSANTEIL_MIN, Waermepumpe.WIRKLEISTUNGSANTEIL_MAX,
                Waermepumpe.WIRKLEISTUNGSANTEIL_MAX);
    }

    /**
     * First or last reading of a Home Assistant counter in kWh from von to bis, one per table
     */
    static String zaehlerFlux(String bucket, String entity, Instant von, Instant bis, String funktion) {
        pruefen(bucket);
        pruefen(entity);
        if (!funktion.equals("first") && !funktion.equals("last")) {
            throw new IllegalArgumentException("Ungültige Funktion: " + funktion);
        }
        return "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"kWh\" and r._field == \"value\" and r.entity_id == \""
                + entity + "\") |> " + funktion + "()";
    }

    private static void pruefen(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Ungültiger Name: " + name);
        }
    }
}
