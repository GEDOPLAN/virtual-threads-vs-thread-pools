# Virtual Threads oder einfach größere Thread-Pools?

Code und Messergebnisse zum Blogbeitrag
[Virtual Threads oder einfach größere Thread-Pools?](https://gedoplan.de/virtual-threads-oder-einfach-groessere-thread-pools/).

Der Beitrag vergleicht Thread-Pools mit 200, 2.000 und 10.000 Platform-Threads mit Virtual
Threads: Laufzeit, Speicher, CPU-Zeit und die Kosten für das Erzeugen der Threads. Die Requests
gehen an einen lokalen Server, der nach einer einstellbaren Verzögerung antwortet.

## Inhalt

| Pfad | Inhalt |
|---|---|
| `src/main/java/.../poolsizing/` | Messaufbau: `BenchmarkRunner` startet jede Messung in eigenen JVMs (`DelayServer` bzw. `SocketDelayServer` als Server, `LoadClient` für Requests, `ThreadCost` für die Kosten wartender Threads) |
| `src/main/java/.../poolsizing/SemaphoreDemo.java` | das `Semaphore`-Beispiel aus dem Beitrag zum Ausführen |
| `src/main/java/.../VirtualThreadsDemo.java` | die einfache Demo aus dem Überblicksartikel [Von Java 17 zu Java 25](https://gedoplan.de/von-java-17-zu-java-25-espresso-oder-kalter-kaffee/), unverändert |
| `scripts/` | Messskripte und die Auswertung |
| `results/` | Ergebnisdateien der Messungen im Beitrag, zusammengefasst in [`results/ERGEBNISSE.md`](results/ERGEBNISSE.md) |

## Voraussetzungen

- Java 25 und Maven 3.9.12 oder neuer (getestet mit 3.10.0; ältere Versionen geben unter Java 25 Warnungen aus)
- Für die Skripte: Linux oder WSL2. Gemessen wurde in WSL2 unter Windows 11.

## Messen

```bash
mvn compile
scripts/messreihe.sh      # Messreihe aus dem Beitrag, rund eine halbe Stunde
scripts/engpass.sh        # Woher die Zeit bei 10.000 Virtual Threads kommt, einige Minuten
scripts/keepalive.sh      # Gegenprobe zu Keep-Alive
scripts/kontrolle-nmt.sh threads|load   # Einfluss der Speichermessung
scripts/reproduktion.sh   # die unveränderte VirtualThreadsDemo, zehnmal
```

Die Skripte schreiben vor jeder Messung eine Umgebungsdatei (Kernel, Java, CPU, Grenzwerte des
Systems) und die Messwerte als CSV nach `results/`. Mit zusätzlichen Parametern lässt sich eine
Messung verkleinern, z. B. `scripts/messreihe.sh reps=1 requests=2000`. Alle Parameter
beschreibt der Kommentar in `BenchmarkRunner`.

Unter WSL2 fragen die Skripte zusätzlich bei Windows ab, ob das Notebook am Netzteil hängt und
ob die CPU gedrosselt ist, und brechen ab, wenn das nicht gelingt. Die Ergebnisse hängen stark
davon ab.

Ohne die Skripte, z. B. unter Windows oder macOS, lässt sich der `BenchmarkRunner` auch direkt
starten:

```bash
java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner load
java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner threads
```

Dabei gibt es zwei Einschränkungen:

- Unter Windows brechen Messungen mit vielen gleichzeitigen Verbindungen mit Java 25 meist mit
  `ConnectException` ab. Ursache ist [JDK-8330940](https://bugs.openjdk.org/browse/JDK-8330940),
  behoben ab Java 26.
- Die Spalte `passiveOpens` (vom Betriebssystem gezählte Verbindungen) gibt es nur unter
  Linux, sonst steht dort -1.

## Auswerten

```bash
java scripts/Auswertung.java
```

erzeugt die Tabellen in `results/ERGEBNISSE.md` neu. Welche Ergebnisdateien gelten, steht als
Konstanten am Anfang von `scripts/Auswertung.java`. Eigene Messungen dort eintragen.

## Semaphore-Beispiel

Nach `mvn compile` schickt

```bash
java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.SemaphoreDemo
```

1.000 Requests an einen Server mit 1 Sekunde Antwortzeit, davon höchstens 50 gleichzeitig.
Die Ausgabe zeigt, wie viele Requests der Server tatsächlich gleichzeitig bearbeitet hat und
wie lange alles gedauert hat (Little's Law erwartet 20 Sekunden). Server und Client laufen in
derselben JVM, die Skripte werden nicht gebraucht, das Beispiel läuft also auch unter Windows.

## Tests

```bash
mvn test
```

prüft die Parameter des `BenchmarkRunner` und lässt alle Varianten einmal mit kleinen Werten
laufen (rund ein bis zwei Minuten). Die Tests messen nichts, sie stellen nur sicher, dass alle Varianten
vollständige Werte liefern.

## Hinweise zum Code

Die Kommentare sind Markdown-Kommentare (`///`, [JEP 467](https://openjdk.org/jeps/467)),
die IDEs wie IntelliJ IDEA formatiert anzeigen.

## Lizenz

[Apache License 2.0](LICENSE)
