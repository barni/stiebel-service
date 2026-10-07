package nrw.andresen.stbl.services.can;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Trial of indices which might show a fault of the WPM3. How the fault list of the WPM3 is read over CAN is not
 * documented, these are the candidates found in 2026-10 (OneESP32 wpl17.yaml, ElsterHeatingBridge, Elster table).
 * The answers are kept per node and index, the same index of two nodes must not overwrite each other.
 */
public class FehlerDiagnose {

    public static final short FEHLERMELDUNG = 0x0001;
    public static final short BETRIEBS_STATUS = 0x0176;
    public static final short FEHLERLISTEN_EINTRAG = 0x01f1;
    public static final short FEHLERART = 0x01f2;
    public static final short FEHLERAUSGANG = 0x0730;
    // Bit of the Betriebsstatus of the manager 0x480 for the fault output, as used by OneESP32 (wpl_base.yaml)
    private static final int BIT_STOERAUSGANG = 4;
    private static final int NICHT_VERFUEGBAR = 0x8000;

    /**
     * Requested node and index
     */
    public record Kandidat(int knoten, short index, String name, String erklaerung) {
    }

    /**
     * Last answer, wert is the raw value 0..0xffff
     */
    public record Antwort(int knoten, short index, int wert, Instant zeit) {
        public boolean nichtVerfuegbar() {
            return wert == NICHT_VERFUEGBAR;
        }
    }

    /**
     * Line for the status page and the REST API, antwort is null if nothing was received yet
     */
    public record Zeile(int knoten, short index, String name, String erklaerung, Antwort antwort) {
    }

    public static final List<Kandidat> KANDIDATEN = List.of(
            new Kandidat(0x180, FEHLERAUSGANG, "FEHLERAUSGANG",
                    "Störausgang, so fragt ihn OneESP32 für die WPL 17 beim Kessel ab."),
            new Kandidat(0x180, FEHLERMELDUNG, "FEHLERMELDUNG",
                    "Aktueller Fehlercode, bei anderen Stiebel-Geräten belegt (z. B. 0x5145 = 20805)."),
            new Kandidat(0x480, FEHLERMELDUNG, "FEHLERMELDUNG", "Aktueller Fehlercode beim Manager."),
            new Kandidat(0x500, FEHLERMELDUNG, "FEHLERMELDUNG", "Aktueller Fehlercode der Außeneinheit."),
            new Kandidat(0x514, FEHLERMELDUNG, "FEHLERMELDUNG",
                    "Aktueller Fehlercode über den externen Zugang der Außeneinheit."),
            new Kandidat(0x480, BETRIEBS_STATUS, "BETRIEBS_STATUS",
                    "Betriebsstatus des Managers, Bit 4 ist laut OneESP32 der Störausgang. Der Dienst liest den "
                            + "Betriebsstatus sonst vom Kessel 0x180 mit anderer Bitbelegung."),
            new Kandidat(0x180, FEHLERLISTEN_EINTRAG, "FEHLERLISTEN_EINTRAG",
                    "Eintrag der Fehlerliste, nur der Name in der Elster-Tabelle ist bekannt."),
            new Kandidat(0x180, FEHLERART, "FEHLERART",
                    "Art des Fehlers, nur der Name in der Elster-Tabelle ist bekannt."),
            new Kandidat(0x480, FEHLERLISTEN_EINTRAG, "FEHLERLISTEN_EINTRAG",
                    "Eintrag der Fehlerliste beim Manager, nur der Name in der Elster-Tabelle ist bekannt."),
            new Kandidat(0x480, FEHLERART, "FEHLERART",
                    "Art des Fehlers beim Manager, nur der Name in der Elster-Tabelle ist bekannt."));

    // Nodes asked for the possible fault list, 0x500 is the outdoor unit and has its own fault number
    public static final List<Integer> LISTEN_KNOTEN = List.of(0x180, 0x480, 0x514);

    /**
     * Indices of the Elster table which might hold the fault list of 20 entries: K_OS_STOERMELDUNG_1..10 with
     * K_OS_STUNDEN_TAGESZAEHLER_1..10 next to each (0x022b-0x023e), the pointer (0x037f), K_OS_STOERMELDUNG_11..20
     * (0x0380-0x0389) and K_FEHLERZAEHLER_01..25 (0x0366-0x037e). Requested once per hour, read only.
     */
    public static final List<Short> LISTEN_INDIZES = listenIndizes();

    private static List<Short> listenIndizes() {
        List<Short> indizes = new ArrayList<>();
        for (int index = 0x022b; index <= 0x023e; index++) {
            indizes.add((short) index);
        }
        for (int index = 0x0366; index <= 0x0389; index++) {
            indizes.add((short) index);
        }
        return List.copyOf(indizes);
    }

    // Indices which are only requested for the trial, an answer from any node is kept
    private static final Set<Short> NUR_DIAGNOSE = nurDiagnose();

    private static Set<Short> nurDiagnose() {
        Set<Short> indizes = KANDIDATEN.stream().map(Kandidat::index)
                .filter(index -> index != BETRIEBS_STATUS).collect(Collectors.toSet());
        indizes.addAll(LISTEN_INDIZES);
        return Set.copyOf(indizes);
    }

    private static final ElsterTable ELSTER_TABLE = new ElsterTable();

    private final Map<Integer, Antwort> antworten = new ConcurrentHashMap<>();

    private static int schluessel(int knoten, short index) {
        return knoten << 16 | (index & 0xffff);
    }

    /**
     * True if the answer of this node with this index belongs to the trial. The Betriebsstatus of the Kessel 0x180
     * is not part of it, it is handled as before.
     */
    public static boolean istKandidat(int knoten, short index) {
        return NUR_DIAGNOSE.contains(index) || (index == BETRIEBS_STATUS && knoten == 0x480);
    }

    public void antwort(int knoten, short index, short rohwert, Instant zeit) {
        antworten.put(schluessel(knoten, index), new Antwort(knoten, index, rohwert & 0xffff, zeit));
    }

    /**
     * Name of a fault list index from the Elster table
     */
    public static String listenName(short index) {
        ElsterIndex elsterIndex = ELSTER_TABLE.findByIndex(index);
        return elsterIndex != null && elsterIndex != ElsterIndex.UNKOWN_ELSTER_INDEX ? elsterIndex.getName()
                : String.format("Index 0x%04X", index);
    }

    /**
     * True for an index of the fault list trial
     */
    public static boolean istListenIndex(short index) {
        return LISTEN_INDIZES.contains(index);
    }

    /**
     * Fault list trial: every requested node and index with its answer, null if nothing was received yet
     */
    public List<Zeile> listenZeilen() {
        List<Zeile> zeilen = new ArrayList<>();
        for (int knoten : LISTEN_KNOTEN) {
            for (short index : LISTEN_INDIZES) {
                zeilen.add(new Zeile(knoten, index, listenName(index), "Kandidat für die Fehlerliste.",
                        antworten.get(schluessel(knoten, index))));
            }
        }
        return zeilen;
    }

    /**
     * All candidates in their order, then answers of other nodes with a trial index
     */
    public List<Zeile> zeilen() {
        List<Zeile> zeilen = new ArrayList<>();
        Set<Integer> bekannt = KANDIDATEN.stream().map(k -> schluessel(k.knoten(), k.index()))
                .collect(Collectors.toSet());
        for (int knoten : LISTEN_KNOTEN) {
            for (short index : LISTEN_INDIZES) {
                bekannt.add(schluessel(knoten, index));
            }
        }
        for (Kandidat k : KANDIDATEN) {
            zeilen.add(new Zeile(k.knoten(), k.index(), k.name(), k.erklaerung(),
                    antworten.get(schluessel(k.knoten(), k.index()))));
        }
        antworten.entrySet().stream()
                .filter(e -> !bekannt.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> zeilen.add(new Zeile(e.getValue().knoten(), e.getValue().index(),
                        String.format("Index 0x%04X", e.getValue().index()),
                        "Antwort eines Geräts, das nicht gefragt wurde.", e.getValue())));
        return zeilen;
    }

    public List<Antwort> getAntworten() {
        List<Antwort> liste = new ArrayList<>(antworten.values());
        liste.sort(Comparator.comparingInt((Antwort a) -> a.knoten()).thenComparingInt(a -> a.index() & 0xffff));
        return liste;
    }

    /**
     * Raw value as hex and decimal with the known meaning
     */
    public static String beschreibung(Antwort antwort) {
        if (antwort == null) {
            return "keine Antwort";
        }
        if (antwort.nichtVerfuegbar()) {
            return "nicht verfügbar";
        }
        String text = String.format("0x%04X (%d)", antwort.wert(), antwort.wert());
        if (antwort.index() == BETRIEBS_STATUS) {
            return text + ", Bit 4: " + ((antwort.wert() >> BIT_STOERAUSGANG & 1) == 1 ? "an" : "aus");
        }
        if (antwort.index() == FEHLERMELDUNG && antwort.wert() != 0) {
            ErrorIndex fehler = ErrorIndex.getErrorIndex(antwort.wert());
            if (fehler != null) {
                return text + ", " + fehler.name;
            }
        }
        return text;
    }
}
