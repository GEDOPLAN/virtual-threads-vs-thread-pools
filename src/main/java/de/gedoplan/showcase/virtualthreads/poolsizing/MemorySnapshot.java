package de.gedoplan.showcase.virtualthreads.poolsizing;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Speicherzustand der eigenen JVM. Die nativen Werte stammen aus dem Native Memory Tracking
/// (NMT) und sind nur gefüllt, wenn die JVM mit `-XX:NativeMemoryTracking=summary` läuft,
/// sonst -1. Alle Werte in KB.
///
/// @param heapUsedKb        belegter Heap (enthält die Stacks geparkter Virtual Threads)
/// @param threadCommittedKb NMT-Kategorie „Thread“: Stacks und Verwaltung der Platform-Threads
/// @param totalCommittedKb  gesamter von der JVM belegter Speicher laut NMT
/// @param platformThreads   lebende Platform-Threads
record MemorySnapshot(long heapUsedKb, long threadCommittedKb, long totalCommittedKb, int platformThreads) {

  private static final Pattern TOTAL = Pattern.compile("Total: reserved=\\d+KB, committed=(\\d+)KB");
  private static final Pattern THREAD = Pattern.compile("Thread \\(reserved=\\d+KB, committed=(\\d+)KB\\)");

  static MemorySnapshot take() {
    long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024;
    int threads = ManagementFactory.getThreadMXBean().getThreadCount();
    String nmt = nativeMemorySummary();
    return new MemorySnapshot(heapUsed, extract(THREAD, nmt), extract(TOTAL, nmt), threads);
  }

  /// Wertweises Maximum aus zwei Zuständen, für den Spitzenwert während einer Messung.
  MemorySnapshot max(MemorySnapshot other) {
    return new MemorySnapshot(
        Math.max(heapUsedKb, other.heapUsedKb),
        Math.max(threadCommittedKb, other.threadCommittedKb),
        Math.max(totalCommittedKb, other.totalCommittedKb),
        Math.max(platformThreads, other.platformThreads));
  }

  /// Ausgabe von `jcmd <pid> VM.native_memory summary`, aus der eigenen JVM abgefragt.
  /// Leer, wenn NMT nicht eingeschaltet ist.
  private static String nativeMemorySummary() {
    try {
      return (String) ManagementFactory.getPlatformMBeanServer().invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"),
          "vmNativeMemory",
          new Object[] { new String[] { "summary", "scale=KB" } },
          new String[] { String[].class.getName() });
    } catch (Exception _) {
      return "";
    }
  }

  private static long extract(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }
}
