#!/usr/bin/env bash
# Woher die Zeit bei 10.000 Virtual Threads kommt: HttpServer des JDK oder nackter
# Socket-Server, jeweils mit HttpClient oder nacktem Socket-Client.
# Schreibt Umgebungsinfos (scripts/umgebung.sh) und die Messwerte als CSV nach results/.
# Voraussetzung: mvn compile wurde ausgeführt. Zusätzliche Argumente überschreiben die
# Vorgaben, z. B. scripts/engpass.sh reps=1

cd "$(dirname "$0")/.."
# SDKMAN wird nur in interaktiven Shells initialisiert
command -v java >/dev/null || source "$HOME/.sdkman/bin/sdkman-init.sh"
bash scripts/umgebung.sh || exit 1

java -cp target/classes de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner load \
  requests=10000 delays=0,1 pools=virtual client=default,socket server=jdk,socket "$@"
