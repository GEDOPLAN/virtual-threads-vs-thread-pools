#!/usr/bin/env bash
# Schreibt Umgebungsinfos nach results/umgebung-<plattform>-<zeitstempel>.txt.
# Wird von den Messskripten vor jeder Messung aufgerufen.

cd "$(dirname "$0")/.."
uname -r | grep -qi microsoft && label=wsl2 || label=$(uname -s | tr 'A-Z' 'a-z')
mkdir -p results
{
  echo "Datum:      $(date -Iseconds)"
  echo "Kernel:     $(uname -sr)"
  echo "Java:       $(java -version 2>&1 | head -2 | tr '\n' ' ')"
  echo "CPU:        $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs), $(nproc) logische CPUs"
  echo "RAM:        $(free -g | awk '/Mem:/ {print $2 " GB"}')"
  echo "somaxconn:  $(cat /proc/sys/net/core/somaxconn)"
  echo "Ports:      $(cat /proc/sys/net/ipv4/ip_local_port_range | xargs)"
  echo "ulimit -n:  $(ulimit -n), ulimit -u: $(ulimit -u)"
  echo "threads-max: $(cat /proc/sys/kernel/threads-max)"
  # Höchstzahl Threads in der cgroup dieser Shell, mit systemd standardmäßig 15 % von threads-max.
  # Liegt sie unter 10.000, bricht die Messung bei 10.000 Platform-Threads ab.
  # Pfad unter cgroup v2 (Zeile "0::") oder v1 (Controller "pids")
  v2=$(awk -F: '$1 == "0" { print $3 }' /proc/self/cgroup)
  v1=$(awk -F: '$2 ~ /(^|,)pids(,|$)/ { print $3 }' /proc/self/cgroup)
  pids=$(cat "/sys/fs/cgroup$v2/pids.max" 2>/dev/null || cat "/sys/fs/cgroup/pids$v1/pids.max" 2>/dev/null)
  echo "pids.max:   ${pids:-n/a}"
  # Netzbetrieb prüfen: im Akkubetrieb drosselt Windows die CPU
  # Leere Ausgabe (Abfrage gescheitert oder kein powershell.exe) wird zu "unbekannt"
  power=$(powershell.exe -NoProfile -Command '(Get-CimInstance -Namespace root/wmi -ClassName BatteryStatus).PowerOnline' 2>/dev/null | tr -d '\r')
  echo "Netzteil:   ${power:-unbekannt}"
  # Maximaler Leistungszustand des Prozessors im Netzbetrieb: unter 100 % schaltet Windows den Turbo Boost ab
  maxcpu=$(powercfg.exe /query SCHEME_CURRENT SUB_PROCESSOR PROCTHROTTLEMAX 2>/dev/null \
    | grep -iE 'Wechselstromeinstellung|AC Power Setting Index' | grep -o '0x[0-9a-fA-F]*')
  [ -n "$maxcpu" ] && maxcpu="$((maxcpu)) %"
  echo "Max. CPU:   ${maxcpu:-unbekannt}"
} > "results/umgebung-$label-$(date +%Y%m%d-%H%M%S).txt"

# In WSL2 ist eine Messung ohne belegten Netzbetrieb und Leistungszustand nicht verwertbar.
# Häufigste Ursache: WSL-Interop nach einem Neustart der WSL-VM ausgefallen
# ("Exec format error" bei powercfg.exe); hilft "wsl --shutdown" unter Windows.
# Auf anderen Systemen gibt es diese Abfragen nicht, dort bleiben die Werte "unbekannt".
if [ "$label" = wsl2 ] && { [ -z "$power" ] || [ -z "$maxcpu" ]; }; then
  echo "Netzteil oder Max. CPU unbekannt, Messung abgebrochen (WSL-Interop prüfen)" >&2
  exit 1
fi
