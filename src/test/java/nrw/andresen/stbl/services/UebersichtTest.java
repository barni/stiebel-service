package nrw.andresen.stbl.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UebersichtTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final long MIN = 60_000;

    @Test
    public void testRahmen() {
        String kopf = Rahmen.kopf("zustand", "", true, List.of(
                new Rahmen.Signal("Betrieb", "Stillstand seit 14:32", "neutral", "■", "status"),
                new Rahmen.Signal("Warnungen", "keine", "ok", "✓", "anlage#meldungen")), "14:59");
        assertTrue(kopf.contains("<a href=\"zustand\" aria-current=\"page\">Zustand</a>"));
        assertTrue(kopf.contains("<a href=\"status\">Übersicht</a>"));
        assertTrue(kopf.contains("Stand 14:59"));
        assertTrue(kopf.contains("<a class=\"signal ok\" href=\"anlage#meldungen\">"));
        // Without InfluxDB the pages from stored values are left out
        String ohne = Rahmen.kopf("status", "../", false, List.of(), "14:59");
        assertFalse(ohne.contains("wochenbericht"));
        assertTrue(ohne.contains("<a href=\"../canbus\">CAN-Bus</a>"));
        assertFalse(ohne.contains("class=\"signale\""));

        String seite = "<main><header><div><h1>Zustand</h1><p class=\"sub\">Je Saison</p></div>"
                + "<nav><a class=\"nav\" href=\"status\">← Status</a></nav></header><p>Inhalt</p></main>";
        String neu = Rahmen.einsetzen(seite, "<header class=\"kopf\">K</header>");
        assertEquals("<main><header class=\"kopf\">K</header><div class=\"seitentitel\"><div><h1>Zustand</h1>"
                + "<p class=\"sub\">Je Saison</p></div></div><p>Inhalt</p></main>", neu);
        assertEquals("<p>ohne Kopf</p>", Rahmen.einsetzen("<p>ohne Kopf</p>", "K"));
        // Links of the page itself stay, links to other pages are tabs
        String can = Rahmen.einsetzen("<main><header><div><h1>CAN-Bus</h1></div><nav><a class=\"nav\" href=\"status\">"
                + "← Status</a> · <a class=\"nav\" href=\"canbus/scan\">Bus-Scan</a></nav></header></main>", "K");
        assertTrue(can.contains("<nav class=\"seitenlinks\"><a class=\"nav\" href=\"canbus/scan\">Bus-Scan</a></nav>"));
        assertFalse(can.contains("← Status"));
    }

    @Test
    public void testSignale() {
        Instant jetzt = Instant.parse("2026-11-18T14:00:00Z");
        Rahmen.Signal heizt = SignalService.betrieb(true, false, jetzt.minusSeconds(42 * 60), null, 890.0, jetzt);
        assertEquals("Heizt seit 42 min · Leistung ≈ 0,89 kW", heizt.wert());
        assertEquals("aktiv", heizt.art());
        assertEquals("Taut ab", SignalService.betrieb(true, true, null, null, 900.0, jetzt).wert());
        assertTrue(SignalService.betrieb(false, false, null, jetzt.minusSeconds(3600), 0.0, jetzt).wert()
                .startsWith("Stillstand seit "));
        assertEquals("keine Daten", SignalService.betrieb(null, null, null, null, null, jetzt).wert());
        assertEquals("1:07 h", SignalService.dauer(jetzt.minusSeconds(67 * 60), jetzt));

        assertEquals("ok", SignalService.warnungen(List.of()).art());
        assertEquals("Heizungsdruck niedrig", SignalService.warnungen(List.of("Heizungsdruck niedrig")).wert());
        assertEquals("2 aktiv: A, B", SignalService.warnungen(List.of("A", "B")).wert());

        assertEquals("wird berechnet", SignalService.zustand(null).wert());
        Rahmen.Signal beobachten = SignalService.zustand(List.of(
                new Zustand.Pruefung("Arbeitszahl", "2025/26", 4.0, 4.0, "", 2, "unauffällig", ""),
                new Zustand.Pruefung("Druckschwankung je 10 K", "2025/26", 0.4, 0.3, "bar", 3, "beobachten", "")));
        assertEquals("beobachten: Druckschwankung je 10 K", beobachten.wert());
        assertEquals("achtung", beobachten.art());

        assertEquals("Prüfung läuft", SignalService.dienst(List.of(), null).wert());
        assertEquals("OK · Antworten 100 %", SignalService.dienst(List.of(
                new Ueberwachung.Pruefung("InfluxDB", true, "OK", ""),
                new Ueberwachung.Pruefung("Mail", false, "nicht eingerichtet", "")), 100.0).wert());
        Rahmen.Signal fehler = SignalService.dienst(List.of(new Ueberwachung.Pruefung("InfluxDB", false, "aus", "")),
                99.0);
        assertEquals("Fehler: InfluxDB", fehler.wert());
        assertEquals("warn", fehler.art());
    }

    @Test
    public void testJetzt() {
        String html = Uebersicht.jetzt(new Uebersicht.Jetzt("Heizt", "aktiv", "seit 42 min · Strom ≈ 0,89 kW",
                "Vorlauf 34,6 °C.", new Uebersicht.Balken("Reserve bis Abtauung", "2,3 von 3,1 K", "", 0.74, "aktiv",
                "Sinkt mit Eis."), List.of(new Uebersicht.Zahl("Starts", "4", "", "gestern 5"))));
        assertTrue(html.contains("<section class=\"jetzt aktiv\""));
        assertTrue(html.contains("<span class=\"jetzt-zustand\">Heizt</span>"));
        assertTrue(html.contains("style=\"width:74.0%\""));
        assertTrue(html.contains("gestern 5"));
        // A bar is limited to 0 to 100 %
        assertTrue(Uebersicht.jetzt(new Uebersicht.Jetzt("Stillstand", "neutral", "", null,
                new Uebersicht.Balken("", "", "", 1.4, "neutral", null), List.of())).contains("width:100.0%"));
    }

    @Test
    public void testKacheln() {
        assertEquals("0.0,28.0 50.0,15.0 100.0,2.0", Uebersicht.punkte(new long[]{0, 5, 10},
                new double[]{0, 5, 10}, 0, 10));
        assertEquals("", Uebersicht.punkte(new long[]{0}, new double[]{1}, 0, 10));
        String html = Uebersicht.kacheln(List.of(
                new Uebersicht.Kachel("Heizungsdruck", "1,79", "bar", "Warnung unter 1,3", new long[]{0, 1},
                        new double[]{1.78, 1.80}, 1.7, 1.9, "druck", "Heizungsdruck", 2),
                new Uebersicht.Kachel("Leistung jetzt ≈", "0", "W", "", new long[0], new double[0], 0, 1000, "strom",
                        null, 0)));
        assertTrue(html.contains("class=\"kachel klickbar\" data-series=\"Heizungsdruck\""));
        assertTrue(html.contains("data-decimals=\"2\""));
        assertTrue(html.contains("<svg class=\"spark\" viewBox=\"0 0 100 30\" preserveAspectRatio=\"none\" "
                + "aria-hidden=\"true\" data-verlauf=\""));
        // Without a course no chart and no dialog
        assertTrue(html.contains("<div class=\"kachel\"><span class=\"kachel-name\">Leistung jetzt ≈</span>"));
    }

    @Test
    public void testZeitleiste() {
        long start = Instant.parse("2026-11-17T14:00:00Z").toEpochMilli();
        long ende = start + 24 * 60 * MIN;
        long[] zeit = {start + 10 * MIN, start + 20 * MIN, start + 30 * MIN, start + 40 * MIN};
        double[] watt = {0, 600, 650, 0};
        List<long[]> laeufe = Uebersicht.laeufe(zeit, watt, 50, 10 * MIN);
        assertEquals(1, laeufe.size());
        assertEquals(start + 10 * MIN, laeufe.get(0)[0]);
        assertEquals(start + 30 * MIN, laeufe.get(0)[1]);
        String satz = Uebersicht.zusammenfassung(laeufe, 0.2, 1, ZONE);
        assertEquals("1 Lauf, zusammen 0,3 h · Strom ≈ 0,2 kWh · längster Lauf 15:10–15:30 (0:20 h) · 1 Abtauung "
                + "· aus 10-Minuten-Mitteln", satz);
        assertEquals("Kein Lauf in den letzten 24 Stunden", Uebersicht.zusammenfassung(List.of(), 0, 0, ZONE));

        String html = Uebersicht.zeitleiste(new Uebersicht.Zeitleiste(start, ende, zeit, watt,
                List.of(start + 12 * 60 * MIN), List.of(new Uebersicht.Linie("Vorlauf", "vorlauf", zeit,
                new double[]{33, 34, 35, Double.NaN})), satz), ZONE);
        assertTrue(html.contains("<polygon points=\"0,58"));
        assertTrue(html.contains("class=\"zl-abtau\" data-wert=\"Abtauung 03:00\" style=\"left:50.00%\""));
        // Values under the pointer: electricity and lines with their times, the gap as null
        assertTrue(html.contains("class=\"zl-raster\" data-verlauf=\"{&quot;von&quot;:" + start));
        assertTrue(html.contains("&quot;n&quot;:&quot;Leistung ≈&quot;,&quot;e&quot;:&quot;W&quot;,&quot;s&quot;:0"));
        assertTrue(html.contains("&quot;w&quot;:[33.0,34.0,35.0,null]"));
        assertTrue(html.contains("<div class=\"zl-strom\" data-plot>"));
        assertTrue(html.contains("<polyline class=\"vorlauf\""));
        // Water and outdoor temperature each in their own band with their own scale
        assertTrue(html.contains("<span class=\"zl-achse-name\">Wasser °C</span><div class=\"zl-temp\" data-plot>"));
        assertFalse(html.contains("Außen °C"));
        double[] aussen = Uebersicht.skala(8.0, 11.4, 4);
        assertTrue(aussen[0] < 8.0 && aussen[1] > 11.4 && aussen[1] - aussen[0] < 6);
        assertEquals(2, aussen[2], 1e-9);
        double[] wasser = Uebersicht.skala(24.2, 36.8, 10);
        assertEquals(5, wasser[2], 1e-9);
        assertTrue(wasser[0] > 15 && wasser[1] < 45);
        assertTrue(html.contains(">jetzt</span>"));
        assertTrue(html.contains(satz));
    }
}
