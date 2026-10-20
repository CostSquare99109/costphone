#!/data/data/com.termux/files/usr/bin/bash
# bench.sh — mide la latencia real de los comandos de amctl.
#
#   ./bench.sh [iteraciones]
#
# Reporta p50/p95/min/mean por comando. Mide con $EPOCHREALTIME (builtin de
# bash: cero forks), porque en este dispositivo CADA exec de un binario cuesta
# ~150 ms y contaminaría la medición.

set -u
N="${1:-10}"
AMCTL="${AMCTL:-$HOME/bin/amctl}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

stats() {   # stats <etiqueta> < fichero
    local lbl="$1" f="$2"
    python3 - "$lbl" "$f" <<'PY'
import sys
lbl, path = sys.argv[1], sys.argv[2]
vals = []
for line in open(path):
    p = line.split()
    if len(p) == 2:
        vals.append(int(p[1]))
if not vals:
    print(f"{lbl:<18} (sin datos)"); sys.exit()
vals.sort(); n = len(vals)
p50 = vals[min(n-1, int((n+1)*0.50))]
p95 = vals[min(n-1, int((n+1)*0.95))]
print(f"{lbl:<18} p50={p50:4d}ms  p95={p95:4d}ms  min={vals[0]:4d}ms  "
      f"mean={sum(vals)//n:4d}ms  max={vals[-1]:4d}ms")
PY
}

# bench <fichero-out> <comando...>  -> mide cada iteración
bench() {
    local out="$1"; shift
    local i t0 t1
    : > "$out"
    for (( i=0; i<N; i++ )); do
        # EPOCHREALTIME usa '.' decimal en este locale: se quita el punto
        t0=${EPOCHREALTIME/./}
        "$@" >/dev/null 2>&1
        t1=${EPOCHREALTIME/./}
        printf '%s %d\n' x "$(( (t1 - t0) / 1000 ))" >> "$out"
    done
}

echo "== amctl bench — $N iteraciones =="
echo

# --- suelo del sistema: coste de un exec de un binario de Termux ----------
bench "$TMP/b1" /data/data/com.termux/files/usr/bin/true
stats "exec /bin/true" "$TMP/b1"
bench "$TMP/b2" /data/data/com.termux/files/usr/bin/base64 --help
stats "exec base64" "$TMP/b2"

# --- suelo de amctl: el propio arranque del script ------------------------
bench "$TMP/b3" "$AMCTL" --version
stats "amctl arranque" "$TMP/b3"

echo
echo "== comandos reales =="

bench "$TMP/s"  "$AMCTL" status
stats "status" "$TMP/s"

bench "$TMP/sn" "$AMCTL" snap
stats "snap" "$TMP/sn"

bench "$TMP/sf" "$AMCTL" snap --filter chat
stats "snap --filter" "$TMP/sf"

bench "$TMP/tr" "$AMCTL" tree
stats "tree" "$TMP/tr"

bench "$TMP/cl" "$AMCTL" click "Chats"
stats "click(texto)" "$TMP/cl"

bench "$TMP/ci" "$AMCTL" clickid nonexistent_id
stats "clickid" "$TMP/ci"

bench "$TMP/tp" "$AMCTL" tap 500 1200
stats "tap" "$TMP/tp"

bench "$TMP/ln" "$AMCTL" launch com.whatsapp
stats "launch (pkg)" "$TMP/ln"

bench "$TMP/ln2" "$AMCTL" launch whatsapp
stats "launch (nombre)" "$TMP/ln2"

bench "$TMP/wf" "$AMCTL" waitfor "Chats" 3000
stats "waitfor(acierto)" "$TMP/wf"

bench "$TMP/sh" "$AMCTL" see
stats "see" "$TMP/sh"

echo
echo "== flujos (varios comandos en una sola invocación) =="

# 3 amctl separados en la MISMA línea de bash (como haría el agente)
bench "$TMP/f1" bash -c "$AMCTL home; $AMCTL back; $AMCTL snap >/dev/null"
stats "3 amctl / 1 shell" "$TMP/f1"

# 1 amctl con batch (si existe)
if "$AMCTL" help 2>&1 | grep -q 'batch'; then
    bench "$TMP/f2" bash -c "$AMCTL batch 'home; back; snap'"
    stats "1 amctl batch" "$TMP/f2"
fi

echo
echo "== coste por exec externo dentro de amctl =="
bash -x "$AMCTL" snap 2>&1 >/dev/null \
  | grep -oE '^\+* (date|base64|sed|awk|tr|head|cut|stat|sleep|pm|termux-am|python3|cat|rm|wc|grep|seq|mkdir|ls|dirname|basename|touch|true|date)$' \
  | awk '{print $2}' | sort | uniq -c | sort -rn | sed 's/^/   /'
echo "   (cada uno ≈ 150-190 ms en este dispositivo)"
