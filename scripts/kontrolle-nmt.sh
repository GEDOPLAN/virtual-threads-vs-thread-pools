#!/usr/bin/env bash
# Kontrollmessung: Wie stark beeinflusst die Speichermessung (Native Memory Tracking und
# Speicher-Sampler) die Laufzeiten? Misst jede Variante abwechselnd mit und ohne beides
# (nmt=on,off), damit Drift beide Fälle gleich trifft.
# Aufruf: scripts/kontrolle-nmt.sh threads|load (zwei Teile, damit jeder unter 10 min bleibt)
# Schreibt Umgebungsinfos (scripts/umgebung.sh) und die Messwerte als CSV nach results/.
# Voraussetzung: mvn compile wurde ausgeführt.

cd "$(dirname "$0")/.."
# SDKMAN wird nur in interaktiven Shells initialisiert
command -v java >/dev/null || source "$HOME/.sdkman/bin/sdkman-init.sh"

runner=de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner
case "$1" in
  threads)
    bash scripts/umgebung.sh || exit 1
    java -cp target/classes $runner threads nmt=on,off ;;
  load)
    bash scripts/umgebung.sh || exit 1
    java -cp target/classes $runner load nmt=on,off requests=10000 delays=0,1 \
      pools=platform:2000,platform:10000,virtual ;;
  *)
    echo "Aufruf: $0 threads|load" >&2
    exit 1 ;;
esac
