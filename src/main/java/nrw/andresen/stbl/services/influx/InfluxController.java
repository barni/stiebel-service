package nrw.andresen.stbl.services.influx;

import com.influxdb.LogLevel;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.InfluxDBClientOptions;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import nrw.andresen.stbl.services.can.ValueContainer;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class InfluxController {

    // Only plain names are put into the Flux query, everything else is rejected
    private static final Pattern SERIES_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private InfluxDBClient client;
    private String bucket;
    private String org;
    private WriteApi writeApi;

    /**
     * Time series of one stored value, times in epoch milliseconds
     */
    public record History(String name, long[] time, double[] value) {
    }

    public InfluxController(@Value("${influx.url}") String influxURL, @Value("${influx.token}") String token,
                            @Value("${influx.org}") String org, @Value("${influx.bucket}") String bucket){
        this.bucket = bucket;
        this.org = org;
        // Daily values over several months take longer than the default of 10 s
        OkHttpClient.Builder http = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(60));
        client = InfluxDBClientFactory.create(InfluxDBClientOptions.builder().url(influxURL)
                        .authenticateToken(token.toCharArray()).org(org).bucket(bucket).okHttpClient(http).build())
                .setLogLevel(LogLevel.NONE);
        writeApi = client.getWriteApi(WriteOptions.builder().flushInterval(5_000).build());

    }

    public void storePoints(List<Point> points){
        if (points.isEmpty()) {
            return;
        }
        writeApi.writePoints(bucket, org, points);

    }

    /**
     * Creates a point with the time the value was received, so outdated values are not stored as current ones
     */
    public Point createPoint(String name, ValueContainer<Double> value){
        return Point.measurement("WP_"+ name)
                .time(value.getTimestamp(), WritePrecision.NS)
                .addField("value", value.getValue());

    }

    /**
     * Mean values of the stored value name (without the prefix WP_) over the given range, one per window.
     * The token needs read access to the bucket.
     */
    public History history(String name, Duration range, Duration window) {
        List<FluxRecord> records = new ArrayList<>();
        for (FluxTable table : client.getQueryApi().query(flux(bucket, name, range, window), org)) {
            records.addAll(table.getRecords());
        }
        records.sort(Comparator.comparing(FluxRecord::getTime));
        long[] time = new long[records.size()];
        double[] value = new double[records.size()];
        for (int i = 0; i < records.size(); i++) {
            Instant t = records.get(i).getTime();
            time[i] = t == null ? 0 : t.toEpochMilli();
            value[i] = ((Number) records.get(i).getValue()).doubleValue();
        }
        return new History(name, time, value);
    }

    /**
     * Values of a Flux query with one value per time, e.g. daily values, sorted by time. Missing values are skipped.
     */
    public Map<Instant, Double> werte(String flux) {
        Map<Instant, Double> werte = new LinkedHashMap<>();
        List<FluxRecord> records = new ArrayList<>();
        for (FluxTable table : client.getQueryApi().query(flux, org)) {
            records.addAll(table.getRecords());
        }
        records.sort(Comparator.comparing(FluxRecord::getTime));
        for (FluxRecord record : records) {
            if (record.getValue() instanceof Number wert && record.getTime() != null) {
                werte.put(record.getTime(), wert.doubleValue());
            }
        }
        return werte;
    }

    public String getBucket() {
        return bucket;
    }

    static String flux(String bucket, String name, Duration range, Duration window) {
        if (name == null || !SERIES_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unbekannter Wert: " + name);
        }
        return "from(bucket: \"" + bucket + "\")"
                + " |> range(start: -" + range.toSeconds() + "s)"
                + " |> filter(fn: (r) => r._measurement == \"WP_" + name + "\" and r._field == \"value\")"
                + " |> aggregateWindow(every: " + window.toSeconds() + "s, fn: mean, createEmpty: false)"
                + " |> keep(columns: [\"_time\", \"_value\"])";
    }


}
