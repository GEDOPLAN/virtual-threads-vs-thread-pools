#!/usr/bin/env bash
# Lässt die unveränderte VirtualThreadsDemo n-mal laufen (Default 10)
# und schreibt die Ausgaben nach results/reproduktion-<plattform>-<zeitstempel>.txt.
# Voraussetzung: mvn compile wurde ausgeführt.
#
# Läuft in WSL2/Linux und in Git Bash unter Windows. Optional:
#   JAVA=/pfad/zu/bin/java  anderes JDK verwenden
#   LABEL=windows-jdk27     Plattformnamen im Dateinamen überschreiben

cd "$(dirname "$0")/.."
# SDKMAN wird nur in interaktiven Shells initialisiert
[ -n "$JAVA" ] || command -v java >/dev/null || source "$HOME/.sdkman/bin/sdkman-init.sh"
JAVA=${JAVA:-java}
runs=${1:-10}
if [ -z "$LABEL" ]; then
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) LABEL=windows ;;
    *) uname -r | grep -qi microsoft && LABEL=wsl2 || LABEL=$(uname -s | tr 'A-Z' 'a-z') ;;
  esac
fi
# Netzbetrieb prüfen: im Akkubetrieb drosselt Windows die CPU
# Ohne Akku (Desktop-PC) "kein Akku", das gilt wie Netzbetrieb.
# Leere Ausgabe (Abfrage gescheitert oder kein powershell.exe) wird zu "unbekannt"
power=$(powershell.exe -NoProfile -Command 'if (Get-CimInstance -ClassName Win32_Battery -ErrorAction SilentlyContinue) { (Get-CimInstance -Namespace root/wmi -ClassName BatteryStatus).PowerOnline } else { "kein Akku" }' 2>/dev/null | tr -d '\r')
# Maximaler Leistungszustand des Prozessors im Netzbetrieb: unter 100 % schaltet Windows den Turbo Boost ab
maxcpu=$(MSYS_NO_PATHCONV=1 powercfg.exe /query SCHEME_CURRENT SUB_PROCESSOR PROCTHROTTLEMAX 2>/dev/null \
  | grep -iE 'Wechselstromeinstellung|AC Power Setting Index' | grep -o '0x[0-9a-fA-F]*')
[ -n "$maxcpu" ] && maxcpu="$((maxcpu)) %"
# Wie in umgebung.sh: In WSL2 und unter Windows ist eine Messung ohne belegten Netzbetrieb
# und Leistungszustand nicht verwertbar. In WSL2 meist ausgefallenes WSL-Interop
# ("Exec format error" bei powercfg.exe); hilft "wsl --shutdown" unter Windows.
case "$LABEL" in
  wsl2|windows*)
    if [ -z "$power" ] || [ -z "$maxcpu" ]; then
      echo "Netzteil oder Max. CPU unbekannt, Messung abgebrochen" >&2
      exit 1
    fi ;;
esac
out=results/reproduktion-$LABEL-$(date +%Y%m%d-%H%M%S).txt
mkdir -p results
{
  echo "# $("$JAVA" -version 2>&1 | head -1), $(nproc) CPUs, somaxconn=$(cat /proc/sys/net/core/somaxconn 2>/dev/null || echo n/a)"
  echo "# Netzteil: ${power:-unbekannt}"
  echo "# Max. CPU: ${maxcpu:-unbekannt}"
  for i in $(seq 1 "$runs"); do
    echo "=== Lauf $i"
    "$JAVA" -cp target/classes de.gedoplan.showcase.virtualthreads.VirtualThreadsDemo 2>&1 \
      | grep -v -E '^\s+at |^Caused|^\s+\.\.\.' | head -3
  done
} > "$out"
cat "$out"
