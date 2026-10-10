package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.can.ValueContainer;
import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Values of today and yesterday from the stored 20 s values: compressor starts and run time, heat, estimated
 * electric energy of the compressor, efficiency and defrosts. Yesterday is compared with the days of similar outdoor
 * temperature from the comparison of the setting Wärmebedarf.
 */
@Component
public class Tagesuebersicht {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    // Inverter power above this means the compressor runs, the same limit as in the comparison
    private static final double LAEUFT_AB_W = 50;
    // A longer gap between two values is counted only with this time, e.g. a restart of the service
    private static final Duration MAX_ABSTAND = Duration.ofSeconds(60);
    // Days with a daily mean within this range of yesterday count as similar
    static final double AEHNLICH_K = 1.5;
    // The pressure swing is only meaningful on a real heating day: the whole floor has to warm up and cool down,
    // not only the sensors in the heat pump. Checked with the days of January 2026: 0.17 to 0.32 bar per 10 K.
    static final double DRUCKHUB_MIN_LAUFZEIT_H = 12;
    static final double DRUCKHUB_MIN_TEMPERATURHUB_K = 5;

    private final Logger logger = LoggerFactory.getLogger(Tagesuebersicht.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    private volatile Tag heute;
    private volatile Tag gestern;
    private volatile Double druckhubGestern;
    private LocalDate druckhubGespeichert;
    private volatile Instant berechnet;

    /**
     * Values of one day; aussentemp is null if unknown
     */
    public record Tag(LocalDate datum, Double aussentemp, int starts, double laufzeitH, double waermeKWh,
                      double stromKWh, int abtauungen) {

        public Double laufzeitProStart() {
            return starts > 0 ? laufzeitH / starts : null;
        }

        public Double arbeitszahl() {
            return stromKWh > 0 ? waermeKWh / stromKWh : null;
        }
    }

    /**
     * Mean values of the days with similar outdoor temperature
     */
    public record Vergleichstage(int tage, double aussentemp, double starts, double laufzeitH, double waermeKWh,
                                 double abtauungen) {

        public Double laufzeitProStart() {
            return starts > 0 ? laufzeitH / starts : null;
        }
    }

    /**
     * Calculates today and yesterday every 10 minutes, starting 90 s after the start
     */
    @Scheduled(initialDelay = 90000, fixedRate = 600000)
    public void berechnen() {
        try {
            LocalDate datum = LocalDate.now(ZONE);
            Instant mitternacht = datum.atStartOfDay(ZONE).toInstant();
            Instant vorgestern = datum.minusDays(1).atStartOfDay(ZONE).toInstant();
            Instant jetzt = Instant.now();
            Map<LocalDate, Double> temperatur = vergleich.aussentemperatur(vorgestern, jetzt);
            gestern = tag(datum.minusDays(1), vorgestern, mitternacht, temperatur.get(datum.minusDays(1)));
            heute = tag(datum, mitternacht, jetzt, temperatur.get(datum));
            druckhub(datum.minusDays(1), vorgestern, mitternacht);
            berechnet = jetzt;
        } catch (Exception e) {
            logger.warn("Daily overview failed: " + e.getMessage());
        }
    }

    public Tag getHeute() {
        return heute;
    }

    public Tag getGestern() {
        return gestern;
    }

    /**
     * Pressure swing of yesterday in bar per 10 K, null if yesterday was no heating day
     */
    public Double getDruckhubGestern() {
        return druckhubGestern;
    }

    public Instant getBerechnet() {
        return berechnet;
    }

    /**
     * Days of the comparison with a daily mean close to yesterday, null without yesterday's temperature or days
     */
    public Vergleichstage getAehnlicheTage() {
        Tag tag = gestern;
        if (tag == null || tag.aussentemp() == null) {
            return null;
        }
        return aehnlicheTage(vergleich.getTage(), tag.datum(), tag.aussentemp());
    }

    static Vergleichstage aehnlicheTage(List<WaermebedarfVergleich.Tag> tage, LocalDate ohne, double aussentemp) {
        List<WaermebedarfVergleich.Tag> aehnlich = tage.stream()
                .filter(t -> !t.datum().equals(ohne) && Math.abs(t.aussentemp() - aussentemp) <= AEHNLICH_K)
                .toList();
        if (aehnlich.isEmpty()) {
            return null;
        }
        int n = aehnlich.size();
        return new Vergleichstage(n, aehnlich.stream().mapToDouble(WaermebedarfVergleich.Tag::aussentemp).sum() / n,
                aehnlich.stream().mapToInt(WaermebedarfVergleich.Tag::starts).sum() / (double) n,
                aehnlich.stream().mapToDouble(WaermebedarfVergleich.Tag::laufzeitH).sum() / n,
                aehnlich.stream().mapToDouble(WaermebedarfVergleich.Tag::waermeKWh).sum() / n,
                aehnlich.stream().mapToInt(WaermebedarfVergleich.Tag::abtauungen).sum() / (double) n);
    }

    private Tag tag(LocalDate datum, Instant von, Instant bis, Double aussentemp) {
        String bucket = influx.getBucket();
        return tag(datum, aussentemp, influx.werte(roh(bucket, "WP_LeistungInverter", von, bis)),
                influx.werte(roh(bucket, "WP_Abtauung", von, bis)),
                influx.werte(roh(bucket, "WP_AbgabeWaerme", von, bis)));
    }

    /**
     * Day from the stored values: starts are changes from standstill to running after a standstill of at least 3
     * minutes (not the restart after a defrost), the run time and the energy add up the time to the next value
     *
     * @param leistung apparent power of the inverter in VA
     * @param abtauung 1 during a defrost, 0 otherwise
     * @param waerme   heat counter in MWh
     */
    static Tag tag(LocalDate datum, Double aussentemp, Map<Instant, Double> leistung, Map<Instant, Double> abtauung,
                   Map<Instant, Double> waerme) {
        int starts = 0;
        double laufzeitS = 0;
        double energieWs = 0;
        List<Map.Entry<Instant, Double>> werte = new ArrayList<>(leistung.entrySet());
        Instant stopp = null;
        for (int i = 0; i < werte.size(); i++) {
            double va = werte.get(i).getValue();
            boolean laeuft = va > LAEUFT_AB_W;
            boolean liefVorher = i > 0 && werte.get(i - 1).getValue() > LAEUFT_AB_W;
            if (!laeuft && liefVorher) {
                stopp = werte.get(i).getKey();
            }
            // The restart after the short break of a defrost belongs to the same run
            if (laeuft && i > 0 && !liefVorher && (stopp == null || Duration.between(stopp, werte.get(i).getKey())
                    .compareTo(Fehlstarts.MIN_STILLSTAND_START) >= 0)) {
                starts++;
            }
            if (laeuft && i + 1 < werte.size()) {
                Duration abstand = Duration.between(werte.get(i).getKey(), werte.get(i + 1).getKey());
                double sekunden = Math.min(abstand.toMillis(), MAX_ABSTAND.toMillis()) / 1000d;
                laufzeitS += sekunden;
                energieWs += Waermepumpe.wirkleistung(va) * sekunden;
            }
        }
        int abtauungen = 0;
        Double vorher = null;
        for (double wert : abtauung.values()) {
            if (wert > 0 && vorher != null && vorher <= 0) {
                abtauungen++;
            }
            vorher = wert;
        }
        double waermeKWh = waerme.isEmpty() ? 0 : (waerme.values().stream().mapToDouble(Double::doubleValue).max()
                .orElse(0) - waerme.values().stream().mapToDouble(Double::doubleValue).min().orElse(0)) * 1000;
        return new Tag(datum, aussentemp, starts, laufzeitS / 3600, waermeKWh, energieWs / 3600 / 1000, abtauungen);
    }

    /**
     * Calculates the pressure swing of the day and stores it once as WP_DruckhubJe10K with the start of the day
     */
    private void druckhub(LocalDate datum, Instant von, Instant bis) {
        Tag tag = gestern;
        String bucket = influx.getBucket();
        Double wert = tag == null ? null : druckhub(influx.werte(minutenmittel(bucket, "WP_Heizungsdruck", von, bis)),
                influx.werte(minutenmittel(bucket, "WP_VorlaufIstTemp", von, bis)),
                influx.werte(minutenmittel(bucket, "WP_RuecklaufIstTemp", von, bis)), tag.laufzeitH());
        druckhubGestern = wert;
        if (wert != null && !datum.equals(druckhubGespeichert)) {
            influx.storePoints(List.of(influx.createPoint("DruckhubJe10K", new ValueContainer<>(wert, von))));
            druckhubGespeichert = datum;
        }
    }

    /**
     * Pressure swing of the heating circuit: span of the pressure of the day divided by the span of the mean water
     * temperature, in bar per 10 K. An expansion vessel that loses its gas charge has a smaller gas cushion, the
     * same expansion of the water then changes the pressure more, so the value rises over the months.
     *
     * @param druck     pressure in bar, one mean value per minute
     * @param vorlauf   flow temperature in °C per minute
     * @param ruecklauf return temperature in °C per minute
     * @param laufzeitH run time of the compressor on this day
     * @return null on days with little heating or without values
     */
    static Double druckhub(Map<Instant, Double> druck, Map<Instant, Double> vorlauf, Map<Instant, Double> ruecklauf,
                           double laufzeitH) {
        if (laufzeitH < DRUCKHUB_MIN_LAUFZEIT_H || druck.isEmpty()) {
            return null;
        }
        double tMin = Double.MAX_VALUE;
        double tMax = -Double.MAX_VALUE;
        for (Map.Entry<Instant, Double> v : vorlauf.entrySet()) {
            Double r = ruecklauf.get(v.getKey());
            if (r != null) {
                double mittel = (v.getValue() + r) / 2;
                tMin = Math.min(tMin, mittel);
                tMax = Math.max(tMax, mittel);
            }
        }
        if (tMax - tMin < DRUCKHUB_MIN_TEMPERATURHUB_K) {
            return null;
        }
        double pMin = druck.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double pMax = druck.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return (pMax - pMin) / (tMax - tMin) * 10;
    }

    private static String minutenmittel(String bucket, String measurement, Instant von, Instant bis) {
        return "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)"
                + " |> keep(columns: [\"_time\", \"_value\"])";
    }

    private static String roh(String bucket, String measurement, Instant von, Instant bis) {
        return "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> keep(columns: [\"_time\", \"_value\"])";
    }
}
