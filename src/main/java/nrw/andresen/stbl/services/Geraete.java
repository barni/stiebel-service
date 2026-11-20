package nrw.andresen.stbl.services;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Devices with their own energy counter that the year comparison shows as parts of the grid import, up to ANZAHL
 * (auswertung.geraet.N.*), e.g. the wallbox or Shellys at the air conditioning or the water heater. The former
 * setting auswertung.haushalt.wallbox is still read as a wallbox that came new, unless a device uses its counter.
 */
@Component
public class Geraete {

    static final int ANZAHL = 5;

    /**
     * @param zaehlerDavor entity with earlier readings, e.g. read by hand, empty for none
     * @param vorherDa     the device ran before its first reading (part of the household until then), otherwise
     *                     it came new and counts 0 before
     */
    public record Geraet(String name, String zaehler, String zaehlerDavor, boolean vorherDa) {
    }

    private final List<Geraet> liste;

    // Two constructors: Spring needs to be told which one it uses, without it the service did not start
    @org.springframework.beans.factory.annotation.Autowired
    public Geraete(Environment env) {
        this(lesen(env));
    }

    Geraete(List<Geraet> liste) {
        this.liste = List.copyOf(liste);
    }

    static List<Geraet> lesen(Environment env) {
        List<Geraet> liste = new ArrayList<>();
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
        // The former setting of the wallbox, as long as it is not in the table
        String wallbox = env.getProperty("auswertung.haushalt.wallbox", "").trim();
        if (!wallbox.isEmpty() && liste.stream().noneMatch(g -> g.zaehler().equals(wallbox))) {
            liste.addFirst(new Geraet("Wallbox", wallbox, "", false));
        }
        return liste;
    }

    public List<Geraet> getListe() {
        return liste;
    }
}
