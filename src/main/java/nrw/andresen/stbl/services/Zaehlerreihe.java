package nrw.andresen.stbl.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Readings of an energy counter in kWh, joined from manual readings (zaehlerDavor) and a counter that follows, e.g.
 * a Shelly. The values are made rising: when a counter starts again at 0 (reset, new device) the drop is ignored and
 * counting goes on. Between two readings the consumption is spread evenly over the time.
 */
public final class Zaehlerreihe {

    // After the last reading the consumption is not known; a counter reports at least once a day
    static final java.time.Duration NACH_ENDE = java.time.Duration.ofDays(2);
    private final TreeMap<Instant, Double> stand = new TreeMap<>();
    // After the last manual reading, while no counter follows yet, the consumption goes on at the rate of the weeks
    // before, at most this long: readings by hand come about once a month
    static final java.time.Duration HOCHRECHNEN = java.time.Duration.ofDays(62);
    // The rate is taken over at least this time before the last manual reading
    static final java.time.Duration RATE_UEBER = java.time.Duration.ofDays(28);
    // A gap between the readings before and the counter up to this long counts without consumption, e.g. the hours
    // between the last value of a series and fitting the counter; a longer one is not known
    static final java.time.Duration LUECKE_MAX = java.time.Duration.ofDays(2);
    // Between the last manual reading and the first reading of the counter nothing was measured
    private Instant lueckeVon;
    private Instant lueckeBis;
    // kWh per millisecond after the last reading, null if the series ends with a counter
    private Double rate;

    /**
     * @param davor  manual readings by time, may be empty
     * @param zaehler readings of the counter by time, e.g. one per day, may be empty; only those after the last
     *                manual reading count
     */
    public Zaehlerreihe(Map<Instant, Double> davor, Map<Instant, Double> zaehler) {
        double summe = 0;
        Double letzter = null;
        Instant ende = null;
        for (Map.Entry<Instant, Double> e : new TreeMap<>(davor).entrySet()) {
            summe += zuwachs(letzter, e.getValue());
            letzter = e.getValue();
            stand.put(e.getKey(), summe);
            ende = e.getKey();
        }
        // The counter goes on from the last manual reading; what was used between the two is not known
        letzter = null;
        boolean mitZaehler = false;
        for (Map.Entry<Instant, Double> e : new TreeMap<>(zaehler).entrySet()) {
            if (ende != null && !e.getKey().isAfter(ende)) {
                continue;
            }
            if (letzter == null && ende != null
                    && java.time.Duration.between(ende, e.getKey()).compareTo(LUECKE_MAX) > 0) {
                lueckeVon = ende;
                lueckeBis = e.getKey();
            }
            summe += zuwachs(letzter, e.getValue());
            letzter = e.getValue();
            stand.put(e.getKey(), summe);
            mitZaehler = true;
        }
        if (!mitZaehler && ende != null) {
            // Rate over the last readings that cover at least RATE_UEBER
            Map.Entry<Instant, Double> anfang = stand.floorEntry(ende.minus(RATE_UEBER));
            if (anfang != null && ende.isAfter(anfang.getKey())) {
                rate = (stand.get(ende) - anfang.getValue()) / (ende.toEpochMilli() - anfang.getKey().toEpochMilli());
            }
        }
    }

    /**
     * Rise from the reading before; a drop counts as a counter that started again at 0
     */
    private static double zuwachs(Double vorher, double jetzt) {
        if (vorher == null) {
            return 0;
        }
        return jetzt >= vorher ? jetzt - vorher : Math.max(0, jetzt);
    }

    /**
     * True if values after the last reading are estimated (manual readings, no counter yet)
     */
    public boolean isHochgerechnet() {
        return rate != null;
    }

    public boolean isLeer() {
        return stand.isEmpty();
    }

    /**
     * Time of the first reading, null without readings
     */
    public Instant getAnfang() {
        return stand.isEmpty() ? null : stand.firstKey();
    }

    /**
     * Reading at a point in time, interpolated between the readings around it; null before the first reading and
     * between the manual readings and the counter. After the last reading: of manual readings without a counter
     * yet, estimated at the rate before for up to HOCHRECHNEN; otherwise the last reading for NACH_ENDE, then null.
     */
    public Double stand(Instant zeit) {
        Map.Entry<Instant, Double> vorher = stand.floorEntry(zeit);
        Instant letzte = stand.isEmpty() ? null : stand.lastKey();
        if (vorher != null && rate != null && zeit.isAfter(letzte)) {
            return zeit.isAfter(letzte.plus(HOCHRECHNEN)) ? null
                    : vorher.getValue() + rate * (zeit.toEpochMilli() - letzte.toEpochMilli());
        }
        if (vorher == null || zeit.isAfter(stand.lastKey().plus(NACH_ENDE))
                || (lueckeVon != null && zeit.isAfter(lueckeVon) && zeit.isBefore(lueckeBis))) {
            return null;
        }
        Map.Entry<Instant, Double> nachher = stand.ceilingEntry(zeit);
        if (nachher == null || !nachher.getKey().isAfter(vorher.getKey())) {
            return vorher.getValue();
        }
        double anteil = (double) (zeit.toEpochMilli() - vorher.getKey().toEpochMilli())
                / (nachher.getKey().toEpochMilli() - vorher.getKey().toEpochMilli());
        return vorher.getValue() + anteil * (nachher.getValue() - vorher.getValue());
    }

    /**
     * Consumption from von to bis in kWh. Before the first reading the device was either not there (vorherDa false,
     * counts 0) or not measured (vorherDa true, null: unknown, it is part of the household then).
     */
    public Double verbrauch(Instant von, Instant bis, boolean vorherDa) {
        if (stand.isEmpty()) {
            return vorherDa ? null : 0.0;
        }
        Instant anfang = stand.firstKey();
        if (von.isBefore(anfang)) {
            if (vorherDa) {
                return null;
            }
            if (!bis.isAfter(anfang)) {
                return 0.0;
            }
            von = anfang;
        }
        // What was used between the manual readings and the counter is not known
        if (lueckeVon != null && von.isBefore(lueckeBis) && bis.isAfter(lueckeVon)) {
            return null;
        }
        Double a = stand(von);
        Double b = stand(bis);
        return a == null || b == null ? null : Math.max(0, b - a);
    }

    List<Instant> zeiten() {
        return new ArrayList<>(stand.keySet());
    }
}
