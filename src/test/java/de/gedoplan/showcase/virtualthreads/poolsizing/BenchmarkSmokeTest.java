package de.gedoplan.showcase.virtualthreads.poolsizing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Startet den [BenchmarkRunner] mit kleinen Parametern für alle Varianten und prüft, dass
/// jede Messung vollständige und plausible Werte liefert. Keine Messung: Die Laufzeiten
/// sind bedeutungslos.
///
/// Fängt vor allem Fehler, die sich sonst nicht melden: Ändert ein neueres JDK das Format
/// des Native Memory Tracking, wären alle Speicherwerte still `-1`.
/// Dauert rund eine Minute, weil jede Messung eigene JVMs startet.
class BenchmarkSmokeTest {

  // Spalten, die scripts/Auswertung.java erwartet, in der Reihenfolge der CSV
  private static final List<String> LOAD_COLUMNS = List.of("client", "connections", "cpuMs", "delay", "durationMs",
      "errorTypes", "errors", "maxInFlight", "passiveOpens", "peakHeapUsedKb", "peakPlatformThreads",
      "peakThreadCommittedKb", "peakTotalCommittedKb", "pool", "requests", "shutdownMs", "submitMs", "nmt", "server", "rep");
  private static final List<String> THREADS_COLUMNS = List.of("kind", "count", "startMs", "joinMs", "heapDeltaKb",
      "threadCommittedDeltaKb", "totalCommittedDeltaKb", "platformThreads", "nmt", "rep");

  // PassiveOpens gibt es nur unter Linux
  private static final boolean LINUX = Files.exists(Path.of("/proc/net/snmp"));

  @Test
  void load() throws Exception {
    var rows = run("load", "reps=1", "requests=100", "delays=0.1", "pools=platform:20,virtual",
        "client=default,virtual,socket", "server=jdk,jdk-close,socket", "nmt=on,off");

    assertEquals(LOAD_COLUMNS, List.copyOf(rows.getFirst().keySet()));
    assertEquals(2 * 3 * 3 * 2, rows.size());
    for (var row : rows) {
      String variant = row.toString();
      assertEquals(0, num(row, "errors"), variant);
      // 0,1 s Verzögerung: kein Request kann schneller fertig sein
      assertTrue(num(row, "durationMs") >= 100, variant);
      assertTrue(num(row, "maxInFlight") >= 1, variant);
      if (row.get("pool").equals("platform:20")) {
        assertTrue(num(row, "maxInFlight") <= 20, variant);
      }
      assertTrue(num(row, "connections") >= 1, variant);
      if (LINUX) {
        // Der Server zählt nur eine Untergrenze, der Kernel zählt genau
        assertTrue(num(row, "passiveOpens") >= num(row, "connections"), variant);
      } else {
        assertEquals(-1, num(row, "passiveOpens"), variant);
      }
      assertTrue(num(row, "peakHeapUsedKb") > 0, variant);
      if (row.get("nmt").equals("on")) {
        assertTrue(num(row, "peakThreadCommittedKb") > 0, variant);
        assertTrue(num(row, "peakTotalCommittedKb") > 0, variant);
      } else {
        assertEquals(-1, num(row, "peakThreadCommittedKb"), variant);
        assertEquals(-1, num(row, "peakTotalCommittedKb"), variant);
      }
    }
  }

  @Test
  void threads() throws Exception {
    var rows = run("threads", "reps=1", "counts=200", "nmt=on,off");

    assertEquals(THREADS_COLUMNS, List.copyOf(rows.getFirst().keySet()));
    assertEquals(2 * 2, rows.size());
    for (var row : rows) {
      String variant = row.toString();
      boolean platform = row.get("kind").equals("platform");
      assertTrue(num(row, "platformThreads") >= (platform ? 200 : 1), variant);
      if (!platform) {
        // Die Stacks wartender Virtual Threads liegen auf dem Heap
        assertTrue(num(row, "heapDeltaKb") > 0, variant);
      }
      if (row.get("nmt").equals("off")) {
        // Ohne NMT sind beide Werte -1, die Differenz also 0
        assertEquals(0, num(row, "threadCommittedDeltaKb"), variant);
        assertEquals(0, num(row, "totalCommittedDeltaKb"), variant);
      } else if (platform) {
        // Die Stacks der Platform-Threads liegen im NMT-Bereich "Thread"
        assertTrue(num(row, "threadCommittedDeltaKb") > 0, variant);
        assertTrue(num(row, "totalCommittedDeltaKb") > 0, variant);
      }
    }
  }

  /// Führt den Runner aus und liest die erzeugte CSV-Datei. Die Ergebnisse landen in
  /// `target/smoke-test/<mode>`, damit sie nicht zwischen die echten in `results/` geraten.
  private static List<Map<String, String>> run(String mode, String... options) throws Exception {
    var argList = new ArrayList<>(List.of(mode));
    argList.addAll(List.of(options));
    String[] args = argList.toArray(String[]::new);
    assertSmall(BenchmarkRunner.parse(args));

    Path dir = Path.of("target", "smoke-test", mode);
    deleteRecursively(dir);
    Files.createDirectories(dir);
    System.setProperty("results.dir", dir.toString());
    try {
      BenchmarkRunner.main(args);
    } finally {
      System.clearProperty("results.dir");
    }
    try (var files = Files.list(dir)) {
      return csv(files.filter(f -> f.getFileName().toString().startsWith(mode + "-")).findFirst().orElseThrow());
    }
  }

  private static List<Map<String, String>> csv(Path file) throws IOException {
    var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    String[] header = lines.getFirst().split(";");
    List<Map<String, String>> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] values = line.split(";");
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.length; i++) {
        row.put(header[i], values[i]);
      }
      rows.add(row);
    }
    return rows;
  }

  /// Schutz vor einer langen Messreihe: Liest [BenchmarkRunner#parse] die Parameter falsch,
  /// würde [BenchmarkRunner#main] sonst womöglich stundenlang mit den Standardwerten messen.
  private static void assertSmall(BenchmarkRunner.Settings s) {
    int measurements = s.mode().equals("load")
        ? s.reps() * s.servers().size() * s.clients().size() * s.requests().size() * s.delays().size()
            * s.pools().size() * s.nmtModes().size()
        : s.reps() * s.counts().size() * 2 * s.nmtModes().size();
    assertTrue(measurements <= 50, "zu viele Messungen: " + measurements);
    var sizes = s.mode().equals("load") ? s.requests() : s.counts();
    assertTrue(sizes.stream().allMatch(n -> Integer.parseInt(n) <= 1000), "zu große Messung: " + sizes);
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (Files.exists(dir)) {
      try (var paths = Files.walk(dir)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }

  private static long num(Map<String, String> row, String column) {
    return Long.parseLong(row.get(column));
  }
}
