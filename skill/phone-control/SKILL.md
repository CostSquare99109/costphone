---
name: phone-control
description: "Control del teléfono Android del usuario desde Termux con amctl (servicio de accesibilidad CostPhone): leer la pantalla como texto, snapshot con refs @pN, tap por referencia, click por texto/id, escribir, swipe, lanzar apps, capturas PNG. Úsala cuando el usuario pida hacer algo en su teléfono (WhatsApp, Instagram, Facebook/Meta Business, ajustes, cualquier app), ver qué hay en pantalla, o automatizar la UI de Android. English: control the user's Android phone."
---

# phone-control — el teléfono como un navegador

Equivalente móvil de agent-browser. El CLI es `amctl` (~/bin/amctl), que habla
con el servicio de accesibilidad CostPhone (com.accessdroid.termux) e incluye
el `secret` automáticamente.

## El bucle central (igual filosofía que agent-browser)

```
amctl launch <package.name>   # 1. abrir la app
amctl snap                    # 2. ver elementos interactivos con refs @p1, @p2...
amctl tapref @p3              # 3. actuar por referencia
amctl snap                    # 4. RE-SNAPSHOT tras cualquier cambio de pantalla
```

Las refs se asignan frescas en cada `snap` y **quedan obsoletas en cuanto la
pantalla cambia** (navegar, abrir un diálogo, teclado, etc.). Antes de cada
acción por ref: re-snapshot.

## Leer la pantalla

| Comando | Qué hace |
|---|---|
| `amctl snap` | Solo interactivos (clicables, editables, check) con refs @pN |
| `amctl snap -a` | Interactivos + todos los nodos con texto (lectura completa) |
| `amctl snap --filter "chat"` | Filtra por texto o id |
| `amctl tree` | Árbol crudo completo → /sdcard/Download/accessdroid-tree.txt (leerlo con la tool read) |
| `amctl see` | Captura PNG → /sdcard/Download/accessdroid-shot.png (leerla con read: es una imagen) |

El snapshot trae: ref, clase, texto, id corto y **coordenadas del centro**.
Si el teclado o una barra flotante (Seleccionar todo/Pegar) están abiertos,
sus elementos aparecen también como refs, marcados **[ime]** (teclado — Enter,
borrar ⌫, stickers...), **[ovl]** (toolbar flotante) o **[sys]** (overlays del
sistema). Ya no hay que adivinar coordenadas del teclado: Enter y ⌫ son refs.
Cuando falla `see`, el motivo exacto queda en
/sdcard/Download/accessdroid-shot-error.txt.

## Actuar

| Comando | Qué hace |
|---|---|
| `amctl tapref @p3` | Toca el centro del elemento (ref del último snap) |
| `amctl tap X Y` | Toque crudo en coordenadas (p.ej. de `snap -a`) |
| `amctl tapshot X Y [W H]` | Toca coords vistas en el PNG del último `see` (W H = tamaño con el que viste la imagen); escalea a coords reales |
| `amctl longpress X Y [ms]` | Pulsación larga |
| `amctl swipe X1 Y1 X2 Y2 [ms]` | Deslizar/arrastrar |
| `amctl scroll down\|up [x y]` | Scroll |
| `amctl click "texto"` | Click por texto visible (fallback sin snapshot) |
| `amctl clickid <id>` | Click por resource-id (p.ej. `com.whatsapp:id/send`) |
| `amctl type "texto"` | Escribe en el campo con foco ([edt][FOCUS] en snap). **REEMPLAZA todo el contenido** (setText), desconocido no appenda |
| `amctl cleartext` | Vacía el campo con foco |
| `amctl paste` | Pega el clipboard en el campo con foco |
| `amctl focus "texto"` | Da foco a un campo por texto (o resource-id) |
| `amctl openurl <url>` | Navega al URL en el Chrome default — SIN pelear con el omnibox (mejor que tap URL + type + Enter) |
| `amctl float <texto> [pkg]` | Pon la app en ventana flotante (recents → long-press en tarjeta → "ventanas flotantes" de MIUI). `float X pkg Y split` = pantalla dividida. Si no está en recents, lánzala con pkg |
| `amctl back` / `home` / `recents` | Botones del sistema |
| `amctl launch <pkg>` | Abre app (terceros: vía servicio CostPhone) |
| `amctl scrollto "texto"` | Busca y hace scroll a un texto |

Para escribir: `tapref` al campo `[edt]` (o `clickid ...entry`) → `amctl type` →
`tapref` al botón enviar (`'Enviar'` / `#send`) → `snap` para verificar.

Para navegar (Chrome/otro navegador): SIEMPRE primero
`amctl openurl "https://..."` — saltarse el omnibox evita autocompletados,
Enter fallido a ciegas, y páginas erróneas. Solo recurrir a snap URL+type si
openurl no existiera.

## Ejemplo real verificado (WhatsApp)

```bash
amctl launch com.whatsapp && sleep 6
amctl snap                       # @p1 [EditText] 'Preguntar a Meta AI...' ...
                                 # fila del primer chat = ViewGroup clicable con nombre concatenado
amctl tapref @p3                 # abre el primer chat
amctl snap                       # confirmar conversación abierta (conversation_contact_name)
amctl clickid com.whatsapp:id/entry
amctl type "No"
amctl snap                       # ver la ref del botón 'Enviar'
amctl tapref @pN                 # (o: amctl clickid com.whatsapp:id/send)
amctl snap                       # verificar que el mensaje salió
```

## Pantalla virtual — trabajar aparte sin molestar al usuario (app v1.2+)

Si el usuario dice "hazlo en la otra pantalla" o quiere seguir usando el
teléfono mientras el agente automatiza: se crea un display secundario y se
trabaja con `-d N`.

```bash
amctl display on 1080 1920 320   # crea la pantalla virtual
amctl displays                   # OBLIGATORIO: el id no se asume, se pregunta
amctl -d 2 launch com.android.chrome
amctl -d 2 snap
amctl -d 2 tapref @p3
amctl display off
```

Si se quiere que no estorbe: `amctl display on 1080 1920 320
gravity_bottom_right` (también `gravity_top_left|top_right|bottom_left`).

Nunca uses `mirror` para trabajar: quita `own_content_only`, el overlay enseña
la pantalla del usuario y lo que escriba el agente va a ella.

Reglas para no cagarla:

- **El id se pregunta, nunca se supone.** Puede ser 2, 3, 4… según qué haya
  ya en el dispositivo. `amctl displays` y se usa ese número.
- **`-d N` es un flag, no un subcomando:** `amctl -d 2 snap` y
  `amctl snap -d 2` son lo mismo.
- **Sin `-d`, todo va al display 0** — o sea, la pantalla del usuario. No lo
  hagas por inercia cuando la tarea es para la pantalla virtual.
- **`home`, `recents`, `power` y `notifications` son globales: pertenecen al
  display 0 aunque pongas `-d`.** No los uses como si fueran de la pantalla
  virtual; `back` sí tiene versión dirigida, y si la app no tiene botón de
  retroceso visible lo dice por pantalla (`back (display 6): la app no tiene
  botón de retroceso reconocible…`) — entonces usa `click "Cancelar"` o un
  `tapref`.
- **`-d N` solo ve esa pantalla.** El display virtual se crea con
  `own_content_only` —que CostPhone impone siempre— y si ese display no tiene
  ventanas propias la respuesta es **"vacío"**, nunca lo que hay en la pantalla
  del usuario. Si lees "vacío (sin ventanas propias)", el servicio va bien:
  lánzale algo con `amctl -d N launch <pkg>`.
- **`openurl -d N` sí abre en esa pantalla** (resuelve la URL a su app), pero
  si hay varias apps posibles el selector del sistema se abre en la 0 y avisa.
- **Las refs son por display:** un `@p3` de la pantalla 2 no es válido en la 0.
  Re-snapshot después de cada cambio, como siempre.
- **Requiere un `pm grant` previo** (hecho por el usuario, una vez):
  `WRITE_SECURE_SETTINGS`. Si `amctl display on` responde con `✗ ...`,
  es que falta: decírselo al usuario, no intentar eludirlo.

Detalle completo y límites: `docs/pantalla-virtual.md` en el repo de CostPhone.

## Transporte y limitaciones (Termux estable v0.118.3)

- Transporte `am` clásico (socket AM no existe en v0.118.x). No funcionan los
  comandos `list/find/stop/info/perms/grant/revoke/clear` (necesitan socket).
- `launch` de apps de terceros va vía el servicio (hay límite de visibilidad de
  paquetes del `am` clásico). Si falla: `am start -n <pkg>/<Activity>` explícito.
- `openurl`/`cleartext`/`paste`/`focus`/`tapshot` funcionan en ambos transportes.
- El árbol exige el servicio activado: Ajustes → Accesibilidad → CostPhone ON.
  Las refs [ime]/[ovl] requieren app >= v1.1 (dump multi-ventana). Con v1.0.x
  el snap sigue funcionando pero sin elementos del teclado.

## Recetas

- **Navegar a una página:** `amctl openurl "https://github.com/user/repo/settings/access"`.
  Nunca construir la navegación escribiendo en el omnibox (autocompletado + Enter ciego).
- **Código de verificación GitHub (sudo mode) en Chrome:** Settings pide código →
  `amctl launch com.google.android.gm` → `amctl snap` (el código suele venir
  en el texto de la fila del correo GitHub, sin abrirlo) → volver con
  `amctl launch com.android.chrome` → tap al campo → `amctl type "<código>"`.
- **Formulario web largo / muchos campos en diálogos:** `snap` al campo [edt] → tapref →
  si ya tiene texto: `amctl cleartext` (o directamente `type` que reemplaza).
  Seleccionar todo/Pegar están en toolbars [ovl] cuando están visibles.
- **Enter / borrar sin teclado físico:** con el teclado abierto, `amctl snap`
  y buscar las refs [ime] (fila final: Enter y ⌫). Tapref a esas refs.
- **Tocs desde un screenshot:** leer el PNG con read; si la imagen viene
  redimensionada, `amctl tapshot X Y W H` con el tamaño con el que la viste.

## Seguridad

- Confirmar con el usuario antes de acciones destructivas o sensibles: enviar
  dinero, borrar chats/archivos, publicar, comprar, cambiar ajustes críticos.
- Navegar/leer/responder mensajes pedidos: OK. Ante duda, preguntar primero.
- No cerrar sesiones de cuentas del usuario ni tocar credenciales.

## Troubleshooting

- ⚠ **IMPORTANTE — al instalar/actualizar la app, Android DESACTIVA el servicio
  de accesibilidad.** Tras cada update hay que reactivarlo: Ajustes →
  Accesibilidad → CostPhone → Activar. El panel CostPhone lo muestra en rojo.
- Desde 2026-10, `amctl tree/snap/see` **borran el archivo antes de pedirlo y
  verifican que el servicio lo regenere** — si el servicio está apagado ahora
  imprimen "(⚠ SERVICIO NO RESPONDE...)" en vez de devolver datos viejos.
  NUNCA confiar en un archivo leído sin la verificación de freshness.
- `snap` vacío o sin refs → servicio apagado, o app vieja (reinstalar APK actual
  de /storage/emulated/0/Download/CostPhone-v1.0.X.apk).
- "ref @pN no existe" → la pantalla cambió: `amctl snap` de nuevo.
- Elemento no visible → `amctl scroll down` / `swipe` y re-snap.
- Play Protect bloquea instalar APKs propios ("Aplicación bloqueada") → tocar
  Entendido y reintentar, o desactivar temporalmente el análisis en Play Store
  → Play Protect, instalar, y reactivar. NO es APK corrupto.
- El árbol no se actualiza → verificar servicio; los archivos se escriben en
  /sdcard/Download/accessdroid-*.txt|png.
