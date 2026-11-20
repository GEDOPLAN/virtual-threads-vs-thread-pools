#!/usr/bin/env bash
# Hauptmessreihe: Thread-Kosten und Lastmessung über den BenchmarkRunner.
# Schreibt Umgebungsinfos (scripts/umgebung.sh) und die Messwerte als CSV nach results/.
# Voraussetzung: mvn compile wurde ausgeführt. Ohne Argumente ergibt sich die Messreihe
# aus dem Blogbeitrag. Zusätzliche Argumente gehen an den Lastmodus, z. B.
# scripts/messreihe.sh reps=1 requests=2000; reps gilt auch für die Thread-Kosten.

cd "$(dirname "$0")/.."
# SDKMAN wird nur in interaktiven Shells initialisiert
command -v java >/dev/null || source "$HOME/.sdkman/bin/sdkman-init.sh"
bash scripts/umgebung.sh || exit 1

runner=de.gedoplan.showcase.virtualthreads.poolsizing.BenchmarkRunner
reps=$(printf '%s\n' "$@" | grep '^reps=')
java -cp target/classes $runner threads $reps && java -cp target/classes $runner load "$@"
