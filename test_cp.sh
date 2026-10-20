#!/data/data/com.termux/files/usr/bin/bash
# test_cp.sh — regresión de la librería cp.sh (0 execs).
#
# Comprueba que cp_* habla el mismo protocolo que amctl y que devuelve
# exactamente el mismo texto.
set -u
AMCTL="${AMCTL:-$HOME/bin/amctl}"
CP_LIB="${CP_LIB:-$HOME/costphone/skill/phone-control/cp.sh}"
pass=0; fail=0

[[ -f "$CP_LIB" ]] || { echo "no encuentro $CP_LIB" >&2; exit 1; }
# shellcheck disable=SC1090
. "$CP_LIB"

t() {   # t <nombre> <esperado> <comando...>
    local name="$1" want="$2"; shift 2
    local out
    out=$("$@" 2>&1)
    if [[ "$out" == *"$want"* ]]; then
        printf '  OK   %-26s %s\n' "$name" "${out%%$'\n'*}"
        pass=$((pass+1))
    else
        printf '  FAIL %-26s esperaba %q, got %q\n' "$name" "$want" "${out%%$'\n'*}"
        fail=$((fail+1))
    fi
}

echo "== cp.sh regresión =="

t "cp_status"    "canal rápido: OK"  cp_status
t "cp_snap"      "Pantalla"           cp_snap
t "cp_snap -a"   "elementos"          cp_snap -a
t "cp_snap f"    "Pantalla"           cp_snap --filter a
t "cp_back"      "back"               cp_back
t "cp_home"      "home"               cp_home
t "cp_tap"       "tap"                cp_tap 5 5
t "cp_swipe"     "swipe"              cp_swipe 100 100 100 200
t "cp_scroll"    "scroll"             cp_scroll down
t "cp_click"     ""                   cp_click "NoExisteEsteTexto"
t "cp_clickid"   ""                   cp_clickid no_existe_id
t "cp_type"      ""                   cp_type "texto de prueba"
t "cp_cleartext" ""                   cp_cleartext
t "cp_paste"     ""                   cp_paste
t "cp_waitfor"   ""                   cp_waitfor "Chats" 1500
t "cp_launch"    ""                   cp_launch com.termux
t "cp_see"       "captura"            cp_see

# --- batch ---
out=$(cp_batch "home; back" 2>&1)
if [[ "$out" == *"home"* && "$out" == *"back"* && "$out" == *"---"* ]]; then
    printf '  OK   %-26s %s\n' "cp_batch" "$(echo "$out" | tr '\n' ' ')"
    pass=$((pass+1))
else
    printf '  FAIL %-26s %q\n' "cp_batch" "$out"; fail=$((fail+1))
fi

# --- cp.sh y amctl deben coincidir EXACTAMENTE ---
same=1
for args in "snap --filter a" "snap -a" "back"; do
    a=$($AMCTL $args 2>&1)
    b=$(cp_${args%% *} ${args#* } 2>&1)
    [[ "$a" == "$b" ]] || { same=0; echo "  (difiere: $args)"; }
done
if [[ $same -eq 1 ]]; then
    printf '  OK   %-26s salida identica a amctl\n' "cp.sh == amctl"
    pass=$((pass+1))
else
    printf '  FAIL %-26s la salida difiere\n' "cp.sh == amctl"; fail=$((fail+1))
fi

# --- unicode por el protocolo (base64 en bash puro) ---
out=$(cp_type "ñáéíóú € 😀" 2>&1)
if [[ -n "$out" ]]; then
    printf '  OK   %-26s %s\n' "cp_type unicode" "$(echo "$out" | tr '\n' ' ')"
    pass=$((pass+1))
else
    printf '  FAIL %-26s (vacío)\n' "cp_type unicode"; fail=$((fail+1))
fi

echo
echo "  $pass OK, $fail FAIL"
[[ $fail -eq 0 ]]
