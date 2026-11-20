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
    // A device counts as used in the year before from this much electricity on the same days
    static final double GENUTZT_KWH = 5;
    // The bars per year look back this far, as long as the house meter has readings
    static final int JAHRE_ZURUECK = 10;
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
    @Autowired
    private Geraete geraete;
    // Readings of the devices, read again with every calculation
    private volatile Map<String, Zaehlerreihe> reihen = Map.of();
    // Months that are over do not change any more and are read only once
    private final Map<YearMonth, Double> heizungFertig = new ConcurrentHashMap<>();
    private final Map<String, Optional<Double>> zaehlerFertig = new ConcurrentHashMap<>();
    // Daily means of the outdoor temperature of the months that are over
    private final Map<LocalDate, Double> aussenFertig = new ConcurrentHashMap<>();
    private volatile Vergleich ergebnis;

    /**
     * One month; the lists hold one value per year, the oldest first, null where there is no data (the months still
     * to come)
     *
     * @param heizungGleich the years before up to the same day and hour, differs from heizung only in the current
     *                      month
     * @param haushalt      household electricity (grid import minus heating minus the devices with a value), all
     *                      null without the house meter
     * @param geraete       per device of Vergleich.geraete its electricity per year, null where it is not known
     * @param laufend       the month is not over yet
     */
    public record Monat(int monat, String name, List<Double> heizung, List<Double> heizungGleich,
                        List<Double> haushalt, List<List<Double>> geraete, List<Double> aussen, boolean laufend) {

        /**
         * Change of the current year against the same days of the year before in percent, null below 5 kWh in the
         * year before
         */
        public Double aenderung() {
            Double jetzt = heizung.get(JAHRE - 1);
            Double vorher = heizungGleich.get(JAHRE - 2);
            return jetzt == null || vorher == null || vorher < 5 ? null : (jetzt / vorher - 1) * 100;
        }

        /**
         * Electricity of the device g in the year with index i, null if not known
         */
        public Double geraet(int g, int i) {
            return g < geraete.size() ? geraete.get(g).get(i) : null;
        }
    }

    /**
     * Electricity of a device from the 1st of January up to now and in the years before up to the same day and hour;
     * null where it is not known. restVorjahr: from the same day and hour of the year before up to its end, null if
     * the device was not measured yet then.
     */
    public record GleicheTage(Double jetzt, Double vorjahr, Double vorvorjahr, boolean hochgerechnet,
                              Double restVorjahr) {

        public GleicheTage(Double jetzt, Double vorjahr, Double vorvorjahr) {
            this(jetzt, vorjahr, vorvorjahr, false, null);
        }

        public GleicheTage(Double jetzt, Double vorjahr, Double vorvorjahr, boolean hochgerechnet) {
            this(jetzt, vorjahr, vorvorjahr, hochgerechnet, null);
        }
    }

    /**
     * @param jahre       the years, the oldest first, the last is the current year
     * @param bis         the last day with data in the current year
     * @param mitHaushalt the house meter is configured
     * @param geraete     devices with their own counter, e.g. the wallbox
     * @param gesamt      grid import of the house meter in the current year up to now, null without the meter
     * @param gesamtVorjahrGleich    grid import in the year before up to the same day and hour
     * @param gesamtVorvorjahrGleich grid import two years before up to the same day and hour, null if unknown
     * @param geraeteGleich          per device its electricity on the same days
     * @param jahressummen           all whole years that are over, for the bars per year
     * @param gradtage               per year the heating degree days from the 1st of January up to the same day,
     *                               null where the outdoor temperature is missing; empty if not known
     * @param preis                  price of electricity in € per kWh, null if not set
     * @param gradtageMonate         per month (index 0 January) and year the heating degree days, the current
     *                               month up to the same day; empty if not known
     */
    public record Vergleich(List<Integer> jahre, LocalDate bis, List<Monat> monate, boolean mitHaushalt,
                            List<Geraete.Geraet> geraete, Double gesamt, Double gesamtVorjahrGleich,
                            Double gesamtVorvorjahrGleich, List<GleicheTage> geraeteGleich, Instant berechnet,
                            List<Jahressumme> jahressummen, List<Double> gradtage, Double preis,
                            List<List<Double>> gradtageMonate) {

        public Vergleich(List<Integer> jahre, LocalDate bis, List<Monat> monate, boolean mitHaushalt,
                         List<Geraete.Geraet> geraete, Double gesamt, Double gesamtVorjahrGleich,
                         Double gesamtVorvorjahrGleich, List<GleicheTage> geraeteGleich, Instant berechnet,
                         List<Jahressumme> jahressummen, List<Double> gradtage, Double preis) {
            this(jahre, bis, monate, mitHaushalt, geraete, gesamt, gesamtVorjahrGleich, gesamtVorvorjahrGleich,
                    geraeteGleich, berechnet, jahressummen, gradtage, preis, List.of());
        }

        /**
         * Copy with another price
         */
        public Vergleich mitPreis(Double neu) {
            return new Vergleich(jahre, bis, monate, mitHaushalt, geraete, gesamt, gesamtVorjahrGleich,
                    gesamtVorvorjahrGleich, geraeteGleich, berechnet, jahressummen, gradtage, neu, gradtageMonate);
        }

        public Vergleich(List<Integer> jahre, LocalDate bis, List<Monat> monate, boolean mitHaushalt,
                         List<Geraete.Geraet> geraete, Double gesamt, Double gesamtVorjahrGleich,
                         Double gesamtVorvorjahrGleich, List<GleicheTage> geraeteGleich, Instant berechnet,
                         List<Jahressumme> jahressummen) {
            this(jahre, bis, monate, mitHaushalt, geraete, gesamt, gesamtVorjahrGleich, gesamtVorvorjahrGleich,
                    geraeteGleich, berechnet, jahressummen, List.of(), null, List.of());
        }

        /**
         * Heating degree days of the year with the given index up to the same day, null if not known
         */
        public Double gradtage(int i) {
            return i < gradtage.size() ? gradtage.get(i) : null;
        }

        /**
         * Part of the change of the heating against the same days of the year before that comes from the weather.
         * Month by month the heating of the year before is scaled by the heating degree days: a colder November
         * weighs more than a milder September. A month with fewer than MIN_GRADTAGE in the year before has no
         * reference and counts without weather; a month with too many days without the outdoor temperature is
         * scaled like the whole year, as is everything without the months. Null without the degree days of both
         * years.
         */
        public Double wetter() {
            List<Double> teile = wetterMonate();
            return teile == null ? null : teile.stream().mapToDouble(Double::doubleValue).sum();
        }

        /**
         * The weather part of the heating per month (index 0 January), 0 for a month without reference; empty if
         * only the whole year is known, null without the degree days of both years
         */
        public List<Double> wetterMonate() {
            Double jetzt = gradtage(JAHRE - 1);
            Double vorher = gradtage(JAHRE - 2);
            if (jetzt == null || vorher == null || vorher <= 0) {
                return null;
            }
            if (gradtageMonate.isEmpty()) {
                return List.of(heizungGleich(JAHRE - 2) * (jetzt / vorher - 1));
            }
            List<Double> teile = new ArrayList<>();
            for (int m = 0; m < Math.min(12, gradtageMonate.size()); m++) {
                List<Double> g = gradtageMonate.get(m);
                Double gJetzt = g.get(JAHRE - 1);
                Double gVorher = g.get(JAHRE - 2);
                Double hVorher = m < monate.size() ? monate.get(m).heizungGleich().get(JAHRE - 2) : null;
                if (hVorher == null) {
                    teile.add(0.0);
                } else if (gJetzt == null || gVorher == null) {
                    // Too many days without the outdoor temperature: the ratio of the whole year
                    teile.add(hVorher * (jetzt / vorher - 1));
                } else {
                    teile.add(gVorher >= MIN_GRADTAGE ? hVorher * (gJetzt / gVorher - 1) : 0.0);
                }
            }
            return teile;
        }

        /**
         * Change of the heating against the same days of the year before without the weather, null if not known
         */
        public Double bereinigt() {
            Double w = wetter();
            return w == null ? null : heizungGleich(JAHRE - 1) - heizungGleich(JAHRE - 2) - w;
        }

        /**
         * Expected electricity of a device from now up to the end of the year: as in the rest of the year before if
         * it was measured and used then, else as in the rest of the year before from its whole year, else at the
         * rate of the last three months that are over. A counter that ran the year before without use, e.g. a
         * wallbox before the electric car, says nothing about this year.
         */
        public double erwartet(int k) {
            GleicheTage t = geraeteGleich.get(k);
            if (t.restVorjahr() != null && t.vorjahr() != null && t.vorjahr() >= GENUTZT_KWH) {
                return t.restVorjahr();
            }
            Double vorjahr = geraet(k, JAHRE - 2);
            long restTage = LocalDate.of(jahr() + 1, 1, 1).toEpochDay() - bis.toEpochDay();
            return geraete.get(k).vorherDa() && vorjahr != null && t.vorjahr() != null
                    ? Math.max(0, vorjahr - t.vorjahr()) : jeTag(k) * restTage;
        }

        /**
         * Devices with values on the same days of this year and the year before: they get their own part in the
         * comparison. The others are counted in the rest, e.g. a device measured only since this year that ran
         * before.
         */
        public List<Integer> gezeigt() {
            List<Integer> g = new ArrayList<>();
            for (int k = 0; k < geraete.size(); k++) {
                GleicheTage t = geraeteGleich.get(k);
                if (t.jetzt() != null && t.vorjahr() != null) {
                    g.add(k);
                }
            }
            return g;
        }

        /**
         * Devices counted in the rest because their values on the same days are missing
         */
        public List<String> imRest() {
            List<Integer> g = gezeigt();
            List<String> namen = new ArrayList<>();
            for (int k = 0; k < geraete.size(); k++) {
                if (!g.contains(k)) {
                    namen.add(geraete.get(k).name());
                }
            }
            return namen;
        }

        /**
         * What the grid import from the 1st of January up to now consists of, against the same days of the year
         * before: total, the devices, heating and the rest. Empty without the house meter.
         */
        /**
         * Index of the rest in {@link #aufteilung()}: total, heating, rest, then the devices
         */
        public static final int REST = 2;

        public List<Anteil> aufteilung() {
            if (gesamt == null || gesamtVorjahrGleich == null) {
                return List.of();
            }
            double heizungJetzt = heizungGleich(JAHRE - 1);
            double heizungVorher = heizungGleich(JAHRE - 2);
            // Two years before, only with the grid import of then
            boolean mitVorvorjahr = gesamtVorvorjahrGleich != null;
            Double heizungVorvorher = mitVorvorjahr ? heizungGleich(JAHRE - 3) : null;
            List<Anteil> anteile = new ArrayList<>();
            anteile.add(new Anteil("Gesamt", gesamtVorjahrGleich, gesamt, gesamtVorvorjahrGleich));
            double restJetzt = gesamt - heizungJetzt;
            double restVorher = gesamtVorjahrGleich - heizungVorher;
            Double restVorvorher = mitVorvorjahr ? gesamtVorvorjahrGleich - heizungVorvorher : null;
            // Same order as the forecast: total, heating, rest, devices
            List<Anteil> eigene = new ArrayList<>();
            for (int k : gezeigt()) {
                GleicheTage t = geraeteGleich.get(k);
                eigene.add(new Anteil(geraete.get(k).name(), t.vorjahr(), t.jetzt(), mitVorvorjahr ? t.vorvorjahr() : null));
                restJetzt -= t.jetzt();
                restVorher -= t.vorjahr();
                restVorvorher = restVorvorher == null || t.vorvorjahr() == null ? null : restVorvorher - t.vorvorjahr();
            }
            anteile.add(new Anteil("Heizung", heizungVorher, heizungJetzt, heizungVorvorher));
            anteile.add(new Anteil(restName(), restVorher, restJetzt, restVorvorher));
            anteile.addAll(eigene);
            return anteile;
        }

        /**
         * "Rest (Haushalt)", with the devices counted in it
         */
        public String restName() {
            List<String> imRest = imRest();
            return imRest.isEmpty() ? "Rest (Haushalt)" : "Rest (Haushalt mit " + String.join(", ", imRest) + ")";
        }

        /**
         * Forecast up to the end of the year per part: heating as in the rest of the years before (mean of the two),
         * the rest as in the rest of the year before, a device that ran before as in the rest of the year before, a
         * new one at the rate of the last three months that are over. The total is the sum of the parts. Without the
         * house meter only the heating.
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
            List<Anteil> anteile = aufteilung();
            List<Integer> gezeigt = gezeigt();
            if (!anteile.isEmpty() && haushalt(JAHRE - 2) != null) {
                Anteil rest = anteile.get(REST);
                // The whole rest of the year before: household plus the devices counted in the rest
                double restVorjahr = haushalt(JAHRE - 2);
                for (int k = 0; k < geraete.size(); k++) {
                    if (!gezeigt.contains(k)) {
                        restVorjahr += geraetBekannt(k, JAHRE - 2);
                    }
                }
                teile.add(new Prognose(rest.name(), rest.jetzt(), Math.max(0, restVorjahr - rest.vorher()),
                        restVorjahr));
            }
            if (!anteile.isEmpty()) {
                for (int k : gezeigt) {
                    Double vorjahr = geraet(k, JAHRE - 2);
                    teile.add(new Prognose(geraete.get(k).name(), geraeteGleich.get(k).jetzt(), erwartet(k),
                            vorjahr == null ? 0 : vorjahr));
                }
            }
            if (teile.size() > 1) {
                teile.addFirst(new Prognose("Gesamt", teile.stream().mapToDouble(Prognose::bisher).sum(),
                        teile.stream().mapToDouble(Prognose::erwartet).sum(),
                        teile.stream().mapToDouble(p -> p.vorjahr() == null ? 0 : p.vorjahr()).sum()));
            }
            return teile;
        }

        /**
         * Mean electricity of a device per day in the last three months that are over
         */
        double jeTag(int k) {
            double kwh = 0;
            int tage = 0;
            for (int z = 1; z <= 3; z++) {
                YearMonth monat = YearMonth.from(bis).minusMonths(z);
                int i = jahre.indexOf(monat.getYear());
                Double wert = i < 0 ? null : monate.get(monat.getMonthValue() - 1).geraet(k, i);
                if (wert != null) {
                    kwh += wert;
                    tage += monat.lengthOfMonth();
                }
            }
            return tage == 0 ? 0 : kwh / tage;
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
         * Electricity of a device in the year with the given index, null if no month of it is known
         */
        public Double geraet(int k, int i) {
            List<Double> werte = monate.stream().map(m -> m.geraet(k, i)).toList();
            return werte.stream().anyMatch(w -> w != null) ? summe(werte) : null;
        }

        private double geraetBekannt(int k, int i) {
            Double w = geraet(k, i);
            return w == null ? 0 : w;
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
     * One whole year that is over, from the house meter: grid import and its parts in kWh
     *
     * @param geraete per device of the comparison, null where it is not known (part of the household then)
     */
    public record Jahressumme(int jahr, double gesamt, double heizung, List<Double> geraete) {

        public double haushalt() {
            return gesamt - heizung - geraete.stream().filter(g -> g != null).mapToDouble(Double::doubleValue).sum();
        }
    }

    /**
     * Consumption from the start of the day von to the end of the day bis, e.g. between the readings of two bills
     *
     * @param standAnfang reading of the house meter at the start, null if unknown
     * @param standEnde   reading of the house meter at the end (now if the end is still to come), null if unknown
     * @param heizung     estimated electricity of the heating
     * @param namen       the devices
     * @param geraete     their electricity, null where not known
     */
    public record Zeitraum(LocalDate von, LocalDate bis, Instant ende, Double standAnfang, Double standEnde,
                           double heizung, List<String> namen, List<Double> geraete) {

        public Double gesamt() {
            return standAnfang == null || standEnde == null ? null : standEnde - standAnfang;
        }

        public Double haushalt() {
            Double g = gesamt();
            return g == null ? null : g - heizung
                    - geraete.stream().filter(x -> x != null).mapToDouble(Double::doubleValue).sum();
        }

        public long tage() {
            return Math.max(1, Math.round(Duration.between(von.atStartOfDay(ZONE).toInstant(), ende).toHours() / 24.0));
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
    public record Anteil(String name, double vorher, double jetzt, Double vorvorher) {

        public Anteil(String name, double vorher, double jetzt) {
            this(name, vorher, jetzt, null);
        }

        public double differenz() {
            return jetzt - vorher;
        }

        public Double prozent() {
            return vorher > 0 ? (jetzt / vorher - 1) * 100 : null;
        }
    }

    // Price of electricity in € per kWh, null if not set
    private volatile Double preis;

    public Jahresvergleich(@Value("${auswertung.haushalt.bucket:}") String haushaltBucket,
                           @Value("${auswertung.haushalt.zaehler:}") String hauszaehler) {
        this.haushaltBucket = haushaltBucket.trim();
        this.hauszaehler = hauszaehler.trim();
    }

    /**
     * Takes over the price saved on the configuration page at once, also for the comparison already calculated
     */
    public void preisSetzen(String preis) {
        setPreis(preis);
        Vergleich v = ergebnis;
        if (v != null) {
            ergebnis = v.mitPreis(this.preis);
        }
    }

    @Value("${auswertung.strompreis:}")
    void setPreis(String preis) {
        String p = preis == null ? "" : preis.trim().replace(',', '.');
        try {
            this.preis = p.isEmpty() ? null : Double.valueOf(p);
        } catch (NumberFormatException e) {
            logger.warn("auswertung.strompreis is no number: " + preis);
            this.preis = null;
        }
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
        List<Geraete.Geraet> liste = geraete.getListe();
        reihen = reihenLesen(liste);
        List<Map<YearMonth, Double>> geraeteMonate = new ArrayList<>();
        List<GleicheTage> geraeteGleich = new ArrayList<>();
        for (Geraete.Geraet g : liste) {
            Zaehlerreihe r = reihen.get(g.name());
            Map<YearMonth, Double> monate = new TreeMap<>();
            for (YearMonth monat = erster; !monat.isAfter(laufend); monat = monat.plusMonths(1)) {
                Instant ende = monat.equals(laufend) ? jetzt.toInstant() : start(monat.plusMonths(1));
                monate.put(monat, r.verbrauch(start(monat), ende, g.vorherDa()));
            }
            geraeteMonate.add(monate);
            geraeteGleich.add(new GleicheTage(
                    r.verbrauch(start(YearMonth.of(jahr, 1)), jetzt.toInstant(), g.vorherDa()),
                    r.verbrauch(start(YearMonth.of(jahr - 1, 1)), jetzt.minusYears(1).toInstant(), g.vorherDa()),
                    r.verbrauch(start(YearMonth.of(jahr - 2, 1)), jetzt.minusYears(2).toInstant(), g.vorherDa()),
                    r.isHochgerechnet(),
                    // Only if it was measured then: before its first value it is not known (vorherDa true)
                    r.verbrauch(jetzt.minusYears(1).toInstant(), start(YearMonth.of(jahr, 1)), true)));
        }
        // The outdoor temperature of the months that are over is read once too
        boolean fertig = aussenFertig.keySet().stream().anyMatch(t -> YearMonth.from(t).equals(laufend.minusMonths(1)));
        YearMonth ab = fertig ? laufend : erster;
        Map<LocalDate, Double> tage = new TreeMap<>(aussenFertig);
        vergleich.aussentemperatur(start(ab), jetzt.toInstant()).forEach((tag, wert) -> {
            tage.put(tag, wert);
            if (YearMonth.from(tag).isBefore(laufend)) {
                aussenFertig.put(tag, wert);
            }
        });
        Map<YearMonth, Double> aussen = monatsmittel(tage);
        // Grid import from the 1st of January up to now and up to the same hour in the years before
        Double[] gesamt = bisJetzt(hauszaehler, jetzt);
        Vergleich v = vergleich(jahr, jetzt.toLocalDate(), heizung, gleich, haushalt(bezug, heizung, geraeteMonate),
                !hauszaehler.isEmpty(), liste, geraeteMonate, aussen, gesamt[0], gesamt[1], gesamt[2],
                geraeteGleich, Instant.now(), jahressummen(jahr, liste));
        List<Double> gradtage = new ArrayList<>();
        for (int j : v.jahre()) {
            gradtage.add(gradtage(tage, LocalDate.of(j, 1, 1), jetzt.toLocalDate().withYear(j)));
        }
        // Per month up to the current one, which counts up to the same day
        List<List<Double>> gradtageMonate = new ArrayList<>();
        for (int m = 1; m <= laufend.getMonthValue(); m++) {
            List<Double> jeJahr = new ArrayList<>();
            for (int j : v.jahre()) {
                LocalDate von = LocalDate.of(j, m, 1);
                LocalDate bisTag = m == laufend.getMonthValue() ? jetzt.toLocalDate().withYear(j) : von.plusMonths(1);
                jeJahr.add(gradtage(tage, von, bisTag));
            }
            gradtageMonate.add(java.util.Collections.unmodifiableList(jeJahr));
        }
        return new Vergleich(v.jahre(), v.bis(), v.monate(), v.mitHaushalt(), v.geraete(), v.gesamt(),
                v.gesamtVorjahrGleich(), v.gesamtVorvorjahrGleich(), v.geraeteGleich(), v.berechnet(),
                v.jahressummen(), java.util.Collections.unmodifiableList(gradtage), preis,
                java.util.Collections.unmodifiableList(gradtageMonate));
    }

    /**
     * Readings of every device: the earlier ones as they are, the counter as the last value of each day
     */
    private Map<String, Zaehlerreihe> reihenLesen(List<Geraete.Geraet> liste) {
        Map<String, Zaehlerreihe> neu = new java.util.HashMap<>();
        String bucket = haushaltBucket.isEmpty() ? influx.getBucket() : haushaltBucket;
        for (Geraete.Geraet g : liste) {
            Map<Instant, Double> davor = g.zaehlerDavor().isEmpty() ? Map.of()
                    : influx.werte(reiheFlux(bucket, g.zaehlerDavor(), false));
            Map<Instant, Double> zaehler = g.zaehler().isEmpty() ? Map.of()
                    : influx.werte(reiheFlux(bucket, g.zaehler(), true));
            neu.put(g.name(), new Zaehlerreihe(davor, zaehler));
        }
        return neu;
    }

    /**
     * All readings of a counter in kWh, with taeglich the last of each day (Home Assistant stores every change)
     */
    static String reiheFlux(String bucket, String entity, boolean taeglich) {
        pruefen(bucket);
        pruefen(entity);
        return (taeglich ? "import \"timezone\"\noption location = timezone.location(name: \"Europe/Berlin\")\n" : "")
                + "from(bucket: \"" + bucket + "\") |> range(start: 2010-01-01T00:00:00Z)"
                + " |> filter(fn: (r) => r._measurement == \"kWh\" and r._field == \"value\" and r.entity_id == \""
                + entity + "\") |> group() |> sort(columns: [\"_time\"])"
                + (taeglich ? " |> aggregateWindow(every: 1d, fn: last, createEmpty: false, timeSrc: \"_stop\")" : "");
    }

    /**
     * All whole years that are over and have readings of the house meter at both ends, read once; empty without
     * the house meter. Looks back at most JAHRE_ZURUECK years.
     */
    private List<Jahressumme> jahressummen(int jahr, List<Geraete.Geraet> liste) {
        List<Jahressumme> summen = new ArrayList<>();
        if (hauszaehler.isEmpty()) {
            return summen;
        }
        for (int j = jahr - JAHRE_ZURUECK; j < jahr; j++) {
            Instant anfang = start(YearMonth.of(j, 1));
            Instant ende = start(YearMonth.of(j + 1, 1));
            Double gesamt = differenz(stand(hauszaehler, anfang), stand(hauszaehler, ende));
            if (gesamt == null) {
                continue;
            }
            double heizung = 0;
            for (YearMonth m = YearMonth.of(j, 1); m.getYear() == j; m = m.plusMonths(1)) {
                heizung += heizungFertig.computeIfAbsent(m, x -> strom(start(x), start(x.plusMonths(1))));
            }
            List<Double> teile = new ArrayList<>();
            for (Geraete.Geraet g : liste) {
                teile.add(reihen.get(g.name()).verbrauch(anfang, ende, g.vorherDa()));
            }
            summen.add(new Jahressumme(j, gesamt, heizung, Collections.unmodifiableList(teile)));
        }
        return summen;
    }

    /**
     * Consumption from the start of the day von to the end of the day bis, or up to now if that is later
     */
    public Zeitraum zeitraum(LocalDate von, LocalDate bis) {
        Instant jetzt = Instant.now();
        Instant anfang = von.atStartOfDay(ZONE).toInstant();
        Instant ende = bis.plusDays(1).atStartOfDay(ZONE).toInstant();
        if (ende.isAfter(jetzt)) {
            ende = jetzt;
        }
        Double standAnfang = hauszaehler.isEmpty() ? null : zaehlerstand(hauszaehler, anfang, jetzt);
        Double standEnde = hauszaehler.isEmpty() ? null : zaehlerstand(hauszaehler, ende, jetzt);
        List<String> namen = new ArrayList<>();
        List<Double> teile = new ArrayList<>();
        Map<String, Zaehlerreihe> r = reihen;
        for (Geraete.Geraet g : geraete.getListe()) {
            namen.add(g.name());
            Zaehlerreihe reihe = r.get(g.name());
            teile.add(reihe == null ? null : reihe.verbrauch(anfang, ende, g.vorherDa()));
        }
        // Whole months from the cache of the comparison, the parts at the edges directly
        double heizung = 0;
        Instant t = anfang;
        while (t.isBefore(ende)) {
            YearMonth monat = YearMonth.from(t.atZone(ZONE));
            Instant monatEnde = start(monat.plusMonths(1));
            Instant bisHier = monatEnde.isAfter(ende) ? ende : monatEnde;
            boolean ganz = t.equals(start(monat)) && bisHier.equals(monatEnde) && monatEnde.isBefore(jetzt);
            heizung += ganz ? heizungFertig.computeIfAbsent(monat, x -> strom(start(x), start(x.plusMonths(1))))
                    : strom(t, bisHier);
            t = bisHier;
        }
        return new Zeitraum(von, bis, ende, standAnfang, standEnde, heizung, namen, teile);
    }

    /**
     * Reading at a point in time, interpolated; the last reading for now
     */
    private Double zaehlerstand(String entity, Instant zeit, Instant jetzt) {
        return zeit.equals(jetzt) ? letzter(entity, zeit)
                : stand(zaehler(entity, zeit, false), zaehler(entity, zeit, true), zeit);
    }

    /**
     * Consumption of a counter from the 1st of January up to now, and in the year before up to the same hour; both
     * null without the counter
     */
    private Double[] bisJetzt(String entity, ZonedDateTime jetzt) {
        if (entity.isEmpty()) {
            return new Double[3];
        }
        int jahr = jetzt.getYear();
        Instant vorJahr = jetzt.minusYears(1).toInstant();
        Instant vorvorJahr = jetzt.minusYears(2).toInstant();
        return new Double[]{differenz(stand(entity, start(YearMonth.of(jahr, 1))), letzter(entity, jetzt.toInstant())),
                differenz(stand(entity, start(YearMonth.of(jahr - 1, 1))),
                        stand(zaehler(entity, vorJahr, false), zaehler(entity, vorJahr, true), vorJahr)),
                differenz(stand(entity, start(YearMonth.of(jahr - 2, 1))),
                        stand(zaehler(entity, vorvorJahr, false), zaehler(entity, vorvorJahr, true), vorvorJahr))};
    }

    /**
     * Comparison from the values per month: the current month counts up to today, for it the years before are given
     * up to the same day in gleich; gesamt is the grid import of this year up to now, gesamtVorjahrGleich and
     * gesamtVorvorjahrGleich the same days of the years before
     */
    static Vergleich vergleich(int jahr, LocalDate heute, Map<YearMonth, Double> heizung, Map<Integer, Double> gleich,
                               Map<YearMonth, Double> haushalt, boolean mitHaushalt, List<Geraete.Geraet> geraete,
                               List<Map<YearMonth, Double>> geraeteMonate, Map<YearMonth, Double> aussen,
                               Double gesamt, Double gesamtVorjahrGleich, Double gesamtVorvorjahrGleich,
                               List<GleicheTage> geraeteGleich, Instant berechnet, List<Jahressumme> jahressummen) {
        YearMonth laufend = YearMonth.from(heute);
        List<Integer> jahre = jahre(jahr);
        List<Monat> monate = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            boolean istLaufend = m == laufend.getMonthValue();
            boolean kommt = m > laufend.getMonthValue();
            List<Double> h = new ArrayList<>();
            List<Double> hg = new ArrayList<>();
            List<Double> hh = new ArrayList<>();
            List<Double> a = new ArrayList<>();
            List<List<Double>> g = new ArrayList<>();
            for (Map<YearMonth, Double> werte : geraeteMonate) {
                List<Double> w = new ArrayList<>();
                for (int j : jahre) {
                    YearMonth monat = YearMonth.of(j, m);
                    w.add(monat.isAfter(laufend) ? null : werte.get(monat));
                }
                g.add(Collections.unmodifiableList(w));
            }
            for (int j : jahre) {
                YearMonth monat = YearMonth.of(j, m);
                boolean zukunft = monat.isAfter(laufend);
                h.add(zukunft ? null : heizung.get(monat));
                hg.add(kommt ? null : istLaufend && j < jahr ? gleich.get(j) : zukunft ? null : heizung.get(monat));
                hh.add(zukunft ? null : haushalt.get(monat));
                a.add(zukunft ? null : aussen.get(monat));
            }
            monate.add(new Monat(m, MONATE[m - 1], Collections.unmodifiableList(h), Collections.unmodifiableList(hg),
                    Collections.unmodifiableList(hh), Collections.unmodifiableList(g), Collections.unmodifiableList(a),
                    istLaufend));
        }
        return new Vergleich(List.copyOf(jahre), heute, monate, mitHaushalt, List.copyOf(geraete), gesamt,
                gesamtVorjahrGleich, gesamtVorvorjahrGleich, List.copyOf(geraeteGleich), berechnet,
                List.copyOf(jahressummen));
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
     * Household electricity per month: grid import minus heating minus the devices with a value, not below 0. Null
     * where the grid import is not known.
     */
    static Map<YearMonth, Double> haushalt(Map<YearMonth, Double> bezug, Map<YearMonth, Double> heizung,
                                           List<Map<YearMonth, Double>> geraete) {
        Map<YearMonth, Double> haushalt = new TreeMap<>();
        bezug.forEach((monat, kwh) -> {
            if (kwh != null) {
                double rest = kwh - heizung.getOrDefault(monat, 0d);
                for (Map<YearMonth, Double> g : geraete) {
                    rest -= Optional.ofNullable(g.get(monat)).orElse(0d);
                }
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

    // Heating degree days after VDI 3807 (Gt20/15): on a day below 15 °C the difference to 20 °C
    static final double HEIZGRENZE = 15;
    static final double INNEN = 20;
    // Share of the days that needs a value, otherwise the degree days are not known
    static final double MIN_ABDECKUNG = 0.9;
    // A month with fewer degree days in the year before is no reference for the weather, e.g. July
    static final double MIN_GRADTAGE = 20;

    /**
     * Heating degree days from the day von up to the day before bis (the day bis is not over yet); null if the
     * daily means of more than a tenth of the days are missing. The missing days count as the mean of the others.
     */
    static Double gradtage(Map<LocalDate, Double> tage, LocalDate von, LocalDate bis) {
        long anzahl = bis.toEpochDay() - von.toEpochDay();
        if (anzahl <= 0) {
            return null;
        }
        double summe = 0;
        int mitWert = 0;
        for (LocalDate t = von; t.isBefore(bis); t = t.plusDays(1)) {
            Double grad = tage.get(t);
            if (grad != null) {
                summe += grad < HEIZGRENZE ? INNEN - grad : 0;
                mitWert++;
            }
        }
        return mitWert < anzahl * MIN_ABDECKUNG ? null : summe * anzahl / mitWert;
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
                          |> %s
                          |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
                          |> sum()
                          |> map(fn: (r) => ({r with _value: r._value / 60000.0, _time: r._start}))
                        """, bucket, von, bis, Waermepumpe.wirkleistungFlux(LAEUFT_AB_W));
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
