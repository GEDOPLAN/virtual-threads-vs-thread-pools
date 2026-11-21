package de.gedoplan.showcase.virtualthreads.poolsizing;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/// Eine einzelne Messung: schickt n Requests gegen den [DelayServer] und misst Laufzeit,
/// Parallelität, CPU-Zeit und Speicher. Läuft in einer eigenen JVM, die der
/// [BenchmarkRunner] startet.
///
/// Argumente: `port pool requests delaySeconds client`
/// - `pool`: `platform:<n>` (`Executors.newFixedThreadPool(n)`) oder `virtual`
///   (`Executors.newVirtualThreadPerTaskExecutor()`)
/// - `client`: `default` (`HttpClient.newHttpClient()` wie in `VirtualThreadsDemo`),
///   `virtual` (`HttpClient` mit Virtual-Thread-Executor) oder `socket` (ohne `HttpClient`,
///   eine Socket-Verbindung pro Request)
///
/// Ausgabe: eine Zeile `RESULT key=value ...`.
public class LoadClient {

  static void main(String[] args) throws Exception {
    BenchmarkRunner.exitOnUncaughtException();
    int port = Integer.parseInt(args[0]);
    String pool = args[1];
    int requests = Integer.parseInt(args[2]);
    String delay = args[3];
    String client = args[4];

    warmup(client, port);
    System.gc();
    var threadBean = ManagementFactory.getThreadMXBean();
    threadBean.resetPeakThreadCount();
    var osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    var peak = new AtomicReference<>(MemorySnapshot.take());
    ScheduledExecutorService sampler = startMemorySampler(peak);

    var inFlight = new AtomicInteger();
    var maxInFlight = new AtomicInteger();
    Map<String, LongAdder> errors = new ConcurrentHashMap<>();
    // Je Fehlertyp das erste Beispiel, zur Diagnose nach der Messung
    Map<String, Exception> firstErrors = new ConcurrentHashMap<>();

    var result = new TreeMap<String, Object>();
    int connectionsBefore = connections(port);
    long passiveOpensBefore = passiveOpens();
    long cpuStart = osBean.getProcessCpuTime();
    try (var sender = newSender(client, port, delay)) {
      ExecutorService executor = newPool(pool);
      long start = System.nanoTime();
      List<Future<?>> futures = new ArrayList<>(requests);
      try {
        for (int i = 0; i < requests; i++) {
          futures.add(executor.submit(() -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
              int status = sender.send();
              if (status != 200) {
                errors.computeIfAbsent("HTTP-" + status, _ -> new LongAdder()).increment();
              }
            } catch (Exception e) {
              errors.computeIfAbsent(e.getClass().getSimpleName(), _ -> new LongAdder()).increment();
              firstErrors.putIfAbsent(e.getClass().getSimpleName(), e);
            } finally {
              inFlight.decrementAndGet();
            }
          }));
        }
      } catch (Throwable e) {
        // Startet kein Pool-Thread mehr, sofort beenden: Das Schließen des HttpClient am Ende
        // des try-Blocks würde auf die laufenden Requests warten, und die hängen, wenn auch
        // der HttpClient keine Threads mehr bekommt
        BenchmarkRunner.abort(e);
      }
      // Bei newFixedThreadPool enthält die Submit-Schleife das Erzeugen der Pool-Threads
      long submitted = System.nanoTime();
      for (var f : futures) {
        f.get();
      }
      long completed = System.nanoTime();
      result.put("passiveOpens", passiveOpensBefore < 0 ? -1 : passiveOpens() - passiveOpensBefore);
      // Nicht per try-with-resources: Das Herunterfahren wird getrennt gemessen (shutdownMs)
      executor.close();
      long terminated = System.nanoTime();
      result.put("durationMs", (completed - start) / 1_000_000);
      result.put("submitMs", (submitted - start) / 1_000_000);
      result.put("shutdownMs", (terminated - completed) / 1_000_000);
    }
    // CPU-Zeit einschließlich Herunterfahren von Pool und HttpClient
    result.put("cpuMs", (osBean.getProcessCpuTime() - cpuStart) / 1_000_000);
    sampler.shutdownNow();
    firstErrors.forEach((type, e) -> System.err.println("Erster Fehler " + type + ": " + e.getMessage()
        + (e.getCause() != null ? " / Ursache: " + e.getCause() : "")));
    var mem = peak.get();

    result.put("pool", pool);
    result.put("requests", requests);
    result.put("delay", delay);
    result.put("client", client);
    result.put("maxInFlight", maxInFlight.get());
    result.put("connections", connections(port) - connectionsBefore);
    result.put("peakPlatformThreads", threadBean.getPeakThreadCount());
    result.put("peakHeapUsedKb", mem.heapUsedKb());
    result.put("peakThreadCommittedKb", mem.threadCommittedKb());
    result.put("peakTotalCommittedKb", mem.totalCommittedKb());
    result.put("errors", errors.values().stream().mapToLong(LongAdder::sum).sum());
    result.put("errorTypes", errors.isEmpty() ? "-" : String.join("|", errors.keySet()));
    printResult(result);
  }

  /// Tastet den Speicher alle 250 ms ab und hält in `peak` das Maximum fest. Mit
  /// `-Dsampler=off` (Kontrollmessung, siehe `nmt=off` im [BenchmarkRunner]) tastet er nicht ab.
  private static ScheduledExecutorService startMemorySampler(AtomicReference<MemorySnapshot> peak) {
    ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
      var t = new Thread(r, "memory-sampler");
      t.setDaemon(true);
      return t;
    });
    if (!"off".equals(System.getProperty("sampler"))) {
      sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(MemorySnapshot.take(), MemorySnapshot::max),
          0, 250, TimeUnit.MILLISECONDS);
    }
    return sampler;
  }

  /// Gibt die Ergebniszeile `RESULT key=value ...` aus, die der [BenchmarkRunner] liest.
  private static void printResult(Map<String, Object> result) {
    var line = new StringBuilder("RESULT");
    result.forEach((k, v) -> line.append(' ').append(k).append('=').append(v));
    System.out.println(line);
  }

  static ExecutorService newPool(String pool) {
    if (pool.equals("virtual")) {
      return Executors.newVirtualThreadPerTaskExecutor();
    }
    if (pool.startsWith("platform:")) {
      return Executors.newFixedThreadPool(Integer.parseInt(pool.substring("platform:".length())));
    }
    throw new IllegalArgumentException("Unbekannter Pool: " + pool);
  }

  /// Schickt einen Request an `/delay/<delay>` und liefert den HTTP-Status.
  interface Sender extends AutoCloseable {
    int send() throws Exception;

    @Override
    void close();
  }

  static Sender newSender(String client, int port, String delay) {
    return switch (client) {
      case "default" -> httpClientSender(HttpClient.newHttpClient(), port, delay);
      case "virtual" -> httpClientSender(
          HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build(), port, delay);
      case "socket" -> socketSender(port, delay);
      default -> throw new IllegalArgumentException("Unbekannter Client: " + client);
    };
  }

  private static Sender httpClientSender(HttpClient httpClient, int port, String delay) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/delay/" + delay)).build();
    return new Sender() {
      @Override
      public int send() throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      }

      @Override
      public void close() {
        httpClient.close();
      }
    };
  }

  /// Ohne `HttpClient`: eine neue Verbindung pro Request, kein Keep-Alive. Zeigt, wie viel
  /// Zeit der `HttpClient` selbst kostet.
  private static Sender socketSender(int port, String delay) {
    byte[] request = ("GET /delay/" + delay + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .getBytes(StandardCharsets.US_ASCII);
    return new Sender() {
      @Override
      public int send() throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
          socket.getOutputStream().write(request);
          socket.shutdownOutput();
          String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
          // Statuszeile: "HTTP/1.1 200 OK"
          return Integer.parseInt(response.substring(9, 12));
        }
      }

      @Override
      public void close() {
      }
    };
  }

  /// Zahl der Verbindungen, über die der Server bisher `/delay`-Requests erhalten hat (nur
  /// eine Untergrenze, siehe [DelayServer]).
  private static int connections(int port) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stats")).build();
    try (var httpClient = HttpClient.newHttpClient()) {
      return Integer.parseInt(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
  }

  /// Zahl der bisher vom Betriebssystem angenommenen TCP-Verbindungen (`PassiveOpens` in
  /// `/proc/net/snmp`, nur Linux), sonst -1. Zählt anders als `/stats` des Servers jede
  /// Verbindung, auch wenn ein Client-Port wiederverwendet wird. Gilt für das ganze System,
  /// nebenher sollte also nichts Verbindungen annehmen.
  private static long passiveOpens() throws IOException {
    Path snmp = Path.of("/proc/net/snmp");
    if (!Files.exists(snmp)) {
      return -1;
    }
    List<String> tcp = Files.readAllLines(snmp).stream().filter(l -> l.startsWith("Tcp:")).toList();
    List<String> names = List.of(tcp.getFirst().split(" "));
    return Long.parseLong(tcp.get(1).split(" ")[names.indexOf("PassiveOpens")]);
  }

  /// Lädt Klassen und wirft den JIT an, bevor gemessen wird: je 200 Requests ohne
  /// Verzögerung über einen kleinen Platform-Pool und über Virtual Threads. Mit eigenem
  /// Sender, damit keine aufgewärmten Verbindungen in die Messung übernommen werden.
  private static void warmup(String client, int port) {
    try (var sender = newSender(client, port, "0")) {
      for (var executor : List.of(Executors.newFixedThreadPool(10), Executors.newVirtualThreadPerTaskExecutor())) {
        try (executor) {
          for (int i = 0; i < 200; i++) {
            executor.submit(sender::send);
          }
        }
      }
    }
  }
}
