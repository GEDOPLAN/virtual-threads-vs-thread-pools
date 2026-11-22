package de.gedoplan.showcase.virtualthreads.poolsizing;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/// Das Beispiel aus dem Abschnitt „Nicht die Threads begrenzen, sondern die knappe Ressource“
/// des Beitrags: Jeder Request bekommt seinen eigenen Virtual Thread, eine `Semaphore` begrenzt
/// aber, wie viele davon gleichzeitig mit dem Dienst sprechen.
///
/// 1.000 Requests an einen Server mit 1 Sekunde Antwortzeit, höchstens 50 gleichzeitig.
/// Little's Law erwartet 1.000 × 1 s / 50 = 20 s. Die Schleife entspricht dem Schnipsel im
/// Beitrag. Wie viele Requests tatsächlich gleichzeitig ankommen, zählt der Server, denn ihn
/// soll die Grenze schützen.
///
/// Eine Vorführung, keine Messung: Server und Client laufen in derselben JVM.
public class SemaphoreDemo {

  private static final int REQUESTS = 1_000;
  private static final int PERMITS = 50;
  private static final double DELAY_SECONDS = 1;

  private static final AtomicInteger ACTIVE = new AtomicInteger();
  private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();

  static void main(String[] args) throws IOException {
    var server = startDelayServer();
    var delayUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/delay");
    List<URI> uris = Collections.nCopies(REQUESTS, delayUri);
    long start = System.nanoTime();

    try (var client = HttpClient.newHttpClient()) {
      var permits = new Semaphore(PERMITS);   // So viele gleichzeitige Anfragen verträgt der Dienst

      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var uri : uris) {
          executor.submit(() -> {
            permits.acquire();
            try {
              return client.send(HttpRequest.newBuilder(uri).build(),
                  HttpResponse.BodyHandlers.ofString()).body();
            } finally {
              permits.release();
            }
          });
        }
      }
    } finally {
      server.stop(0);
    }

    double seconds = (System.nanoTime() - start) / 1e9;
    System.out.printf("%d Requests, Grenze der Semaphore: %d%n", REQUESTS, PERMITS);
    System.out.printf("Vom Server höchstens gleichzeitig bearbeitete Requests: %d%n", MAX_ACTIVE.get());
    System.out.printf("Laufzeit: %.1f s, erwartet nach Little's Law: %.1f s%n",
        seconds, REQUESTS * DELAY_SECONDS / PERMITS);
  }

  private static HttpServer startDelayServer() throws IOException {
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), REQUESTS);
    server.createContext("/delay", exchange -> {
      MAX_ACTIVE.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
      try {
        Thread.sleep((long) (DELAY_SECONDS * 1000));
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } finally {
        ACTIVE.decrementAndGet();
      }
      byte[] response = "ok".getBytes(StandardCharsets.US_ASCII);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    // Der Server selbst soll nie der Engpass bei der Parallelität sein
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }
}
