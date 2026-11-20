package de.gedoplan.showcase.virtualthreads.poolsizing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/// Simuliert einen langsamen Server, analog zu `https://httpbin.org/delay/<n>`:
/// `/delay/0.1` antwortet nach 0,1 Sekunden.
///
/// Läuft in einer eigenen JVM, damit Speicher und CPU des Servers nicht in die Messwerte des
/// Clients eingehen. Gibt nach dem Start `PORT <n>` aus.
///
/// `/stats` liefert die Zahl der bisher genutzten Verbindungen. Jede TCP-Verbindung hat einen
/// eigenen Client-Port, daher zählen die verschiedenen Client-Adressen der `/delay`-Requests.
/// Das ist nur eine Untergrenze: Das Betriebssystem kann den Port einer geschlossenen
/// Verbindung für eine spätere wieder vergeben, die dann nicht mehr mitzählt.
///
/// Mit dem Argument `close` schickt der Server bei jeder Antwort `Connection: close` und
/// schließt danach die Verbindung. Der Client kann sie dann nicht wiederverwenden
/// (Gegenprobe zu Keep-Alive).
public class DelayServer {

  private static final Set<InetSocketAddress> CLIENT_ADDRESSES = ConcurrentHashMap.newKeySet();

  static void main(String[] args) throws IOException {
    var server = start(args.length > 0 && args[0].equals("close"));
    System.out.println("PORT " + server.getAddress().getPort());
  }

  private static HttpServer start(boolean closeConnections) throws IOException {
    // Port 0: OS wählt freien Port; Backlog groß genug für alle gleichzeitigen Requests
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10_000);
    server.createContext("/delay", exchange -> {
      CLIENT_ADDRESSES.add(exchange.getRemoteAddress());
      String path = exchange.getRequestURI().getPath();
      double seconds = Double.parseDouble(path.substring(path.lastIndexOf('/') + 1));
      try {
        Thread.sleep((long) (seconds * 1000));
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
      if (closeConnections) {
        exchange.getResponseHeaders().set("Connection", "close");
      }
      respond(exchange, "ok");
    });
    server.createContext("/stats", exchange -> respond(exchange, String.valueOf(CLIENT_ADDRESSES.size())));
    // Der Server selbst soll nie der Engpass bei der Parallelität sein
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] response = body.getBytes(StandardCharsets.US_ASCII);
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
