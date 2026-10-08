package nrw.andresen.stbl.services;

import nrw.andresen.stbl.services.influx.InfluxController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Compares the days before and after a change of the setting Wärmebedarf: compressor starts, run time and heat per
 * day, grouped by the daily mean of the outdoor temperature. The daily values are calculated from InfluxDB every 6
 * hours, only complete days are used.
 */
@Component
public class WaermebedarfVergleich {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    // Inverter power above this means the compressor runs, the same limit as in the analysis of the starts
    private static final double LAEUFT_AB_W = 50;
    // Values every 20 s give 4320 per day, days with gaps of the service are not used
    private static final int MIN_WERTE_PRO_TAG = 3456;
    // Bands of the daily mean outdoor temperature, above the last one the house hardly needs heat
    static final double[] BAENDER = {-5, 0, 5, 10, 15};
    private static final int TAGE_JE_ABFRAGE = 90;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,64}|°C");

    private final Logger logger = LoggerFactory.getLogger(WaermebedarfVergleich.class);
    @Autowired
    private InfluxController influx;
    private final LocalDate start;
    private final String temperaturBucket;
    private final String temperaturEntity;
    private volatile List<Gruppe> gruppen = List.of();
    private volatile List<Tag> tage = List.of();
    private volatile Instant berechnet;

    /**
     * Values of one complete day
     */
    public record Tag(LocalDate datum, double aussentemp, int starts, double laufzeitH, double waermeKWh,
                      double waermebedarf) {
    }

    /**
     * Mean values of the days of one temperature band with one setting, bis is the last of these days
     */
    public record Gruppe(String band, double waermebedarf, int tage, double aussentemp, double startsProTag,
                         double laufzeitProTag, double laufzeitProStart, double waermeProTag, double leistungKW,
                         LocalDate bis) {
    }

    /**
     * @param start            first day of the comparison
     * @param temperaturBucket bucket of the outdoor temperature, empty for the bucket of the service
     * @param temperaturEntity Home Assistant entity of the outdoor temperature (measurement °C), empty for the
     *                         value WP_Aussentemp of the heat pump, which is stored only since 2026-09-12
     */
    public WaermebedarfVergleich(@Value("${auswertung.start:2023-07-01}") String start,
                                 @Value("${auswertung.aussentemp.bucket:}") String temperaturBucket,
                                 @Value("${auswertung.aussentemp.entity:}") String temperaturEntity) {
        this.start = LocalDate.parse(start);
        this.temperaturBucket = temperaturBucket;
        this.temperaturEntity = temperaturEntity;
    }

    /**
     * Calculates the comparison one minute after the start and then every 6 hours, takes about half a minute
     */
    @Scheduled(initialDelay = 60000, fixedRate = 6 * 3600000)
    public void berechnen() {
        try {
            tage = tage();
            gruppen = vergleich(tage);
            berechnet = Instant.now();
        } catch (Exception e) {
            logger.warn("Comparison of the setting Waermebedarf failed: " + e.getMessage());
        }
    }

    public List<Gruppe> getGruppen() {
        return gruppen;
    }

    /**
     * Complete days of the last calculation
     */
    public List<Tag> getTage() {
        return tage;
    }

    /**
     * Bucket of the outdoor temperature if it is not the bucket of the service, otherwise null
     */
    public String getTemperaturBucket() {
        return temperaturEntity.isEmpty() || temperaturBucket.isEmpty() ? null : temperaturBucket;
    }

    /**
     * Daily mean of the outdoor temperature from the configured source
     */
    public Map<LocalDate, Double> aussentemperatur(Instant von, Instant bis) {
        String bucket = influx.getBucket();
        return temperaturEntity.isEmpty()
                ? tageswerte(von, bis, bucket, "WP_Aussentemp", null, "mean()", "")
                : tageswerte(von, bis, temperaturBucket.isEmpty() ? bucket : temperaturBucket, "°C",
                temperaturEntity, "mean()", "");
    }

    public LocalDate getStart() {
        return start;
    }

    public Instant getBerechnet() {
        return berechnet;
    }

    private List<Tag> tage() {
        String bucket = influx.getBucket();
        Instant von = start.atStartOfDay(ZONE).toInstant();
        Instant bis = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Map<LocalDate, Double> werte = tageswerte(von, bis, bucket, "WP_LeistungInverter", null, "count()", "");
        Map<LocalDate, Double> starts = tageswerte(von, bis, bucket, "WP_LeistungInverter", null, "count()",
                " |> map(fn: (r) => ({r with _value: if r._value > " + LAEUFT_AB_W + " then 1 else 0}))"
                        + " |> difference() |> filter(fn: (r) => r._value == 1)");
        Map<LocalDate, Double> laufzeit = tageswerte(von, bis, bucket, "WP_LeistungInverter", null,
                "integral(unit: 1h)",
                " |> map(fn: (r) => ({r with _value: if r._value > " + LAEUFT_AB_W + " then 1.0 else 0.0}))");
        Map<LocalDate, Double> waerme = tageswerte(von, bis, bucket, "WP_AbgabeWaerme", null, "spread()", "");
        Map<LocalDate, Double> temperatur = aussentemperatur(von, bis);
        Map<LocalDate, Double> einstellung = tageswerte(von, bis, bucket, "WP_Einstellung_Waermebedarf", null,
                "last()", "");
        return tage(werte, starts, laufzeit, waerme, temperatur, einstellung);
    }

    /**
     * Joins the daily values. Days with gaps or without temperature are skipped. The setting is taken from the last
     * stored day before; before the first stored setting (2026-09-22) the first stored value is used.
     */
    static List<Tag> tage(Map<LocalDate, Double> werte, Map<LocalDate, Double> starts,
                          Map<LocalDate, Double> laufzeit, Map<LocalDate, Double> waerme,
                          Map<LocalDate, Double> temperatur, Map<LocalDate, Double> einstellung) {
        TreeMap<LocalDate, Double> einstellungen = new TreeMap<>(einstellung);
        List<Tag> tage = new ArrayList<>();
        if (einstellungen.isEmpty()) {
            return tage;
        }
        for (Map.Entry<LocalDate, Double> tag : new TreeMap<>(werte).entrySet()) {
            LocalDate datum = tag.getKey();
            Double aussentemp = temperatur.get(datum);
            if (tag.getValue() < MIN_WERTE_PRO_TAG || aussentemp == null || !waerme.containsKey(datum)) {
                continue;
            }
            Map.Entry<LocalDate, Double> gueltig = einstellungen.floorEntry(datum);
            double waermebedarf = gueltig != null ? gueltig.getValue() : einstellungen.firstEntry().getValue();
            tage.add(new Tag(datum, aussentemp, starts.getOrDefault(datum, 0d).intValue(),
                    laufzeit.getOrDefault(datum, 0d), waerme.get(datum) * 1000, waermebedarf));
        }
        return tage;
    }

    /**
     * Groups the days by temperature band and setting, the coldest band first
     */
    static List<Gruppe> vergleich(List<Tag> tage) {
        Map<String, List<Tag>> gruppiert = new LinkedHashMap<>();
        tage.stream()
                .filter(tag -> band(tag.aussentemp()) >= 0)
                .sorted(Comparator.comparingInt((Tag tag) -> band(tag.aussentemp()))
                        .thenComparing(Comparator.comparingDouble(Tag::waermebedarf).reversed()))
                .forEach(tag -> gruppiert.computeIfAbsent(band(tag.aussentemp()) + "|" + tag.waermebedarf(),
                        k -> new ArrayList<>()).add(tag));
        List<Gruppe> gruppen = new ArrayList<>();
        for (List<Tag> gruppe : gruppiert.values()) {
            int n = gruppe.size();
            double starts = gruppe.stream().mapToInt(Tag::starts).sum();
            double laufzeit = gruppe.stream().mapToDouble(Tag::laufzeitH).sum();
            double waerme = gruppe.stream().mapToDouble(Tag::waermeKWh).sum();
            Tag erster = gruppe.get(0);
            gruppen.add(new Gruppe(bandName(band(erster.aussentemp())), erster.waermebedarf(), n,
                    gruppe.stream().mapToDouble(Tag::aussentemp).average().orElse(0), starts / n, laufzeit / n,
                    starts > 0 ? laufzeit / starts : 0, waerme / n, laufzeit > 0 ? waerme / laufzeit : 0,
                    gruppe.stream().map(Tag::datum).max(Comparator.naturalOrder()).orElse(null)));
        }
        return gruppen;
    }

    /**
     * One row per temperature band: the setting used last and, if the band also has days with another setting, the
     * one used before it
     */
    public record Vergleich(String band, Gruppe vorher, Gruppe nachher) {
    }

    /**
     * Groups by band for the table, the coldest band first
     */
    static List<Vergleich> vorherNachher(List<Gruppe> gruppen) {
        Map<String, List<Gruppe>> jeBand = new LinkedHashMap<>();
        gruppen.forEach(g -> jeBand.computeIfAbsent(g.band(), k -> new ArrayList<>()).add(g));
        List<Vergleich> zeilen = new ArrayList<>();
        for (Map.Entry<String, List<Gruppe>> band : jeBand.entrySet()) {
            List<Gruppe> zeitlich = band.getValue().stream().sorted(Comparator.comparing(Gruppe::bis)).toList();
            Gruppe nachher = zeitlich.get(zeitlich.size() - 1);
            Gruppe vorher = zeitlich.size() > 1 ? zeitlich.get(zeitlich.size() - 2) : null;
            zeilen.add(new Vergleich(band.getKey(), vorher, nachher));
        }
        return zeilen;
    }

    public List<Vergleich> getVergleich() {
        return vorherNachher(gruppen);
    }

    /**
     * Index of the band of the daily mean temperature, -1 above the last band
     */
    static int band(double aussentemp) {
        for (int i = 0; i < BAENDER.length; i++) {
            if (aussentemp < BAENDER[i]) {
                return i;
            }
        }
        return -1;
    }

    static String bandName(int band) {
        if (band == 0) {
            return "unter " + (int) BAENDER[0] + " °C";
        }
        return (int) BAENDER[band - 1] + " bis " + (int) BAENDER[band] + " °C";
    }

    /**
     * One value per local day from the measurement, e.g. count() or mean(). The filter is put in front of the
     * aggregation.
     */
    private Map<LocalDate, Double> tageswerte(Instant von, Instant bis, String bucket, String measurement,
                                              String entity, String funktion, String filter) {
        Map<LocalDate, Double> tage = new TreeMap<>();
        // In parts, a query over years takes longer than the InfluxDB client waits
        for (LocalDate tag = von.atZone(ZONE).toLocalDate(); tag.atStartOfDay(ZONE).toInstant().isBefore(bis);
             tag = tag.plusDays(TAGE_JE_ABFRAGE)) {
            Instant teilBis = tag.plusDays(TAGE_JE_ABFRAGE).atStartOfDay(ZONE).toInstant();
            influx.werte(flux(tag.atStartOfDay(ZONE).toInstant(), teilBis.isAfter(bis) ? bis : teilBis, bucket,
                            measurement, entity, funktion, filter))
                    .forEach((zeit, wert) -> tage.put(zeit.atZone(ZONE).toLocalDate(), wert));
        }
        return tage;
    }

    static String flux(Instant von, Instant bis, String bucket, String measurement, String entity,
                       String funktion, String filter) {
        for (String name : new String[]{bucket, measurement, entity == null ? "x" : entity}) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Ungültiger Name: " + name);
            }
        }
        return "import \"timezone\"\n"
                + "option location = timezone.location(name: \"" + ZONE.getId() + "\")\n"
                + "from(bucket: \"" + bucket + "\") |> range(start: " + von + ", stop: " + bis + ")"
                + " |> filter(fn: (r) => r._measurement == \"" + measurement + "\" and r._field == \"value\""
                + (entity == null ? "" : " and r.entity_id == \"" + entity + "\"") + ")"
                + filter
                + " |> aggregateWindow(every: 1d, fn: (tables=<-, column) => tables |> " + funktion
                + ", createEmpty: false, timeSrc: \"_start\")"
                + " |> keep(columns: [\"_time\", \"_value\"])";
    }
}
