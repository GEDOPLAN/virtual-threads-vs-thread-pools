import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Erzeugt die Tabellen in `results/ERGEBNISSE.md` aus den Ergebnisdateien. Ersetzt nur den
/// Bereich zwischen den Markierungen BEGIN/END Auswertung, der Rest der Datei bleibt.
/// Jede Zelle zeigt den Median und in Klammern die Spanne (Minimum–Maximum) der Läufe; für die
/// Laufzeit gibt es zusätzlich Mittelwert und 95-%-Konfidenzintervall (t-Verteilung).
///
/// Aufruf aus dem Projektverzeichnis, ohne Build: `java scripts/Auswertung.java`
public class Auswertung {

  // Gültige Ergebnisdateien. Neue Messläufe hier eintragen.
  static final String THREADS = "threads-20261118-170804.csv";
  static final String THREADS_TURBO_AUS = "turbo-aus/threads-20261118-150033.csv";
  static final String LOAD = "load-20261118-170939.csv";
  static final String ENGPASS = "load-20261118-173503.csv";
  static final String KEEP_ALIVE = "load-20261119-185054.csv";
  static final String NMT_THREADS = "threads-20261119-172625.csv";
  static final String NMT_LOAD = "load-20261119-172919.csv";
  static final List<String> UMGEBUNG = List.of("umgebung-wsl2-20261118-170803.txt", "umgebung-wsl2-20261118-173502.txt",
      "turbo-aus/umgebung-wsl2-20261118-150032.txt", "umgebung-wsl2-20261119-172624.txt",
      "umgebung-wsl2-20261119-172919.txt", "umgebung-wsl2-20261119-185053.txt");
  static final List<String> REPRODUKTION = List.of(
      "reproduktion-wsl2-20261118-173941.txt",
      "reproduktion-windows-jdk25.0.5-20261118-174147.txt",
      "reproduktion-windows-jdk27-20261118-174349.txt",
      "cpu20/reproduktion-windows-jdk25.0.5-cpu20-akku-20261118-191249.txt",
      "cpu20/reproduktion-windows-jdk27-cpu20-akku-20261118-191535.txt");

  static final Path RESULTS = Path.of("results");
  static final String BEGIN = "<!-- BEGIN Auswertung: erzeugt mit java scripts/Auswertung.java, nicht von Hand ändern -->";
  static final String END = "<!-- END Auswertung -->";
  static final Locale DE = Locale.GERMANY;

  static void main(String[] args) throws IOException {
    var out = new StringBuilder();
    umgebung(out);
    threadKosten(out, THREADS, "Thread-Kosten", "");
    threadKosten(out, THREADS_TURBO_AUS, "Thread-Kosten mit Turbo Boost aus (Gegenprobe)",
        "Wie oben, aber mit maximalem Leistungszustand des Prozessors 99 % (Turbo Boost aus). "
            + "Die Umgebungsdatei enthält noch keine Zeile „Max. CPU“, der Wert steht in den Hinweisen oben. ");
    lasttest(out);
    engpass(out);
    keepAlive(out);
    nmtKontrolle(out);
    reproduktion(out);

    Path file = RESULTS.resolve("ERGEBNISSE.md");
    String text = Files.readString(file, StandardCharsets.UTF_8);
    int begin = text.indexOf(BEGIN);
    int end = text.indexOf(END);
    if (begin < 0 || end < begin) {
      throw new IllegalStateException("Markierungen fehlen in " + file);
    }
    Files.writeString(file, text.substring(0, begin + BEGIN.length()) + "\n\n" + out + text.substring(end),
        StandardCharsets.UTF_8);
    System.out.println("Aktualisiert: " + file.toAbsolutePath());
  }

  static void umgebung(StringBuilder out) throws IOException {
    out.append("### Umgebung laut Umgebungsdateien\n\n");
    for (String name : UMGEBUNG) {
      out.append("`results/").append(name).append("`:\n\n```\n");
      out.append(Files.readString(RESULTS.resolve(name), StandardCharsets.UTF_8).strip()).append("\n```\n\n");
    }
  }

  static void threadKosten(StringBuilder out, String file, String title, String intro) throws IOException {
    var rows = csv(file);
    out.append("### ").append(title).append("\n\n");
    source(out, file);
    out.append(intro).append("n Threads starten und warten lassen, ohne HTTP. Speicher als Zuwachs gegenüber dem Zustand vor dem Start.\n\n");
    List<Column> columns = List.of(
        new Column("Start bis alle laufen (ms)", r -> num(r, "startMs"), Auswertung::ms),
        new Column("Speicher gesamt, NMT (MB)", r -> num(r, "totalCommittedDeltaKb") / 1024, Auswertung::mb1),
        new Column("davon NMT „Thread“ (MB)", r -> num(r, "threadCommittedDeltaKb") / 1024, Auswertung::mb1),
        new Column("Heap (MB)", r -> num(r, "heapDeltaKb") / 1024, Auswertung::mb1));
    table(out, "Threads", rows, r -> String.format(DE, "%,d %s", Integer.parseInt(r.get("count")), kind(r.get("kind"))), columns);
  }

  static void lasttest(StringBuilder out) throws IOException {
    var rows = csv(LOAD);
    out.append("### Lasttest: Pools gegen Virtual Threads\n\n");
    source(out, LOAD);
    out.append("Client `HttpClient.newHttpClient()`, Server `HttpServer` des JDK. Zeilen: Pool, Spalten: Requests / Verzögerung in s. ")
        .append("Speicher ist der Spitzenwert der ganzen Client-JVM während der Messung (alle 250 ms abgetastet), inklusive Grundbedarf.\n\n");
    Function<Map<String, String>, String> rowKey = r -> pool(r.get("pool"));
    Function<Map<String, String>, String> colKey = r -> String.format(DE, "%,d / %s", Integer.parseInt(r.get("requests")),
        r.get("delay").replace('.', ','));
    pivot(out, "Laufzeit (ms)", rows, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivotCi(out, "Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)", rows, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivot(out, "Submit-Schleife inkl. Erzeugen der Pool-Threads (ms)", rows, rowKey, colKey, r -> num(r, "submitMs"), Auswertung::ms);
    pivot(out, "Höchste Zahl gleichzeitig laufender Requests", rows, rowKey, colKey, r -> num(r, "maxInFlight"), Auswertung::ms);
    pivot(out, "Verbindungen, die der Server gesehen hat", rows, rowKey, colKey, r -> num(r, "connections"), Auswertung::ms);
    pivot(out, "CPU-Zeit der Client-JVM (s)", rows, rowKey, colKey, r -> num(r, "cpuMs") / 1000, Auswertung::s1);
    pivot(out, "Speicher gesamt, NMT, Spitzenwert (MB)", rows, rowKey, colKey, r -> num(r, "peakTotalCommittedKb") / 1024, Auswertung::mb0);
    pivot(out, "Heap belegt, Spitzenwert (MB)", rows, rowKey, colKey, r -> num(r, "peakHeapUsedKb") / 1024, Auswertung::mb0);
    errors(out, rows);
  }

  static void engpass(StringBuilder out) throws IOException {
    var rows = csv(ENGPASS);
    out.append("### Engpass bei 10.000 Virtual Threads\n\n");
    source(out, ENGPASS);
    out.append("10.000 Requests mit Virtual Threads. Server: `HttpServer` des JDK oder nackter Socket-Server; ")
        .append("Client: `HttpClient` oder nackter Socket (eine Verbindung pro Request). Spalten: Verzögerung in s.\n\n");
    Function<Map<String, String>, String> rowKey = r -> "Server: " + (r.get("server").equals("jdk") ? "HttpServer" : "Socket")
        + ", Client: " + (r.get("client").equals("default") ? "HttpClient" : "Socket");
    Function<Map<String, String>, String> colKey = r -> r.get("delay").replace('.', ',');
    pivot(out, "Laufzeit (ms)", rows, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivotCi(out, "Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)", rows, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivot(out, "Verbindungen, die der Server gesehen hat", rows, rowKey, colKey, r -> num(r, "connections"), Auswertung::ms);
    pivot(out, "CPU-Zeit der Client-JVM (s)", rows, rowKey, colKey, r -> num(r, "cpuMs") / 1000, Auswertung::s1);
    errors(out, rows);
  }

  static void keepAlive(StringBuilder out) throws IOException {
    var rows = csv(KEEP_ALIVE);
    out.append("### Gegenprobe Keep-Alive\n\n");
    source(out, KEEP_ALIVE);
    out.append("10.000 Requests, Client `HttpClient.newHttpClient()`, Server `HttpServer` des JDK, einmal normal und einmal mit ")
        .append("`Connection: close` nach jeder Antwort (der Client kann keine Verbindung wiederverwenden). Spalten: Verzögerung in s. ")
        .append("„Verbindungen laut Kernel“ ist der Zuwachs von `PassiveOpens` in `/proc/net/snmp` direkt um die Messung herum, ")
        .append("ohne Aufwärmphase.\n\n");
    Function<Map<String, String>, String> rowKey = r -> pool(r.get("pool"))
        + (r.get("server").equals("jdk-close") ? ", Connection: close" : ", normal");
    Function<Map<String, String>, String> colKey = r -> r.get("delay").replace('.', ',');
    pivot(out, "Laufzeit (ms)", rows, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivotCi(out, "Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)", rows, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivot(out, "Verbindungen laut Kernel", rows, rowKey, colKey, r -> num(r, "passiveOpens"), Auswertung::ms);
    pivot(out, "Verbindungen, die der Server gesehen hat", rows, rowKey, colKey, r -> num(r, "connections"), Auswertung::ms);
    pivot(out, "CPU-Zeit der Client-JVM (s)", rows, rowKey, colKey, r -> num(r, "cpuMs") / 1000, Auswertung::s1);
    errors(out, rows);
  }

  static void nmtKontrolle(StringBuilder out) throws IOException {
    out.append("### Kontrollmessung: Einfluss der Speichermessung\n\n");
    out.append("Jede Variante abwechselnd mit Speichermessung (`-XX:NativeMemoryTracking=summary` und Abtasten alle 250 ms) ")
        .append("und ohne. Nur innerhalb dieses Laufs vergleichen, nicht mit den Dateien vom 2026-11-18.\n\n");
    Function<Map<String, String>, String> nmt = r -> r.get("nmt").equals("on") ? "mit" : "ohne";

    var threads = csv(NMT_THREADS);
    source(out, NMT_THREADS);
    Function<Map<String, String>, String> threadKey = r -> String.format(DE, "%,d %s", Integer.parseInt(r.get("count")),
        kind(r.get("kind")));
    pivot(out, "Thread-Kosten: Start bis alle laufen (ms)", threads, threadKey, nmt, r -> num(r, "startMs"), Auswertung::ms);
    pivotCi(out, "Thread-Kosten: Start bis alle laufen, Mittelwert ± 95-%-Konfidenzintervall (ms)", threads, threadKey, nmt,
        r -> num(r, "startMs"), Auswertung::ms);

    var load = csv(NMT_LOAD);
    source(out, NMT_LOAD);
    out.append("10.000 Requests, Client `HttpClient.newHttpClient()`, Server `HttpServer` des JDK. Spalten: Verzögerung in s / Speichermessung.\n\n");
    Function<Map<String, String>, String> rowKey = r -> pool(r.get("pool"));
    Function<Map<String, String>, String> colKey = r -> r.get("delay").replace('.', ',') + " / " + nmt.apply(r);
    pivot(out, "Lasttest: Laufzeit (ms)", load, rowKey, colKey, r -> num(r, "durationMs"), Auswertung::ms);
    pivotCi(out, "Lasttest: Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)", load, rowKey, colKey, r -> num(r, "durationMs"),
        Auswertung::ms);
    errors(out, load);
  }

  static final Pattern JAVA_VERSION = Pattern.compile("^# openjdk version \"([^\"]+)\"");
  static final Pattern PLATFORM_POOL = Pattern.compile("^Platform-Pool \\(200 Threads\\)\\s+([\\d.,]+) Sekunden");
  static final Pattern VIRTUAL_POOL = Pattern.compile("^Virtual-Thread-Pool\\s+([\\d.,]+) Sekunden");
  static final Pattern MAX_CPU = Pattern.compile("^# Max\\. CPU: (.+)$");
  static final Pattern NETZTEIL = Pattern.compile("^# Netzteil: (.+)$");

  static void reproduktion(StringBuilder out) throws IOException {
    out.append("### Reproduktion der Original-Demo\n\n");
    out.append("Unveränderte `VirtualThreadsDemo` (2.000 Requests, 1 s Verzögerung), 10 Läufe je Datei. ")
        .append("Läuft der Virtual-Thread-Teil nicht durch, fehlt sein Wert. ")
        .append("Max. CPU ist der maximale Leistungszustand des Prozessors (unter 100 % ist der Turbo Boost aus).\n\n");
    out.append("| Datei | Plattform | Java | Max. CPU | Netzteil | Pool 200 (s) | Virtual Threads (s) | Virtual Threads durchgelaufen |\n");
    out.append("|---|---|---|---|---|---|---|---|\n");
    for (String name : REPRODUKTION) {
      String java = "?";
      String maxCpu = "–";
      String netzteil = "–";
      int runs = 0;
      List<Double> platform = new ArrayList<>();
      List<Double> virtual = new ArrayList<>();
      for (String line : Files.readAllLines(RESULTS.resolve(name), StandardCharsets.UTF_8)) {
        Matcher m;
        if ((m = JAVA_VERSION.matcher(line)).find()) {
          java = m.group(1);
        } else if ((m = MAX_CPU.matcher(line)).find()) {
          maxCpu = m.group(1);
        } else if ((m = NETZTEIL.matcher(line)).find()) {
          netzteil = switch (m.group(1)) {
            case "True" -> "ja";
            case "False" -> "nein";
            default -> m.group(1);
          };
        } else if (line.startsWith("=== Lauf ")) {
          runs++;
        } else if ((m = PLATFORM_POOL.matcher(line)).find()) {
          platform.add(Double.parseDouble(m.group(1).replace(',', '.')));
        } else if ((m = VIRTUAL_POOL.matcher(line)).find()) {
          virtual.add(Double.parseDouble(m.group(1).replace(',', '.')));
        }
      }
      String plattform = name.contains("wsl2") ? "WSL2" : "Windows";
      out.append(String.format(DE, "| `%s` | %s | %s | %s | %s | %s | %s | %d von %d |\n", name, plattform, java, maxCpu, netzteil,
          cell(platform, Auswertung::s2), cell(virtual, Auswertung::s2), virtual.size(), runs));
    }
    out.append('\n');
  }

  // --- Tabellen

  record Column(String title, ToDoubleFunction<Map<String, String>> value, Function<Double, String> format) {
  }

  /// Eine Zeile je Schlüssel, eine Spalte je Messgröße.
  static void table(StringBuilder out, String keyTitle, List<Map<String, String>> rows,
      Function<Map<String, String>, String> rowKey, List<Column> columns) {
    var groups = group(rows, rowKey);
    out.append("| ").append(keyTitle);
    columns.forEach(c -> out.append(" | ").append(c.title()));
    out.append(" |\n|---").append("|---".repeat(columns.size())).append("|\n");
    groups.forEach((key, group) -> {
      out.append("| ").append(key);
      columns.forEach(c -> out.append(" | ").append(cell(values(group, c.value()), c.format())));
      out.append(" |\n");
    });
    out.append('\n');
  }

  /// Eine Messgröße, Zeilen und Spalten nach Schlüsseln, Reihenfolge wie in der CSV.
  static void pivot(StringBuilder out, String title, List<Map<String, String>> rows,
      Function<Map<String, String>, String> rowKey, Function<Map<String, String>, String> colKey,
      ToDoubleFunction<Map<String, String>> value, Function<Double, String> format) {
    pivotCells(out, title, rows, rowKey, colKey, value, v -> cell(v, format));
  }

  /// Wie oben, aber Mittelwert ± Halbbreite des 95-%-Konfidenzintervalls je Zelle.
  static void pivotCi(StringBuilder out, String title, List<Map<String, String>> rows,
      Function<Map<String, String>, String> rowKey, Function<Map<String, String>, String> colKey,
      ToDoubleFunction<Map<String, String>> value, Function<Double, String> format) {
    pivotCells(out, title, rows, rowKey, colKey, value, v -> ci(v, format));
  }

  static void pivotCells(StringBuilder out, String title, List<Map<String, String>> rows,
      Function<Map<String, String>, String> rowKey, Function<Map<String, String>, String> colKey,
      ToDoubleFunction<Map<String, String>> value, Function<List<Double>, String> cell) {
    var cols = group(rows, colKey).keySet();
    out.append("**").append(title).append("**\n\n| ");
    cols.forEach(c -> out.append(" | ").append(c));
    out.append(" |\n|---").append("|---".repeat(cols.size())).append("|\n");
    group(rows, rowKey).forEach((key, group) -> {
      out.append("| ").append(key);
      var byCol = group(group, colKey);
      cols.forEach(c -> out.append(" | ").append(byCol.containsKey(c) ? cell.apply(values(byCol.get(c), value)) : "–"));
      out.append(" |\n");
    });
    out.append('\n');
  }

  static void errors(StringBuilder out, List<Map<String, String>> rows) {
    long errors = rows.stream().mapToLong(r -> Long.parseLong(r.get("errors"))).sum();
    out.append(String.format(DE, "Fehlerhafte Requests in allen %d Messungen zusammen: %d.%n%n", rows.size(), errors));
  }

  static void source(StringBuilder out, String name) {
    out.append("Datei: `results/").append(name).append("`\n\n");
  }

  static Map<String, List<Map<String, String>>> group(List<Map<String, String>> rows,
      Function<Map<String, String>, String> key) {
    Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
    rows.forEach(r -> groups.computeIfAbsent(key.apply(r), _ -> new ArrayList<>()).add(r));
    return groups;
  }

  static List<Double> values(List<Map<String, String>> rows, ToDoubleFunction<Map<String, String>> value) {
    return rows.stream().map(value::applyAsDouble).toList();
  }

  /// Median (Minimum–Maximum); bei nur einem Wert der Wert allein.
  static String cell(List<Double> values, Function<Double, String> format) {
    if (values.isEmpty()) {
      return "–";
    }
    double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    int n = v.length;
    double median = n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    if (n == 1) {
      return format.apply(median);
    }
    return format.apply(median) + " (" + format.apply(v[0]) + "–" + format.apply(v[n - 1]) + ")";
  }

  // Quantile der t-Verteilung (zweiseitig 95 %) für 1 bis 30 Freiheitsgrade
  static final double[] T95 = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
      2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
      2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

  /// Mittelwert ± Halbbreite des 95-%-Konfidenzintervalls (t-Verteilung, n−1 Freiheitsgrade).
  static String ci(List<Double> values, Function<Double, String> format) {
    int n = values.size();
    if (n < 2) {
      return n == 0 ? "–" : format.apply(values.getFirst());
    }
    double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    double var = values.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1);
    double t = n - 1 <= T95.length ? T95[n - 2] : 1.96;
    return format.apply(mean) + " ± " + format.apply(t * Math.sqrt(var / n));
  }

  // --- Formate und Bezeichnungen

  static String ms(double v) {
    return String.format(DE, "%,.0f", v);
  }

  static String s1(double v) {
    return String.format(DE, "%,.1f", v);
  }

  static String s2(double v) {
    return String.format(DE, "%,.2f", v);
  }

  static String mb0(double v) {
    return String.format(DE, "%,.0f", v);
  }

  static String mb1(double v) {
    return String.format(DE, "%,.1f", v);
  }

  static String kind(String kind) {
    return kind.equals("platform") ? "Platform-Threads" : "Virtual Threads";
  }

  static String pool(String pool) {
    return pool.equals("virtual") ? "Virtual Threads"
        : String.format(DE, "Pool %,d", Integer.parseInt(pool.substring("platform:".length())));
  }

  static double num(Map<String, String> row, String key) {
    return Double.parseDouble(row.get(key));
  }

  static List<Map<String, String>> csv(String name) throws IOException {
    var lines = Files.readAllLines(RESULTS.resolve(name), StandardCharsets.UTF_8);
    String[] header = lines.getFirst().split(";");
    List<Map<String, String>> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] values = line.split(";");
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.length; i++) {
        row.put(header[i], values[i]);
      }
      rows.add(row);
    }
    return rows;
  }
}
