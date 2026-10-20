#!/data/data/com.termux/files/usr/bin/bash
#==============================================================================
# CostPhone — instalador todo-en-uno para Termux
#
# Instala:
#   1. Dependencias: termux-am (transporte "am") + python
#   2. Modificación del "am" de Termux (socket server + PATH)
#   3. amctl + amctl-snap.py  ->  ~/bin
#   4. Skill "phone-control"  ->  ~/.config/opencode/skills/android/
#   5. Bloque de registro    ->  ~/.config/opencode/AGENTS.md
#   6. APK CostPhone          ->  abre el instalador en el teléfono
#
# Uso:  bash install.sh
#==============================================================================
set -u

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$HOME/bin"
SKILL_DIR="$HOME/.config/opencode/skills/android/phone-control"
AGENTS="$HOME/.config/opencode/AGENTS.md"
APK_SRC="$(ls -1 "$REPO_DIR"/apk/CostPhone-v*.apk 2>/dev/null | sort -V | tail -1 || true)"
[[ -z "$APK_SRC" ]] && APK_SRC="$REPO_DIR/app/build/CostPhone.apk"
APK_DEST="/sdcard/Download/$(basename "$APK_SRC")"

G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'; N='\033[0m'
step() { echo -e "${G}[+]${N} $*"; }
warn() { echo -e "${Y}[!]${N} $*"; }

echo "══════════════════════════════════════════════════"
echo "  CostPhone installer — control del teléfono desde Termux"
echo "══════════════════════════════════════════════════"

# --- 1. Dependencias -------------------------------------------------------
step "1/6  Instalando dependencias (termux-am, python)..."
pkg install -y termux-am python >/dev/null 2>&1 || warn "pkg install falló — revisa tu conexión y re-ejecuta"

# --- 2. Modificar el "am" de Termux ---------------------------------------
step "2/6  Configurando el transporte am (socket server + PATH)..."
mkdir -p "$HOME/.termux"
TP="$HOME/.termux/termux.properties"
touch "$TP"
if ! grep -q 'run-termux-am-socket-server' "$TP"; then
    echo 'run-termux-am-socket-server = true' >> "$TP"
fi
termux-reload-settings >/dev/null 2>&1 || true
if ! grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null; then
    echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"
fi
export PATH="$BIN:$PATH"
echo "     am --version  =>  $(am --version 2>/dev/null || echo 'NO INSTALADO')"

# --- 3. amctl --------------------------------------------------------------
step "3/6  Instalando amctl + amctl-snap.py en ~/bin..."
mkdir -p "$BIN"
cp "$REPO_DIR/termux/amctl" "$BIN/amctl"
cp "$REPO_DIR/termux/amctl-snap.py" "$BIN/amctl-snap.py"
chmod +x "$BIN/amctl" "$BIN/amctl-snap.py"
echo "     listo: $BIN/amctl"

# --- 4. Skill para OpenCode ------------------------------------------------
step "4/6  Instalando la skill 'phone-control' para OpenCode..."
if [ -d "$HOME/.config/opencode" ]; then
    mkdir -p "$SKILL_DIR"
    # copia TODA la skill (SKILL.md + CHEATSHEET.md + futuros archivos):
    # el cheat sheet se enlaza por ruta relativa, si falta el link se rompe.
    cp -r "$REPO_DIR/skill/phone-control/." "$SKILL_DIR/"
    echo "     listo: $SKILL_DIR/ ($(ls -1 "$SKILL_DIR" | tr '\n' ' '))"
else
    warn "No hay ~/.config/opencode (¿OpenCode no instalado?) — skill omitida"
fi

# --- 5. AGENTS.md ----------------------------------------------------------
step "5/6  Registrando la skill en AGENTS.md..."
if [ -d "$HOME/.config/opencode" ]; then
    touch "$AGENTS"
    if ! grep -q 'phone-control' "$AGENTS" 2>/dev/null; then
        cat >> "$AGENTS" <<'EOF'

<!-- phone-control -->
Phone tasks (control the user's Android apps): use `amctl` + skill `phone-control`.
Loop: `amctl launch <pkg>` → `amctl snap` (refs @pN) → `amctl tapref @pN` / `type` / `click` → re-snap.
Fast: chain commands in ONE bash call and use `amctl waitfor "texto"` instead of `sleep`.
Read screen as text: `amctl snap` (or `amctl tree` for the raw tree). Screenshot: `amctl see`.
Guide: ~/.config/opencode/skills/android/phone-control/SKILL.md
<!-- end phone-control -->
EOF
        echo "     bloque añadido a $AGENTS"
    else
        echo "     ya estaba registrado en $AGENTS"
    fi
fi

# --- 6. APK ----------------------------------------------------------------
step "6/6  Instalando la app CostPhone (servicio de accesibilidad)..."
if [ -f "$APK_SRC" ]; then
    cp "$APK_SRC" "$APK_DEST"
    termux-open "$APK_DEST" 2>/dev/null || warn "Abre manualmente: $APK_DEST"
    echo ""
    echo "  ┌─────────────────────────────────────────────────┐"
    echo "  │  EN EL TELÉFONO:                                │"
    echo "  │   1. Toca 'Instalar'                            │"
    echo "  │      (si Play Protect avisa: Entendido)         │"
    echo "  │   2. Ajustes → Accesibilidad → CostPhone        │"
    echo "  │      → Activar y confirmar                      │"
    echo "  └─────────────────────────────────────────────────┘"
else
    warn "No encontré el APK en $APK_SRC"
fi

echo ""
echo "══════════════════════════════════════════════════"
echo "  LISTO — prueba con:   amctl status"
echo "  amctl snap            (debe imprimir la pantalla)"
echo "  amctl see             (captura -> /sdcard/Download/accessdroid-shot.jpg)"
echo "  Si 'status' dice que no hay canal rápido, reactiva"
echo "  Ajustes → Accesibilidad → CostPhone (Android lo desactiva"
echo "  solo al actualizar la app)."
echo "══════════════════════════════════════════════════"
