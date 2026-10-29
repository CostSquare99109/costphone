# CHEAT SHEET — phone-control (amctl)

> 1 página. Docs completos: [SKILL.md](SKILL.md) · Virtual: `docs/pantalla-virtual.md` · Velocidad: [SPEED_AUDIT.md](../../SPEED_AUDIT.md)

## 🚀 LO MÁS RÁPIDO: `cp.sh` (0 execs)

En un teléfono **cada `exec` de un binario cuesta ~150 ms**: `amctl` es un script de bash → lo lanzas y pagas ese exec. `cp.sh` habla el **mismo protocolo sin lanzar ningún proceso**:

```bash
. ~/.config/opencode/skills/android/phone-control/cp.sh && cp_snap --filter chat
```

`cp_snap [--filter T] [-a]` · `cp_tap X Y` · `cp_swipe X1 Y1 X2 Y2 [ms]` ·
`cp_scroll up|down` · `cp_back` · `cp_home` · `cp_recents` · `cp_click "txt"` ·
`cp_clickid id` · `cp_tapref @pN` · `cp_type "txt"` · `cp_cleartext` ·
`cp_paste` · `cp_waitfor "txt" [ms]` · `cp_launch pkg` · `cp_openurl url` ·
`cp_see` · `cp_batch "a; b"` · `cp_status` *(mismo formato que `amctl`)*
**Coste:** `amctl status` 254 ms → **`cp_status` 14 ms** · `amctl snap` 224 ms → **`cp_snap` 89 ms**

## ⚡ `amctl` — referencia completa

| Qué necesitas | Comando | Coste | Nota clave |
|---|---|---|---|
| **Verificar canal** | `amctl status \| head -1` | 0.25s | "canal rápido: OK" = todo verde |
| **Pre-calentar** | `amctl warmup` | 0.02s | Cachea el puerto |
| **Reactivar servicio** | `amctl enable` | 1-2s | Tras actualizar la app (Android lo desactiva) |
| **Abrir app** | `amctl launch com.pkg` | 0.3s | Por nombre: `amctl launch whatsapp` (lo resuelve la app) |
| **Buscar apps** | `amctl list` · `amctl find <pkg>` | 0.5s | Lista / busca por paquete |
| **Abrir URL** | `amctl openurl "https://…"` | 0.4s | Evita el omnibox; `amctl openurl <url> com.android.chrome` fuerza browser |
| **Esperar texto real** | `amctl waitfor "texto" 10000` | 0.1-2.4s | **Devuelve snapshot con refs** — nunca uses `sleep` |
| **Ver pantalla (filtro)** | `amctl snap --filter "chat"` | 0.25s | Menos ruido, refs @pN frescas |
| **Ver todo (texto + interactivos)** | `amctl snap -a` | 0.3s | Pantallas densas |
| **Árbol crudo** | `amctl tree` | 0.3s | Solo para depurar: formato largo, sin refs |
| **Click por texto** | `amctl click "Enviar"` | 0.24s | ⚠ ver trampas 1-2 |
| **Click por id** | `amctl clickid send` | 0.23s | Id corto del snapshot (`#send` → `clickid send`) — **lo más estable** |
| **Tocar ref** | `amctl tapref @p3 --settle 500` | 0.2s | Re-resuelve contra el árbol vivo (toolbar que colapsa) · nodo SIN texto → `amctl tapshot` · `--verify` confirma que cambió · `--fast` evita el dump |
| **Tocar coordenadas** | `amctl tap X Y [ms]` · `amctl tapv X Y` · `amctl longpress X Y [ms]` | 0.15s | `tapv` = `tap --verify`: avisa si la pantalla NO cambió (`[sin cambios]`); `longpress` = 500 ms |
| **Escribir (reemplaza)** | `amctl type "texto" [ms]` | 0.2s | setText **borra** lo anterior; verifica que aparezca |
| **Vaciar / pegar / enfocar** | `amctl cleartext` · `amctl paste` · `amctl focus "texto"` | 0.2s | `focus` busca texto o descripción |
| **Scroll semántico** | `amctl scroll down\|up [x y]` | 0.2s | La app desplaza su lista |
| **Scroll a un texto** | `amctl scrollto "texto" 10` | 0.2s×n | Busca → desplaza → repite: sirve con listas virtualizadas |
| **Swipe bruto** | `amctl swipe X1 Y1 X2 Y2 [ms]` | 0.2s | Para feeds (donde `scroll` falla) |
| **Back / Home / Recents** | `amctl back` / `amctl home` / `amctl recents` · `amctl presskey back` | 0.15s | `back` sí tiene versión `-d`; los demás son globales |
| **Varias órdenes (1 proceso)** | `amctl batch "home; back; snap"` | 1 exec | 3 órdenes: 2.2s → 0.44s |
| **OCR (canvas/WebView)** | `amctl ocr "Actualizar" --region 700,1300,300,120` | 2-3s | Último recurso; sin `--region` ~19s |
| **Captura JPEG** | `amctl see` | 0.7s | Lee la imagen con `read`; `amctl tapshot X Y W H` para tocar |
| **Ventana flotante (MIUI)** | `amctl float "texto" [pkg]` | 1-2s | recents → long-press → flotante |
| **Display virtual ON** | `amctl display on 1080 1920 320` | 1-2s | Luego `amctl displays` para saber el ID real |
| **Comando en display N** | `amctl -d 2 snap` | igual | Flag `-d`, no subcomando |

---

## ⚠️ TRAMPAS UNIVERSALES (en toda app Android)

| Trampa | Síntoma | Fix universal |
|---|---|---|
| **1. Contenedor vs botón** | `click "texto"` no hace nada: el nodo es el contenedor (concatena el texto de sus hijos) y es el único clicable | `tapref` del nodo **más profundo** con ese texto (o `clickid`). En el snap salen como 2 líneas con el mismo texto: `@p18 [FrameLayout] '1'` vs `@p19 [TextView] '1'` → elige la del **id propio** |
| **2. Fila con zonas** | icono navega a A, título a B, mensaje a C | **Nunca `click` por el título**: `tapref` de la fila (`*_row_container`) o `click` del mensaje |
| **3. Placeholder variable** | `waitfor "Buscar"` expira aunque la barra esté ahí | `waitfor "search_input"` (id **sin** `#`) o `snap --filter "search"` |
| **4. Overlay tapa el botón** | `clickid send` falla tras abrir el teclado | `snap` **después** del teclado y `tapref` de la ref nueva; `--settle 500` |
| **5. Lista virtualizada** | `click "X"` dice "no se encontró" aunque X esté en la lista | `amctl scrollto "X"`: busca/desplaza/repite hasta que la lista lo cree |
| **6. Texto truncado ("…")** | `click "Texto largo..."` no encuentra match | `click` por substring único sin los puntos, o `tapref` de la fila, o `snap -a` |
| **7. Loading ≠ vacío** | `waitfor "Cargando"` expira y la lista sigue vacía | Espera a un texto que **solo existe con datos**; si no, `snap -a` y lee el error |
| **8. Tap y snap juntos** | `tapref && snap` devuelve el snapshot **anterior** | `tapref @pN --settle 500` |
| **9. Ref de nodo SIN texto** | `tapref` responde `[re-resuelto]` y toca otro nodo (un campo vacío no tiene texto con el que comparar) | `amctl see` → `read` → `amctl tapshot X Y W H`; o `amctl clickid <id>` / `amctl focus "<texto>"` |
| **10. Snapshot vacío fantasma** | `snap --filter` dice "sin elementos interactivos" pero el `snap -a` completo sí da elementos (carrera con el teclado) | **Reintenta una vez** antes de concluir que el servicio está apagado |

**Cómo elegir el objetivo:** ¿id corto? → `clickid <id>` (lo más estable).
¿Texto único? → `click "<texto>"`. ¿Fila? → `tapref` de la **fila**. ¿Truncado? → `snap -a`. ¿**Sin texto** (campo vacío)? → `tapshot`/`focus`, nunca `tapref`.

## 📐 PATRONES POR TIPO DE UI

| Patrón | Plantilla |
|---|---|
| **A. Lista → detalle** (chats, correos, settings) | `launch; waitfor "<lista>" 10000` → `snap --filter "<fila>"` → `tapref @pN --settle 500` → `waitfor "<detalle>" 5000` |
| **B. Formulario / chat** | `clickid <campo>` → `cleartext` → `type "<texto>"` → `snap --filter "<texto>"` (verificar) → `clickid send` |
| **C. Tabs / bottom bar** | `clickid <id_del_tab>` — **nunca** por la etiqueta visible |
| **D. Modal / bottom sheet** | `waitfor "<título>" 5000` → `snap --filter "<título>"` → `clickid <botón>` |
| **E. WebView / canvas** | `clickid <campo>` → `type "<texto>" 1000` → si no sale: `ocr "<texto>" --region …` → `tapshot X Y W H` |

## ⚡ REGLA DE ORO
**1 tool-call = 1 línea de bash encadenada** (cada tool-call cuesta ida y vuelta):
`amctl launch com.whatsapp; amctl waitfor "buscar" 10000; amctl snap --filter "chat"`
…o **1 proceso** para todas: `amctl batch "launch …; waitfor …; snap …"`

## ❌ ANTI-PATRONES
| ❌ Lento/frágil | ✅ Rápido |
|---|---|
| `launch` + `sleep 8` + `snap` | `launch` + `waitfor "texto" 10000` |
| `snap \| head -40` | `snap --filter "lo que buscas"` |
| `snap` → `tapref` → `snap` → `tapref` | `clickid <id>` o `click "texto"` |
| Un `amctl` por tool-call | `amctl batch "a; b; c"` en una llamada |
| `amctl` en bucle | `. cp.sh && cp_*` (0 execs: 254 ms → 14 ms) |

## 🔁 BUCLE CENTRAL
`amctl launch <pkg>` → `amctl snap` (refs @pN) → `amctl tapref @p3` →
`amctl snap` (**re-snapshot** tras el cambio).
**Las refs @pN mueren al cambiar de pantalla** → re-snapshot antes de cada acción.

## 📺 DISPLAY VIRTUAL (app v1.2+)
`amctl display on 1080 1920 320` → `amctl displays` (el ID **se pregunta**) →
`amctl -d 2 launch com.android.chrome` → `amctl -d 2 snap` → `amctl display off`
- `-d N` es flag global. Sin `-d`, todo va al **display 0** (la del usuario).
- `home`/`recents`/`power` son **globales** (display 0); `back` sí es dirigido.
- **Las refs son por display**: un `@p3` del display 2 no vale en el 0.
- Requiere `pm grant WRITE_SECURE_SETTINGS` (una vez, hecho por el usuario).

## ⌨️ TECLADO (IME) — refs `[ime]`
Con el teclado abierto: `amctl snap` → busca `[ime]` → `tapref @pN` a **Enter** o **⌫**. No adivines coordenadas: son refs.

## 🛠 TROUBLESHOOTING RÁPIDO
| Síntoma | Fix |
|---|---|
| "canal no disponible" | `amctl enable` |
| `snap` vacío / sin refs | reintenta 1 vez (trampa 10); si sigue: `amctl enable` → `amctl snap` |
| "ref @pN no existe" / "obsoleta" | `amctl snap` (la pantalla cambió) |
| Elemento no visible | `amctl scroll down` / `swipe` → `snap` |
| `click "X"` no hace nada | Trampa 1: `tapref` del nodo más profundo, o `clickid` |
| `type` "no lo muestra" | es un desplegable → `click "opción"`; input web → `type "texto" 1000` |
| Play Protect bloquea APK | "Entendido" y reintentar, o desactivar Play Protect temporalmente |
