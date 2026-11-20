package de.gedoplan.showcase.virtualthreads.poolsizing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/// Gegenstück zum [DelayServer] ohne `com.sun.net.httpserver`: ein Virtual Thread pro
/// Verbindung, nur das Nötigste von HTTP/1.1 (GET, Keep-Alive). Zeigt, wie viel Zeit der
/// `HttpServer` des JDK selbst kostet.
///
/// Gleiches Protokoll wie der [DelayServer]: gibt nach dem Start `PORT <n>` aus, versteht
/// `/delay/<sek>` und `/stats`. Anders als dort zählt `/stats` die angenommenen Verbindungen
/// mit `/delay`-Requests genau, nicht nur als Untergrenze.
public class SocketDelayServer {

  private static final AtomicInteger CONNECTIONS = new AtomicInteger();

  static void main(String[] args) throws IOException {
    // Backlog wie beim DelayServer
    var server = new ServerSocket(0, 10_000, InetAddress.getLoopbackAddress());
    System.out.println("PORT " + server.getLocalPort());
    while (true) {
      Socket socket = server.accept();
      Thread.ofVirtual().start(() -> handle(socket));
    }
  }

  private static void handle(Socket socket) {
    try (socket;
         var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
         var out = socket.getOutputStream()) {
      boolean counted = false;
      String requestLine;
      // Mehrere Requests pro Verbindung (Keep-Alive), bis der Client die Verbindung schließt
      while ((requestLine = in.readLine()) != null) {
        String path = requestLine.split(" ")[1];
        String header;
        do {
          header = in.readLine();
        } while (header != null && !header.isEmpty());

        String body;
        if (path.startsWith("/delay/")) {
          // Wie beim DelayServer zählen nur Verbindungen mit /delay-Requests
          if (!counted) {
            CONNECTIONS.incrementAndGet();
            counted = true;
          }
          double seconds = Double.parseDouble(path.substring("/delay/".length()));
          Thread.sleep((long) (seconds * 1000));
          body = "ok";
        } else {
          body = String.valueOf(CONNECTIONS.get());
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
    } catch (IOException | InterruptedException _) {
      // Verbindung abgebrochen oder Server beendet: nichts zu tun
    }
  }
}
