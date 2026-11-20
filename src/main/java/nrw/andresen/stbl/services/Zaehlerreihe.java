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
    // Between the last manual reading and the first reading of the counter nothing was measured
    private Instant lueckeVon;
    private Instant lueckeBis;

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
        for (Map.Entry<Instant, Double> e : new TreeMap<>(zaehler).entrySet()) {
            if (ende != null && !e.getKey().isAfter(ende)) {
                continue;
            }
            if (letzter == null && ende != null) {
                lueckeVon = ende;
                lueckeBis = e.getKey();
            }
            summe += zuwachs(letzter, e.getValue());
            letzter = e.getValue();
            stand.put(e.getKey(), summe);
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
     * Reading at a point in time, interpolated between the readings around it; null before the first reading, more
     * than NACH_ENDE after the last one and between the manual readings and the counter
     */
    public Double stand(Instant zeit) {
        Map.Entry<Instant, Double> vorher = stand.floorEntry(zeit);
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
