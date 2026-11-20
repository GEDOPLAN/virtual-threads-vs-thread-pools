# Ergebnisse der Messreihe

Messwerte zum Beitrag über Virtual Threads und Pool-Dimensionierung. Die Tabellen unten
erzeugt `java scripts/Auswertung.java` aus den genannten Ergebnisdateien. Jede Zelle zeigt
den Median und in Klammern die Spanne (Minimum–Maximum) der Läufe. Für die Laufzeit gibt es
zusätzlich Mittelwert und 95-%-Konfidenzintervall (t-Verteilung, 9 Freiheitsgrade).

## Hardware und Umgebung

- Notebook Dell Inspiron 15 7510, Intel Core i7-11800H (8 Kerne, 16 logische CPUs), 40 GB RAM
- Windows 11 Pro (Build 26300), Messungen in WSL2 (der WSL2-VM stehen 19 GB RAM zur Verfügung)
- Eclipse Temurin 25.0.5
- am Netzteil (im Akkubetrieb drosselt Windows die CPU), Energieschema „Ausbalanciert“ mit
  maximalem Leistungszustand des Prozessors 100 % (Turbo Boost an), Energiestatus „Ausbalanciert“

Die Details stehen in den Umgebungsdateien, siehe unten. Die Reproduktion der Original-Demo
lief zusätzlich direkt unter Windows.

## Messaufbau

- `scripts/messreihe.sh`: Thread-Kosten (`BenchmarkRunner threads`) und Lasttest
  (`BenchmarkRunner load delays=0,0.1,1`), Requests 2.000 und 10.000, Pools mit 200, 2.000
  und 10.000 Platform-Threads (`Executors.newFixedThreadPool`) sowie Virtual Threads
  (`Executors.newVirtualThreadPerTaskExecutor`).
- `scripts/engpass.sh`: 10.000 Virtual Threads mit Server und Client jeweils als JDK-Klasse
  oder als nackter Socket.
- `scripts/keepalive.sh`: Gegenprobe zu Keep-Alive. Pool 2.000 und Virtual Threads bei
  10.000 Requests, einmal mit dem normalen Server, einmal mit `Connection: close` nach jeder
  Antwort.
- `scripts/kontrolle-nmt.sh`: Kontrollmessung, wie stark die Speichermessung die Laufzeiten
  beeinflusst. Jede Variante läuft abwechselnd mit und ohne Speichermessung.
- `scripts/reproduktion.sh`: unveränderte Demo aus dem Originalartikel.
- Jede Messung startet einen frischen Server und einen frischen Client in eigenen JVMs, damit
  sich Server- und Client-Werte nicht vermischen. Thread-Kosten und Lasttest laufen mit
  `-XX:NativeMemoryTracking=summary`. Alle Messungen laufen zehnmal; die Wiederholungen
  liegen außen, damit Drift alle Varianten gleich trifft.
- Speicher: „NMT gesamt“ ist der gesamte Speicher, den die JVM belegt. Die Stacks der
  Virtual Threads liegen auf dem Heap, die der Platform-Threads im NMT-Bereich „Thread“.
  Für einen fairen Vergleich zählt deshalb der Gesamtwert.

## Hinweise zur Aussagekraft

- Es gelten nur Unterschiede, deren Konfidenzintervalle sich nicht überschneiden.
- Fehler: Im Lasttest scheiterten in einer Messung (Pool 2.000, 10.000 Requests, 0,1 s,
  Wiederholung 1) 3 von 10.000 Requests mit `IOException: HTTP/1.1 header parser received no
  bytes`. Vermutlich schließt der Server eine Keep-Alive-Verbindung, während der Client sie
  wiederverwendet. Nicht untersucht, auf die Laufzeit praktisch ohne Einfluss.
- Die Spalte „Verbindungen, die der Server gesehen hat“ ist nur eine Untergrenze. Der Server
  zählt verschiedene Client-Adressen (IP und Port), und das Betriebssystem kann den Port
  einer geschlossenen Verbindung für eine spätere wieder vergeben. Genau zählt erst die
  Gegenprobe Keep-Alive mit „Verbindungen laut Kernel“: Mit `Connection: close` sind es dort
  in allen Läufen exakt 10.000. Steht in der Untergrenze die Zahl der Requests, ist auch sie
  exakt.
- Die Speichermessung (Native Memory Tracking und Abtasten alle 250 ms) verändert die
  Ergebnisse nicht nennenswert: Der Start der Threads dauert gleich lange, der Lasttest mit
  Speichermessung ist höchstens 1–2 % langsamer, und das für alle Varianten gleich. Die
  Konfidenzintervalle überschneiden sich überall (Tabelle „Kontrollmessung“).
- Die Kontrollmessungen liefen am 2026-11-19, einen Tag nach der Hauptmessreihe. Das Notebook
  war an diesem Tag etwas langsamer (Start von 10.000 Platform-Threads im Median 1.924 statt
  1.831 ms). Kontrollmessungen deshalb nur innerhalb ihres eigenen Laufs vergleichen.
- Gegenproben mit Turbo Boost aus (maximaler Leistungszustand des Prozessors 99 %) liegen in
  `results/turbo-aus/` (alle Messungen, 10 Läufe) und eine Stichprobe mit Turbo an in
  `results/turbo-stichprobe/`. Ohne Turbo dauert der Start von 10.000 Platform-Threads mehr
  als doppelt so lange (Tabelle „Thread-Kosten mit Turbo Boost aus“), und große
  Platform-Pools schneiden schlechter ab.
- Die Reproduktion in `results/cpu20/` lief mit einem Energieschema, das den maximalen
  Leistungszustand des Prozessors auf 20 % begrenzt, im Akkubetrieb und mit Energiestatus
  „Ausbalanciert“ (von Hand notiert, das Skript erfasst den Energiestatus nicht).
- Die CSV-Dateien enthalten weitere Spalten (z. B. `shutdownMs`, `peakPlatformThreads`), die
  hier nicht ausgewertet sind.

## Tabellen

<!-- BEGIN Auswertung: erzeugt mit java scripts/Auswertung.java, nicht von Hand ändern -->

### Umgebung laut Umgebungsdateien

`results/umgebung-wsl2-20261118-170803.txt`:

```
Datum:      2026-11-18T17:08:03+01:00
Kernel:     Linux 6.18.33.2-microsoft-standard-WSL2
Java:       openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) 
CPU:        11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logische CPUs
RAM:        19 GB
somaxconn:  4096
Ports:      32768 60999
ulimit -n:  10240, ulimit -u: 79435
threads-max: 158871
Netzteil:   True
Max. CPU:   100 %
```

`results/umgebung-wsl2-20261118-173502.txt`:

```
Datum:      2026-11-18T17:35:02+01:00
Kernel:     Linux 6.18.33.2-microsoft-standard-WSL2
Java:       openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) 
CPU:        11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logische CPUs
RAM:        19 GB
somaxconn:  4096
Ports:      32768 60999
ulimit -n:  10240, ulimit -u: 79435
threads-max: 158871
Netzteil:   True
Max. CPU:   100 %
```

`results/turbo-aus/umgebung-wsl2-20261118-150032.txt`:

```
Datum:      2026-11-18T15:00:32+01:00
Kernel:     Linux 6.18.33.2-microsoft-standard-WSL2
Java:       openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) 
CPU:        11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logische CPUs
RAM:        19 GB
somaxconn:  4096
Ports:      32768 60999
ulimit -n:  10240, ulimit -u: 79435
threads-max: 158871
Netzteil:   True
```

`results/umgebung-wsl2-20261119-172624.txt`:

```
Datum:      2026-11-19T17:26:24+01:00
Kernel:     Linux 6.18.33.2-microsoft-standard-WSL2
Java:       openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) 
CPU:        11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logische CPUs
RAM:        19 GB
somaxconn:  4096
Ports:      32768 60999
ulimit -n:  10240, ulimit -u: 79435
threads-max: 158871
Netzteil:   True
Max. CPU:   100 %
```

`results/umgebung-wsl2-20261119-172919.txt`:

```
Datum:      2026-11-19T17:29:19+01:00
Kernel:     Linux 6.18.33.2-microsoft-standard-WSL2
Java:       openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) 
CPU:        11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logische CPUs
RAM:        19 GB
somaxconn:  4096
Ports:      32768 60999
ulimit -n:  10240, ulimit -u: 79435
threads-max: 158871
Netzteil:   True
Max. CPU:   100 %
```

`results/umgebung-wsl2-20261119-185053.txt`:

```
Datum:      2026-11-19T18:50:53+01:00
Kernel:     Linux 6.18.33.2-microsoft-standard-WSL2
Java:       openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) 
CPU:        11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logische CPUs
RAM:        19 GB
somaxconn:  4096
Ports:      32768 60999
ulimit -n:  10240, ulimit -u: 79435
threads-max: 158871
Netzteil:   True
Max. CPU:   100 %
```

### Thread-Kosten

Datei: `results/threads-20261118-170804.csv`

n Threads starten und warten lassen, ohne HTTP. Speicher als Zuwachs gegenüber dem Zustand vor dem Start.

| Threads | Start bis alle laufen (ms) | Speicher gesamt, NMT (MB) | davon NMT „Thread“ (MB) | Heap (MB) |
|---|---|---|---|---|
| 200 Platform-Threads | 24 (23–28) | 21,5 (21,5–21,5) | 21,0 (21,0–21,1) | 0,7 (0,7–0,7) |
| 200 Virtual Threads | 6 (5–9) | 0,7 (0,1–2,0) | 0,5 (0,0–1,1) | 1,1 (1,1–1,1) |
| 2.000 Platform-Threads | 237 (225–302) | 90,0 (89,7–97,0) | 84,3 (84,1–91,3) | 1,2 (1,2–1,2) |
| 2.000 Virtual Threads | 15 (12–16) | 1,1 (1,0–1,5) | 0,8 (0,2–1,1) | 4,5 (4,4–4,8) |
| 10.000 Platform-Threads | 1.831 (1.765–1.928) | 361,1 (358,5–361,6) | 332,6 (330,0–333,0) | 3,4 (3,4–3,4) |
| 10.000 Virtual Threads | 55 (42–73) | 4,3 (1,5–141,8) | 1,0 (0,8–1,3) | 16,4 (14,5–18,0) |

### Thread-Kosten mit Turbo Boost aus (Gegenprobe)

Datei: `results/turbo-aus/threads-20261118-150033.csv`

Wie oben, aber mit maximalem Leistungszustand des Prozessors 99 % (Turbo Boost aus). Die Umgebungsdatei enthält noch keine Zeile „Max. CPU“, der Wert steht in den Hinweisen oben. n Threads starten und warten lassen, ohne HTTP. Speicher als Zuwachs gegenüber dem Zustand vor dem Start.

| Threads | Start bis alle laufen (ms) | Speicher gesamt, NMT (MB) | davon NMT „Thread“ (MB) | Heap (MB) |
|---|---|---|---|---|
| 200 Platform-Threads | 51 (49–53) | 21,5 (21,5–21,5) | 21,1 (21,0–21,1) | 0,7 (0,7–0,7) |
| 200 Virtual Threads | 15 (11–19) | 0,5 (0,1–1,3) | 0,3 (0,0–1,1) | 1,1 (1,1–1,1) |
| 2.000 Platform-Threads | 497 (480–579) | 89,7 (87,2–93,5) | 84,1 (81,6–87,9) | 1,2 (1,2–1,2) |
| 2.000 Virtual Threads | 31 (24–36) | 1,1 (0,3–1,4) | 0,9 (0,0–1,0) | 4,5 (4,4–4,8) |
| 10.000 Platform-Threads | 4.284 (4.185–4.548) | 361,2 (354,0–367,8) | 332,7 (330,0–339,2) | 3,4 (3,4–3,4) |
| 10.000 Virtual Threads | 102 (79–136) | 2,4 (0,8–4,6) | 0,8 (0,1–1,1) | 17,3 (15,7–19,3) |

### Lasttest: Pools gegen Virtual Threads

Datei: `results/load-20261118-170939.csv`

Client `HttpClient.newHttpClient()`, Server `HttpServer` des JDK. Zeilen: Pool, Spalten: Requests / Verzögerung in s. Speicher ist der Spitzenwert der ganzen Client-JVM während der Messung (alle 250 ms abgetastet), inklusive Grundbedarf.

**Laufzeit (ms)**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 350 (316–375) | 1.080 (1.070–1.090) | 10.073 (10.054–10.090) | 1.151 (1.094–1.226) | 5.142 (5.129–5.156) | 50.167 (50.150–50.187) |
| Pool 2.000 | 505 (468–520) | 643 (605–669) | 1.451 (1.424–1.560) | 2.068 (1.915–2.336) | 2.202 (2.116–2.326) | 5.462 (5.430–5.534) |
| Pool 10.000 | 529 (502–558) | 623 (613–646) | 1.436 (1.399–1.456) | 2.850 (2.758–2.938) | 3.130 (3.052–3.760) | 4.221 (4.024–4.409) |
| Virtual Threads | 550 (516–659) | 605 (530–663) | 1.332 (1.290–1.405) | 2.756 (2.635–2.827) | 2.863 (2.616–3.159) | 2.516 (2.373–2.673) |

**Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 350 ± 12 | 1.080 ± 4 | 10.071 ± 7 | 1.150 ± 29 | 5.143 ± 6 | 50.167 ± 9 |
| Pool 2.000 | 502 ± 12 | 638 ± 15 | 1.459 ± 29 | 2.095 ± 81 | 2.201 ± 46 | 5.466 ± 21 |
| Pool 10.000 | 532 ± 12 | 627 ± 8 | 1.435 ± 13 | 2.847 ± 46 | 3.216 ± 152 | 4.208 ± 90 |
| Virtual Threads | 567 ± 34 | 599 ± 29 | 1.344 ± 28 | 2.756 ± 49 | 2.855 ± 105 | 2.519 ± 61 |

**Submit-Schleife inkl. Erzeugen der Pool-Threads (ms)**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 67 (59–83) | 65 (53–73) | 59 (46–69) | 79 (69–97) | 68 (53–80) | 72 (59–103) |
| Pool 2.000 | 501 (467–516) | 537 (498–560) | 447 (423–559) | 536 (497–600) | 558 (517–595) | 426 (402–470) |
| Pool 10.000 | 528 (497–557) | 521 (502–545) | 435 (398–454) | 2.849 (2.744–2.937) | 3.025 (2.951–3.660) | 3.218 (3.023–3.409) |
| Virtual Threads | 14 (10–18) | 15 (9–17) | 14 (10–24) | 37 (30–53) | 38 (28–53) | 33 (31–55) |

**Höchste Zahl gleichzeitig laufender Requests**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 200 (200–200) | 200 (200–200) | 200 (200–200) | 200 (200–200) | 200 (200–200) | 200 (200–200) |
| Pool 2.000 | 43 (32–65) | 650 (505–767) | 2.000 (2.000–2.000) | 2.000 (2.000–2.000) | 2.000 (2.000–2.000) | 2.000 (2.000–2.000) |
| Pool 10.000 | 37 (29–62) | 681 (589–799) | 2.000 (2.000–2.000) | 50 (45–85) | 651 (422–758) | 4.754 (3.947–4.890) |
| Virtual Threads | 1.859 (1.774–1.961) | 2.000 (1.988–2.000) | 2.000 (2.000–2.000) | 9.685 (9.588–9.745) | 9.837 (9.649–9.899) | 10.000 (10.000–10.000) |

**Verbindungen, die der Server gesehen hat**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 200 (200–200) | 200 (200–200) | 200 (200–200) | 200 (200–200) | 200 (200–200) | 200 (200–200) |
| Pool 2.000 | 41 (31–60) | 711 (504–889) | 2.000 (2.000–2.000) | 5.358 (5.238–5.573) | 5.654 (5.415–5.892) | 2.468 (2.049–2.628) |
| Pool 10.000 | 36 (28–60) | 767 (589–975) | 2.000 (2.000–2.000) | 49 (43–79) | 745 (484–884) | 5.080 (4.242–6.338) |
| Virtual Threads | 1.776 (1.591–1.817) | 1.920 (1.716–2.000) | 2.000 (2.000–2.000) | 9.508 (9.411–9.648) | 9.661 (9.430–9.816) | 10.000 (10.000–10.000) |

**CPU-Zeit der Client-JVM (s)**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 3,5 (3,2–3,8) | 3,1 (2,7–3,4) | 2,8 (2,6–3,0) | 11,0 (10,3–12,2) | 8,6 (8,2–9,1) | 8,4 (8,1–9,2) |
| Pool 2.000 | 4,2 (3,9–4,5) | 4,8 (4,4–5,2) | 5,4 (4,8–5,9) | 19,8 (17,8–22,7) | 21,4 (18,5–22,7) | 12,4 (11,7–13,1) |
| Pool 10.000 | 4,6 (4,4–4,9) | 4,7 (4,4–5,1) | 5,2 (4,5–5,7) | 20,9 (19,1–22,8) | 21,6 (20,1–27,9) | 27,1 (23,0–30,3) |
| Virtual Threads | 4,7 (4,4–5,8) | 4,9 (4,3–5,9) | 4,2 (3,8–5,0) | 18,0 (16,3–20,0) | 19,7 (17,5–22,6) | 19,2 (15,9–20,7) |

**Speicher gesamt, NMT, Spitzenwert (MB)**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 273 (247–307) | 249 (238–265) | 240 (230–250) | 340 (319–365) | 266 (250–284) | 259 (241–273) |
| Pool 2.000 | 388 (379–407) | 418 (373–438) | 455 (371–490) | 746 (676–812) | 792 (707–856) | 641 (536–719) |
| Pool 10.000 | 378 (355–398) | 412 (388–440) | 455 (380–473) | 1.456 (1.253–1.541) | 1.474 (1.373–1.577) | 1.587 (1.254–1.863) |
| Virtual Threads | 339 (262–409) | 354 (271–462) | 294 (229–318) | 828 (727–964) | 771 (688–967) | 790 (716–935) |

**Heap belegt, Spitzenwert (MB)**

|  | 2.000 / 0 | 2.000 / 0,1 | 2.000 / 1 | 10.000 / 0 | 10.000 / 0,1 | 10.000 / 1 |
|---|---|---|---|---|---|---|
| Pool 200 | 26 (19–33) | 29 (24–37) | 32 (28–39) | 47 (40–58) | 50 (46–57) | 52 (42–57) |
| Pool 2.000 | 24 (20–30) | 40 (32–45) | 62 (41–67) | 187 (172–205) | 202 (174–211) | 158 (141–199) |
| Pool 10.000 | 27 (18–36) | 38 (31–49) | 63 (40–70) | 89 (75–97) | 122 (115–139) | 225 (208–242) |
| Virtual Threads | 55 (46–67) | 55 (47–74) | 50 (43–65) | 349 (300–366) | 330 (302–355) | 330 (295–354) |

Fehlerhafte Requests in allen 240 Messungen zusammen: 3.

### Engpass bei 10.000 Virtual Threads

Datei: `results/load-20261118-173503.csv`

10.000 Requests mit Virtual Threads. Server: `HttpServer` des JDK oder nackter Socket-Server; Client: `HttpClient` oder nackter Socket (eine Verbindung pro Request). Spalten: Verzögerung in s.

**Laufzeit (ms)**

|  | 0 | 1 |
|---|---|---|
| Server: HttpServer, Client: HttpClient | 2.748 (2.561–2.861) | 2.641 (2.453–2.725) |
| Server: HttpServer, Client: Socket | 1.446 (1.360–1.562) | 2.440 (2.274–2.561) |
| Server: Socket, Client: HttpClient | 2.167 (2.064–2.537) | 2.603 (2.428–2.945) |
| Server: Socket, Client: Socket | 464 (404–526) | 2.288 (1.588–2.340) |

**Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)**

|  | 0 | 1 |
|---|---|---|
| Server: HttpServer, Client: HttpClient | 2.737 ± 70 | 2.591 ± 76 |
| Server: HttpServer, Client: Socket | 1.446 ± 36 | 2.439 ± 59 |
| Server: Socket, Client: HttpClient | 2.203 ± 104 | 2.637 ± 125 |
| Server: Socket, Client: Socket | 463 ± 22 | 2.037 ± 254 |

**Verbindungen, die der Server gesehen hat**

|  | 0 | 1 |
|---|---|---|
| Server: HttpServer, Client: HttpClient | 9.562 (9.400–9.716) | 10.000 (10.000–10.000) |
| Server: HttpServer, Client: Socket | 9.999 (8.828–10.000) | 10.000 (9.736–10.000) |
| Server: Socket, Client: HttpClient | 9.238 (8.617–9.527) | 10.000 (10.000–10.000) |
| Server: Socket, Client: Socket | 10.000 (10.000–10.000) | 10.000 (10.000–10.000) |

**CPU-Zeit der Client-JVM (s)**

|  | 0 | 1 |
|---|---|---|
| Server: HttpServer, Client: HttpClient | 18,2 (15,4–19,5) | 19,4 (17,4–20,8) |
| Server: HttpServer, Client: Socket | 6,8 (5,1–7,9) | 7,5 (5,5–9,9) |
| Server: Socket, Client: HttpClient | 18,4 (16,4–20,4) | 19,5 (18,0–21,9) |
| Server: Socket, Client: Socket | 3,3 (2,8–4,2) | 7,0 (6,0–7,5) |

Fehlerhafte Requests in allen 80 Messungen zusammen: 0.

### Gegenprobe Keep-Alive

Datei: `results/load-20261119-185054.csv`

10.000 Requests, Client `HttpClient.newHttpClient()`, Server `HttpServer` des JDK, einmal normal und einmal mit `Connection: close` nach jeder Antwort (der Client kann keine Verbindung wiederverwenden). Spalten: Verzögerung in s. „Verbindungen laut Kernel“ ist der Zuwachs von `PassiveOpens` in `/proc/net/snmp` direkt um die Messung herum, ohne Aufwärmphase.

**Laufzeit (ms)**

|  | 0 | 0,1 |
|---|---|---|
| Pool 2.000, normal | 2.060 (1.880–2.240) | 2.129 (1.978–2.307) |
| Virtual Threads, normal | 2.785 (2.550–3.007) | 2.777 (2.599–2.967) |
| Pool 2.000, Connection: close | 1.915 (1.711–2.064) | 2.053 (1.800–2.175) |
| Virtual Threads, Connection: close | 1.942 (1.759–2.139) | 1.996 (1.762–2.105) |

**Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)**

|  | 0 | 0,1 |
|---|---|---|
| Pool 2.000, normal | 2.040 ± 85 | 2.133 ± 83 |
| Virtual Threads, normal | 2.743 ± 95 | 2.771 ± 75 |
| Pool 2.000, Connection: close | 1.897 ± 68 | 2.022 ± 78 |
| Virtual Threads, Connection: close | 1.938 ± 72 | 1.985 ± 68 |

**Verbindungen laut Kernel**

|  | 0 | 0,1 |
|---|---|---|
| Pool 2.000, normal | 6.003 (5.362–6.213) | 6.393 (6.072–6.792) |
| Virtual Threads, normal | 9.634 (9.510–9.778) | 9.799 (9.753–9.847) |
| Pool 2.000, Connection: close | 10.000 (10.000–10.000) | 10.000 (10.000–10.000) |
| Virtual Threads, Connection: close | 10.000 (10.000–10.000) | 10.000 (10.000–10.000) |

**Verbindungen, die der Server gesehen hat**

|  | 0 | 0,1 |
|---|---|---|
| Pool 2.000, normal | 5.351 (4.833–5.510) | 5.652 (5.485–5.964) |
| Virtual Threads, normal | 9.523 (9.318–9.699) | 9.700 (9.576–9.772) |
| Pool 2.000, Connection: close | 7.561 (7.499–7.660) | 7.574 (7.507–7.759) |
| Virtual Threads, Connection: close | 9.458 (9.259–9.590) | 9.567 (9.377–9.688) |

**CPU-Zeit der Client-JVM (s)**

|  | 0 | 0,1 |
|---|---|---|
| Pool 2.000, normal | 18,8 (16,1–22,1) | 21,1 (18,9–22,8) |
| Virtual Threads, normal | 17,3 (14,7–19,4) | 18,2 (16,2–19,7) |
| Pool 2.000, Connection: close | 17,7 (16,1–20,0) | 18,6 (15,7–19,3) |
| Virtual Threads, Connection: close | 18,0 (16,3–19,0) | 17,5 (14,2–19,1) |

Fehlerhafte Requests in allen 80 Messungen zusammen: 0.

### Kontrollmessung: Einfluss der Speichermessung

Jede Variante abwechselnd mit Speichermessung (`-XX:NativeMemoryTracking=summary` und Abtasten alle 250 ms) und ohne. Nur innerhalb dieses Laufs vergleichen, nicht mit den Dateien vom 2026-11-18.

Datei: `results/threads-20261119-172625.csv`

**Thread-Kosten: Start bis alle laufen (ms)**

|  | mit | ohne |
|---|---|---|
| 200 Platform-Threads | 25 (24–32) | 25 (24–32) |
| 200 Virtual Threads | 6 (5–9) | 6 (5–8) |
| 2.000 Platform-Threads | 244 (238–294) | 245 (238–274) |
| 2.000 Virtual Threads | 16 (15–21) | 16 (14–19) |
| 10.000 Platform-Threads | 1.924 (1.877–2.105) | 1.925 (1.881–2.117) |
| 10.000 Virtual Threads | 55 (41–76) | 58 (41–66) |

**Thread-Kosten: Start bis alle laufen, Mittelwert ± 95-%-Konfidenzintervall (ms)**

|  | mit | ohne |
|---|---|---|
| 200 Platform-Threads | 26 ± 2 | 26 ± 2 |
| 200 Virtual Threads | 6 ± 1 | 6 ± 1 |
| 2.000 Platform-Threads | 252 ± 14 | 248 ± 7 |
| 2.000 Virtual Threads | 16 ± 1 | 16 ± 1 |
| 10.000 Platform-Threads | 1.939 ± 47 | 1.937 ± 49 |
| 10.000 Virtual Threads | 58 ± 8 | 57 ± 6 |

Datei: `results/load-20261119-172919.csv`

10.000 Requests, Client `HttpClient.newHttpClient()`, Server `HttpServer` des JDK. Spalten: Verzögerung in s / Speichermessung.

**Lasttest: Laufzeit (ms)**

|  | 0 / mit | 0 / ohne | 1 / mit | 1 / ohne |
|---|---|---|---|---|
| Pool 2.000 | 2.040 (2.000–2.191) | 2.006 (1.941–2.112) | 5.472 (5.465–5.595) | 5.456 (5.423–5.498) |
| Pool 10.000 | 2.899 (2.863–3.030) | 2.859 (2.784–3.016) | 4.334 (4.134–4.535) | 4.280 (4.096–4.445) |
| Virtual Threads | 2.725 (2.530–2.824) | 2.686 (2.528–2.836) | 2.521 (2.416–2.599) | 2.495 (2.395–2.669) |

**Lasttest: Laufzeit, Mittelwert ± 95-%-Konfidenzintervall (ms)**

|  | 0 / mit | 0 / ohne | 1 / mit | 1 / ohne |
|---|---|---|---|---|
| Pool 2.000 | 2.069 ± 51 | 2.020 ± 46 | 5.494 ± 31 | 5.460 ± 18 |
| Pool 10.000 | 2.911 ± 35 | 2.873 ± 50 | 4.312 ± 92 | 4.256 ± 79 |
| Virtual Threads | 2.718 ± 68 | 2.689 ± 79 | 2.524 ± 41 | 2.531 ± 66 |

Fehlerhafte Requests in allen 120 Messungen zusammen: 0.

### Reproduktion der Original-Demo

Unveränderte `VirtualThreadsDemo` (2.000 Requests, 1 s Verzögerung), 10 Läufe je Datei. Läuft der Virtual-Thread-Teil nicht durch, fehlt sein Wert. Max. CPU ist der maximale Leistungszustand des Prozessors (unter 100 % ist der Turbo Boost aus).

| Datei | Plattform | Java | Max. CPU | Netzteil | Pool 200 (s) | Virtual Threads (s) | Virtual Threads durchgelaufen |
|---|---|---|---|---|---|---|---|
| `reproduktion-wsl2-20261118-173941.txt` | WSL2 | 25.0.5 | 100 % | ja | 11,02 (10,61–11,26) | 1,27 (1,22–1,31) | 10 von 10 |
| `reproduktion-windows-jdk25.0.5-20261118-174147.txt` | Windows | 25.0.5 | 100 % | ja | 10,29 (10,27–10,35) | – | 0 von 10 |
| `reproduktion-windows-jdk27-20261118-174349.txt` | Windows | 27.0.1.0.2 | 100 % | ja | 10,32 (10,29–10,34) | 1,33 (1,27–1,45) | 10 von 10 |
| `cpu20/reproduktion-windows-jdk25.0.5-cpu20-akku-20261118-191249.txt` | Windows | 25.0.5 | 20 % | nein | 11,31 (11,17–11,81) | 3,04 | 1 von 10 |
| `cpu20/reproduktion-windows-jdk27-cpu20-akku-20261118-191535.txt` | Windows | 27.0.1.0.2 | 20 % | nein | 11,36 (11,25–11,52) | 2,61 (2,29–2,85) | 10 von 10 |

<!-- END Auswertung -->
