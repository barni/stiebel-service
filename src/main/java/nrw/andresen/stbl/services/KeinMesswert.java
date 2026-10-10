package nrw.andresen.stbl.services;

/**
 * A value cannot be calculated in the current state of the heat pump, e.g. the efficiency while the compressor is
 * off. This is expected and no fault, so it is not logged as a warning.
 */
public class KeinMesswert extends Exception {

    public KeinMesswert(String grund) {
        super(grund);
    }
}
