package de.gedoplan.showcase.virtualthreads.poolsizing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Prüft das Lesen der Aufrufparameter des [BenchmarkRunner]. Ruft nur
/// [BenchmarkRunner#parse] auf, damit ein Fehler nie eine Messreihe startet.
class BenchmarkRunnerOptionsTest {

  // Fehleingaben müssen abbrechen. Sonst misst etwa ein Tippfehler wie rep=1 unbemerkt
  // mit den Standardwerten.
  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      load    | rep=1             | Unbekannter Parameter: rep
      load    | reps              | Parameter ohne Wert
      load    | nmt=of            | Unbekannter Wert für nmt: of
      load    | pools=platform200 | Unbekannter Pool: platform200
      load    | client=defualt    | Unbekannter Wert für client: defualt
      load    | server=tomcat     | Unbekannter Wert für server: tomcat
      threads | delays=1          | Unbekannter Parameter: delays
      load    | requests=2000,abc | Keine positive ganze Zahl für requests: abc
      threads | counts=0          | Keine positive ganze Zahl für counts: 0
      threads | reps=1,2          | Keine positive ganze Zahl für reps: 1,2
      """)
  void rejectsInvalidOptions(String mode, String option, String message) {
    var e = assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.parse(new String[] { mode, option }));
    assertTrue(e.getMessage().startsWith(message), e.getMessage());
  }

  @Test
  void rejectsUnknownMode() {
    var e = assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.parse(new String[] { "foo" }));
    assertEquals("Unbekannter Modus: foo", e.getMessage());
  }

  // Ohne Parameter misst der Runner die Messreihe aus dem Blogbeitrag
  @Test
  void defaultsMatchBlogPost() {
    var settings = BenchmarkRunner.parse(new String[] { "load" });
    assertEquals(10, settings.reps());
    assertEquals(List.of("on"), settings.nmtModes());
    assertEquals(List.of("2000", "10000"), settings.requests());
    assertEquals(List.of("0", "0.1", "1"), settings.delays());
    assertEquals(List.of("platform:200", "platform:2000", "platform:10000", "virtual"), settings.pools());
    assertEquals(List.of("default"), settings.clients());
    assertEquals(List.of(BenchmarkRunner.Server.JDK), settings.servers());
    assertEquals(List.of("200", "2000", "10000"), settings.counts());
  }

  @Test
  void acceptsOptions() {
    var settings = BenchmarkRunner.parse(new String[] { "threads", "reps=1", "counts=5,50", "nmt=on,off" });
    assertEquals("threads", settings.mode());
    assertEquals(1, settings.reps());
    assertEquals(List.of("5", "50"), settings.counts());
    assertEquals(List.of("on", "off"), settings.nmtModes());
  }
}
