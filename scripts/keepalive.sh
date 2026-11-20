#!/usr/bin/env bash
# Gegenprobe zu Keep-Alive: Pool 2.000 und Virtual Threads bei 10.000 Requests, einmal mit
# dem normalen HttpServer, einmal mit "Connection: close" nach jeder Antwort.
# Schreibt Umgebungsinfos (scripts/umgebung.sh) und die Messwerte als CSV nach results/.
# Voraussetzung: mvn compile wurde ausgeführt. Zusätzliche Argumente überschreiben die
# Vorgaben, z. B. scripts/keepalive.sh reps=1

cd "$(dirname "$0")/.."
# SDKMAN wird nur in interaktiven Shells initialisiert
command -v java >/dev/null || source "$HOME/.sdkman/bin/sdkman-init.sh"
bash scripts/umgebung.sh || exit 1

java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner load \
  requests=10000 delays=0,0.1 pools=platform:2000,virtual client=default server=jdk,jdk-close "$@"
