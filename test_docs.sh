#!/data/data/com.termux/files/usr/bin/bash
# test_docs.sh — checks de las docs de la skill.
#
#   1. Que TODOS los comandos `amctl <cmd>` citados existan de verdad.
#   2. Que ningun subcomando real aparezca "suelto" sin el prefijo `amctl`
#      (un agente no sabria que ejecutar).
#   3. Que la cheat sheet no pase del limite de 1 pagina.
#   4. Que los enlaces y anchors internos resuelvan.
#   5. Que el frontmatter de SKILL.md siga siendo valido.
#   6. Que las trampas y los patrones esten en los dos ficheros.
#   7. Que la skill 'phone-control-cheatsheet' este instalada al dia.

set -u
cd "$(dirname "$0")" || exit 1
pass=0; fail=0
ok() { printf '  OK   %s\n' "$1"; pass=$((pass+1)); }
ko() { printf '  FAIL %s\n' "$1"; fail=$((fail+1)); }

echo "== docs regresión =="

# --- todo en python: es mas fiable que grep para esto ---------------------
if python3 - <<'PY'
import re, os, sys

SK = 'skill/phone-control/SKILL.md'
CS = 'skill/phone-control/CHEATSHEET.md'
CLI = 'termux/amctl'

# --- subcomandos reales de amctl ---
src = open(CLI, encoding='utf-8').read()
body = re.search(r'^amctl_run\(\) \{(.*?)^\}', src, re.S | re.M)
real = set(re.findall(r'^\s{4}([a-z][a-z-]*)\)', body.group(1), re.M)) if body else set()
# los que viven en cp_or_bc (acciones simples)
real |= {'tap', 'longpress', 'swipe', 'scroll', 'back', 'home', 'recents',
         'type', 'cleartext', 'paste', 'focus', 'click', 'clickid', 'scrollto',
         'presskey'}
real.discard('help')

PLACEHOLDERS = {'a', 'b', 'c'}
fails = []

for f in (SK, CS):
    s = open(f, encoding='utf-8').read()

    # 1) todo `amctl <cmd>` citado debe existir
    cited = set(re.findall(r'amctl ([a-z][a-z-]*)', s))
    missing = sorted(c for c in cited
                      if c not in real and c not in PLACEHOLDERS)
    if missing:
        fails.append(f"{f}: comandos inexistentes: {missing}")
    else:
        print(f"  OK   {f}: los {len(cited)} subcomandos citados existen")

    # 2) en las TABLAS DE REFERENCIA, la COLUMNA DE COMANDO no puede dejar
    #    subcomandos sueltos: la celda tiene que ser copiable tal cual
    #    (`amctl tap X Y`, no `tap X Y`). Solo se mira esa columna.
    #    (fichero) -> (sección, columna del comando)
    TABLAS = {
        SK: [('## Actuar', 0)],
        CS: [('## ⚡ `amctl` — referencia completa', 1)],
    }
    sueltos = set()
    for f, secs in TABLAS.items():
        s = open(f, encoding='utf-8').read()
        for sec, col in secs:
            in_sec = False
            for line in s.split('\n'):
                if line.startswith('## '):
                    in_sec = line.strip() == sec.strip()
                    continue
                if not in_sec or not line.startswith('|'):
                    continue
                cells = line.strip('|').split('|')
                if len(cells) <= col:
                    continue
                cell = cells[col]
                # el spec de `batch "..."` y el flag `-d N` no llevan prefijo
                cell = re.sub(r'batch\s+"[^"]*"', 'batch', cell)
                cell = re.sub(r'-d\s*\d+', '', cell)
                for w in re.findall(r'(?<![\w./-])([a-z][a-z-]*)(?![\w-])', cell):
                    if w in real and w not in PLACEHOLDERS:
                        if not re.search(r'amctl\s+' + re.escape(w) + r'\b', cell):
                            sueltos.add((f, w, cell.strip()[:60]))
    if sueltos:
        for f, w, c in sorted(sueltos):
            print(f"  FAIL {f}: '{w}' sin prefijo en la columna de comando: {c}")
        fails.append(f"tablas de referencia: {len(sueltos)} comandos sin prefijo 'amctl'")
    else:
        print("  OK   tablas de referencia: todo comando lleva su 'amctl'")

# 3) limite de 1 pagina
n = len(open(CS, encoding='utf-8').read().split('\n')) - 1
if n > 120:
    fails.append(f"cheat sheet: {n} lineas (> 120)")
else:
    print(f"  OK   cheat sheet: {n} lineas (<= 120)")

# 4) enlaces y anchors
bad = 0
for f in (SK, CS):
    s = open(f, encoding='utf-8').read()
    heads = [l.lstrip('# ') for l in s.split('\n') if re.match(r'^#{1,6} ', l)]
    def anchor(h):
        return re.sub(r'\s', '-', re.sub(r'[^\w\s-]', '', h.lower(), flags=re.U))
    anchors = {anchor(h) for h in heads}
    for line in s.split('\n'):
        for a in re.findall(r'\]\(#([^)]+)\)', line):
            if a not in anchors:
                print(f"  FAIL ancla rota {f}: #{a}"); bad += 1
        for a in re.findall(r'\]\((?!#)([^)]+)\)', line):
            p = os.path.normpath(os.path.join(os.path.dirname(f), a))
            if not os.path.exists(p):
                print(f"  FAIL fichero roto {f}: {a}"); bad += 1
if not bad:
    print("  OK   enlaces y anchors resuelven")

# 5) frontmatter
s = open(SK, encoding='utf-8').read()
if not (s.startswith('---\n') and re.search(r'^name: phone-control$', s, re.M)
        and re.search(r'^description: ".+"$', s, re.M)):
    fails.append("frontmatter de SKILL.md roto")
else:
    print("  OK   frontmatter de SKILL.md válido")

# 6) trampas + patrones en los dos ficheros
for f in (SK, CS):
    s = open(f, encoding='utf-8').read()
    if not (re.search(r'TRAMPAS UNIVERSALES', s, re.I) and re.search(r'PATRONES', s, re.I)):
        fails.append(f"{f}: falta trampas o patrones")
    else:
        print(f"  OK   {f}: trampas + patrones presentes")

for x in fails:
    print("  FAIL " + x)
sys.exit(1 if fails else 0)
PY
then ok "docs: comandos, prefijos, anchors y frontmatter"; else ko "docs: hay fallos (arriba)"; fi

# --- 7. la skill generada está instalada y al día -------------------------
gen="$HOME/.config/opencode/skills/android/phone-control-cheatsheet/SKILL.md"
if [[ -f "$gen" ]]; then
    if grep -q "phone-control-cheatsheet" "$gen" \
       && grep -q "TRAMPAS UNIVERSALES" "$gen"; then
        ok "skill 'phone-control-cheatsheet' instalada en el entorno"
    else
        ko "skill 'phone-control-cheatsheet' mal generada"
    fi
else
    printf '  SKIP skill phone-control-cheatsheet no instalada (ejecuta install.sh)\n'
fi

echo
echo "  $pass OK, $fail FAIL"
[[ $fail -eq 0 ]]
