package nrw.andresen.stbl.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The model of the heat pump, set on the configuration page, with the values of its data sheet (installation manual
 * WPL 07–17 ACS classic, data table). Without a model the pages explain the values without the numbers of a data
 * sheet.
 */
@Component
public class Anlage {

    /**
     * Data sheet values of one model
     *
     * @param minA7     heat output at A7/W35 min., kW
     * @param maxA7     heat output at A7/W35 max., kW
     * @param minAm7    heat output at A−7/W35 min., kW
     * @param maxAm7    heat output at A−7/W35 max., kW
     * @param silent70  heat output in Silent Mode 70 % at A−7/W35, kW
     * @param volumenMin   minimum flow, l/min
     * @param volumenNenn  nominal flow at A−7/W35 and 5 K, l/min
     */
    public record Modell(String name, double minA7, double maxA7, double minAm7, double maxAm7, double copA7,
                         double copA2, double copAm7, double scop, double silent70, double volumenMin,
                         double volumenNenn, double leistungMax) {
    }

    // m³/h × 1000 / 60 = l/min
    public static final Map<String, Modell> MODELLE = modelle();

    private static Map<String, Modell> modelle() {
        Map<String, Modell> m = new LinkedHashMap<>();
        for (Modell x : List.of(
                new Modell("WPL 07 ACS classic", 1.3, 3.5, 1.0, 3.2, 4.54, 3.75, 2.81, 4.23, 2.23, 6.7, 9.2, 2.2),
                new Modell("WPL 09 ACS classic", 1.3, 4.5, 1.0, 4.06, 4.54, 3.72, 2.72, 4.15, 2.65, 6.7, 11.7, 2.2),
                new Modell("WPL 13 ACS classic", 2.6, 6.5, 3.0, 6.0, 4.76, 3.97, 2.92, 4.63, 4.96, 10, 22.3, 4.6),
                new Modell("WPL 17 ACS classic", 2.6, 8.5, 3.0, 7.8, 4.76, 3.97, 2.92, 4.48, 4.96, 10, 22.3, 4.6))) {
            m.put(x.name(), x);
        }
        return m;
    }

    private volatile Modell modell;

    @Value("${waermepumpe.modell:}")
    public void setModell(String name) {
        modell = name == null ? null : MODELLE.get(name.trim());
    }

    /**
     * The set model, null if none is set
     */
    public Modell getModell() {
        return modell;
    }

    /**
     * Sentence on the heat output of the data sheet, empty without a model
     */
    public String waermeleistung() {
        Modell m = modell;
        return m == null ? "" : " Laut Anleitung kann die " + m.name() + " bei A7/W35 " + kw(m.minA7()) + "–"
                + kw(m.maxA7()) + " kW und bei A−7/W35 " + kw(m.minAm7()) + "–" + kw(m.maxAm7()) + " kW.";
    }

    public String arbeitszahl() {
        Modell m = modell;
        return m == null ? "" : " Datenblatt " + m.name() + ": A7/W35 " + z(m.copA7()) + ", A2/W35 " + z(m.copA2())
                + ", A−7/W35 " + z(m.copAm7()) + ", SCOP " + z(m.scop()) + ".";
    }

    public String volumenstrom() {
        Modell m = modell;
        return m == null ? " Den Mindestdurchfluss nennt die Anleitung der Wärmepumpe." : " Laut Anleitung "
                + m.name() + ": mindestens " + kw(m.volumenMin()) + " l/min, nenn " + kw(m.volumenNenn())
                + " l/min bei A−7/W35.";
    }

    public String silent() {
        Modell m = modell;
        return m == null ? "" : " Bei der " + m.name() + " mit 70 % höchstens etwa " + kw(m.silent70())
                + " kW bei A−7/W35.";
    }

    private static String kw(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.GERMANY, "%.1f", v);
    }

    private static String z(double v) {
        return String.format(Locale.GERMANY, "%.2f", v);
    }
}
