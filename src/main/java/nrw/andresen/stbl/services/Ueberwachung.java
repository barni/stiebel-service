package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.CanStatistik;
import nrw.andresen.stbl.services.can.Fehlerliste;
import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks the heat pump and the service every minute and sends a mail when a warning becomes active. Every 15 minutes
 * (and shortly after the start) the service checks whether InfluxDB, the USBtin and the mail work.
 */
@Component
public class Ueberwachung {

    // The pressure has to come back this much within the limit before a new warning is possible
    private static final double DRUCK_HYSTERESE_BAR = 0.1;
    // A USBtin without answer for this time counts as failed
    private static final Duration MAX_OHNE_ANTWORT = Duration.ofSeconds(60);
    // The mean pressure of the last 24 hours is compared with the 24 hours before
    private static final Duration DRUCK_FENSTER = Duration.ofHours(24);
    // The warning for a falling pressure ends when the drop is this much smaller
    private static final double ABFALL_HYSTERESE_BAR = 0.05;
    // A mean needs values for this share of its time span, e.g. not right after a start of the service
    private static final double MIN_ABDECKUNG = 0.8;

    private final Logger logger = LoggerFactory.getLogger(Ueberwachung.class);
    @Autowired
    private Waermepumpe wp;
    @Autowired
    private CanBus can;
    @Autowired
    private EmailService mail;
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;

    private final double druckMin;
    private final double druckMax;
    private final double antwortquoteMin;
    private final int startsProStundeMax;
    private final double hochdruckMax;
    private final double spreizungMax;
    private final double frostAussentemp;
    private final Duration frostOhneLauf;
    private final double druckAbfallMax;
    private final double niederdruckMin;
    private final double heissgasMax;
    private final double spannungMin;
    private final double spannungMax;
    private final Warnung druck;
    private final Warnung druckHoch;
    private final Warnung hochdruck;
    private final Warnung spreizung;
    private final Warnung keinHeizbetrieb;
    private final Warnung druckAbfall;
    private final Warnung niederdruck;
    private final Warnung heissgas;
    private final Warnung abtauung;
    private final Warnung spannung;
    private final Warnung heizstab;
    private final Warnung antwortquote;
    private final Warnung starts;
    private final Warnung dienst;
    // Compressor starts counter of the last hour
    private final Deque<ValueContainer<Double>> startsVerlauf = new ArrayDeque<>();
    // Outdoor temperature and heating pressure once a minute, for the means over hours
    private final Deque<Messung> aussentempVerlauf = new ArrayDeque<>();
    private final Deque<Messung> druckVerlauf = new ArrayDeque<>();
    // Last minute with the compressor running, at first the first check after the start of the service
    private Instant letzterLauf;
    private volatile List<Pruefung> pruefungen = List.of();
    // Last known value of each setting and the last changes as text, the newest first
    private final Map<String, Double> einstellungen = new HashMap<>();
    private final Deque<String> einstellungsaenderungen = new ArrayDeque<>();
    private volatile Instant geprueft;

    /**
     * Result of one check of the service
     */
    public record Pruefung(String name, boolean ok, String text, String erklaerung) {
    }

    /**
     * A value at a time, for the means over the last hours
     */
    record Messung(Instant zeit, double wert) {
    }

    public Ueberwachung(@Value("${warnung.heizungsdruck.min:1.3}") double druckMin,
                        @Value("${warnung.heizungsdruck.max:2.5}") double druckMax,
                        @Value("${warnung.antwortquote.min:90}") double antwortquoteMin,
                        @Value("${warnung.starts.proStunde:3}") int startsProStundeMax,
                        @Value("${warnung.hochdruck.max:38}") double hochdruckMax,
                        @Value("${warnung.spreizung.max:10}") double spreizungMax,
                        @Value("${warnung.frost.aussentemp:2}") double frostAussentemp,
                        @Value("${warnung.frost.stunden:4}") double frostStunden,
                        @Value("${warnung.heizungsdruck.abfall:0.15}") double druckAbfallMax,
                        @Value("${warnung.niederdruck.min:2.5}") double niederdruckMin,
                        @Value("${warnung.heissgas.max:110}") double heissgasMax,
                        @Value("${warnung.abtauung.minuten:15}") int abtauungMinuten,
                        @Value("${warnung.spannung.min:195}") double spannungMin,
                        @Value("${warnung.spannung.max:255}") double spannungMax) {
        this.druckMin = druckMin;
        this.druckMax = druckMax;
        this.antwortquoteMin = antwortquoteMin;
        this.startsProStundeMax = startsProStundeMax;
        this.hochdruckMax = hochdruckMax;
        this.spreizungMax = spreizungMax;
        this.frostAussentemp = frostAussentemp;
        this.frostOhneLauf = Duration.ofMinutes(Math.round(frostStunden * 60));
        this.druckAbfallMax = druckAbfallMax;
        this.niederdruckMin = niederdruckMin;
        this.heissgasMax = heissgasMax;
        this.spannungMin = spannungMin;
        this.spannungMax = spannungMax;
        hochdruck = new Warnung("Hochdruck hoch", "Hochdruck im Kältekreis über " + (int) hochdruckMax
                + " bar; im Betrieb sind es sonst höchstens etwa 30 bar, der Hochdruckwächter schaltet bei 45 bar ab. "
                + "Meist fließt zu wenig Heizwasser durch die Wärmepumpe (Heizkreise zu, Luft, Pumpe).",
                Duration.ofMinutes(1));
        spreizung = new Warnung("Spreizung groß", "Vorlauf mehr als " + (int) spreizungMax
                + " K über dem Rücklauf, 5 Minuten lang bei laufendem Verdichter (Soll 5 K). Es fließt zu wenig "
                + "Heizwasser durch die Wärmepumpe: Heizkreise, Entlüftung und Pumpe prüfen. So war es am "
                + "24.04.2021 (Vorlauf bis 62 °C, Hochdruck 44 bar).", Duration.ofMinutes(5));
        druck = new Warnung("Heizungsdruck niedrig", "Heizungsdruck unter " + zahl(druckMin)
                + " bar. Wasser nachfüllen; die Warnung endet ab " + zahl(druckMin + DRUCK_HYSTERESE_BAR) + " bar.",
                Duration.ofMinutes(5));
        druckHoch = new Warnung("Heizungsdruck hoch", "Heizungsdruck über " + zahl(druckMax)
                + " bar, z. B. zu viel nachgefüllt oder Ausdehnungsgefäß ohne Vordruck. Das Sicherheitsventil öffnet "
                + "meist bei 3 bar; die Warnung endet ab " + zahl(druckMax - DRUCK_HYSTERESE_BAR) + " bar.",
                Duration.ofMinutes(5));
        keinHeizbetrieb = new Warnung("Keine Wärme bei Frost", "Der Verdichter lief " + kurz(frostStunden)
                + " Stunden nicht, bei im Mittel unter " + kurz(frostAussentemp) + " °C außen. Bei Frost lief er "
                + "2024 bis 2026 nie länger als 2 Stunden nicht. Störung im Display des WPM, EVU-Sperre und "
                + "Sicherungen prüfen.", Duration.ZERO);
        druckAbfall = new Warnung("Heizungsdruck fällt", "Der Heizungsdruck lag im Mittel der letzten 24 Stunden "
                + "mehr als " + zahl(druckAbfallMax) + " bar unter dem Mittel der 24 Stunden davor (2024 bis 2026 "
                + "höchstens 0,08 bar, sonst etwa 0,2 bar im Jahr). Hinweis auf ein Leck: Heizkreisverteiler, "
                + "Sicherheitsventil und Wärmepumpe auf Wasser prüfen.", Duration.ZERO);
        niederdruck = new Warnung("Niederdruck niedrig", "Niederdruck im Kältekreis unter " + kurz(niederdruckMin)
                + " bar, 10 Minuten lang bei laufendem Verdichter (2024 bis 2026 bei Frost mindestens 3,2 bar). "
                + "Hinweis auf zu wenig Kältemittel oder einen vereisten Verdampfer.", Duration.ofMinutes(10));
        heissgas = new Warnung("Heißgas heiß", "Heißgastemperatur über " + (int) heissgasMax
                + " °C, 5 Minuten lang (seit 10/2026 höchstens 88 °C). Hinweis auf zu wenig Kältemittel; der "
                + "Verdichter wird zu heiß.", Duration.ofMinutes(5));
        abtauung = new Warnung("Abtauung dauert lange", "Eine Abtauung läuft länger als " + abtauungMinuten
                + " Minuten (sonst 2 bis 6 Minuten). Verdampfer auf Eis und den Ablauf des Tauwassers prüfen.",
                Duration.ofMinutes(abtauungMinuten));
        spannung = new Warnung("Netzspannung", "Spannung am Inverter unter " + (int) spannungMin + " oder über "
                + (int) spannungMax + " V, 5 Minuten lang (2024 bis 2026 203 bis 245 V; der Inverter zeigt etwa "
                + "11 V zu wenig an).", Duration.ofMinutes(5));
        heizstab = new Warnung("Heizstab läuft", "Stufe 1 oder 2 des Heizstabs (DHC) ist an. Das kam bisher "
                + "praktisch nie vor, der Heizstab braucht für dieselbe Wärme ein Vielfaches an Strom.",
                Duration.ZERO);
        antwortquote = new Warnung("Antwortquote niedrig", "Weniger als " + (int) antwortquoteMin
                + " % der Anfragen beantwortet, 10 Minuten lang. Hinweis auf Probleme am CAN-Bus.",
                Duration.ofMinutes(10));
        starts = new Warnung("Viele Verdichterstarts", "Mehr als " + startsProStundeMax
                + " Verdichterstarts in der letzten Stunde (Takten). Bei Frost sind es sonst etwa 1 bis 1,5.",
                Duration.ZERO);
        dienst = new Warnung("Dienstprüfung fehlgeschlagen", "Eine Prüfung des Dienstes schlägt fehl, z. B. "
                + "InfluxDB nicht erreichbar oder Schreiben/Lesen nicht erlaubt.", Duration.ofMinutes(15));
    }

    public List<Warnung> getWarnungen() {
        return List.of(druck, druckHoch, druckAbfall, keinHeizbetrieb, hochdruck, niederdruck, heissgas, spreizung,
                abtauung, spannung, heizstab, antwortquote, starts, dienst);
    }

    /**
     * Up to five changes of the settings seen since the start of the service, the newest first
     */
    public synchronized List<String> getEinstellungsaenderungen() {
        return List.copyOf(einstellungsaenderungen);
    }

    /**
     * Compares the settings with the values known before, 5 minutes after the start and then every hour. After a
     * start the values stored in InfluxDB are the reference, so a change while the service was stopped is found too.
     */
    @Scheduled(initialDelay = 300000, fixedRate = 3600000)
    public synchronized void einstellungenPruefen() {
        List<String> geaendert = new ArrayList<>();
        Instant jetzt = Instant.now();
        for (Waermepumpe.Einstellung einstellung : wp.getEinstellungen()) {
            Double wert;
            try {
                ValueContainer<Double> container = einstellung.wert().call();
                if (container.getTimestamp().isBefore(jetzt.minus(Waermepumpe.MAX_AGE_3600))) {
                    continue;
                }
                wert = container.getValue();
            } catch (Exception e) {
                continue;
            }
            Double vorher = einstellungen.get(einstellung.name());
            if (vorher == null && influx.isAktiv()) {
                try {
                    vorher = influx.letzterWert("Einstellung_" + einstellung.name());
                } catch (Exception e) {
                    // no reference, the next hour compares with this value
                }
            }
            einstellungen.put(einstellung.name(), wert);
            String aenderung = aenderung(einstellung, vorher, wert);
            if (aenderung != null) {
                geaendert.add(aenderung);
            }
        }
        if (!geaendert.isEmpty()) {
            String text = String.join("\n", geaendert);
            logger.warn("Settings changed: " + text.replace("\n", "; "));
            mail.sendAlert("Wärmepumpe: Einstellung geändert", "Geänderte Einstellungen im Wärmepumpenmanager:\n"
                    + text + "\n\nDie Auswirkung zeigen der Vergleich Einstellung Wärmebedarf und die Starts auf der "
                    + "Statusseite.");
            String zeit = Fehlerliste.ZEITFORMAT.format(jetzt.atZone(ZoneId.systemDefault()));
            for (String aenderung : geaendert) {
                einstellungsaenderungen.addFirst(zeit + " " + aenderung);
            }
            while (einstellungsaenderungen.size() > 5) {
                einstellungsaenderungen.removeLast();
            }
        }
    }

    /**
     * Text of a change, e.g. "Steigung Heizkurve: 0,35 → 0,40", null if the value is unchanged or unknown before
     */
    static String aenderung(Waermepumpe.Einstellung einstellung, Double vorher, double wert) {
        if (vorher == null || Math.abs(vorher - wert) < 1e-6) {
            return null;
        }
        if (einstellung.anzeige() != null) {
            return einstellung.label() + ": " + einstellung.text(vorher) + " → " + einstellung.text(wert);
        }
        String format = "%." + einstellung.decimals() + "f";
        String einheit = einstellung.unit().isEmpty() ? "" : " " + einstellung.unit();
        return einstellung.label() + ": " + String.format(Locale.GERMANY, format, vorher) + " → "
                + String.format(Locale.GERMANY, format, wert) + einheit;
    }

    public List<Pruefung> getPruefungen() {
        return pruefungen;
    }

    public Instant getGeprueft() {
        return geprueft;
    }

    /**
     * Checks the values every minute, from the second minute after the start
     */
    @Scheduled(initialDelay = 120000, fixedRate = 60000)
    public synchronized void pruefen() {
        Instant jetzt = Instant.now();
        try {
            double bar = wp.getHeizungsdruck().getValue();
            melden(druck, bar < druckMin + (druck.isAktiv() ? DRUCK_HYSTERESE_BAR : 0),
                    "Heizungsdruck " + zahl(bar) + " bar", jetzt);
            melden(druckHoch, bar > druckMax - (druckHoch.isAktiv() ? DRUCK_HYSTERESE_BAR : 0),
                    "Heizungsdruck " + zahl(bar) + " bar", jetzt);
            merken(druckVerlauf, new Messung(jetzt, bar), DRUCK_FENSTER.multipliedBy(2));
            Double letzte = mittel(druckVerlauf, jetzt.minus(DRUCK_FENSTER), jetzt);
            Double davor = mittel(druckVerlauf, jetzt.minus(DRUCK_FENSTER.multipliedBy(2)), jetzt.minus(DRUCK_FENSTER));
            if (letzte != null && davor != null) {
                double grenze = druckAbfallMax - (druckAbfall.isAktiv() ? ABFALL_HYSTERESE_BAR : 0);
                melden(druckAbfall, davor - letzte > grenze,
                        "Heizungsdruck im Mittel " + zahl(letzte) + " bar, 24 Stunden davor " + zahl(davor) + " bar",
                        jetzt);
            }
        } catch (Exception e) {
            // no current value, keep the state
        }
        boolean laeuft = false;
        try {
            laeuft = wp.getVerdichterDrehzahl().getValue() > 0;
        } catch (Exception e) {
            // unknown, counts as not running
        }
        if (laeuft || letzterLauf == null) {
            letzterLauf = jetzt;
        }
        try {
            merken(aussentempVerlauf, new Messung(jetzt, wp.getAussentemp().getValue()), frostOhneLauf);
            Double aussen = mittel(aussentempVerlauf, jetzt.minus(frostOhneLauf), jetzt);
            if (aussen != null) {
                melden(keinHeizbetrieb, keinHeizbetrieb(letzterLauf, aussen, jetzt, frostOhneLauf, frostAussentemp),
                        "Kein Verdichterlauf seit " + Fehlerliste.ZEITFORMAT.format(letzterLauf.atZone(
                                ZoneId.systemDefault())) + ", außen im Mittel " + kurz(aussen) + " °C", jetzt);
            }
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            double bar = wp.getNiederdruck().getValue();
            melden(niederdruck, laeuft && bar < niederdruckMin, "Niederdruck " + kurz(bar) + " bar", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            double grad = wp.getHeissgasTemp().getValue();
            melden(heissgas, grad > heissgasMax, "Heißgas " + kurz(grad) + " °C", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            melden(abtauung, wp.isAbtauung().getValue(), "Abtauung läuft", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            double volt = wp.getSpannungInverter().getValue();
            melden(spannung, volt < spannungMin || volt > spannungMax, "Spannung " + kurz(volt) + " V", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            double bar = wp.getHochdruck().getValue();
            melden(hochdruck, bar > hochdruckMax, "Hochdruck " + zahl(bar) + " bar", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            // Only while the compressor heats: during the defrost the flow is colder than the return
            double kelvin = wp.getSpreizung().getValue();
            melden(spreizung, laeuft && kelvin > spreizungMax, "Spreizung " + zahl(kelvin) + " K", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            boolean an = wp.isDHC_1On().getValue() || wp.isDHC_2On().getValue();
            melden(heizstab, an, "Heizstab an", jetzt);
        } catch (Exception e) {
            // no current value, keep the state
        }
        try {
            double quote = can.minute(CanStatistik.Minute::antwortquote).getValue();
            melden(antwortquote, quote < antwortquoteMin, "Antwortquote " + (int) quote + " %", jetzt);
        } catch (Exception e) {
            // no complete minute yet
        }
        try {
            Integer proStunde = startsInDerLetztenStunde(startsVerlauf, wp.getVerdichterStarts(), jetzt);
            if (proStunde != null) {
                melden(starts, proStunde > startsProStundeMax, proStunde + " Starts in der letzten Stunde", jetzt);
            }
        } catch (Exception e) {
            // no current value, keep the state
        }
        List<String> fehler = pruefungen.stream().filter(p -> !p.ok() && !p.name().equals("Mail"))
                .map(p -> p.name() + ": " + p.text()).toList();
        melden(dienst, !fehler.isEmpty(), String.join("; ", fehler), jetzt);
    }

    private void melden(Warnung warnung, boolean bedingung, String text, Instant jetzt) {
        if (warnung.pruefen(bedingung, text, jetzt)) {
            logger.warn("Warning " + warnung.getName() + ": " + text);
            mail.sendAlert("Wärmepumpe: " + warnung.getName(), text + "\n\n" + warnung.getRegel());
        }
    }

    /**
     * True if the compressor did not run for the given time while the mean outdoor temperature was below the limit
     */
    static boolean keinHeizbetrieb(Instant letzterLauf, double aussenMittel, Instant jetzt, Duration ohneLauf,
                                   double grenze) {
        return !letzterLauf.isAfter(jetzt.minus(ohneLauf)) && aussenMittel < grenze;
    }

    /**
     * Adds the value and removes the values older than the given time span
     */
    static void merken(Deque<Messung> verlauf, Messung messung, Duration spanne) {
        verlauf.addLast(messung);
        while (verlauf.peekFirst().zeit().isBefore(messung.zeit().minus(spanne))) {
            verlauf.removeFirst();
        }
    }

    /**
     * Mean of the values from (exclusive) to (inclusive), null if there are values for less than 80 % of the
     * minutes, e.g. right after the start of the service
     */
    static Double mittel(Deque<Messung> verlauf, Instant von, Instant bis) {
        double summe = 0;
        int anzahl = 0;
        for (Messung messung : verlauf) {
            if (messung.zeit().isAfter(von) && !messung.zeit().isAfter(bis)) {
                summe += messung.wert();
                anzahl++;
            }
        }
        long minuten = Duration.between(von, bis).toMinutes();
        return anzahl == 0 || anzahl < MIN_ABDECKUNG * minuten ? null : summe / anzahl;
    }

    /**
     * Starts within the last hour from the counter, null until the counter was seen for 55 minutes. Keeps the
     * values of the last 65 minutes in the history.
     */
    static Integer startsInDerLetztenStunde(Deque<ValueContainer<Double>> verlauf, ValueContainer<Double> zaehler,
                                            Instant jetzt) {
        if (verlauf.isEmpty() || !verlauf.peekLast().getTimestamp().equals(zaehler.getTimestamp())) {
            verlauf.addLast(zaehler);
        }
        while (verlauf.size() > 1 && verlauf.peekFirst().getTimestamp().isBefore(jetzt.minus(Duration.ofMinutes(65)))) {
            verlauf.removeFirst();
        }
        ValueContainer<Double> aelter = null;
        for (ValueContainer<Double> wert : verlauf) {
            if (!wert.getTimestamp().isAfter(jetzt.minus(Duration.ofMinutes(55)))) {
                aelter = wert;
            }
        }
        if (aelter == null) {
            return null;
        }
        // The counter of the heat pump is reset only by a service technician, a smaller value counts as 0
        return (int) Math.max(0, zaehler.getValue() - aelter.getValue());
    }

    /**
     * Checks InfluxDB (if it is used), the USBtin and the mail 30 s after the start and then every 15 minutes
     */
    @Scheduled(initialDelay = 30000, fixedRate = 900000)
    public void dienstPruefen() {
        List<Pruefung> liste = new ArrayList<>();
        if (influx.isAktiv()) {
            influxPruefen(liste);
        }
        liste.add(adapter());
        boolean mailOk = mail.isKonfiguriert() && mail.getLetzterFehler() == null;
        liste.add(new Pruefung("Mail", mailOk, !mail.isKonfiguriert() ? "nicht eingerichtet"
                : mail.getLetzterFehler() == null ? "OK" : "letzte Mail fehlgeschlagen: " + mail.getLetzterFehler(),
                "Warnmails an stbl.mail.to über spring.mail.host. Ohne Mail stehen die Warnungen nur hier."));
        pruefungen = liste;
        geprueft = Instant.now();
    }

    private void influxPruefen(List<Pruefung> liste) {
        boolean erreichbar = false;
        try {
            erreichbar = influx.erreichbar();
        } catch (Exception e) {
            // shown as not reachable
        }
        liste.add(new Pruefung("InfluxDB erreichbar", erreichbar, erreichbar ? "OK" : "keine Antwort",
                "Der Dienst erreicht den InfluxDB-Server unter influx.url."));
        liste.add(lesen("InfluxDB lesen", influx.getBucket(), "Lesen aus dem Bucket " + influx.getBucket()
                + ", nötig für die Verläufe und die Auswertungen."));
        String temperaturBucket = vergleich.getTemperaturBucket();
        if (temperaturBucket != null) {
            liste.add(lesen("Außentemperatur lesen", temperaturBucket, "Lesen der Außentemperatur aus dem Bucket "
                    + temperaturBucket + " für den Vergleich der Einstellung Wärmebedarf und die Tagesübersicht."));
        }
        liste.add(schreiben());
    }

    private Pruefung lesen(String name, String bucket, String erklaerung) {
        try {
            influx.lesen(bucket);
            return new Pruefung(name, true, "OK", erklaerung);
        } catch (Exception e) {
            return new Pruefung(name, false, e.getMessage(), erklaerung);
        }
    }

    private Pruefung schreiben() {
        String erklaerung = "Schreiben der Messwerte, alle 20 s gesammelt und alle 5 s gesendet.";
        Instant erfolg = influx.getLetzterSchreiberfolg();
        Instant fehler = influx.getLetzterSchreibfehler();
        if (fehler != null && (erfolg == null || fehler.isAfter(erfolg))) {
            return new Pruefung("InfluxDB schreiben", false, influx.getSchreibfehler(), erklaerung);
        }
        if (erfolg == null) {
            // Right after the start or without values from the heat pump, not an error of InfluxDB
            return new Pruefung("InfluxDB schreiben", true, "noch nichts geschrieben", erklaerung);
        }
        return new Pruefung("InfluxDB schreiben", true, "OK", erklaerung);
    }

    private Pruefung adapter() {
        String erklaerung = "USBtin verbunden und Antwort der Wärmepumpe innerhalb der letzten "
                + MAX_OHNE_ANTWORT.toSeconds() + " s.";
        if (!can.getStatistik().isVerbunden()) {
            return new Pruefung("USB-Adapter", false, "nicht verbunden", erklaerung);
        }
        Instant antwort = can.getStatistik().getLetzteAntwort();
        if (antwort == null || antwort.isBefore(Instant.now().minus(MAX_OHNE_ANTWORT))) {
            return new Pruefung("USB-Adapter", false, "keine Antwort der Wärmepumpe", erklaerung);
        }
        return new Pruefung("USB-Adapter", true, "OK", erklaerung);
    }

    private static String zahl(double wert) {
        return String.format(Locale.GERMANY, "%.2f", wert);
    }

    /**
     * Number with at most one decimal, without ",0", e.g. "4" or "2,5"
     */
    static String kurz(double wert) {
        return String.format(Locale.GERMANY, "%.1f", wert).replace(",0", "");
    }
}
