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

    private final Logger logger = LoggerFactory.getLogger(Zustand.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    @Autowired
    private Fehlstarts fehlstarts;
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
                         Double fehlstartsJe1000, Double druckWinter) {
    }

    /**
     * One indicator: the newest season with enough days against the mean of the seasons before
     *
     * @param status "unauffällig", "beobachten", "auffällig" or "zu wenige Daten"
     */
    public record Pruefung(String name, String saison, Double wert, Double vorher, String einheit, int stellen,
                           String status, String hinweis) {
    }

    /**
     * @param unvollstaendig a season is missing because its queries failed, it is tried again in 30 minutes
     */
    public record Ergebnis(List<Saison> saisons, List<Pruefung> pruefungen, boolean unvollstaendig,
                           Instant berechnet) {
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
            for (LocalDate von = saisonStart(start); von.isBefore(heute); von = von.plusYears(1)) {
                LocalDate bis = von.plusYears(1);
                String name = name(von);
                boolean laufend = bis.isAfter(heute);
                Saison saison = laufend ? null : fertig.get(name);
                if (saison == null) {
                    try {
                        saison = saison(name, laufend, von, laufend ? heute : bis, fehlstartRate.get(name));
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
            ergebnis = new Ergebnis(List.copyOf(saisons), pruefungen(saisons), unvollstaendig, Instant.now());
        } catch (Exception e) {
            logger.warn("Condition over the seasons could not be calculated: " + e.getMessage());
        }
    }

    /**
     * Last calculated result, null before the first calculation or without InfluxDB
     */
    public Ergebnis getErgebnis() {
        return ergebnis;
    }

    private Saison saison(String name, boolean laufend, LocalDate von, LocalDate bis, Double fehlstartsJe1000) {
        List<WaermebedarfVergleich.Tag> tage = vergleich.tage(von.atStartOfDay(ZONE).toInstant(),
                bis.atStartOfDay(ZONE).toInstant());
        Map<LocalDate, Double> druck = vergleich.tagesmittel("WP_Heizungsdruck",
                von.withMonth(11).withDayOfMonth(1).atStartOfDay(ZONE).toInstant(),
                min(bis, von.plusYears(1).withMonth(4).withDayOfMonth(1)).atStartOfDay(ZONE).toInstant());
        return saison(name, laufend, tage, fehlstartsJe1000, druck);
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
        List<Pruefung> p = new ArrayList<>();
        p.add(pruefung("Arbeitszahl bei 0 bis 5 °C", saisons, s -> s.kalt().arbeitszahl(), "", 2, false, 0.95, 0.90,
                "Sinkt sie über Jahre bei gleicher Außentemperatur, arbeiten Verdichter oder Kältekreis schlechter "
                        + "(Kältemittel, Verschleiß, verschmutzter Verdampfer)."));
        p.add(pruefung("Arbeitszahl bei 5 bis 10 °C", saisons, s -> s.mild().arbeitszahl(), "", 2, false, 0.95,
                0.90, "Wie bei 0 bis 5 °C, mit mehr Tagen in milden Wintern."));
        p.add(pruefung("Abtauungen je Tag bei 0 bis 5 °C", saisons, s -> s.kalt().abtauungenProTag(), "", 1, true,
                1.15, 1.30, "Mehr Abtauungen bei gleicher Temperatur deuten auf einen verschmutzten oder vereisten "
                        + "Verdampfer oder auf einen Fühlerfehler hin. Hängen auch von der Luftfeuchte ab."));
        p.add(pruefung("Laufzeit je Start bei 0 bis 5 °C", saisons, s -> s.kalt().laufzeitProStart(), "h", 2, false,
                0.85, 0.70, "Kürzere Läufe heißen mehr Takten. Hängt stark von den Einstellungen ab (Wärmebedarf, "
                        + "Mindestlaufzeit, Betriebsart), eine Änderung dort erklärt einen Sprung."));
        p.add(pruefung("Fehlstarts je 1000 Starts", saisons, Saison::fehlstartsJe1000, "", 1, true, 1.25, 1.50,
                "Der Verdichter läuft nicht im ersten Anlauf an (INV H ROTORVEKTOR). Nimmt das deutlich zu, "
                        + "Verdichter und Inverter vom Kundendienst prüfen lassen."));
        p.add(druck(saisons));
        return p;
    }

    /**
     * Newest season with a value against the mean of all seasons before with a value
     *
     * @param hoeherSchlechter    true if a higher value is worse
     * @param beobachten, auffaellig factors against the mean before, e.g. 0.95 and 0.90 for 5 % and 10 % worse
     */
    static Pruefung pruefung(String name, List<Saison> saisons, Function<Saison, Double> wert, String einheit,
                             int stellen, boolean hoeherSchlechter, double beobachten, double auffaellig,
                             String hinweis) {
        List<Saison> mitWert = saisons.stream().filter(s -> wert.apply(s) != null).toList();
        if (mitWert.size() < 2) {
            return new Pruefung(name, mitWert.isEmpty() ? null : mitWert.getLast().name(),
                    mitWert.isEmpty() ? null : wert.apply(mitWert.getLast()), null, einheit, stellen,
                    "zu wenige Daten", hinweis);
        }
        Saison neu = mitWert.getLast();
        double jetzt = wert.apply(neu);
        double vorher = mitWert.subList(0, mitWert.size() - 1).stream().mapToDouble(wert::apply).average()
                .orElse(0);
        double faktor = vorher == 0 ? 1 : jetzt / vorher;
        String status = hoeherSchlechter
                ? faktor >= auffaellig ? "auffällig" : faktor >= beobachten ? "beobachten" : "unauffällig"
                : faktor <= auffaellig ? "auffällig" : faktor <= beobachten ? "beobachten" : "unauffällig";
        return new Pruefung(name, neu.name() + (neu.laufend() ? " (laufend)" : ""), jetzt, vorher, einheit,
                stellen, status, hinweis);
    }

    /**
     * Heating pressure in winter: below 1.5 bar it is time to refill, below 1.4 bar the warning is close
     */
    static Pruefung druck(List<Saison> saisons) {
        List<Saison> mitWert = saisons.stream().filter(s -> s.druckWinter() != null).toList();
        String hinweis = "Mittel der Tagesmittel von November bis März. Verliert die Anlage mehr als etwa 0,2 bar "
                + "im Jahr, ist irgendwo eine Undichtigkeit; nach dem Nachfüllen steigt der Wert wieder.";
        if (mitWert.isEmpty()) {
            return new Pruefung("Heizungsdruck im Winter", null, null, null, "bar", 2, "zu wenige Daten", hinweis);
        }
        Saison neu = mitWert.getLast();
        Double vorher = mitWert.size() < 2 ? null : mitWert.get(mitWert.size() - 2).druckWinter();
        String status = neu.druckWinter() < 1.4 ? "auffällig" : neu.druckWinter() < 1.5
                || (vorher != null && vorher - neu.druckWinter() > 0.3) ? "beobachten" : "unauffällig";
        return new Pruefung("Heizungsdruck im Winter", neu.name() + (neu.laufend() ? " (laufend)" : ""),
                neu.druckWinter(), vorher, "bar", 2, status, hinweis);
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
