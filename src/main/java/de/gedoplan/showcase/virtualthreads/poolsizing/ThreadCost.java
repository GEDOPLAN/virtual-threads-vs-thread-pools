package de.gedoplan.showcase.virtualthreads.poolsizing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/// Misst, was das Erzeugen von n gleichzeitig lebenden Threads kostet: Startzeit und
/// Speicher, ohne HTTP drumherum. Jeder Thread wartet auf einen Latch, bis alle laufen.
/// Läuft in einer eigenen JVM, die der [BenchmarkRunner] startet, normalerweise mit
/// `-XX:NativeMemoryTracking=summary` (sonst sind die NMT-Werte -1, siehe [MemorySnapshot]).
///
/// Argumente: `kind count`, `kind` ist `platform` oder `virtual`.
///
/// Ausgabe: eine Zeile `RESULT key=value ...`.
public class ThreadCost {

  static void main(String[] args) throws Exception {
    BenchmarkRunner.exitOnUncaughtException();
    String kind = args[0];
    int count = Integer.parseInt(args[1]);
    Thread.Builder builder = switch (kind) {
      case "platform" -> Thread.ofPlatform();
      case "virtual" -> Thread.ofVirtual();
      default -> throw new IllegalArgumentException("Unbekannte Thread-Art: " + kind);
    };

    // Klassen laden, bevor der Ausgangswert für den Speicher genommen wird
    warmup(builder, 100);

    System.gc();
    var before = MemorySnapshot.take();

    var running = new CountDownLatch(count);
    var release = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>(count);
    long start = System.nanoTime();
    for (int i = 0; i < count; i++) {
      threads.add(builder.start(() -> {
        running.countDown();
        awaitQuietly(release);
      }));
    }
    running.await();
    long allRunning = System.nanoTime();

    // Nach GC enthält der belegte Heap nur noch lebende Objekte, also auch die
    // Stacks der geparkten Virtual Threads
    System.gc();
    var during = MemorySnapshot.take();

    release.countDown();
    for (var t : threads) {
      t.join();
    }
    long allJoined = System.nanoTime();

    System.out.printf("RESULT kind=%s count=%d startMs=%d joinMs=%d heapDeltaKb=%d threadCommittedDeltaKb=%d totalCommittedDeltaKb=%d platformThreads=%d%n",
        kind, count,
        (allRunning - start) / 1_000_000,
        (allJoined - allRunning) / 1_000_000,
        during.heapUsedKb() - before.heapUsedKb(),
        during.threadCommittedKb() - before.threadCommittedKb(),
        during.totalCommittedKb() - before.totalCommittedKb(),
        during.platformThreads());
  }

  /// Startet `count` Threads, gibt sie gleich wieder frei und wartet auf ihr Ende.
  private static void warmup(Thread.Builder builder, int count) throws InterruptedException {
    var release = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      threads.add(builder.start(() -> awaitQuietly(release)));
    }
    release.countDown();
    for (var t : threads) {
      t.join();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
