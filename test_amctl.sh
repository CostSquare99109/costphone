#!/data/data/com.termux/files/usr/bin/bash
# test_amctl.sh — prueba de regresión del CLI.
# Comprueba que cada subcomando responde como se espera y que el canal
# rápido sigue funcionando. No necesita root ni interacción.
set -u
AMCTL="${AMCTL:-$HOME/bin/amctl}"
pass=0; fail=0

t() {   # t <nombre> <esperado-substring> <comando...>
    local name="$1" want="$2"; shift 2
    local out rc
    out=$("$@" 2>&1); rc=$?
    if [[ "$out" == *"$want"* ]]; then
        printf '  OK   %-22s %s\n' "$name" "${out%%$'\n'*}"
        pass=$((pass+1))
    else
        printf '  FAIL %-22s esperaba %q, got %q (rc=%d)\n' "$name" "$want" "${out%%$'\n'*}" "$rc"
        fail=$((fail+1))
    fi
}

echo "== amctl regresión =="

t "warmup"       "canal listo"        "$AMCTL" warmup
t "status"       "canal rápido: OK"   "$AMCTL" status
t "snap"         "Pantalla"           "$AMCTL" snap
t "snap -a"      "elementos"          "$AMCTL" snap -a
t "snap filter"  "Pantalla"           "$AMCTL" snap --filter a
t "snap filter=" "Pantalla"           "$AMCTL" snap --filter=xyz
t "tree"         "# pkg="             "$AMCTL" tree
t "back"         "back"               "$AMCTL" back
t "home"         "home"               "$AMCTL" home
t "tap"          "tap"                "$AMCTL" tap 5 5
t "tapv"         "tap (5,5)"          "$AMCTL" tapv 5 5
t "tap verify"   "tap (5,5)"          "$AMCTL" tap 5 5 --verify=800
t "swipe"        "swipe"              "$AMCTL" swipe 100 100 100 200 100
t "scroll"       "scroll"             "$AMCTL" scroll down
t "recents"      "recents"            "$AMCTL" recents
t "type"         ""                   "$AMCTL" type "texto de prueba"
t "cleartext"    ""                   "$AMCTL" cleartext
t "paste"        ""                   "$AMCTL" paste
t "click"        ""                   "$AMCTL" click "NoExisteEsteTexto"
t "clickid"      ""                   "$AMCTL" clickid no_existe_id
t "scrollto"     ""                   "$AMCTL" scrollto "NoExisteEsteTexto"
t "presskey"     "key"                "$AMCTL" presskey back
t "display"      "d0"                 "$AMCTL" display
t "displays"     "d0"                 "$AMCTL" displays
t "see"          "captura"            "$AMCTL" see
t "launch pkg"   ""                   "$AMCTL" launch com.termux
t "enable"       ""                   "$AMCTL" enable

# --- batch ---
out=$("$AMCTL" batch "home; back" 2>&1)
if [[ "$out" == *"home"* && "$out" == *"back"* && "$out" == *"---"* ]]; then
    printf '  OK   %-22s %s\n' "batch" "$(echo "$out" | tr '\n' ' ')"
    pass=$((pass+1))
else
    printf '  FAIL %-22s %q\n' "batch" "$out"; fail=$((fail+1))
fi

# --- batch con comillas (un texto con espacios) ---
out=$("$AMCTL" batch "click 'texto con espacios'" 2>&1)
if [[ -n "$out" ]]; then
    printf '  OK   %-22s %s\n' "batch comillas" "$(echo "$out" | tr '\n' ' ')"
    pass=$((pass+1))
else
    printf '  FAIL %-22s (vacío)\n' "batch comillas"; fail=$((fail+1))
fi

# --- batch con -d propio ---
out=$("$AMCTL" batch "-d 1 snap" 2>&1)
if [[ -n "$out" ]]; then
    printf '  OK   %-22s %s\n' "batch -d" "$(echo "$out" | tr '\n' ' ')"
    pass=$((pass+1))
else
    printf '  FAIL %-22s (vacío)\n' "batch -d"; fail=$((fail+1))
fi

# --- unicode por el canal (base64 puro) ---
out=$("$AMCTL" type "ñáéíóú € 😀" 2>&1)
if [[ -n "$out" ]]; then
    printf '  OK   %-22s %s\n' "type unicode" "$(echo "$out" | tr '\n' ' ')"
    pass=$((pass+1))
else
    printf '  FAIL %-22s (vacío)\n' "type unicode"; fail=$((fail+1))
fi

# --- help ---
t "help"         "Usage"              "$AMCTL" help
t "help batch"   "batch"              "$AMCTL" help

# --- comando inexistente ---
out=$("$AMCTL" no_existe 2>&1); rc=$?
if [[ $rc -ne 0 && "$out" == *"unknown command"* ]]; then
    printf '  OK   %-22s rc=%d\n' "unknown cmd" "$rc"; pass=$((pass+1))
else
    printf '  FAIL %-22s rc=%d %q\n' "unknown cmd" "$rc" "$out"; fail=$((fail+1))
fi

echo
echo "  $pass OK, $fail FAIL"
[[ $fail -eq 0 ]]
