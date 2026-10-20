# cp.sh — cliente del canal rápido de CostPhone en bash PURO (0 execs).
#
# -----------------------------------------------------------------------------
#  POR QUÉ EXISTE ESTE ARCHIVO
# -----------------------------------------------------------------------------
#  En un teléfono con Termux, CADA exec de un binario cuesta ~150 ms:
#
#      /bin/true .... 135 ms      base64 .... 174 ms      bash -c .... 146 ms
#
#  Even sourcearlo cuesta 0 (bash lee el fichero). Con este fichero, el
#  agente habla con el servicio de accesibilidad desde SU propio shell, sin
#  lanzar ningún proceso:
#
#      . ~/costphone/skill/phone-control/cp.sh && cp_snap --filter chat
#
#  Coste medido en el dispositivo de referencia:
#
#      amctl snap --filter chat ..... 274 ms   (1 exec de bash + trabajo)
#      cp_snap --filter chat .........  48 ms   (0 execs, solo el protocolo)
#
#  El protocolo es el mismo (CP/1) que usa el CLI `amctl`, así que las dos
#  vías son intercambiables. `amctl` sigue siendo la interfaz completa
#  (help, ocr, display, float…); cp.sh es el camino rápido para lo que el
#  agente hace en bucle.
#
# -----------------------------------------------------------------------------
#  USO
# -----------------------------------------------------------------------------
#   . cp.sh                       # carga la librería (0 execs)
#
#   cp_status                     # el canal responde?
#   cp_snap [--filter T] [-a]     # snapshot con refs @pN
#   cp_tap X Y                    # toque
#   cp_swipe X1 Y1 X2 Y2 [ms]
#   cp_scroll up|down
#   cp_back | cp_home | cp_recents
#   cp_click "texto"              # click por texto visible
#   cp_clickid resource_id        # click por id (#id del snapshot)
#   cp_tapref @pN                 # toque por referencia del último snap
#   cp_type "texto"               # escribe (REEMPLAZA)
#   cp_waitfor "texto" [ms]       # espera a que aparezca (1 solo viaje)
#   cp_launch com.paquete
#   cp_see [fichero]              # captura JPEG
#   cp_batch "cmd1; cmd2; ..."    # varias órdenes en un socket
#
# -----------------------------------------------------------------------------
#  COMPATIBILIDAD
# -----------------------------------------------------------------------------
#  Formato del snapshot: '@pN [Clase] 'texto' #id (x,y) [edt]'  (idéntico
#  al de amctl). La clave es la misma. Si el servicio está caído, todas las
#  funciones devuelven 1 y escriben el motivo por stderr.
# -----------------------------------------------------------------------------

# --- estado -----------------------------------------------------------------
CP_HOST=""
CP_PORT=""
CP_CODE=""
CP_TEXT=""
CP_B64=""
CP_ARGS=""
CP_SECRET="termux-accessdroid-2025"
CP_SECRET_B64="dGVybXV4LWFjY2Vzc2Ryb2lkLTIwMjU="
CP_PORTS=(39871 39872 39873 39874 39875)
CP_HOSTS=(127.0.0.1 ::1)
CP_CACHE="${AMCTL_CACHE:-$HOME/.cache/amctl}"

# --- base64 en bash puro ----------------------------------------------------
# carácter -> code point -> bytes UTF-8 -> base64. Verificado byte a byte
# contra el base64 del sistema (incluidos emoji y caracteres astral).
_cp_b64() {
    local s=${1-}
    local -a T=(A B C D E F G H I J K L M N O P Q R S T U V W X Y Z \
                a b c d e f g h i j k l m n o p q r s t u v w x y z \
                0 1 2 3 4 5 6 7 8 9 + /)
    local out="" i=0 n=${#s} cp b1 b2 b3 v
    local -a by=()
    while (( i < n )); do
        printf -v cp '%d' "'${s:i:1}"
        if   (( cp < 0x80 ));   then by+=("$cp")
        elif (( cp < 0x800 ));  then by+=($(( 0xC0 | (cp >> 6) )) $(( 0x80 | (cp & 0x3F) )))
        elif (( cp < 0x10000 )); then by+=($(( 0xE0 | (cp >> 12) )) $(( 0x80 | ((cp >> 6) & 0x3F) )) $(( 0x80 | (cp & 0x3F) )))
        else by+=($(( 0xF0 | (cp >> 18) )) $(( 0x80 | ((cp >> 12) & 0x3F) )) $(( 0x80 | ((cp >> 6) & 0x3F) )) $(( 0x80 | (cp & 0x3F) )))
        fi
        (( i++ ))
    done
    local nb=${#by[@]} j=0
    while (( j < nb )); do
        b1=${by[j]}
        if (( j + 1 < nb )); then b2=${by[j+1]}; else b2=0; fi
        if (( j + 2 < nb )); then b3=${by[j+2]}; else b3=0; fi
        v=$(( (b1 << 16) | (b2 << 8) | b3 ))
        out+="${T[$(( (v >> 18) & 63 ))]}${T[$(( (v >> 12) & 63 ))]}"
        if (( j + 1 < nb )); then out+="${T[$(( (v >> 6) & 63 ))]}"; else out+="="; fi
        if (( j + 2 < nb )); then out+="${T[$(( v & 63 ))]}"; else out+="="; fi
        (( j += 3 ))
    done
    printf '%s' "$out"
}

# --- payload de argumentos --------------------------------------------------
_cp_arg() {
    local v="$3"
    v=${v//\\/\\\\}
    v=${v//$'\n'/\\n}
    CP_ARGS="${CP_ARGS}$1:$2=$v"$'\n'
}

_cp_args_b64() {
    [[ -z "$CP_ARGS" ]] && { printf ''; return; }
    _cp_b64 "$CP_ARGS"
}

# --- localizar el puerto ----------------------------------------------------
# 0 execs: primero la caché, luego un connect por puerto (sondas de /dev/tcp).
_cp_find_port() {
    [[ -n "$CP_PORT" ]] && return 0
    local f="$CP_CACHE/port" p host
    if [[ -f "$f" ]]; then
        read -r host p < "$f" 2>/dev/null || true
        if [[ -n "${host:-}" && -n "${p:-}" ]]; then
            CP_HOST="$host"; CP_PORT="$p"; return 0
        fi
    fi
    for p in "${CP_PORTS[@]}"; do
        for host in "${CP_HOSTS[@]}"; do
            if { exec 9<>"/dev/tcp/$host/$p"; } 2>/dev/null; then
                exec 9<&- 9>&-
                CP_HOST="$host"; CP_PORT="$p"
                return 0
            fi
        done
    done
    return 1
}

# --- una orden por el canal rápido -----------------------------------------
# _cp_call <ACCION> [args-b64]  -> 0 si hubo respuesta
# Deja el resultado en CP_CODE / CP_TEXT / CP_B64.
_cp_call() {
    local action="$1" args="${2:-}"
    local head code len enc body
    CP_CODE=""; CP_TEXT=""; CP_B64=""
    _cp_find_port || { echo "cp: el canal rápido no está disponible" >&2; return 1; }
    { exec 7<>"/dev/tcp/$CP_HOST/$CP_PORT"; } 2>/dev/null || return 1
    if ! printf 'CP/1 %s b:secret=%s b:args=%s\n' "$action" "$CP_SECRET_B64" "$args" >&7 2>/dev/null; then
        exec 7<&- 7>&-; return 1
    fi
    if IFS= read -r -t 25 head <&7; then
        read -r _ code len enc <<<"$head"
        body=""
        if [[ "${len:-0}" =~ ^[0-9]+$ && "${len:-0}" -gt 0 ]]; then
            # read -N cuenta CARACTERES, no bytes: con UTF-8 puede quedarse
            # corto, pero como el servidor cierra la conexion, lo que llega
            # es el cuerpo entero.
            IFS= read -r -N "$len" body <&7 2>/dev/null || true
        fi
        exec 7<&- 7>&-
        CP_CODE="${code:-fail}"
        if [[ "${enc:-raw}" == "b64" ]]; then CP_B64="$body"; else CP_TEXT="$body"; fi
        return 0
    fi
    exec 7<&- 7>&-
    return 1
}

# --- imprimir el resultado --------------------------------------------------
_cp_report() {
    local body="${CP_TEXT:-$CP_B64}"
    case "$CP_CODE" in
        ok)     [[ -n "$body" ]] && printf '%s\n' "$body"; return 0 ;;
        *)      [[ -n "$body" ]] && printf '%s\n' "$body" >&2; return 1 ;;
    esac
}

# ============================================================================
#  ÓRDENES
# ============================================================================

cp_status() {
    CP_ARGS=""
    if _cp_call ACTION_PING ""; then
        echo "canal rápido: OK ($CP_HOST:$CP_PORT, respuesta: ${CP_TEXT:-sin cuerpo})"
        return 0
    fi
    echo "canal rápido: NO disponible" >&2
    return 1
}

cp_snap() {
    local mode=0 filter="" disp="${CP_DISPLAY:-0}"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -a|--all) mode=1; shift ;;
            --filter) filter="${2:-}"; shift 2 ;;
            --filter=*) filter="${1#*=}"; shift ;;
            -d|--display) disp="${2:-0}"; shift 2 ;;
            *) shift ;;
        esac
    done
    CP_ARGS=""
    _cp_arg i display "$disp"
    _cp_arg i mode "$mode"
    [[ -n "$filter" ]] && _cp_arg s filter "$filter"
    _cp_call ACTION_GET_SNAPSHOT "$(_cp_args_b64)" && { _cp_report; return $?; }
    echo "cp: sin canal rápido" >&2
    return 1
}

cp_tap() {
    CP_ARGS=""
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_arg i x "${1:-0}"
    _cp_arg i y "${2:-0}"
    _cp_arg l duration "${3:-80}"
    _cp_call ACTION_TAP "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_swipe() {
    CP_ARGS=""
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_arg i x1 "${1:-0}"; _cp_arg i y1 "${2:-0}"
    _cp_arg i x2 "${3:-0}"; _cp_arg i y2 "${4:-0}"
    _cp_arg l duration "${5:-300}"
    _cp_call ACTION_SWIPE "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_scroll() {
    local dir="${1:-down}"
    CP_ARGS=""
    _cp_arg i display "${CP_DISPLAY:-0}"
    if [[ "$dir" == "down" ]]; then _cp_call ACTION_SCROLL_DOWN "$(_cp_args_b64)"
    else _cp_call ACTION_SCROLL_UP "$(_cp_args_b64)"; fi
    { _cp_report; return $?; }
    return 1
}

cp_back() {
    CP_ARGS=""; _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_BACK "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_home() {
    CP_ARGS=""
    _cp_call ACTION_HOME "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_recents() {
    CP_ARGS=""
    _cp_call ACTION_RECENTS "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_click() {
    CP_ARGS=""
    _cp_arg s text "${1:-}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_CLICK_TEXT "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_clickid() {
    CP_ARGS=""
    _cp_arg s id "${1:-}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_CLICK_ID "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_tapref() {
    CP_ARGS=""
    _cp_arg s ref "${1:-}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_TAPREF "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_type() {
    CP_ARGS=""
    _cp_arg s text "${1:-}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_TYPE "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_cleartext() {
    CP_ARGS=""; _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_CLEAR_TEXT "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_paste() {
    CP_ARGS=""; _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_PASTE "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_waitfor() {
    CP_ARGS=""
    _cp_arg s text "${1:-}"
    _cp_arg l timeout "${2:-8000}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_arg i mode 0
    _cp_call ACTION_WAITFOR "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_launch() {
    CP_ARGS=""
    _cp_arg s package "${1:-}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    _cp_call ACTION_START_ACTIVITY "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_openurl() {
    CP_ARGS=""
    _cp_arg s uri "${1:-}"
    _cp_arg i display "${CP_DISPLAY:-0}"
    [[ -n "${2:-}" ]] && _cp_arg s package "$2"
    _cp_call ACTION_OPEN_URI "$(_cp_args_b64)" && { _cp_report; return $?; }
    return 1
}

cp_see() {
    local out="${1:-/sdcard/Download/accessdroid-shot.jpg}" scale=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --scale) scale="${2:-}"; shift 2 ;;
            --scale=*) scale="${1#*=}"; shift ;;
            *) out="$1"; shift ;;
        esac
    done
    CP_ARGS=""
    _cp_arg i displayId "${CP_DISPLAY:-0}"
    [[ -n "$scale" ]] && _cp_arg f scale "$scale"
    if _cp_call ACTION_SCREENDUMP "$(_cp_args_b64)"; then
        if [[ "$CP_CODE" == "ok" && -n "$CP_B64" ]]; then
            printf '%s' "$CP_B64" | base64 -d > "$out" 2>/dev/null
            local raw_len=${#CP_B64} pad=0
            [[ "$CP_B64" == *"==" ]] && pad=2 || { [[ "$CP_B64" == *"="* ]] && pad=1; }
            echo "captura: $out ($(( raw_len * 3 / 4 - pad )) bytes)"
            return 0
        fi
        [[ -n "$CP_TEXT" ]] && echo "$CP_TEXT" >&2
        return 1
    fi
    return 1
}

# --- varias órdenes en un socket --------------------------------------------
# cp_batch "home; back; snap --filter chat"
cp_batch() {
    local spec="$*"
    [[ -z "${spec//[[:space:]]/}" ]] && { echo "uso: cp_batch \"cmd1; cmd2\"" >&2; return 2; }
    local cur="" i ch
    local -a items=()
    for (( i=0; i<${#spec}; i++ )); do
        ch="${spec:i:1}"
        if [[ "$ch" == ";" ]]; then items+=("$cur"); cur=""; else cur+="$ch"; fi
    done
    items+=("$cur")
    local it rc=0 first=1
    for it in "${items[@]}"; do
        it="${it#"${it%%[![:space:]]*}"}"
        it="${it%"${it##*[![:space:]]}"}"
        [[ -z "$it" ]] && continue
        local -a argv=()
        local s="$it" j=0 n=${#s} word="" q=""
        while (( j < n )); do
            ch="${s:j:1}"
            if [[ -n "$q" ]]; then
                if [[ "$ch" == "$q" ]]; then q=""; else word+="$ch"; fi
            elif [[ "$ch" == "'" || "$ch" == '"' ]]; then
                q="$ch"
            elif [[ "$ch" == " " || "$ch" == $'\t' ]]; then
                if [[ -n "$word" ]]; then argv+=("$word"); word=""; fi
            else
                word+="$ch"
            fi
            (( j++ ))
        done
        [[ -n "$word" ]] && argv+=("$word")
        [[ ${#argv[@]} -eq 0 ]] && continue
        [[ $first -eq 0 ]] && echo "---"
        first=0
        cp_dispatch "${argv[@]}" || rc=1
    done
    return $rc
}

# --- dispatch de una orden de batch -----------------------------------------
cp_dispatch() {
    case "${1:-help}" in
        snap)     shift; cp_snap "$@" ;;
        tap)      shift; cp_tap "${1:-0}" "${2:-0}" "${3:-80}" ;;
        swipe)    shift; cp_swipe "${1:-0}" "${2:-0}" "${3:-0}" "${4:-0}" "${5:-300}" ;;
        scroll)   shift; cp_scroll "${1:-down}" ;;
        back)     cp_back ;;
        home)     cp_home ;;
        recents)  cp_recents ;;
        click)    shift; cp_click "${1:-}" ;;
        clickid)  shift; cp_clickid "${1:-}" ;;
        tapref)   shift; cp_tapref "${1:-}" ;;
        type)     shift; cp_type "${1:-}" ;;
        cleartext) cp_cleartext ;;
        paste)    cp_paste ;;
        waitfor)  shift; cp_waitfor "${1:-}" "${2:-8000}" ;;
        launch)   shift; cp_launch "${1:-}" ;;
        openurl)  shift; cp_openurl "${1:-}" "${2:-}" ;;
        see)      shift; cp_see "$@" ;;
        status)   cp_status ;;
        *) echo "cp: orden desconocida: ${1:-}" >&2; return 1 ;;
    esac
}
