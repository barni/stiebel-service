package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KonfigurationTest {

    private static final List<String> DATEI = List.of(
            "## Template",
            "stbl.mail.to=",
            "spring.mail.password=geheim",
            "",
            "## Warnings by mail",
            "#warnung.heizungsdruck.min=1.3",
            "warnung.antwortquote.min=90");

    @Test
    public void testPruefen() {
        Map<String, String> werte = new LinkedHashMap<>();
        Map<String, String> fehler = Konfiguration.pruefen(Map.of(
                "warnung.heizungsdruck.min", " 1,40 ",
                "warnung.starts.proStunde", "8",
                "auswertung.start", "2024-01-01",
                "auswertung.aussentemp.entity", "aussen_temperatur",
                "influx.enabled", "false",
                "stbl.mail.to", "name@example.org"), werte);
        assertTrue(fehler.isEmpty());
        // Decimal comma and trailing zeros are normalised for the file
        assertEquals("1.4", werte.get("warnung.heizungsdruck.min"));
        assertEquals("8", werte.get("warnung.starts.proStunde"));
        assertEquals("false", werte.get("influx.enabled"));
        // Settings that are not in the form are not touched
        assertFalse(werte.containsKey("warnung.hochdruck.max"));
    }

    @Test
    public void testPruefenUngueltig() {
        Map<String, String> werte = new LinkedHashMap<>();
        Map<String, String> eingabe = new LinkedHashMap<>();
        eingabe.put("warnung.heizungsdruck.min", "abc");
        eingabe.put("warnung.heizungsdruck.max", "9");
        eingabe.put("warnung.starts.proStunde", "2.5");
        eingabe.put("auswertung.start", "2999-01-01");
        // Nothing but a plain name may end up in the file or a Flux query
        eingabe.put("auswertung.aussentemp.entity", "x\") |> drop()");
        eingabe.put("influx.enabled", "vielleicht");
        eingabe.put("stbl.mail.to", "a@b.de\nspring.security.user.password=neu");
        Map<String, String> fehler = Konfiguration.pruefen(eingabe, werte);
        assertEquals(eingabe.keySet(), fehler.keySet());
        assertTrue(werte.isEmpty());
        assertEquals("muss zwischen 1,5 und 3 liegen", fehler.get("warnung.heizungsdruck.max"));
    }

    @Test
    public void testDruckGrenzenZueinander() {
        Map<String, String> werte = new LinkedHashMap<>();
        Map<String, String> fehler = Konfiguration.pruefen(Map.of("warnung.heizungsdruck.min", "2.5",
                "warnung.heizungsdruck.max", "2"), werte);
        assertEquals("muss unter dem Höchstwert liegen", fehler.get("warnung.heizungsdruck.min"));
    }

    @Test
    public void testErsetzen() {
        Map<String, String> werte = new LinkedHashMap<>();
        werte.put("warnung.heizungsdruck.min", "1.4");
        werte.put("warnung.antwortquote.min", "85");
        werte.put("warnung.spreizung.max", "12");
        // Equal to the default and not in the file: nothing is added
        werte.put("warnung.hochdruck.max", "38.0");
        werte.put("stbl.mail.to", "name@example.org");
        assertEquals(List.of(
                "## Template",
                "stbl.mail.to=name@example.org",
                "spring.mail.password=geheim",
                "",
                "## Warnings by mail",
                "warnung.heizungsdruck.min=1.4",
                "warnung.antwortquote.min=85",
                "",
                Konfiguration.ANGEHAENGT,
                "warnung.spreizung.max=12"), Konfiguration.ersetzen(DATEI, werte));
        // Unchanged values leave the file as it is
        assertEquals(DATEI, Konfiguration.ersetzen(DATEI, Map.of("warnung.antwortquote.min", "90",
                "warnung.heizungsdruck.min", "1.3", "stbl.mail.to", "")));
    }

    @Test
    public void testSpeichernUndStand(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("application.properties");
        Files.write(datei, DATEI, StandardCharsets.ISO_8859_1);
        // The running service was started with the values of the file
        MockEnvironment environment = new MockEnvironment().withProperty("warnung.antwortquote.min", "90");
        Konfiguration konfiguration = new Konfiguration(environment, datei.toString());
        Konfiguration.Stand vorher = konfiguration.stand();
        assertTrue(vorher.schreibbar());
        assertFalse(vorher.neustartNoetig());

        konfiguration.speichern(Map.of("warnung.antwortquote.min", "85"));
        assertEquals(DATEI, Files.readAllLines(ordner.resolve("application.properties.bak")));
        assertTrue(Files.readAllLines(datei).contains("warnung.antwortquote.min=85"));
        // Other entries, also passwords, stay untouched
        assertTrue(Files.readAllLines(datei).contains("spring.mail.password=geheim"));
        Konfiguration.Stand nachher = konfiguration.stand();
        assertTrue(nachher.neustartNoetig());
        Konfiguration.Wert quote = nachher.werte().stream()
                .filter(w -> w.feld().name().equals("warnung.antwortquote.min")).findFirst().orElseThrow();
        assertEquals("85", quote.datei());
        assertEquals("90", quote.laufend());
    }

    @Test
    public void testOhneDatei(@TempDir Path ordner) {
        Konfiguration konfiguration = new Konfiguration(
                new MockEnvironment().withProperty("warnung.hochdruck.max", "40"),
                ordner.resolve("fehlt.properties").toString());
        Konfiguration.Stand stand = konfiguration.stand();
        assertFalse(stand.schreibbar());
        // The running values are shown, so nothing looks like a pending change
        assertFalse(stand.neustartNoetig());
        assertEquals("40", stand.werte().stream().filter(w -> w.feld().name().equals("warnung.hochdruck.max"))
                .findFirst().orElseThrow().datei());
    }

    @Test
    public void testSeite(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("application.properties");
        Files.write(datei, DATEI, StandardCharsets.ISO_8859_1);
        Konfiguration konfiguration = new Konfiguration(new MockEnvironment(), datei.toString());
        String html = KonfigurationSeite.render(konfiguration.stand(), Map.of("stbl.mail.to", "\"><script>"),
                Map.of("stbl.mail.to", "ist keine Mailadresse"), false, "_csrf", "abc");
        assertTrue(html.contains("<a class=\"nav\" href=\"status\">"));
        assertTrue(html.contains("<input type=\"hidden\" name=\"_csrf\" value=\"abc\">"));
        assertTrue(html.contains("name=\"warnung.heizungsdruck.min\" value=\"1,3\""));
        assertTrue(html.contains("Empfänger der Warnmails ist keine Mailadresse."));
        // What was typed is shown again, escaped
        assertTrue(html.contains("value=\"&quot;&gt;&lt;script&gt;\""));
        // Passwords of the file are never on the page
        assertFalse(html.contains("geheim"));
        assertFalse(html.contains("spring.mail.password"));
        assertNull(konfiguration.stand().hinweis());
    }
}
