# CHEAT SHEET — phone-control (amctl)

> Referencia rápida (1 página). Docs completos: [SKILL.md](SKILL.md) · Pantalla virtual: `docs/pantalla-virtual.md`

| Qué necesitas | Comando | Coste | Nota clave |
|---|---|---|---|
| **Verificar canal** | `amctl status \| head -1` | 0.5s | "canal rápido: OK" = todo verde |
| **Reactivar servicio** | `amctl enable` | 1-2s | Tras actualizar la app (Android lo desactiva) |
| **Abrir app** | `amctl launch com.pkg` | 0.6-0.9s | Chrome: mejor `openurl`, restaura sesión |
| **Abrir URL** | `amctl openurl "https://…"` | 0.8-1.2s | Evita el omnibox; `openurl <url> com.android.chrome` fuerza browser |
| **Esperar texto real** | `amctl waitfor "texto" 10000` | 0.6-2.4s | **Devuelve snapshot con refs** — nunca uses `sleep` |
| **Ver pantalla (filtro)** | `amctl snap --filter "chat"` | 0.5s | Menos ruido, refs @pN frescas |
| **Ver todo (texto + interactivos)** | `amctl snap -a` | 0.7-1s | Pantallas densas |
| **Click por texto** | `amctl click "Enviar"` | 0.4-1.3s | Elige el nodo más específico, no el contenedor |
| **Click por id** | `amctl clickid send` | 0.4-1.3s | Id corto del snapshot (`#send` → `clickid send`) |
| **Tocar ref** | `amctl tapref @p3 --settle 500` | 0.5-1.4s | `--settle` espera a que asiente (evita snap prematuro) |
| **Escribir (reemplaza)** | `amctl type "texto"` | 0.5-1.3s | setText: **borra** lo anterior; verifica que aparezca |
| **Vaciar campo** | `amctl cleartext` | 0.4s | Also `paste` (clipboard) y `focus "texto"` |
| **Scroll semántico** | `amctl scroll down` | 0.5s | La app desplaza su lista; `scrollto "texto"` |
| **Swipe bruto** | `amctl swipe 540 1700 540 700 700` | 0.5s | Para YouTube, feeds (donde `scroll` falla) |
| **Back / Home / Recents** | `amctl back` / `home` / `recents` | 0.4s | `back` sí tiene versión `-d`; los demás son globales (display 0) |
| **OCR (canvas/WebView)** | `amctl ocr "Actualizar" --region 700,1300,300,120` | 2-3s | Último recurso; sin `--region` ~19s |
| **Captura JPEG** | `amctl see` | 0.9-1.3s | Lee la imagen con `read`; `tapshot X Y W H` para tocar |
| **Display virtual ON** | `amctl display on 1080 1920 320` | 1-2s | Luego `amctl display` para saber el ID real |
| **Comando en display N** | `amctl -d 2 snap` | igual | Flag `-d`, no subcomando |

---

### ⚡ REGLA DE ORO
**1 tool-call = 1 línea de bash encadenada** — cada tool-call cuesta ida y vuelta:
```bash
amctl launch com.whatsapp; amctl waitfor "buscar" 10000; amctl snap --filter "chat"
```

### ❌ ANTI-PATRONES (lo que NO hacer)
| ❌ Lento/frágil | ✅ Rápido |
|---|---|
| `launch` + `sleep 8` + `snap` | `launch` + `waitfor "texto" 10000` |
| `snap \| head -40` | `snap --filter "lo que buscas"` |
| `snap` → `tapref` → `snap` → `tapref` | `clickid <id>` o `click "texto"` |
| Un `amctl` por tool-call | `amctl a; amctl b; amctl c` en una llamada |
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
| Play Protect bloquea APK | "Entendido" y reintentar, o desactivar Play Protect temporalmente |
