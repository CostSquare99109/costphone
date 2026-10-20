#!/data/data/com.termux/files/usr/bin/bash
# rawbench.sh — mide el coste PURO del protocolo CP/1 desde bash, sin
# ningún exec externo (solo builtins: /dev/tcp, read, printf).
#
# Esto es el suelo real: lo que costaría que el agente hablase el protocolo
# directamente en su propia línea de bash.

set -u
HOST=127.0.0.1
PORT=""
for p in 39871 39872 39873 39874 39875; do
    if { exec 7<>"/dev/tcp/$HOST/$p"; } 2>/dev/null; then exec 7<&- 7>&-; PORT=$p; break; fi
done
[[ -n "$PORT" ]] || { echo "servidor no encontrado"; exit 1; }
echo "puerto: $PORT"

SECRET_B64="dGVybXV4LWFjY2Vzc2Ryb2lkLTIwMjU="

# send <petición>  -> imprime "ms cuerpo"
send() {
    local req="$1"
    local t0 t1 head len body
    t0=${EPOCHREALTIME/./}
    { exec 7<>"/dev/tcp/$HOST/$PORT"; } 2>/dev/null || { echo "-1"; return 1; }
    printf '%s\n' "$req" >&7
    IFS= read -r -t 20 head <&7
    read -r _ code len enc <<<"$head"
    body=""
    if [[ "${len:-0}" =~ ^[0-9]+$ && "${len:-0}" -gt 0 ]]; then
        IFS= read -r -N "$len" body <&7 2>/dev/null || true
    fi
    exec 7<&- 7>&-
    t1=${EPOCHREALTIME/./}
    printf '%d\n' "$(( (t1 - t0) / 1000 ))"
}

bench() {  # bench <etiqueta> <petición>
    local lbl="$1" req="$2" i out="$3"
    : > "$out"
    for (( i=0; i<N; i++ )); do
        printf '%s\n' "$(send "$req")" >> "$out"
    done
    python3 - "$lbl" "$out" <<'PY'
import sys
lbl, path = sys.argv[1], sys.argv[2]
v = sorted(int(x) for x in open(path) if x.strip())
n = len(v)
p50 = v[min(n-1, int((n+1)*.5))]; p95 = v[min(n-1, int((n+1)*.95))]
print(f"{lbl:<22} p50={p50:4d}ms  p95={p95:4d}ms  min={v[0]:4d}ms  mean={sum(v)//n:4d}ms")
PY
}

N="${1:-10}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "== coste PURO del protocolo (0 execs) =="
bench "ping"       "CP/1 ACTION_PING b:secret=$SECRET_B64" "$TMP/a"
bench "snap"       "CP/1 ACTION_GET_SNAPSHOT b:secret=$SECRET_B64 b:args=$(printf 'i:display=0\ni:mode=0\n' | base64 | tr -d '\n')" "$TMP/b"
bench "snap filter" "CP/1 ACTION_GET_SNAPSHOT b:secret=$SECRET_B64 b:args=$(printf 'i:display=0\ni:mode=0\ns:filter=chat\n' | base64 | tr -d '\n')" "$TMP/c"
bench "tap"        "CP/1 ACTION_TAP b:secret=$SECRET_B64 b:args=$(printf 'i:display=0\ni:x=500\ni:y=1200\n' | base64 | tr -d '\n')" "$TMP/d"
bench "waitfor"    "CP/1 ACTION_WAITFOR b:secret=$SECRET_B64 b:args=$(printf 'i:display=0\ns:text=Chats\nl:timeout=3000\n' | base64 | tr -d '\n')" "$TMP/e"

echo
echo "== en una MISMA conexión (keep-alive manual: 3 órdenes) =="
t0=${EPOCHREALTIME/./}
{ exec 7<>"/dev/tcp/$HOST/$PORT"; } 2>/dev/null
for i in 1 2 3; do
    printf 'CP/1 ACTION_PING b:secret=%s\n' "$SECRET_B64" >&7
    IFS= read -r -t 20 head <&7
    read -r _ code len enc <<<"$head"
    [[ "${len:-0}" -gt 0 ]] && { IFS= read -r -N "$len" body <&7 2>/dev/null || true; }
done
exec 7<&- 7>&-
t1=${EPOCHREALTIME/./}
echo "3 pings en 1 socket: $(( (t1 - t0) / 1000 ))ms en total"
echo "(nota: el servidor cierra tras 1 orden → la 2ª y 3ª fallan)"
