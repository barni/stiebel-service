package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.Fehlerliste;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Weekly report: the last 7 complete days next to the 7 days before and to what is usual at the same outdoor
 * temperature. Shown on its own page and sent by mail on Sunday evening. Uses the stored values, so it needs
 * InfluxDB.
 */
@Component
public class Wochenbericht {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final int TAGE = 7;
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.");
    private static final DateTimeFormatter DATUM_JAHR = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    // Starts within this share of the usual number count as usual
    static final double UEBLICH_TOLERANZ = 0.2;
    // A change of the heating pressure below this is no change, the daily mean moves that much with the temperature
    static final double DRUCK_STABIL_BAR = 0.03;
    private static final Duration NEU_NACH = Duration.ofHours(1);

    private final Logger logger = LoggerFactory.getLogger(Wochenbericht.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private Tagesuebersicht tagesuebersicht;
    @Autowired
    private WaermebedarfVergleich vergleich;
    @Autowired
    private CanBus can;
    @Autowired
    private EmailService mail;
    private final boolean mailSenden;
    private volatile Bericht bericht;
    // The last report was calculated with the comparison of the days
    private volatile boolean mitVergleich;

    /**
     * Sums of one week; aussentemp, vorlaufMax and the pressures are null if unknown
     */
    public record Woche(LocalDate von, LocalDate bis, Double aussentemp, double waermeKWh, double stromKWh,
                        int starts, double laufzeitH, int abtauungen, Double vorlaufMax, Double druckAnfang,
                        Double druckEnde, List<Tagesuebersicht.Tag> tage) {

        public Double arbeitszahl() {
            return stromKWh > 0 ? waermeKWh / stromKWh : null;
        }

        public Double laufzeitProStart() {
            return starts > 0 ? laufzeitH / starts : null;
        }
    }

    /**
     * What the same 7 days usually need: for each day the mean of the earlier days with a similar outdoor
     * temperature, added up
     */
    public record Ueblich(double waermeKWh, double starts, double laufzeitH, double abtauungen) {

        public Double laufzeitProStart() {
            return starts > 0 ? laufzeitH / starts : null;
        }
    }

    /**
     * @param ueblich    null if one of the days has no outdoor temperature or no similar days
     * @param neueFehler entries of the fault list dated within the week
     */
    public record Bericht(Woche woche, Woche vorwoche, Ueblich ueblich, int neueFehler, Instant berechnet) {
    }

    public Wochenbericht(@Value("${bericht.woche.mail:true}") boolean mailSenden) {
        this.mailSenden = mailSenden;
    }

    /**
     * Checked every 2 minutes, starting 3 minutes after the start: the report is calculated if there is none yet,
     * after an hour, and once more as soon as the comparison of the days is there. That comparison reads several
     * years and can take longer than the first report; without it the column of the usual values stays empty.
     */
    @Scheduled(initialDelay = 180000, fixedRate = 120000)
    public void berechnen() {
        if (!influx.isAktiv()) {
            return;
        }
        Bericht alt = bericht;
        boolean vergleichDa = vergleich.getBerechnet() != null;
        if (alt != null && alt.berechnet().isAfter(Instant.now().minus(NEU_NACH))
                && (mitVergleich || !vergleichDa)) {
            return;
        }
        rechnen();
    }

    private synchronized void rechnen() {
        try {
            boolean vergleichDa = vergleich.getBerechnet() != null;
            bericht = bericht(LocalDate.now(ZONE));
            mitVergleich = vergleichDa;
        } catch (Exception e) {
            logger.warn("Weekly report could not be calculated: " + e.getMessage());
        }
    }

    /**
     * Sends the report on Sunday at 20:00 for the 7 days up to Saturday
     */
    @Scheduled(cron = "0 0 20 * * SUN", zone = "Europe/Berlin")
    public void senden() {
        if (!mailSenden || !influx.isAktiv()) {
            return;
        }
        rechnen();
        Bericht b = bericht;
        if (b == null) {
            logger.warn("Weekly report not sent, it could not be calculated");
            return;
        }
        mail.sendAlert("Wärmepumpe: Wochenbericht " + zeitraum(b.woche()), text(b));
    }

    /**
     * Last calculated report, null before the first calculation or without InfluxDB
     */
    public Bericht getBericht() {
        return bericht;
    }

    private Bericht bericht(LocalDate heute) {
        LocalDate von = heute.minusDays(TAGE);
        LocalDate vorher = von.minusDays(TAGE);
        Instant start = vorher.atStartOfDay(ZONE).toInstant();
        Instant ende = heute.atStartOfDay(ZONE).toInstant();
        Map<LocalDate, Double> temperatur = vergleich.aussentemperatur(start, ende);
        Map<LocalDate, Double> druck = tageswerte(start, ende, "WP_Heizungsdruck", "mean()");
        Map<LocalDate, Double> vorlauf = tageswerte(start, ende, "WP_VorlaufIstTemp", "max()");
        List<Tagesuebersicht.Tag> tage = new ArrayList<>();
        for (LocalDate tag = vorher; tag.isBefore(heute); tag = tag.plusDays(1)) {
            tage.add(tagesuebersicht.tag(tag, temperatur.get(tag)));
        }
        Woche woche = woche(tage.subList(TAGE, 2 * TAGE), druck, vorlauf);
        // Only days before the week count as usual, the week is not compared with itself
        List<WaermebedarfVergleich.Tag> frueher = vergleich.getTage().stream()
                .filter(t -> t.datum().isBefore(von)).toList();
        return new Bericht(woche, woche(tage.subList(0, TAGE), druck, vorlauf), ueblich(woche.tage(), frueher),
                neueFehler(can.getFehlerliste().eintraege(), von, heute), Instant.now());
    }

    private Map<LocalDate, Double> tageswerte(Instant von, Instant bis, String measurement, String funktion) {
        Map<LocalDate, Double> werte = new TreeMap<>();
        influx.werte(WaermebedarfVergleich.flux(von, bis, influx.getBucket(), measurement, null, funktion, ""))
                .forEach((zeit, wert) -> werte.put(zeit.atZone(ZONE).toLocalDate(), wert));
        return werte;
    }

    /**
     * Sums of the days; the outdoor temperature is the mean of the days that have one
     *
     * @param druck   daily mean of the heating pressure
     * @param vorlauf daily maximum of the flow temperature
     */
    static Woche woche(List<Tagesuebersicht.Tag> tage, Map<LocalDate, Double> druck, Map<LocalDate, Double> vorlauf) {
        LocalDate von = tage.get(0).datum();
        LocalDate bis = tage.get(tage.size() - 1).datum();
        Double vorlaufMax = tage.stream().map(t -> vorlauf.get(t.datum())).filter(v -> v != null)
                .max(Double::compare).orElse(null);
        return new Woche(von, bis,
                tage.stream().filter(t -> t.aussentemp() != null).mapToDouble(Tagesuebersicht.Tag::aussentemp)
                        .average().stream().boxed().findFirst().orElse(null),
                tage.stream().mapToDouble(Tagesuebersicht.Tag::waermeKWh).sum(),
                tage.stream().mapToDouble(Tagesuebersicht.Tag::stromKWh).sum(),
                tage.stream().mapToInt(Tagesuebersicht.Tag::starts).sum(),
                tage.stream().mapToDouble(Tagesuebersicht.Tag::laufzeitH).sum(),
                tage.stream().mapToInt(Tagesuebersicht.Tag::abtauungen).sum(),
                vorlaufMax, druck.get(von), druck.get(bis), List.copyOf(tage));
    }

    /**
     * Usual values of the week, null if a day cannot be compared
     */
    static Ueblich ueblich(List<Tagesuebersicht.Tag> tage, List<WaermebedarfVergleich.Tag> frueher) {
        double waerme = 0;
        double starts = 0;
        double laufzeit = 0;
        double abtauungen = 0;
        for (Tagesuebersicht.Tag tag : tage) {
            Tagesuebersicht.Vergleichstage gleich = tag.aussentemp() == null ? null
                    : Tagesuebersicht.aehnlicheTage(frueher, tag.datum(), tag.aussentemp());
            if (gleich == null) {
                return null;
            }
            waerme += gleich.waermeKWh();
            starts += gleich.starts();
            laufzeit += gleich.laufzeitH();
            abtauungen += gleich.abtauungen();
        }
        return new Ueblich(waerme, starts, laufzeit, abtauungen);
    }

    /**
     * Entries of the fault list with a time from von (included) to bis (excluded)
     */
    static int neueFehler(List<Fehlerliste.Eintrag> eintraege, LocalDate von, LocalDate bis) {
        return (int) eintraege.stream().filter(e -> e.zeit() != null)
                .filter(e -> !e.zeit().toLocalDate().isBefore(von) && e.zeit().toLocalDate().isBefore(bis)).count();
    }

    /**
     * One sentence about the week: temperature against the week before, starts against the usual number, heating
     * pressure and new faults
     */
    public static String satz(Bericht b) {
        List<String> teile = new ArrayList<>();
        Woche w = b.woche();
        if (w.aussentemp() != null && b.vorwoche().aussentemp() != null) {
            double diff = w.aussentemp() - b.vorwoche().aussentemp();
            teile.add(Math.abs(diff) < 1 ? "Etwa so warm wie in den 7 Tagen davor"
                    : zahl(Math.abs(diff), 1) + " K " + (diff < 0 ? "kälter" : "wärmer") + " als in den 7 Tagen davor");
        }
        if (b.ueblich() != null && b.ueblich().starts() >= 1) {
            double anteil = w.starts() / b.ueblich().starts();
            String ueblich = " (" + w.starts() + " statt " + zahl(b.ueblich().starts(), 0) + ")";
            teile.add(anteil < 1 - UEBLICH_TOLERANZ ? "weniger Starts als bei dieser Temperatur üblich" + ueblich
                    : anteil > 1 + UEBLICH_TOLERANZ ? "mehr Starts als bei dieser Temperatur üblich" + ueblich
                    : "Starts wie bei dieser Temperatur üblich");
        }
        if (w.druckAnfang() != null && w.druckEnde() != null) {
            double diff = w.druckEnde() - w.druckAnfang();
            teile.add(Math.abs(diff) < DRUCK_STABIL_BAR ? "Heizungsdruck stabil"
                    : "Heizungsdruck um " + zahl(Math.abs(diff), 2) + " bar " + (diff < 0 ? "gefallen" : "gestiegen"));
        }
        teile.add(b.neueFehler() == 0 ? "keine neuen Einträge in der Fehlerliste"
                : b.neueFehler() + (b.neueFehler() == 1 ? " neuer Eintrag" : " neue Einträge") + " in der Fehlerliste");
        String text = String.join(", ", teile) + ".";
        return text.substring(0, 1).toUpperCase(Locale.GERMANY) + text.substring(1);
    }

    /**
     * Rows of the comparison: label, this week, the week before, usual
     */
    public static List<List<String>> zeilen(Bericht b) {
        Woche w = b.woche();
        Woche v = b.vorwoche();
        Ueblich u = b.ueblich();
        List<List<String>> zeilen = new ArrayList<>();
        zeilen.add(List.of("Außentemperatur °C", zahl(w.aussentemp(), 1), zahl(v.aussentemp(), 1), "gleich"));
        zeilen.add(List.of("Wärme kWh", zahl(w.waermeKWh(), 0), zahl(v.waermeKWh(), 0),
                u == null ? "–" : zahl(u.waermeKWh(), 0)));
        zeilen.add(List.of("Strom kWh", zahl(w.stromKWh(), 1), zahl(v.stromKWh(), 1), "–"));
        zeilen.add(List.of("Arbeitszahl", zahl(w.arbeitszahl(), 1), zahl(v.arbeitszahl(), 1), "–"));
        zeilen.add(List.of("Laufzeit h", zahl(w.laufzeitH(), 1), zahl(v.laufzeitH(), 1),
                u == null ? "–" : zahl(u.laufzeitH(), 1)));
        zeilen.add(List.of("Starts", String.valueOf(w.starts()), String.valueOf(v.starts()),
                u == null ? "–" : zahl(u.starts(), 0)));
        zeilen.add(List.of("h je Start", zahl(w.laufzeitProStart(), 1), zahl(v.laufzeitProStart(), 1),
                u == null ? "–" : zahl(u.laufzeitProStart(), 1)));
        zeilen.add(List.of("Abtauungen", String.valueOf(w.abtauungen()), String.valueOf(v.abtauungen()),
                u == null ? "–" : zahl(u.abtauungen(), 0)));
        zeilen.add(List.of("Höchster Vorlauf °C", zahl(w.vorlaufMax(), 1), zahl(v.vorlaufMax(), 1), "–"));
        zeilen.add(List.of("Heizungsdruck bar", druck(w), druck(v), "–"));
        return zeilen;
    }

    /**
     * Rows of the days of the week: day, outdoor temperature, heat, power, starts, hours per start, defrosts
     */
    public static List<List<String>> tageszeilen(Woche woche) {
        List<List<String>> zeilen = new ArrayList<>();
        DateTimeFormatter tag = DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMANY);
        for (Tagesuebersicht.Tag t : woche.tage()) {
            zeilen.add(List.of(t.datum().format(tag), zahl(t.aussentemp(), 1), zahl(t.waermeKWh(), 0),
                    zahl(t.stromKWh(), 1), String.valueOf(t.starts()), zahl(t.laufzeitProStart(), 1),
                    String.valueOf(t.abtauungen())));
        }
        return zeilen;
    }

    /**
     * Report as plain text for the mail
     */
    public static String text(Bericht b) {
        StringBuilder text = new StringBuilder("Letzte 7 Tage: " + zeitraum(b.woche()) + "\n\n" + satz(b) + "\n\n"
                + "Letzte 7 Tage (7 Tage davor, " + zeitraum(b.vorwoche())
                + " / üblich bei gleicher Außentemperatur):\n");
        for (List<String> zeile : zeilen(b)) {
            text.append("- ").append(zeile.get(0)).append(": ").append(zeile.get(1)).append(" (")
                    .append(zeile.get(2)).append(" / ").append(zeile.get(3)).append(")\n");
        }
        text.append("\nDie Tage (außen °C, Wärme kWh, Strom kWh, Starts, h je Start, Abtauungen):\n");
        for (List<String> zeile : tageszeilen(b.woche())) {
            text.append("- ").append(String.join(" | ", zeile)).append("\n");
        }
        return text.append("\nStrom ist die geschätzte Wirkleistung des Verdichters ohne Pumpe und Regelung, die "
                + "Arbeitszahl daraus liegt deshalb etwas zu hoch. Ein Start zählt nach mindestens 3 Minuten "
                + "Stillstand, also ohne die Wiederanläufe nach einer Abtauung.\n").toString();
    }

    public static String zeitraum(Woche woche) {
        return woche.von().format(DATUM) + "–" + woche.bis().format(DATUM_JAHR);
    }

    /**
     * Period without the year, for the head of a column
     */
    public static String zeitraumKurz(Woche woche) {
        return woche.von().format(DATUM) + "–" + woche.bis().format(DATUM);
    }

    private static String druck(Woche woche) {
        return woche.druckAnfang() == null || woche.druckEnde() == null ? "–"
                : zahl(woche.druckAnfang(), 2) + " → " + zahl(woche.druckEnde(), 2);
    }

    static String zahl(Double wert, int stellen) {
        return wert == null ? "–" : String.format(Locale.GERMANY, "%." + stellen + "f", wert);
    }
}
