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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Condition of the heat pump over the heating seasons (July to June): values that show ageing or a problem, at the
 * same outdoor temperature so that a cold and a mild winter can be compared. Efficiency, defrosts, starts, failed
 * starts and the heating pressure of the last season with enough days are compared with the seasons before. Uses the
 * stored values, so it needs InfluxDB.
 */
@Component
public class Zustand {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    // A temperature band of a season counts with at least this many days
    static final int MIN_TAGE = 10;
    // Days below this outdoor temperature are heating days for the efficiency of the whole season
    private static final double HEIZTAG_BIS = 15;
    private static final Duration NEU_NACH = Duration.ofHours(6);
    // Days with less electricity or a daily efficiency outside these limits are left out of the efficiency
    private static final double MIN_STROM_KWH = 1;
    private static final double AZ_MIN = 1.5;
    private static final double AZ_MAX = 9;
    // The newest season is compared with the mean of at most this many seasons before, so that settings of long
    // ago (e.g. the change of 2023) do not count
    static final int VERGLEICH_SAISONS = 3;
    // The physical values are also compared with a fixed reference, the mean of the first seasons with a value: a
    // steady drift of d per year shows only as about 2 d against the three seasons before, never more
    static final int BEZUG_SAISONS = 2;
    // Steady run: the inverter takes at least this much in the 10 minute window and in the one before
    private static final double LAUF_STETIG_W = 500;
    // Running for the pressure swing: the water flows through the whole circuit
    private static final double LAUF_W = 300;
    private static final Duration FENSTER = Duration.ofMinutes(10);
    // At least this many 10 minute windows or days for a median
    static final int MIN_FENSTER = 30;
    // Pressure swing: per winter month the daily means of the pressure against the daily means of the water
    // temperature while running; within a day the floor heating water changes much less than flow and return and
    // the pump pressure mixes in (r about 0.6), across the days of a month r is 0.8 to 0.98 (checked 2021 to 2026)
    static final int MIN_TAGE_MONAT = 15;
    static final double MIN_SPANNE_MONAT_K = 3;
    static final int MIN_MONATE_DRUCK = 2;
    // Normalized to 1.8 bar and 30 °C water: the swing grows with the square of the absolute pressure and with the
    // thermal expansion of water
    static final double DRUCK_NORM_BAR = 1.8;
    static final double LUFTDRUCK_BAR = 1.013;
    private static final double[][] AUSDEHNUNG_WASSER = {{20, 2.07e-4}, {25, 2.57e-4}, {30, 3.03e-4},
            {35, 3.45e-4}, {40, 3.85e-4}};
    // A day counts for the pressure swing with at least 6 h running and a span of the water temperature of 3 K
    private static final int MIN_FENSTER_TAG = 36;
    // Refill: the hourly mean rises at least 0.12 bar and the next 24 h lie at least 0.1 bar above the 24 h before;
    // the heating raises it by at most 0.15 bar in an hour, but not for a whole day (checked 2021 to 2026: exactly
    // the four refills 2022-04-05, 2024-02-15, 2025-04-28, 2026-10-13)
    static final double NACHFUELLEN_SPRUNG = 0.12;
    static final double NACHFUELLEN_DAUERHAFT = 0.1;
    // Saturation (dew line) of R410A: absolute pressure in bar and temperature in °C
    private static final double[][] R410A = {{2.70, -30}, {3.30, -25}, {3.99, -20}, {4.80, -15}, {5.73, -10},
            {6.79, -5}, {7.99, 0}, {9.34, 5}, {10.86, 10}, {12.55, 15}, {14.43, 20}, {16.5, 25}, {18.9, 30},
            {21.4, 35}, {24.2, 40}, {27.3, 45}, {30.6, 50}};
    // Pressure of the heating circuit since this time for the refills
    private static final Instant DRUCK_AB = Instant.parse("2019-01-01T00:00:00Z");

    private final Logger logger = LoggerFactory.getLogger(Zustand.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    @Autowired
    private Fehlstarts fehlstarts;
    @Autowired
    private EmailService mail;
    // Last rating per check (0 unauffällig, 1 beobachten, 2 auffällig), also stored as WP_Zustand_<check>
    private final Map<String, Integer> stufen = new ConcurrentHashMap<>();
    private final LocalDate start;
    // Seasons that are over do not change and are read only once
    private final Map<String, Saison> fertig = new ConcurrentHashMap<>();
    private volatile Ergebnis ergebnis;

    /**
     * Values of one temperature band in one season; the means are null below MIN_TAGE days
     */
    public record Band(int tage, Double arbeitszahl, Double startsProTag, Double laufzeitProStart,
                       Double abtauungenProTag) {
    }

    /**
     * One heating season, e.g. "2025/26"
     *
     * @param kalt             days with a daily mean from 0 to 5 °C
     * @param mild             days from 5 to 10 °C
     * @param arbeitszahl      heat ÷ estimated electricity of all days below 15 °C
     * @param fehlstartsJe1000 failed starts per 1000 starts on mild days, from the evaluation of the failed starts
     * @param druckWinter      mean heating pressure from November to March
     */
    public record Saison(String name, boolean laufend, int heiztage, Double arbeitszahl, Band kalt, Band mild,
                         Double fehlstartsJe1000, Double druckWinter, Betrieb betrieb) {

        public Saison(String name, boolean laufend, int heiztage, Double arbeitszahl, Band kalt, Band mild,
                      Double fehlstartsJe1000, Double druckWinter) {
            this(name, laufend, heiztage, arbeitszahl, kalt, mild, fehlstartsJe1000, druckWinter, Betrieb.LEER);
        }
    }

    /**
     * Values of the steady run from November to March, medians of 10 minute means; null with too few data
     *
     * @param verdampferAbstand    outdoor air − evaporation temperature at 0 to 5 °C outside, rises with loss of
     *                             refrigerant or a dirty evaporator
     * @param verfluessigerAbstand condensation temperature − flow, rises with deposits in the condenser
     * @param spreizung            flow − return, rises when less water flows
     * @param druckschwankung      change of the heating pressure per 10 K water temperature, rises when the expansion
     *                             vessel loses its gas charge
     */
    public record Betrieb(Double verdampferAbstand, Double verfluessigerAbstand, Double spreizung,
                          Double druckschwankung) {
        static final Betrieb LEER = new Betrieb(null, null, null, null);
    }

    /**
     * Refill of the heating circuit
     *
     * @param bar        rise of the pressure
     * @param barProJahr loss per year since the refill before, null for the first one
     */
    public record Nachfuellung(Instant zeit, double bar, Double barProJahr) {
    }

    /**
     * One indicator: the newest season with enough days against the mean of the seasons before
     *
     * @param status "unauffällig", "beobachten", "auffällig" or "zu wenige Daten"
     */
    public record Pruefung(String name, String saison, Double wert, Double vorher, String einheit, int stellen,
                           String status, String hinweis, Double bezug, String regel, Verlauf verlauf) {

        public Pruefung(String name, String saison, Double wert, Double vorher, String einheit, int stellen,
                        String status, String hinweis, Double bezug, String regel) {
            this(name, saison, wert, vorher, einheit, stellen, status, hinweis, bezug, regel, Verlauf.LEER);
        }

        public Pruefung(String name, String saison, Double wert, Double vorher, String einheit, int stellen,
                        String status, String hinweis, Double bezug) {
            this(name, saison, wert, vorher, einheit, stellen, status, hinweis, bezug, null, Verlauf.LEER);
        }

        public Pruefung(String name, String saison, Double wert, Double vorher, String einheit, int stellen,
                        String status, String hinweis) {
            this(name, saison, wert, vorher, einheit, stellen, status, hinweis, null, null, Verlauf.LEER);
        }

        /**
         * The same check with the rule when it is rated beobachten or auffällig
         */
        Pruefung mitRegel(String regel) {
            return new Pruefung(name, saison, wert, vorher, einheit, stellen, status, hinweis, bezug, regel, verlauf);
        }

        Pruefung mitVerlauf(Verlauf verlauf) {
            return new Pruefung(name, saison, wert, vorher, einheit, stellen, status, hinweis, bezug, regel, verlauf);
        }
    }

    /**
     * Values of a check over the seasons or refills, null where a season has none
     */
    public record Verlauf(List<String> namen, List<Double> werte) {
        static final Verlauf LEER = new Verlauf(List.of(), List.of());

        static Verlauf aus(List<Saison> saisons, Function<Saison, Double> wert) {
            return new Verlauf(saisons.stream().map(Saison::name).toList(),
                    saisons.stream().map(wert).collect(java.util.stream.Collectors.toList()));
        }
    }

    /**
     * @param unvollstaendig a season is missing because its queries failed, it is tried again in 30 minutes
     */
    public record Ergebnis(List<Saison> saisons, List<Pruefung> pruefungen, boolean unvollstaendig,
                           Instant berechnet, List<Nachfuellung> nachfuellungen) {

        public Ergebnis(List<Saison> saisons, List<Pruefung> pruefungen, boolean unvollstaendig, Instant berechnet) {
            this(saisons, pruefungen, unvollstaendig, berechnet, List.of());
        }
    }

    public Zustand(@Value("${auswertung.zustand.start:2022-07-01}") String start) {
        this.start = LocalDate.parse(start);
    }

    /**
     * Checked every 30 minutes from 6 minutes after the start: calculated if there is no result yet, a season is
     * missing or the result is older than 6 hours. The seasons that are over are read once; a season whose queries
     * failed (InfluxDB answers slowly at times) is left out and tried again at the next check.
     */
    @Scheduled(initialDelay = 360000, fixedRate = 1800000)
    public void berechnen() {
        if (!influx.isAktiv()) {
            return;
        }
        Ergebnis alt = ergebnis;
        if (alt != null && !alt.unvollstaendig() && alt.berechnet().isAfter(Instant.now().minus(NEU_NACH))) {
            return;
        }
        try {
            LocalDate heute = LocalDate.now(ZONE);
            List<Saison> saisons = new ArrayList<>();
            boolean unvollstaendig = false;
            Map<String, Double> fehlstartRate = new TreeMap<>();
            fehlstarts.getSaisons().forEach(s -> fehlstartRate.put(s.name(), s.jeTausendMild()));
            // The refills first: the months with a refill are left out of the pressure swing
            List<Nachfuellung> nachfuellungen = List.of();
            boolean nachfuellungenDa = true;
            try {
                nachfuellungen = nachfuellungen(vergleich.mittelwerte("WP_Heizungsdruck", "1h", DRUCK_AB,
                        Instant.now()));
            } catch (Exception e) {
                logger.warn("Refills of the heating circuit could not be read, tried again later: " + e.getMessage());
                unvollstaendig = true;
                nachfuellungenDa = false;
            }
            java.util.Set<java.time.YearMonth> nachfuellMonate = new java.util.HashSet<>();
            nachfuellungen.forEach(n -> nachfuellMonate.add(java.time.YearMonth.from(n.zeit().atZone(ZONE))));
            for (LocalDate von = saisonStart(start); von.isBefore(heute); von = von.plusYears(1)) {
                LocalDate bis = von.plusYears(1);
                String name = name(von);
                boolean laufend = bis.isAfter(heute);
                Saison saison = laufend ? null : fertig.get(name);
                if (saison == null) {
                    if (!nachfuellungenDa) {
                        // Without the refills a season would count months with a refill, tried again later
                        continue;
                    }
                    try {
                        saison = saison(name, laufend, von, laufend ? heute : bis, fehlstartRate.get(name),
                                nachfuellMonate);
                    } catch (Exception e) {
                        logger.warn("Season " + name + " could not be calculated, tried again later: "
                                + e.getMessage());
                        unvollstaendig = true;
                        continue;
                    }
                    if (!laufend) {
                        fertig.put(name, saison);
                    }
                }
                saisons.add(saison);
            }
            ergebnis = new Ergebnis(List.copyOf(saisons), pruefungen(saisons, nachfuellungen), unvollstaendig,
                    Instant.now(), nachfuellungen);
            // With a season missing the newest season could be another one, so nothing is reported
            if (!unvollstaendig) {
                melden(ergebnis.pruefungen(), ergebnis.berechnet());
            }
        } catch (Exception e) {
            logger.warn("Condition over the seasons could not be calculated: " + e.getMessage());
        }
    }

    /**
     * Sends a mail when a check got worse than at the last calculation: with Pushover for "auffällig", only by mail
     * for "beobachten". The ratings are stored, so a restart of the service does not report them again; before the
     * first stored rating a check counts as unauffällig. Getting better is not reported.
     */
    private void melden(List<Pruefung> pruefungen, Instant jetzt) {
        List<Pruefung> schlechter = new ArrayList<>();
        List<com.influxdb.client.write.Point> punkte = new ArrayList<>();
        for (Pruefung p : pruefungen) {
            int stufe = stufe(p.status());
            if (stufe < 0) {
                continue;
            }
            String name = "Zustand_" + schluessel(p.name());
            Integer vorher = stufen.get(name);
            if (vorher == null) {
                try {
                    Double gespeichert = influx.letzterWert(name);
                    vorher = gespeichert == null ? 0 : gespeichert.intValue();
                } catch (Exception e) {
                    // No reference, compared at the next calculation
                    continue;
                }
            }
            if (stufe > vorher) {
                schlechter.add(p);
            }
            stufen.put(name, stufe);
            punkte.add(influx.createPoint(name, new nrw.andresen.stbl.services.can.ValueContainer<>((double) stufe,
                    jetzt)));
        }
        influx.storePoints(punkte);
        if (schlechter.isEmpty()) {
            return;
        }
        boolean auffaellig = schlechter.stream().anyMatch(p -> p.status().equals("auffällig"));
        String betreff = "Wärmepumpe: Zustand " + (auffaellig ? "auffällig" : "beobachten");
        logger.warn("Condition got worse: " + schlechter.stream().map(Pruefung::name).toList());
        if (auffaellig) {
            mail.sendAlert(betreff, meldung(schlechter));
        } else {
            mail.sendMail(betreff, meldung(schlechter));
        }
    }

    /**
     * 0 unauffällig, 1 beobachten, 2 auffällig, -1 for checks without a rating
     */
    static int stufe(String status) {
        return switch (status) {
            case "unauffällig" -> 0;
            case "beobachten" -> 1;
            case "auffällig" -> 2;
            default -> -1;
        };
    }

    /**
     * Name of the check as part of a measurement, e.g. "ArbeitszahlBei0Bis5C"
     */
    static String schluessel(String name) {
        StringBuilder s = new StringBuilder();
        for (String teil : name.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .split("[^A-Za-z0-9]+")) {
            if (!teil.isEmpty()) {
                s.append(Character.toUpperCase(teil.charAt(0))).append(teil.substring(1));
            }
        }
        return s.toString();
    }

    /**
     * Text of the mail for the checks that got worse
     */
    static String meldung(List<Pruefung> schlechter) {
        StringBuilder text = new StringBuilder("Auf der Seite Zustand und Alterung hat sich verschlechtert:\n");
        for (Pruefung p : schlechter) {
            String einheit = p.einheit().isEmpty() ? "" : " " + p.einheit();
            text.append("\n").append(p.name()).append(": ").append(p.status()).append("\n")
                    .append(p.saison()).append(" ").append(ZustandSeite.zahl(p.wert(), p.stellen())).append(einheit)
                    .append(", Mittel davor ").append(ZustandSeite.zahl(p.vorher(), p.stellen())).append(einheit)
                    .append(p.bezug() == null ? "" : ", Bezug " + ZustandSeite.zahl(p.bezug(), p.stellen()) + einheit)
                    .append(p.regel() == null ? "" : "\nGrenze: " + p.regel())
                    .append("\n").append(p.hinweis()).append("\n");
        }
        return text.append("\nEine Besserung wird nicht gemeldet, der aktuelle Stand steht auf der Seite.")
                .toString();
    }

    /**
     * Last calculated result, null before the first calculation or without InfluxDB
     */
    public Ergebnis getErgebnis() {
        return ergebnis;
    }

    private Saison saison(String name, boolean laufend, LocalDate von, LocalDate bis, Double fehlstartsJe1000,
                          java.util.Set<java.time.YearMonth> nachfuellMonate) {
        List<WaermebedarfVergleich.Tag> tage = vergleich.tage(von.atStartOfDay(ZONE).toInstant(),
                bis.atStartOfDay(ZONE).toInstant());
        Instant winterVon = von.withMonth(11).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
        Instant winterBis = min(bis, von.plusYears(1).withMonth(4).withDayOfMonth(1)).atStartOfDay(ZONE).toInstant();
        Map<LocalDate, Double> druck = vergleich.tagesmittel("WP_Heizungsdruck", winterVon, winterBis);
        Saison saison = saison(name, laufend, tage, fehlstartsJe1000, druck);
        if (!winterVon.isBefore(winterBis)) {
            return saison;
        }
        Betrieb betrieb = betrieb(vergleich.mittelwerte("WP_LeistungInverter", "10m", winterVon, winterBis),
                vergleich.mittelwerte("WP_Niederdruck", "10m", winterVon, winterBis),
                vergleich.mittelwerte("WP_Hochdruck", "10m", winterVon, winterBis),
                vergleich.mittelwerte("WP_VorlaufIstTemp", "10m", winterVon, winterBis),
                vergleich.mittelwerte("WP_RuecklaufIstTemp", "10m", winterVon, winterBis),
                vergleich.aussentemperaturMittel("10m", winterVon, winterBis),
                vergleich.mittelwerte("WP_Heizungsdruck", "10m", winterVon, winterBis), nachfuellMonate);
        return new Saison(saison.name(), saison.laufend(), saison.heiztage(), saison.arbeitszahl(), saison.kalt(),
                saison.mild(), saison.fehlstartsJe1000(), saison.druckWinter(), betrieb);
    }

    /**
     * Values of the steady run from the 10 minute means of one winter
     *
     * @param nachfuellMonate months with a refill, left out of the pressure swing
     */
    static Betrieb betrieb(Map<Instant, Double> leistung, Map<Instant, Double> niederdruck,
                           Map<Instant, Double> hochdruck, Map<Instant, Double> vorlauf, Map<Instant, Double> ruecklauf,
                           Map<Instant, Double> aussen, Map<Instant, Double> druck,
                           java.util.Set<java.time.YearMonth> nachfuellMonate) {
        List<Double> verdampfer = new ArrayList<>();
        List<Double> verfluessiger = new ArrayList<>();
        List<Double> spreizung = new ArrayList<>();
        Map<LocalDate, List<double[]>> tage = new TreeMap<>();
        for (Map.Entry<Instant, Double> e : new TreeMap<>(leistung).entrySet()) {
            Instant t = e.getKey();
            Double vl = vorlauf.get(t);
            Double rl = ruecklauf.get(t);
            if (vl == null || rl == null) {
                continue;
            }
            Double p = druck.get(t);
            if (e.getValue() >= LAUF_W && p != null) {
                tage.computeIfAbsent(t.atZone(ZONE).toLocalDate(), d -> new ArrayList<>())
                        .add(new double[]{(vl + rl) / 2, p});
            }
            if (e.getValue() < LAUF_STETIG_W || leistung.getOrDefault(t.minus(FENSTER), 0d) < LAUF_STETIG_W) {
                continue;
            }
            Double tk = hochdruck.containsKey(t) ? saettigung(hochdruck.get(t)) : null;
            Double tv = niederdruck.containsKey(t) ? saettigung(niederdruck.get(t)) : null;
            if (tk == null || tv == null) {
                continue;
            }
            verfluessiger.add(tk - vl);
            spreizung.add(vl - rl);
            Double a = aussen.get(t);
            if (a != null && a >= 0 && a < 5) {
                verdampfer.add(a - tv);
            }
        }
        // Daily means of water temperature and pressure while running, grouped by month
        Map<java.time.YearMonth, List<double[]>> monate = new TreeMap<>();
        for (Map.Entry<LocalDate, List<double[]>> tag : tage.entrySet()) {
            if (tag.getValue().size() < MIN_FENSTER_TAG) {
                continue;
            }
            monate.computeIfAbsent(java.time.YearMonth.from(tag.getKey()), m -> new ArrayList<>())
                    .add(new double[]{tag.getValue().stream().mapToDouble(x -> x[0]).average().orElse(0),
                            tag.getValue().stream().mapToDouble(x -> x[1]).average().orElse(0)});
        }
        List<Double> schwankung = new ArrayList<>();
        for (Map.Entry<java.time.YearMonth, List<double[]>> monat : monate.entrySet()) {
            List<double[]> m = monat.getValue();
            double min = m.stream().mapToDouble(x -> x[0]).min().orElse(0);
            double max = m.stream().mapToDouble(x -> x[0]).max().orElse(0);
            if (nachfuellMonate.contains(monat.getKey()) || m.size() < MIN_TAGE_MONAT
                    || max - min < MIN_SPANNE_MONAT_K) {
                continue;
            }
            double wasser = m.stream().mapToDouble(x -> x[0]).average().orElse(0);
            double bar = m.stream().mapToDouble(x -> x[1]).average().orElse(0);
            schwankung.add(steigung(m) * 10 / normierung(bar, wasser));
        }
        return new Betrieb(median(verdampfer, MIN_FENSTER), median(verfluessiger, MIN_FENSTER),
                median(spreizung, MIN_FENSTER), median(schwankung, MIN_MONATE_DRUCK));
    }

    /**
     * Factor of the pressure swing at this pressure and water temperature against 1.8 bar and 30 °C: the swing of a
     * system with an expansion vessel grows with the square of the absolute pressure and with the expansion of water
     */
    static double normierung(double bar, double wasser) {
        double druck = Math.pow((bar + LUFTDRUCK_BAR) / (DRUCK_NORM_BAR + LUFTDRUCK_BAR), 2);
        return druck * ausdehnung(wasser) / ausdehnung(30);
    }

    static double ausdehnung(double wasser) {
        double[][] a = AUSDEHNUNG_WASSER;
        if (wasser <= a[0][0]) {
            return a[0][1];
        }
        for (int i = 1; i < a.length; i++) {
            if (wasser <= a[i][0]) {
                return a[i - 1][1] + (wasser - a[i - 1][0]) * (a[i][1] - a[i - 1][1]) / (a[i][0] - a[i - 1][0]);
            }
        }
        return a[a.length - 1][1];
    }

    /**
     * Saturation temperature of R410A at the absolute pressure, null outside the table
     */
    static Double saettigung(double bar) {
        if (bar < R410A[0][0] || bar > R410A[R410A.length - 1][0]) {
            return null;
        }
        for (int i = 1; i < R410A.length; i++) {
            if (bar <= R410A[i][0]) {
                double[] a = R410A[i - 1];
                double[] b = R410A[i];
                return a[1] + (bar - a[0]) * (b[1] - a[1]) / (b[0] - a[0]);
            }
        }
        return null;
    }

    /**
     * Least squares slope of y (index 1) over x (index 0)
     */
    static double steigung(List<double[]> punkte) {
        double mx = punkte.stream().mapToDouble(x -> x[0]).average().orElse(0);
        double my = punkte.stream().mapToDouble(x -> x[1]).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        for (double[] p : punkte) {
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
        }
        return sxx == 0 ? 0 : sxy / sxx;
    }

    static Double median(List<Double> werte, int mindestens) {
        if (werte.size() < mindestens) {
            return null;
        }
        List<Double> s = werte.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    /**
     * Refills in the hourly means of the heating pressure, with the loss per year since the refill before
     */
    static List<Nachfuellung> nachfuellungen(Map<Instant, Double> stuendlich) {
        List<Map.Entry<Instant, Double>> w = new ArrayList<>(new TreeMap<>(stuendlich).entrySet());
        List<Nachfuellung> liste = new ArrayList<>();
        int i = 24;
        while (i < w.size() - 24) {
            if (w.get(i).getValue() - w.get(i - 1).getValue() >= NACHFUELLEN_SPRUNG) {
                double vor = w.subList(i - 24, i).stream().mapToDouble(Map.Entry::getValue).average().orElse(0);
                double nach = w.subList(i + 1, i + 25).stream().mapToDouble(Map.Entry::getValue).average()
                        .orElse(0);
                if (nach - vor >= NACHFUELLEN_DAUERHAFT) {
                    Instant zeit = w.get(i).getKey();
                    Double proJahr = null;
                    if (!liste.isEmpty()) {
                        double jahre = Duration.between(liste.getLast().zeit(), zeit).toDays() / 365.25;
                        proJahr = jahre > 0 ? (nach - vor) / jahre : null;
                    }
                    liste.add(new Nachfuellung(zeit, nach - vor, proJahr));
                    i += 24;
                    continue;
                }
            }
            i++;
        }
        return List.copyOf(liste);
    }

    /**
     * Season from its days
     *
     * @param druck daily means of the heating pressure from November to March
     */
    static Saison saison(String name, boolean laufend, List<WaermebedarfVergleich.Tag> tage, Double fehlstartsJe1000,
                         Map<LocalDate, Double> druck) {
        List<WaermebedarfVergleich.Tag> heiztage = tage.stream().filter(t -> t.aussentemp() < HEIZTAG_BIS
                && t.stromKWh() >= MIN_STROM_KWH).toList();
        return new Saison(name, laufend, heiztage.size(), arbeitszahl(heiztage),
                band(tage, t -> t.aussentemp() >= 0 && t.aussentemp() < 5),
                band(tage, t -> t.aussentemp() >= 5 && t.aussentemp() < 10), fehlstartsJe1000,
                druck.isEmpty() ? null : druck.values().stream().mapToDouble(Double::doubleValue).average()
                        .orElse(0));
    }

    static Band band(List<WaermebedarfVergleich.Tag> tage, Predicate<WaermebedarfVergleich.Tag> imBand) {
        List<WaermebedarfVergleich.Tag> b = tage.stream().filter(imBand).toList();
        int n = b.size();
        if (n < MIN_TAGE) {
            return new Band(n, null, null, null, null);
        }
        double starts = b.stream().mapToInt(WaermebedarfVergleich.Tag::starts).sum();
        double laufzeit = b.stream().mapToDouble(WaermebedarfVergleich.Tag::laufzeitH).sum();
        return new Band(n, arbeitszahl(b), starts / n, starts > 0 ? laufzeit / starts : null,
                b.stream().mapToInt(WaermebedarfVergleich.Tag::abtauungen).sum() / (double) n);
    }

    /**
     * Heat ÷ electricity of the plausible days: in winter 2022/23 the stored heat counter stood still for days and
     * then jumped, which gave days with no heat and days with the heat of several days
     */
    static Double arbeitszahl(List<WaermebedarfVergleich.Tag> tage) {
        List<WaermebedarfVergleich.Tag> mitStrom = tage.stream().filter(Zustand::plausibel).toList();
        double strom = mitStrom.stream().mapToDouble(WaermebedarfVergleich.Tag::stromKWh).sum();
        return mitStrom.size() < MIN_TAGE || strom <= 0 ? null
                : mitStrom.stream().mapToDouble(WaermebedarfVergleich.Tag::waermeKWh).sum() / strom;
    }

    static boolean plausibel(WaermebedarfVergleich.Tag tag) {
        if (tag.stromKWh() < MIN_STROM_KWH) {
            return false;
        }
        double az = tag.waermeKWh() / tag.stromKWh();
        return az >= AZ_MIN && az <= AZ_MAX;
    }

    /**
     * The indicators of ageing and problems, each for the newest season with a value against the seasons before
     */
    static List<Pruefung> pruefungen(List<Saison> saisons) {
        return pruefungen(saisons, List.of());
    }

    static List<Pruefung> pruefungen(List<Saison> saisons, List<Nachfuellung> nachfuellungen) {
        List<Pruefung> p = new ArrayList<>();
        p.add(pruefung("Arbeitszahl bei 0 bis 5 °C", saisons, s -> s.kalt().arbeitszahl(), "", 2, false, 0.95, 0.90,
                "Sinkt sie über Jahre bei gleicher Außentemperatur, arbeiten Verdichter oder Kältekreis schlechter "
                        + "(Kältemittel, Verschleiß, verschmutzter Verdampfer)."));
        p.add(pruefung("Arbeitszahl bei 5 bis 10 °C", saisons, s -> s.mild().arbeitszahl(), "", 2, false, 0.95,
                0.90, "Wie bei 0 bis 5 °C, mit mehr Tagen in milden Wintern."));
        p.add(pruefung("Abtauungen je Tag bei 0 bis 5 °C", saisons, s -> s.kalt().abtauungenProTag(), "", 1, true,
                1.15, 1.30, "Mehr Abtauungen bei gleicher Temperatur deuten auf einen verschmutzten oder vereisten "
                        + "Verdampfer oder auf einen Fühlerfehler hin. Hängen auch von der Luftfeuchte ab."));
        p.add(info(pruefung("Laufzeit je Start bei 0 bis 5 °C", saisons, s -> s.kalt().laufzeitProStart(), "h", 2,
                false, 0.85, 0.70, "Kürzere Läufe heißen mehr Takten. Hängt fast nur von den Einstellungen ab "
                        + "(Wärmebedarf, Mindestlaufzeit, Betriebsart), deshalb nur zur Information.")));
        p.add(pruefung("Fehlstarts je 1000 milde Starts", saisons, Saison::fehlstartsJe1000, "", 1, true, 1.25, 1.50,
                "Der Verdichter läuft nicht im ersten Anlauf an (INV H ROTORVEKTOR), gezählt an Tagen mit 0 bis "
                        + "15 °C. Nimmt das deutlich zu, Verdichter und Inverter vom Kundendienst prüfen lassen."));
        p.add(druck(saisons));
        p.add(differenz(true, "Abstand am Verdampfer bei 0 bis 5 °C", saisons, s -> s.betrieb().verdampferAbstand(), "K",
                1, 1.0, 2.0, "Außenluft minus Verdampfungstemperatur (aus dem Niederdruck) im ruhigen Lauf. Steigt "
                        + "er, nimmt der Verdampfer schlechter Wärme auf: Kältemittel fehlt, die Lamellen sind "
                        + "verschmutzt oder ein Fühler driftet. Hängt kaum von den Einstellungen ab."));
        p.add(differenz(true, "Abstand am Verflüssiger", saisons, s -> s.betrieb().verfluessigerAbstand(), "K", 2, 1.0,
                2.0, "Kondensationstemperatur (aus dem Hochdruck) minus Vorlauf im ruhigen Lauf. Steigt er, "
                        + "gibt der Plattenwärmetauscher die Wärme schlechter ab, zum Beispiel durch Ablagerungen. "
                        + "Hier leicht negativ (−0,1 bis −0,3 K): das Heißgas wärmt das Wasser am Austritt über die "
                        + "Kondensationstemperatur, dazu kommen Fühlertoleranzen. Steigen heißt größer werden, also "
                        + "in Richtung null und darüber; nur die Änderung zählt."));
        p.add(differenz(true, "Spreizung im Lauf", saisons, s -> s.betrieb().spreizung(), "K", 2, 0.5, 1.0,
                "Vorlauf minus Rücklauf im ruhigen Lauf. Steigt sie, fließt weniger Wasser: Schmutzfilter, Pumpe, "
                        + "Luft im System oder zugedrehte Kreise."));
        p.add(pruefung(true, "Druckschwankung je 10 K", saisons, s -> s.betrieb().druckschwankung(), "bar", 3, true,
                1.25, 1.50, "Wie stark der Heizungsdruck mit der Wassertemperatur steigt: je Wintermonat die "
                        + "Tagesmittel des Drucks gegen die der Wassertemperatur, umgerechnet auf 1,8 bar und 30 °C, "
                        + "Median der Monate; Monate mit Nachfüllung oder unter 3 K Spanne zählen nicht. Steigt sie, "
                        + "verliert das Ausdehnungsgefäß Vordruck: dann den Vordruck prüfen lassen."));
        p.add(druckverlust(nachfuellungen));
        return p;
    }

    /**
     * The same check without a status, for values that depend on the settings
     */
    private static Pruefung info(Pruefung p) {
        return new Pruefung(p.name(), p.saison(), p.wert(), p.vorher(), p.einheit(), p.stellen(),
                p.status().equals("zu wenige Daten") ? p.status() : "nur Info", p.hinweis(), p.bezug(),
                "keine Meldung, hängt von den Einstellungen ab", p.verlauf());
    }

    /**
     * Loss of heating pressure per year from the refills: the newest against the mean of the ones before
     */
    static Pruefung druckverlust(List<Nachfuellung> nachfuellungen) {
        String regel = "beobachten ab +25 %, auffällig ab +50 % gegen das Mittel davor oder den Bezug, auffällig "
                + "auch über 0,5 bar im Jahr";
        String hinweis = "Aus den Nachfüllungen: Menge geteilt durch die Zeit seit der Nachfüllung davor. Etwa "
                + "0,2 bar im Jahr sind hier normal. Steigt der Verlust, ist irgendwo eine Undichtigkeit oder das "
                + "Ausdehnungsgefäß lässt nach. Ein neuer Wert entsteht erst mit der nächsten Nachfüllung.";
        List<Nachfuellung> mitWert = nachfuellungen.stream().filter(n -> n.barProJahr() != null).toList();
        Verlauf verlauf = new Verlauf(mitWert.stream().map(n -> datum(n.zeit())).toList(),
                mitWert.stream().map(Nachfuellung::barProJahr).collect(java.util.stream.Collectors.toList()));
        if (mitWert.size() < 2) {
            return new Pruefung("Druckverlust je Jahr", mitWert.isEmpty() ? null : datum(mitWert.getLast().zeit()),
                    mitWert.isEmpty() ? null : mitWert.getLast().barProJahr(), null, "bar", 2, "zu wenige Daten",
                    hinweis, null, regel, verlauf);
        }
        Nachfuellung neu = mitWert.getLast();
        List<Nachfuellung> davor = mitWert.subList(Math.max(0, mitWert.size() - 1 - VERGLEICH_SAISONS),
                mitWert.size() - 1);
        double vorher = davor.stream().mapToDouble(Nachfuellung::barProJahr).average().orElse(0);
        String status = verlustStatus(neu.barProJahr(), vorher);
        Double bezug = null;
        if (mitWert.size() > BEZUG_SAISONS) {
            bezug = mitWert.subList(0, BEZUG_SAISONS).stream().mapToDouble(Nachfuellung::barProJahr).average()
                    .orElse(0);
            status = schlechter(status, verlustStatus(neu.barProJahr(), bezug));
        }
        return new Pruefung("Druckverlust je Jahr", datum(neu.zeit()), neu.barProJahr(), vorher, "bar", 2, status,
                hinweis, bezug, regel, verlauf);
    }

    private static String verlustStatus(double jetzt, double vorher) {
        double faktor = vorher == 0 ? 1 : jetzt / vorher;
        return faktor >= 1.5 || jetzt > 0.5 ? "auffällig" : faktor >= 1.25 ? "beobachten" : "unauffällig";
    }

    static String datum(Instant zeit) {
        return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").format(zeit.atZone(ZONE));
    }

    /**
     * Newest season with a value against the mean of at most VERGLEICH_SAISONS seasons before
     *
     * @param hoeherSchlechter    true if a higher value is worse
     * @param beobachten, auffaellig factors against the mean before, e.g. 0.95 and 0.90 for 5 % and 10 % worse
     */
    static Pruefung pruefung(String name, List<Saison> saisons, Function<Saison, Double> wert, String einheit,
                             int stellen, boolean hoeherSchlechter, double beobachten, double auffaellig,
                             String hinweis) {
        return pruefung(false, name, saisons, wert, einheit, stellen, hoeherSchlechter, beobachten, auffaellig,
                hinweis);
    }

    /**
     * @param langzeit also against the fixed reference of the first seasons, the worse rating counts
     */
    static Pruefung pruefung(boolean langzeit, String name, List<Saison> saisons, Function<Saison, Double> wert,
                             String einheit, int stellen, boolean hoeherSchlechter, double beobachten,
                             double auffaellig, String hinweis) {
        String vorzeichen = hoeherSchlechter ? "+" : "−";
        String regel = "beobachten ab " + vorzeichen + prozent(beobachten) + ", auffällig ab " + vorzeichen
                + prozent(auffaellig) + gegen(langzeit);
        return vergleich(langzeit, name, saisons, wert, einheit, stellen, hinweis, (jetzt, vorher) -> {
            double faktor = vorher == 0 ? 1 : jetzt / vorher;
            return hoeherSchlechter
                    ? faktor >= auffaellig ? "auffällig" : faktor >= beobachten ? "beobachten" : "unauffällig"
                    : faktor <= auffaellig ? "auffällig" : faktor <= beobachten ? "beobachten" : "unauffällig";
        }).mitRegel(regel);
    }

    private static String prozent(double faktor) {
        return Math.round(Math.abs(faktor - 1) * 100) + " %";
    }

    private static String gegen(boolean langzeit) {
        return " gegen das Mittel davor" + (langzeit ? " oder den Bezug" : "");
    }

    /**
     * Like pruefung, but rated by the difference to the mean before, higher is worse
     *
     * @param beobachten, auffaellig differences, e.g. 1 and 2 K
     */
    static Pruefung differenz(boolean langzeit, String name, List<Saison> saisons, Function<Saison, Double> wert,
                              String einheit, int stellen, double beobachten, double auffaellig, String hinweis) {
        String regel = "beobachten ab +" + ZustandSeite.zahl(beobachten, 1) + " " + einheit + ", auffällig ab +"
                + ZustandSeite.zahl(auffaellig, 1) + " " + einheit + gegen(langzeit);
        return vergleich(langzeit, name, saisons, wert, einheit, stellen, hinweis, (jetzt, vorher) ->
                jetzt - vorher >= auffaellig ? "auffällig" : jetzt - vorher >= beobachten ? "beobachten"
                        : "unauffällig").mitRegel(regel);
    }

    /**
     * Newest season with a value against the mean of at most VERGLEICH_SAISONS seasons before with a value; with
     * langzeit also against the mean of the first BEZUG_SAISONS seasons, if the newest is not one of them
     */
    private static Pruefung vergleich(boolean langzeit, String name, List<Saison> saisons,
                                      Function<Saison, Double> wert, String einheit, int stellen, String hinweis,
                                      java.util.function.BiFunction<Double, Double, String> bewertung) {
        List<Saison> mitWert = saisons.stream().filter(s -> wert.apply(s) != null).toList();
        Verlauf verlauf = Verlauf.aus(saisons, wert);
        if (mitWert.size() < 2) {
            return new Pruefung(name, mitWert.isEmpty() ? null : mitWert.getLast().name(),
                    mitWert.isEmpty() ? null : wert.apply(mitWert.getLast()), null, einheit, stellen,
                    "zu wenige Daten", hinweis).mitVerlauf(verlauf);
        }
        Saison neu = mitWert.getLast();
        double jetzt = wert.apply(neu);
        double vorher = mitWert.subList(Math.max(0, mitWert.size() - 1 - VERGLEICH_SAISONS), mitWert.size() - 1)
                .stream().mapToDouble(wert::apply).average().orElse(0);
        String status = bewertung.apply(jetzt, vorher);
        Double bezug = null;
        if (langzeit && mitWert.size() > BEZUG_SAISONS) {
            bezug = mitWert.subList(0, BEZUG_SAISONS).stream().mapToDouble(wert::apply).average().orElse(0);
            status = schlechter(status, bewertung.apply(jetzt, bezug));
        }
        return new Pruefung(name, neu.name() + (neu.laufend() ? " (laufend)" : ""), jetzt, vorher, einheit,
                stellen, status, hinweis, bezug).mitVerlauf(verlauf);
    }

    static String schlechter(String a, String b) {
        return stufe(b) > stufe(a) ? b : a;
    }

    /**
     * Heating pressure in winter: below 1.5 bar it is time to refill, below 1.4 bar the warning is close
     */
    static Pruefung druck(List<Saison> saisons) {
        List<Saison> mitWert = saisons.stream().filter(s -> s.druckWinter() != null).toList();
        String hinweis = "Mittel der Tagesmittel von November bis März. Verliert die Anlage mehr als etwa 0,2 bar "
                + "im Jahr, ist irgendwo eine Undichtigkeit; nach dem Nachfüllen steigt der Wert wieder.";
        String regel = "beobachten unter 1,5 bar oder 0,3 bar weniger als im Winter davor, auffällig unter 1,4 bar";
        Verlauf verlauf = Verlauf.aus(saisons, Saison::druckWinter);
        if (mitWert.isEmpty()) {
            return new Pruefung("Heizungsdruck im Winter", null, null, null, "bar", 2, "zu wenige Daten", hinweis,
                    null, regel, verlauf);
        }
        Saison neu = mitWert.getLast();
        Double vorher = mitWert.size() < 2 ? null : mitWert.get(mitWert.size() - 2).druckWinter();
        String status = neu.druckWinter() < 1.4 ? "auffällig" : neu.druckWinter() < 1.5
                || (vorher != null && vorher - neu.druckWinter() > 0.3) ? "beobachten" : "unauffällig";
        return new Pruefung("Heizungsdruck im Winter", neu.name() + (neu.laufend() ? " (laufend)" : ""),
                neu.druckWinter(), vorher, "bar", 2, status, hinweis, null, regel, verlauf);
    }

    /**
     * 1 July of the season the day belongs to
     */
    static LocalDate saisonStart(LocalDate tag) {
        return LocalDate.of(tag.getMonthValue() >= 7 ? tag.getYear() : tag.getYear() - 1, 7, 1);
    }

    static String name(LocalDate saisonStart) {
        return saisonStart.getYear() + "/" + String.format("%02d", (saisonStart.getYear() + 1) % 100);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
