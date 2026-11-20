package nrw.andresen.stbl.services;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Devices with their own energy counter that the year comparison shows as parts of the grid import: the wallbox
 * (auswertung.haushalt.wallbox, came new) and up to ANZAHL more (auswertung.geraet.N.*), e.g. Shellys at the air
 * conditioning or the water heater
 */
@Component
public class Geraete {

    static final int ANZAHL = 4;

    /**
     * @param zaehlerDavor entity with earlier readings, e.g. read by hand, empty for none
     * @param vorherDa     the device ran before its first reading (part of the household until then), otherwise
     *                     it came new and counts 0 before
     */
    public record Geraet(String name, String zaehler, String zaehlerDavor, boolean vorherDa) {
    }

    private final List<Geraet> liste;

    public Geraete(Environment env) {
        this(lesen(env));
    }

    Geraete(List<Geraet> liste) {
        this.liste = List.copyOf(liste);
    }

    static List<Geraet> lesen(Environment env) {
        List<Geraet> liste = new ArrayList<>();
        String wallbox = env.getProperty("auswertung.haushalt.wallbox", "").trim();
        if (!wallbox.isEmpty()) {
            liste.add(new Geraet("Wallbox", wallbox, "", false));
        }
        for (int n = 1; n <= ANZAHL; n++) {
            String name = env.getProperty("auswertung.geraet." + n + ".name", "").trim();
            String zaehler = env.getProperty("auswertung.geraet." + n + ".zaehler", "").trim();
            String davor = env.getProperty("auswertung.geraet." + n + ".davor", "").trim();
            if (name.isEmpty() || (zaehler.isEmpty() && davor.isEmpty())) {
                continue;
            }
            liste.add(new Geraet(name, zaehler, davor,
                    !"false".equals(env.getProperty("auswertung.geraet." + n + ".vorher", "true").trim())));
        }
        return liste;
    }

    public List<Geraet> getListe() {
        return liste;
    }
}
