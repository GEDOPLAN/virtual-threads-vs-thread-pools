package de.gedoplan.showcase.virtualthreads;

import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/// Die Demo aus dem Überblicksartikel [Von Java 17 zu Java 25 – Espresso oder kalter Kaffee?](https://gedoplan.de/von-java-17-zu-java-25-espresso-oder-kalter-kaffee/):
/// 2.000 Requests mit je einer Sekunde Antwortzeit, einmal über einen Pool mit 200
/// Platform-Threads und einmal über Virtual Threads.
///
/// Bewusst unverändert, auch wo der übrige Code es anders macht: `scripts/reproduktion.sh`
/// misst genau diesen Code nach. Die Messreihe läuft über den `poolsizing.BenchmarkRunner`.
public class VirtualThreadsDemo {

  public static void main(String[] args) throws Exception {
    var server = startDelayServer();
    System.out.println("Starte Tests...");
    try (var httpClient = HttpClient.newHttpClient()) {
      int port = server.getAddress().getPort();

      // 2.000 simulierte Requests, jeweils 1 Sekunde "Serverantwortzeit"
      List<URI> urls = IntStream.range(0, 2_000)
          .mapToObj(i -> URI.create("http://127.0.0.1:" + port + "/delay"))
          .toList();

      measure("Platform-Pool (200 Threads)",
          Executors.newFixedThreadPool(200), urls, httpClient);

      measure("Virtual-Thread-Pool",
          Executors.newVirtualThreadPerTaskExecutor(), urls, httpClient);
    } finally {
      server.stop(0);
    }
  }

  private static void measure(String label, ExecutorService pool, List<URI> urls, HttpClient httpClient) throws ExecutionException, InterruptedException {
    try (pool) {
      List<Future<HttpResponse<Void>>> responseFutures = new ArrayList<>();
      Instant start = Instant.now();
      for (URI uri : urls) {
        var responseFuture = pool.submit(() -> httpClient.send(
            HttpRequest.newBuilder(uri).build(),
            HttpResponse.BodyHandlers.discarding()));
        responseFutures.add(responseFuture);
      }
      for (var responseFuture : responseFutures) {
        responseFuture.get();
      }
      double seconds = Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0;
      System.out.printf("%-30s %.2f Sekunden%n", label, seconds);
    }
  }

  // Simuliert einen langsamen Server, analog zu https://httpbin.org/delay/1
  private static HttpServer startDelayServer() throws java.io.IOException {
    var server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10_000);
    server.createContext("/delay", exchange -> {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException _) { }
      byte[] response = "ok".getBytes();
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }
}