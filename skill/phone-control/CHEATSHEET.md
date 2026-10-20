# CHEAT SHEET — phone-control (amctl)

> Referencia rápida (1 página). Docs completos: [SKILL.md](SKILL.md) · Pantalla virtual: `docs/pantalla-virtual.md` · Auditoría de velocidad: [SPEED_AUDIT.md](../../SPEED_AUDIT.md)

## 🚀 LO MÁS RÁPIDO: `cp.sh` (0 execs)

En un teléfono **cada `exec` de un binario cuesta ~150 ms**. `amctl` es un
script de bash, así que lanzarlo cuesta un exec. `cp.sh` es la misma
funcionalidad **sin lanzar ningún proceso** (solo builtins de bash):

```bash
. ~/.config/opencode/skills/android/phone-control/cp.sh && cp_snap --filter chat
```

| | `amctl` | `cp.sh` |
|---|---|---|
| `status` | 254 ms | **14 ms** |
| `snap` | 224 ms | **89 ms** |
| Varias órdenes | `amctl a; amctl b` | `cp_batch "a; b"` |

Mismas órdenes, mismo formato de salida: `cp_snap`, `cp_tap X Y`,
`cp_click "texto"`, `cp_clickid id`, `cp_tapref @pN`, `cp_type "texto"`,
`cp_waitfor "texto" [ms]`, `cp_launch pkg`, `cp_back`, `cp_home`,
`cp_scroll down`, `cp_see`, `cp_batch "…"`.

`amctl` sigue siendo la interfaz completa (`help`, `ocr`, `display`, `float`,
`list`, `find`).

## ⚡ `amctl` — referencia

| Qué necesitas | Comando | Coste | Nota clave |
|---|---|---|---|
| **Verificar canal** | `amctl status \| head -1` | 0.25s | "canal rápido: OK" = todo verde |
| **Pre-calentar canal** | `amctl warmup` | 0.02s | Cachea el puerto: la 1ª orden ya no lo busca |
| **Reactivar servicio** | `amctl enable` | 1-2s | Tras actualizar la app (Android lo desactiva) |
| **Abrir app** | `amctl launch com.pkg` | 0.3s | Por nombre: `amctl launch whatsapp` (lo resuelve la app) |
| **Abrir URL** | `amctl openurl "https://…"` | 0.4s | Evita el omnibox; `openurl <url> com.android.chrome` fuerza browser |
| **Esperar texto real** | `amctl waitfor "texto" 10000` | 0.1-2.4s | **Devuelve snapshot con refs** — nunca uses `sleep` |
| **Ver pantalla (filtro)** | `amctl snap --filter "chat"` | 0.25s | Menos ruido, refs @pN frescas |
| **Ver todo (texto + interactivos)** | `amctl snap -a` | 0.3s | Pantallas densas |
| **Click por texto** | `amctl click "Enviar"` | 0.24s | Elige el nodo más específico, no el contenedor |
| **Click por id** | `amctl clickid send` | 0.23s | Id corto del snapshot (`#send` → `clickid send`) |
| **Tocar ref** | `amctl tapref @p3 --settle 500` | 0.2s | El `settle` lo duerme el servidor (no el CLI) |
| **Escribir (reemplaza)** | `amctl type "texto"` | 0.2s | setText: **borra** lo anterior; verifica que aparezca |
| **Vaciar campo** | `amctl cleartext` | 0.2s | Also `paste` (clipboard) y `focus "texto"` |
| **Scroll semántico** | `amctl scroll down` | 0.2s | La app desplaza su lista; `scrollto "texto"` |
| **Swipe bruto** | `amctl swipe 540 1700 540 700 700` | 0.2s | Para YouTube, feeds (donde `scroll` falla) |
| **Back / Home / Recents** | `amctl back` / `home` / `recents` | 0.15s | `back` sí tiene versión `-d`; los demás son globales (display 0) |
| **Varias órdenes (1 proceso)** | `amctl batch "home; back; snap"` | 1 exec | 3 órdenes: 2.2s → 0.44s |
| **OCR (canvas/WebView)** | `amctl ocr "Actualizar" --region 700,1300,300,120` | 2-3s | Último recurso; sin `--region` ~19s |
| **Captura JPEG** | `amctl see` | 0.7s | Lee la imagen con `read`; `tapshot X Y W H` para tocar |
| **Display virtual ON** | `amctl display on 1080 1920 320` | 1-2s | Luego `amctl display` para saber el ID real |
| **Comando en display N** | `amctl -d 2 snap` | igual | Flag `-d`, no subcomando |

---

### ⚡ REGLA DE ORO
**1 tool-call = 1 línea de bash encadenada** — cada tool-call cuesta ida y vuelta:
```bash
amctl launch com.whatsapp; amctl waitfor "buscar" 10000; amctl snap --filter "chat"
```
…o, aún mejor, **1 proceso** para todas:
```bash
amctl batch "launch com.whatsapp; waitfor buscar 10000; snap --filter chat"
```

### ❌ ANTI-PATRONES (lo que NO hacer)
| ❌ Lento/frágil | ✅ Rápido |
|---|---|
| `launch` + `sleep 8` + `snap` | `launch` + `waitfor "texto" 10000` |
| `snap \| head -40` | `snap --filter "lo que buscas"` |
| `snap` → `tapref` → `snap` → `tapref` | `clickid <id>` o `click "texto"` |
| Un `amctl` por tool-call | `amctl batch "a; b; c"` en una llamada |
| `amctl` en bucle | `. cp.sh && cp_*` (0 execs) |
| `launch com.android.chrome` y luego escribir URL | `openurl "https://…"` |

### 🔁 BUCLE CENTRAL
```
amctl launch <pkg>     # 1. Abrir app
amctl snap             # 2. Ver refs @pN
amctl tapref @p3       # 3. Actuar
amctl snap             # 4. RE-SNAPSHOT tras cambio
```
**Las refs @pN mueren al cambiar de pantalla** → re-snapshot antes de cada acción.
`tapref` avisa "obsoleta — re-snapshot" si el elemento ya no existe.

### 📺 DISPLAY VIRTUAL (app v1.2+)
```bash
amctl display on 1080 1920 320      # Crear (gravity opcional: gravity_bottom_right…)
amctl display                      # OBLIGATORIO: averiguar el ID real
amctl -d 2 launch com.android.chrome
amctl -d 2 snap
amctl display off
```
- `-d N` es flag global: `amctl -d 2 snap`, `amctl -d 2 click "btn"`
- Sin `-d`, todo va al **display 0** (la pantalla del usuario)
- `home`/`recents`/`power` son **globales** (display 0); `back` sí es dirigido
- **Las refs son por display**: un `@p3` del display 2 no vale en el 0
- Requiere `pm grant WRITE_SECURE_SETTINGS` (una vez, hecho por el usuario)

### ⌨️ TECLADO (IME) — refs `[ime]`
Con el teclado abierto: `amctl snap` → busca `[ime]` → `tapref @pN` a **Enter** o **⌫**.
No adivines coordenadas: son refs.

### 🛠 TROUBLESHOOTING RÁPIDO
| Síntoma | Fix |
|---|---|
| "canal no disponible" | `amctl enable` |
| `snap` vacío / sin refs | `amctl enable` → `amctl snap` |
| "ref @pN no existe" / "obsoleta" | `amctl snap` (la pantalla cambió) |
| Elemento no visible | `amctl scroll down` / `swipe` → `snap` |
| `type` "no lo muestra" | es un desplegable → `click "opción"`; input web → abre teclado y reintenta |
| `launch whatsapp` no resuelve | La app es vieja → `amctl launch com.whatsapp` |
| Play Protect bloquea APK | "Entendido" y reintentar, o desactivar Play Protect temporalmente |
