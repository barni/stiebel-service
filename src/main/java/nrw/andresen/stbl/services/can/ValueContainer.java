package nrw.andresen.stbl.services.can;

import java.io.Serializable;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

public class ValueContainer<T extends Serializable> implements Serializable {
    private DateTimeFormatter formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone( ZoneId.systemDefault())
            .withLocale(new Locale("de"));
    private T value;
    private String valueString;
    private Instant timestamp;

    public ValueContainer(T value, String valueString, Instant timestamp) {
        this.value = value;
        this.valueString = valueString;
        this.timestamp = timestamp;
    }

    public ValueContainer(T value, Instant timestamp) {
        this(value, format(value), timestamp);
    }

    /**
     * Doubles without floating point artifacts, e.g. 20.458358 instead of 20.458357999999997
     */
    private static String format(Serializable value) {
        if (value instanceof Double) {
            return new DecimalFormat("0.######", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
        }
        return value.toString();
    }

    public T getValue() {
        return value;
    }

    public String getValueString() {
        return valueString;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String toString() {
        return valueString + "</td><td>Last received: </td><td>" + formatter.format(timestamp);
    }
}