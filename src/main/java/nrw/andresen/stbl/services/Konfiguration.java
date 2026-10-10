package nrw.andresen.stbl.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Settings of this service that can be changed on the configuration page: shows the value of the configuration
 * file next to the value the running service uses, checks new values and writes them back into the file. The
 * service reads its settings only at the start, so a change takes effect after a restart.
 *
 * Not offered here: the login and the address and port of the web server (a typo would lock the page out), the
 * log file and the token of InfluxDB, which usually comes from the environment. The mail password can be set but is
 * never shown. Nothing of this concerns the heat pump, the service still only reads from it.
 */
@Component
public class Konfiguration {

    /**
     * GEHEIM is a password: never shown, an empty input leaves it unchanged
     */
    public enum Typ {ZAHL, GANZZAHL, DATUM, NAME, TEXT, MAIL, URL, HOST, PFAD, SCHALTER, GEHEIM}

    /**
     * One setting; min and max only for numbers. standard is the value the service uses without an entry in the
     * file, null if it has none: then the entry is always written, or commented out for an empty value.
     */
    public record Feld(String name, String gruppe, String label, String einheit, Typ typ, String standard,
                       double min, double max, String erklaerung) {
    }

    /**
     * Value in the file (or the default) and the value of the running service
     */
    public record Wert(Feld feld, String datei, String laufend) {

        public boolean neustartNoetig() {
            return !gleich(feld, datei, laufend);
        }

        /**
         * True if the file has a value, for passwords the only thing the page tells about them
         */
        public boolean gesetzt() {
            return !datei.isEmpty();
        }
    }

    /**
     * @param hinweis why the file cannot be changed, null if it can
     */
    public record Stand(List<Wert> werte, Path datei, String hinweis) {

        public boolean schreibbar() {
            return hinweis == null;
        }

        public boolean neustartNoetig() {
            return werte.stream().anyMatch(Wert::neustartNoetig);
        }
    }

    private static final String WARNUNGEN = "Warnungen";
    private static final String AUSWERTUNG = "Auswertung";
    private static final String INFLUX = "InfluxDB";
    private static final String MAILGRUPPE = "Mail";
    private static final String CAN = "CAN-Adapter";

    static final List<Feld> FELDER = List.of(
            new Feld("warnung.heizungsdruck.min", WARNUNGEN, "Heizungsdruck mindestens", "bar", Typ.ZAHL, "1.3",
                    0.5, 2.5, "Warnung, wenn der Wasserdruck im Heizkreis darunter fällt."),
            new Feld("warnung.heizungsdruck.max", WARNUNGEN, "Heizungsdruck höchstens", "bar", Typ.ZAHL, "2.5",
                    1.5, 3, "Warnung, wenn der Wasserdruck darüber steigt. Zulässig sind laut Anleitung 3 bar."),
            new Feld("warnung.hochdruck.max", WARNUNGEN, "Hochdruck höchstens", "bar", Typ.ZAHL, "38", 20, 45,
                    "Warnung, wenn der Hochdruck im Kältekreis darüber steigt. Der Hochdruckwächter schaltet bei "
                            + "45 bar ab."),
            new Feld("warnung.spreizung.max", WARNUNGEN, "Spreizung höchstens", "K", Typ.ZAHL, "10", 5, 30,
                    "Warnung, wenn der Vorlauf 5 Minuten lang um mehr als diesen Wert über dem Rücklauf liegt "
                            + "(zu wenig Durchfluss)."),
            new Feld("warnung.antwortquote.min", WARNUNGEN, "Antwortquote mindestens", "%", Typ.ZAHL, "90", 0, 100,
                    "Warnung, wenn die Wärmepumpe 10 Minuten lang weniger Anfragen beantwortet."),
            new Feld("warnung.starts.proStunde", WARNUNGEN, "Verdichterstarts je Stunde höchstens", "", Typ.GANZZAHL,
                    "3", 1, 20, "Warnung, wenn der Verdichter in einer Stunde öfter startet. Üblich ist höchstens "
                    + "ein Start je Stunde, Abtauungen zählen nicht."),
            new Feld("auswertung.start", AUSWERTUNG, "Vergleich Wärmebedarf ab", "", Typ.DATUM, "2023-07-01", 0, 0,
                    "Erster Tag für den Vergleich der Einstellung Wärmebedarf und die Tagesübersicht."),
            new Feld("auswertung.fehlstarts.start", AUSWERTUNG, "Fehlstarts ab", "", Typ.DATUM, "2019-01-01", 0, 0,
                    "Erster Tag für die Fehlstarts je Heizsaison."),
            new Feld("auswertung.aussentemp.bucket", AUSWERTUNG, "Bucket der Außentemperatur", "", Typ.NAME, "", 0,
                    0, "InfluxDB-Bucket mit der Außentemperatur aus Home Assistant. Leer: Bucket des Dienstes."),
            new Feld("auswertung.aussentemp.entity", AUSWERTUNG, "Entity der Außentemperatur", "", Typ.NAME, "", 0,
                    0, "Home-Assistant-Entity der Außentemperatur. Leer: der eigene Wert der Wärmepumpe, der erst "
                    + "seit 09/2026 gespeichert wird."),
            new Feld("influx.enabled", INFLUX, "InfluxDB verwenden", "", Typ.SCHALTER, "true", 0, 0,
                    "Aus: Es wird nichts gespeichert, Verläufe und Auswertungen fehlen auf der Statusseite."),
            new Feld("influx.url", INFLUX, "Adresse", "", Typ.URL, "", 0, 0,
                    "Adresse des InfluxDB-2-Servers, zum Beispiel http://localhost:8086."),
            new Feld("influx.org", INFLUX, "Organisation", "", Typ.TEXT, "", 0, 0,
                    "Name oder ID der Organisation in InfluxDB."),
            new Feld("influx.bucket", INFLUX, "Bucket", "", Typ.TEXT, "", 0, 0,
                    "Bucket, in den der Dienst die Werte der Wärmepumpe schreibt. Der Token steht nur in der Datei "
                            + "oder in der Umgebungsvariable INFLUX_TOKEN."),
            new Feld("stbl.mail.to", MAILGRUPPE, "Empfänger der Warnmails", "", Typ.MAIL, "", 0, 0,
                    "Eine Adresse. Leer: Es werden keine Mails gesendet."),
            new Feld("spring.mail.host", MAILGRUPPE, "Mailserver", "", Typ.HOST, null, 0, 0,
                    "Name des SMTP-Servers, zum Beispiel smtp.gmail.com. Leer: Es werden keine Mails gesendet."),
            new Feld("spring.mail.port", MAILGRUPPE, "Port", "", Typ.GANZZAHL, null, 1, 65535,
                    "Port des SMTP-Servers, meist 587. Leer: Standard des Servers."),
            new Feld("spring.mail.username", MAILGRUPPE, "Benutzer", "", Typ.TEXT, null, 0, 0,
                    "Benutzername am Mailserver, meist die eigene Adresse."),
            new Feld("spring.mail.password", MAILGRUPPE, "Passwort", "", Typ.GEHEIM, null, 0, 0,
                    "Passwort am Mailserver. Wird nie angezeigt; leer lassen, um es nicht zu ändern."),
            new Feld("spring.mail.properties.mail.smtp.auth", MAILGRUPPE, "Anmeldung am Server", "", Typ.SCHALTER,
                    "false", 0, 0, "An, wenn der Mailserver Benutzer und Passwort verlangt."),
            new Feld("spring.mail.properties.mail.smtp.starttls.enable", MAILGRUPPE, "Verschlüsselung (STARTTLS)", "",
                    Typ.SCHALTER, "false", 0, 0, "An für Server, die die Verbindung mit STARTTLS verschlüsseln."),
            new Feld("USBtin.port", CAN, "Anschluss des USBtin", "", Typ.PFAD, null, 0, 0,
                    "Serielle Schnittstelle des CAN-Adapters, zum Beispiel /dev/ttyACM0."),
            new Feld("USBtin.speed", CAN, "Geschwindigkeit", "bit/s", Typ.GANZZAHL, null, 10000, 1000000,
                    "Geschwindigkeit des CAN-Busses. Die Wärmepumpe arbeitet mit 20000."),
            new Feld("can.logall", CAN, "Alle Nachrichten empfangen", "", Typ.SCHALTER, null, 0, 0,
                    "An: Der Dienst empfängt alle CAN-Nachrichten und zeigt sie in der Statistik je Knoten. Aus: nur "
                            + "die Antworten auf eigene Anfragen."));

    // Without these the service does not start, they cannot be left empty
    private static final Set<String> PFLICHT = Set.of("USBtin.port", "USBtin.speed", "can.logall");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{0,64}");
    private static final Pattern MAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,180}\\.[A-Za-z]{2,24}");
    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9._%+@-]{0,128}");
    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9.-]{1,253}(:\\d{1,5})?(/[A-Za-z0-9._~/-]*)?");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern PFAD = Pattern.compile("[A-Za-z0-9/_.:-]{1,64}");
    // Printable ASCII without backslash, no blank at the ends: written to the file as it is. "${" would be read by
    // Spring as a placeholder.
    private static final Pattern GEHEIM = Pattern.compile("[\\x21-\\x5B\\x5D-\\x7E]([\\x20-\\x5B\\x5D-\\x7E]{0,126}"
            + "[\\x21-\\x5B\\x5D-\\x7E])?");
    private static final LocalDate FRUEHESTES_DATUM = LocalDate.of(2015, 1, 1);
    // Spring Boot reads application.properties in this encoding; the values written here are plain ASCII
    private static final Charset ZEICHENSATZ = StandardCharsets.ISO_8859_1;
    static final String ANGEHAENGT = "## Changed on the configuration page";

    private final Environment environment;
    private final Path datei;

    /**
     * @param datei configuration file, by default application.properties in the working directory, where Spring
     *              Boot reads it
     */
    public Konfiguration(Environment environment,
                         @Value("${stbl.config.datei:application.properties}") String datei) {
        this.environment = environment;
        this.datei = Path.of(datei).toAbsolutePath().normalize();
    }

    /**
     * Values of the file and of the running service
     */
    public Stand stand() {
        Properties eintraege = new Properties();
        String hinweis = null;
        if (!Files.isRegularFile(datei)) {
            hinweis = "Die Konfigurationsdatei " + datei + " gibt es nicht. Angezeigt werden die Werte des "
                    + "laufenden Dienstes.";
        } else {
            try {
                eintraege.load(new StringReader(String.join("\n", Files.readAllLines(datei, ZEICHENSATZ))));
                if (!Files.isWritable(datei) || !Files.isWritable(datei.getParent())) {
                    hinweis = "Der Dienst darf " + datei + " oder den Ordner nicht schreiben. Die Werte lassen sich "
                            + "nur in der Datei ändern.";
                }
            } catch (IOException e) {
                hinweis = "Die Konfigurationsdatei " + datei + " lässt sich nicht lesen: " + e.getMessage();
            }
        }
        List<Wert> werte = new ArrayList<>();
        for (Feld feld : FELDER) {
            String standard = feld.standard() == null ? "" : feld.standard();
            String laufend = environment.getProperty(feld.name(), standard);
            // Without a readable file the running value is the only one known
            String inDatei = hinweis != null && eintraege.isEmpty() ? laufend
                    : eintraege.getProperty(feld.name(), standard);
            werte.add(new Wert(feld, inDatei.trim(), laufend.trim()));
        }
        return new Stand(werte, datei, hinweis);
    }

    /**
     * Checks the values of the form
     *
     * @param eingabe value per name of a setting, missing settings are not changed
     * @param werte   receives the values in the form written to the file
     * @return message per name of a setting with an invalid value, empty if all are valid
     */
    public static Map<String, String> pruefen(Map<String, String> eingabe, Map<String, String> werte) {
        Map<String, String> fehler = new LinkedHashMap<>();
        for (Feld feld : FELDER) {
            String roh = eingabe.get(feld.name());
            if (roh == null || (feld.typ() == Typ.GEHEIM && roh.isEmpty())) {
                // Not in the form, or a password left empty: unchanged
                continue;
            }
            try {
                werte.put(feld.name(), normalisieren(feld, roh));
            } catch (IllegalArgumentException e) {
                fehler.put(feld.name(), e.getMessage());
            }
        }
        String min = werte.get("warnung.heizungsdruck.min");
        String max = werte.get("warnung.heizungsdruck.max");
        if (min != null && max != null && Double.parseDouble(min) >= Double.parseDouble(max)) {
            fehler.put("warnung.heizungsdruck.min", "muss unter dem Höchstwert liegen");
        }
        if ("true".equals(werte.get("influx.enabled"))) {
            for (String name : List.of("influx.url", "influx.org", "influx.bucket")) {
                if ("".equals(werte.get(name))) {
                    fehler.put(name, "wird gebraucht, solange InfluxDB verwendet wird");
                }
            }
        }
        fehler.keySet().forEach(werte::remove);
        return fehler;
    }

    /**
     * Value as it is written to the file
     *
     * @throws IllegalArgumentException with a message for the page if the value is not valid
     */
    static String normalisieren(Feld feld, String roh) {
        String wert = feld.typ() == Typ.GEHEIM ? roh : roh.trim();
        if (wert.isEmpty() && PFLICHT.contains(feld.name())) {
            throw new IllegalArgumentException("darf nicht leer sein");
        }
        if (wert.isEmpty() && feld.standard() == null && feld.typ() != Typ.GEHEIM) {
            // Optional without a default of the service: the entry is commented out
            return "";
        }
        switch (feld.typ()) {
            case ZAHL, GANZZAHL -> {
                BigDecimal zahl;
                try {
                    zahl = new BigDecimal(wert.replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("ist keine Zahl");
                }
                if (zahl.doubleValue() < feld.min() || zahl.doubleValue() > feld.max()) {
                    throw new IllegalArgumentException("muss zwischen " + anzeige(feld, zahl(feld.min()))
                            + " und " + anzeige(feld, zahl(feld.max())) + " liegen");
                }
                zahl = zahl.stripTrailingZeros();
                if (feld.typ() == Typ.GANZZAHL && zahl.scale() > 0) {
                    throw new IllegalArgumentException("muss eine ganze Zahl sein");
                }
                if (zahl.scale() > 3) {
                    throw new IllegalArgumentException("höchstens drei Nachkommastellen");
                }
                return zahl.toPlainString();
            }
            case DATUM -> {
                LocalDate datum;
                try {
                    datum = LocalDate.parse(wert);
                } catch (Exception e) {
                    throw new IllegalArgumentException("ist kein Datum (JJJJ-MM-TT)");
                }
                if (datum.isBefore(FRUEHESTES_DATUM) || datum.isAfter(LocalDate.now())) {
                    throw new IllegalArgumentException("muss zwischen " + FRUEHESTES_DATUM + " und heute liegen");
                }
                return datum.toString();
            }
            case NAME -> {
                if (!NAME.matcher(wert).matches()) {
                    throw new IllegalArgumentException("nur Buchstaben, Ziffern und Unterstrich, höchstens 64 Zeichen");
                }
                return wert;
            }
            case TEXT -> {
                return passend(TEXT, wert, "nur Buchstaben, Ziffern und . _ % + @ -, höchstens 128 Zeichen");
            }
            case URL -> {
                return wert.isEmpty() ? wert
                        : passend(URL, wert, "ist keine Adresse der Form http://name:port");
            }
            case HOST -> {
                return passend(HOST, wert, "ist kein Servername");
            }
            case PFAD -> {
                return passend(PFAD, wert, "nur Buchstaben, Ziffern und / _ . : -, höchstens 64 Zeichen");
            }
            case GEHEIM -> {
                if (wert.contains("${")) {
                    throw new IllegalArgumentException("darf die Zeichenfolge ${ nicht enthalten");
                }
                return passend(GEHEIM, wert, "nur druckbare Zeichen ohne Umlaute und ohne \\, kein Leerzeichen am "
                        + "Anfang oder Ende, höchstens 128 Zeichen");
            }
            case MAIL -> {
                if (!wert.isEmpty() && !MAIL.matcher(wert).matches()) {
                    throw new IllegalArgumentException("ist keine Mailadresse");
                }
                return wert;
            }
            case SCHALTER -> {
                if (!wert.equals("true") && !wert.equals("false")) {
                    throw new IllegalArgumentException("muss an oder aus sein");
                }
                return wert;
            }
            default -> throw new IllegalArgumentException("unbekannter Typ");
        }
    }

    private static String passend(Pattern muster, String wert, String meldung) {
        if (!muster.matcher(wert).matches()) {
            throw new IllegalArgumentException(meldung);
        }
        return wert;
    }

    /**
     * Writes the checked values into the file, a copy of the file before is kept as .bak next to it
     */
    public synchronized void speichern(Map<String, String> werte) throws IOException {
        List<String> zeilen = ersetzen(Files.readAllLines(datei, ZEICHENSATZ), werte);
        Files.copy(datei, datei.resolveSibling(datei.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        // Written next to the file and moved, so a failure does not leave half a file
        Path neu = datei.resolveSibling(datei.getFileName() + ".neu");
        Files.write(neu, zeilen, ZEICHENSATZ);
        Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Lines of the file with the new values. Comments and order stay: an existing entry is replaced, otherwise the
     * commented entry of the template, otherwise the entry is added at the end. A value equal to the default is not
     * added to a file that has no entry for it.
     */
    static List<String> ersetzen(List<String> zeilen, Map<String, String> werte) {
        List<String> neu = new ArrayList<>(zeilen);
        List<String> anhang = new ArrayList<>();
        for (Feld feld : FELDER) {
            String wert = werte.get(feld.name());
            if (wert == null) {
                continue;
            }
            String name = Pattern.quote(feld.name());
            Pattern aktiv = Pattern.compile("^\\s*" + name + "\\s*[=:].*$");
            Pattern auskommentiert = Pattern.compile("^\\s*#\\s*" + name + "\\s*=.*$");
            String zeile = feld.name() + "=" + wert;
            // An empty value of a setting without default means "not set": the entry is commented out
            boolean entfernen = wert.isEmpty() && feld.standard() == null;
            boolean ersetzt = false;
            for (int i = 0; i < neu.size(); i++) {
                if (aktiv.matcher(neu.get(i)).matches()) {
                    neu.set(i, entfernen ? "#" + neu.get(i).stripLeading() : zeile);
                    ersetzt = true;
                }
            }
            if (ersetzt || entfernen || (feld.standard() != null && gleich(feld, wert, feld.standard()))) {
                continue;
            }
            for (int i = 0; i < neu.size() && !ersetzt; i++) {
                if (auskommentiert.matcher(neu.get(i)).matches()) {
                    neu.set(i, zeile);
                    ersetzt = true;
                }
            }
            if (!ersetzt) {
                anhang.add(zeile);
            }
        }
        if (!anhang.isEmpty()) {
            if (!neu.contains(ANGEHAENGT)) {
                neu.add("");
                neu.add(ANGEHAENGT);
            }
            neu.addAll(anhang);
        }
        return neu;
    }

    /**
     * True if both values mean the same, e.g. 2.5 and 2.50 or TRUE and true
     */
    static boolean gleich(Feld feld, String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        try {
            return switch (feld.typ()) {
                case ZAHL, GANZZAHL -> new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0;
                case SCHALTER -> Boolean.parseBoolean(a.trim()) == Boolean.parseBoolean(b.trim());
                default -> a.trim().equals(b.trim());
            };
        } catch (NumberFormatException e) {
            return a.trim().equals(b.trim());
        }
    }

    /**
     * Value as shown on the page: numbers with a decimal comma, switches as an or aus
     */
    public static String anzeige(Feld feld, String wert) {
        return switch (feld.typ()) {
            case ZAHL, GANZZAHL -> wert.replace('.', ',');
            case SCHALTER -> Boolean.parseBoolean(wert) ? "an" : "aus";
            case GEHEIM -> "";
            default -> wert;
        };
    }

    private static String zahl(double wert) {
        return BigDecimal.valueOf(wert).stripTrailingZeros().toPlainString();
    }
}
