package nrw.andresen.stbl.services;

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

    private final Logger logger = LoggerFactory.getLogger(Tagesuebersicht.class);
    @Autowired
    private InfluxController influx;
    @Autowired
    private WaermebedarfVergleich vergleich;
    private volatile Tag heute;
    private volatile Tag gestern;
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
    public record Vergleichstage(int tage, double aussentemp, double starts, double laufzeitH, double waermeKWh) {

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
                aehnlich.stream().mapToDouble(WaermebedarfVergleich.Tag::waermeKWh).sum() / n);
    }

    private Tag tag(LocalDate datum, Instant von, Instant bis, Double aussentemp) {
        String bucket = influx.getBucket();
        return tag(datum, aussentemp, influx.werte(roh(bucket, "WP_LeistungInverter", von, bis)),
                influx.werte(roh(bucket, "WP_Abtauung", von, bis)),
                influx.werte(roh(bucket, "WP_AbgabeWaerme", von, bis)));
    }

    /**
     * Day from the stored values: starts are changes from standstill to running, the run time and the energy add up
     * the time to the next value
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
        for (int i = 0; i < werte.size(); i++) {
            double va = werte.get(i).getValue();
            boolean laeuft = va > LAEUFT_AB_W;
            if (laeuft && i > 0 && werte.get(i - 1).getValue() <= LAEUFT_AB_W) {
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

    private static String roh(String bucket, String measurement, Instant von, Instant bis) {
        return "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\")"
                + " |> keep(columns: [\"_time\", \"_value\"])";
    }
}
