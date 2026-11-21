package de.gedoplan.showcase.virtualthreads.poolsizing;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Führt die Messreihe aus. Jede Messung läuft in einer frischen Client-JVM, bei `load`
/// gegen einen frisch gestarteten Server in einer eigenen JVM. Die Ergebnisse landen als
/// CSV in `results/`.
///
/// Aufruf nach `mvn compile`, optionale Parameter als `key=value`, Listen kommagetrennt:
///
/// ```
/// java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner load [key=value ...]
/// java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner threads [key=value ...]
/// ```
///
/// Modus `load` (Requests gegen den Server, siehe [LoadClient]):
/// - `requests`: Zahl der Requests, Standard `2000,10000`
/// - `delays`: Antwortzeit des Servers in Sekunden, Standard `0,0.1,1`
/// - `pools`: `platform:<n>` (Pool mit n Platform-Threads) oder `virtual`,
///   Standard `platform:200,platform:2000,platform:10000,virtual`
/// - `client`: `default`, `virtual` oder `socket` (siehe [LoadClient]), Standard `default`
/// - `server`: `jdk` ([DelayServer]), `jdk-close` (derselbe mit `Connection: close`) oder
///   `socket` ([SocketDelayServer]), Standard `jdk`
///
/// Modus `threads` (Kosten wartender Threads, siehe [ThreadCost]):
/// - `counts`: Zahl der Threads, Standard `200,2000,10000`
///
/// Für beide Modi:
/// - `reps`: Zahl der Wiederholungen, Standard `10`
/// - `nmt`: `on` (mit Native Memory Tracking und Speicher-Sampler) oder `off` (ohne beides,
///   Kontrollmessung); `on,off` misst beides abwechselnd. Standard `on`
///
/// Die Standardwerte ergeben die Messreihe aus dem Blogbeitrag. Ein anderes Verzeichnis für
/// die Ergebnisse lässt sich mit `-Dresults.dir=<verzeichnis>` angeben (für die Tests).
public class BenchmarkRunner {

  private static final String JAVA = ProcessHandle.current().info().command().orElse("java");
  private static final String CLASSPATH = System.getProperty("java.class.path");

  private static final Set<String> COMMON_OPTIONS = Set.of("reps", "nmt");
  private static final Set<String> LOAD_OPTIONS = Set.of("requests", "delays", "pools", "client", "server");
  private static final Set<String> THREADS_OPTIONS = Set.of("counts");

  /// Einstellungen einer Messreihe, aus den Aufrufparametern gelesen und geprüft. Die Werte
  /// des jeweils anderen Modus bleiben auf ihren Standardwerten und werden nicht benutzt.
  record Settings(String mode, int reps, List<String> nmtModes, List<String> requests, List<String> delays,
      List<String> pools, List<String> clients, List<String> servers, List<String> counts) {
  }

  static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Modus fehlt: load oder threads");
      System.exit(1);
    }
    var settings = parse(args);
    if (settings.mode().equals("load")) {
      runLoad(settings.reps(), settings.nmtModes(), settings.requests(), settings.delays(), settings.pools(),
          settings.clients(), settings.servers());
    } else {
      runThreadCost(settings.reps(), settings.nmtModes(), settings.counts());
    }
  }

  /// Liest und prüft die Aufrufparameter, ohne etwas zu messen. Getrennt von [#main], damit
  /// die Tests die Prüfung aufrufen können, ohne dass ein Fehler eine Messreihe startet.
  static Settings parse(String[] args) {
    String mode = args[0];
    Set<String> modeOptions = switch (mode) {
      case "load" -> LOAD_OPTIONS;
      case "threads" -> THREADS_OPTIONS;
      default -> throw new IllegalArgumentException("Unbekannter Modus: " + mode);
    };
    Map<String, String> opts = parseOptions(Arrays.copyOfRange(args, 1, args.length), modeOptions);
    return new Settings(mode,
        Integer.parseInt(opts.getOrDefault("reps", "10")),
        values(opts.getOrDefault("nmt", "on"), "nmt", Set.of("on", "off")),
        list(opts.getOrDefault("requests", "2000,10000")),
        list(opts.getOrDefault("delays", "0,0.1,1")),
        pools(opts.getOrDefault("pools", "platform:200,platform:2000,platform:10000,virtual")),
        values(opts.getOrDefault("client", "default"), "client", Set.of("default", "virtual", "socket")),
        values(opts.getOrDefault("server", "jdk"), "server", Set.of("jdk", "jdk-close", "socket")),
        list(opts.getOrDefault("counts", "200,2000,10000")));
  }

  /// Liest die Parameter `key=value`. Bricht bei unbekannten Schlüsseln ab, damit ein
  /// Tippfehler (z. B. `rep=1`) nicht unbemerkt mit den Standardwerten misst.
  private static Map<String, String> parseOptions(String[] args, Set<String> modeOptions) {
    Map<String, String> opts = new HashMap<>();
    for (String arg : args) {
      String[] kv = arg.split("=", 2);
      if (kv.length != 2) {
        throw new IllegalArgumentException("Parameter ohne Wert, erwartet key=value: " + arg);
      }
      if (!COMMON_OPTIONS.contains(kv[0]) && !modeOptions.contains(kv[0])) {
        throw new IllegalArgumentException("Unbekannter Parameter: " + kv[0]);
      }
      opts.put(kv[0], kv[1]);
    }
    return opts;
  }

  private static void runLoad(int reps, List<String> nmtModes, List<String> requests, List<String> delays, List<String> pools,
      List<String> clients, List<String> servers) throws Exception {
    try (var csv = csvWriter("load")) {
      boolean header = true;
      // Wiederholungen außen, damit Drift (z. B. Temperatur) alle Varianten gleich trifft
      for (int rep = 1; rep <= reps; rep++) {
        for (String serverType : servers) {
          for (String client : clients) {
            for (String n : requests) {
              for (String delay : delays) {
                for (String pool : pools) {
                  for (String nmtMode : nmtModes) {
                    Process server = startServer(serverType);
                    try {
                      int port = readPort(server);
                      var result = runJvm(LoadClient.class, nmtMode, String.valueOf(port), pool, n, delay, client);
                      result.put("server", serverType);
                      result.put("rep", String.valueOf(rep));
                      write(csv, result, header);
                      header = false;
                      System.out.printf("[%d/%d] %-16s n=%-6s delay=%-4s client=%-8s server=%-6s nmt=%-3s %6s ms  maxInFlight=%-6s connections=%-6s passiveOpens=%-6s errors=%s%n",
                          rep, reps, pool, n, delay, client, serverType, nmtMode, result.get("durationMs"),
                          result.get("maxInFlight"), result.get("connections"), result.get("passiveOpens"), result.get("errors"));
                    } finally {
                      server.destroy();
                      server.waitFor();
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private static void runThreadCost(int reps, List<String> nmtModes, List<String> counts) throws Exception {
    try (var csv = csvWriter("threads")) {
      boolean header = true;
      for (int rep = 1; rep <= reps; rep++) {
        for (String count : counts) {
          for (String kind : List.of("platform", "virtual")) {
            for (String nmtMode : nmtModes) {
              var result = runJvm(ThreadCost.class, nmtMode, kind, count);
              result.put("rep", String.valueOf(rep));
              write(csv, result, header);
              header = false;
              System.out.printf("[%d/%d] %-8s n=%-6s nmt=%-3s start=%5s ms  heap=%7s KB  nmtThread=%8s KB  nmtTotal=%8s KB%n",
                  rep, reps, kind, count, nmtMode, result.get("startMs"), result.get("heapDeltaKb"),
                  result.get("threadCommittedDeltaKb"), result.get("totalCommittedDeltaKb"));
            }
          }
        }
      }
    }
  }

  /// Für die Mess-JVMs ([ThreadCost], [LoadClient]): Bricht `main` mit einem Fehler ab, z. B.
  /// weil ab einer Grenze des Betriebssystems keine Threads mehr starten, endet die JVM sofort.
  /// Sonst wartet sie auf die schon gestarteten Threads, die nie fertig werden, und der Runner
  /// wartet ewig auf sie.
  static void exitOnUncaughtException() {
    Thread.currentThread().setUncaughtExceptionHandler((_, e) -> abort(e));
  }

  /// Gibt den Fehler aus und beendet die JVM, ohne auf andere Threads zu warten.
  static void abort(Throwable e) {
    e.printStackTrace();
    System.exit(1);
  }

  private static Process startServer(String serverType) throws IOException {
    List<String> command = new ArrayList<>(List.of(JAVA, "-cp", CLASSPATH));
    command.addAll(switch (serverType) {
      case "jdk" -> List.of(DelayServer.class.getName());
      case "jdk-close" -> List.of(DelayServer.class.getName(), "close");
      case "socket" -> List.of(SocketDelayServer.class.getName());
      default -> throw new IllegalArgumentException("Unbekannter Server: " + serverType);
    });
    return new ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
  }

  /// Liest den Port aus der ersten Zeile, die der Server ausgibt (`PORT <n>`).
  private static int readPort(Process server) throws IOException {
    String line = server.inputReader(StandardCharsets.UTF_8).readLine();
    if (line == null || !line.startsWith("PORT ")) {
      throw new IllegalStateException("Server lieferte keinen Port: " + line);
    }
    return Integer.parseInt(line.substring("PORT ".length()));
  }

  /// Startet `mainClass` in einer eigenen JVM und liefert deren Ergebniszeile
  /// (`RESULT key=value ...`) als Map. Alle übrigen Ausgaben werden eingerückt durchgereicht.
  private static Map<String, String> runJvm(Class<?> mainClass, String nmtMode, String... args) throws Exception {
    boolean nmt = nmtMode.equals("on");
    List<String> command = new ArrayList<>(List.of(JAVA,
        nmt ? "-XX:NativeMemoryTracking=summary" : "-Dsampler=off",
        "-cp", CLASSPATH, mainClass.getName()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    String resultLine = null;
    try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("RESULT ")) {
          resultLine = line;
        } else {
          System.out.println("  " + line);
        }
      }
    }
    int exit = process.waitFor();
    if (resultLine == null) {
      throw new IllegalStateException(mainClass.getSimpleName() + " ohne Ergebnis beendet, Exit-Code " + exit);
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (String kv : resultLine.substring("RESULT ".length()).split(" ")) {
      String[] parts = kv.split("=", 2);
      result.put(parts[0], parts[1]);
    }
    result.put("nmt", nmtMode);
    return result;
  }

  private static PrintWriter csvWriter(String mode) throws IOException {
    Path dir = Path.of(System.getProperty("results.dir", "results"));
    Files.createDirectories(dir);
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path file = dir.resolve(mode + "-" + timestamp + ".csv");
    System.out.println("Ergebnisse: " + file.toAbsolutePath());
    return new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true);
  }

  private static void write(PrintWriter csv, Map<String, String> row, boolean header) {
    if (header) {
      csv.println(String.join(";", row.keySet()));
    }
    csv.println(String.join(";", row.values()));
  }

  private static List<String> list(String value) {
    return List.of(value.split(","));
  }

  /// Kommagetrennte Liste, deren Werte alle in `allowed` liegen müssen.
  private static List<String> values(String value, String key, Set<String> allowed) {
    List<String> values = list(value);
    for (String v : values) {
      if (!allowed.contains(v)) {
        throw new IllegalArgumentException("Unbekannter Wert für " + key + ": " + v + ", erlaubt: " + allowed);
      }
    }
    return values;
  }

  private static List<String> pools(String value) {
    List<String> pools = list(value);
    for (String pool : pools) {
      if (!pool.matches("virtual|platform:\\d+")) {
        throw new IllegalArgumentException("Unbekannter Pool: " + pool + ", erlaubt: platform:<n> oder virtual");
      }
    }
    return pools;
  }
}
