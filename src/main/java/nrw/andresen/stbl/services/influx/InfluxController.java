package nrw.andresen.stbl.services.influx;

import com.influxdb.LogLevel;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import nrw.andresen.stbl.services.can.ValueContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InfluxController {

    private InfluxDBClient client;
    private String bucket;
    private String org;
    private WriteApi writeApi;

    public InfluxController(@Value("${influx.url}") String influxURL, @Value("${influx.token}") String token,
                            @Value("${influx.org}") String org, @Value("${influx.bucket}") String bucket){
        this.bucket = bucket;
        this.org = org;
        client = InfluxDBClientFactory.create(influxURL, token.toCharArray(), org, bucket)
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


}
