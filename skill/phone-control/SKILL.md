---
name: phone-control
description: "Control del teléfono Android del usuario desde Termux con amctl (servicio de accesibilidad CostPhone): leer la pantalla como texto, snapshot con refs @pN, tap por referencia, click por texto/id, escribir, swipe, lanzar apps, capturas. Úsala cuando el usuario pida hacer algo en su teléfono (WhatsApp, Instagram, Facebook/Meta Business, ajustes, cualquier app), ver qué hay en pantalla, o automatizar la UI de Android. English: control the user's Android phone."
---

> **⚡ CHEAT SHEET**: Ver [CHEATSHEET.md](CHEATSHEET.md) — comandos clave en 30 líneas

# phone-control — el teléfono como un navegador

Equivalente móvil de agent-browser. El CLI es `amctl` (~/bin/amctl), que habla
con el servicio de accesibilidad CostPhone (com.accessdroid.termux).

> ## ⚡ REGLA DE ORO
> Todo va por socket local: **cada orden cuesta 0.15-0.3s y te dice si
> funcionó**. No hay modo lento. Si una orden te está tardando 3-9s, el
> problema no es la orden: es que estás usando un patrón lento
> (`sleep`, `head -40`, `launch` + espera, un tool-call por acción).
> Mira los anti-patrones de abajo antes de seguir.

## 🏎️ EL CAMINO MÁS RÁPIDO: `cp.sh` (0 execs)

En un teléfono **cada `exec` de un binario cuesta ~150 ms** (el enlazador
dinámico de Android no es trivial). `amctl` es un script de bash, así que
cada invocación paga ese exec. La librería `cp.sh` habla el mismo protocolo
**sin lanzar ningún proceso** (solo builtins de bash contra `/dev/tcp`):

```bash
. ~/.config/opencode/skills/android/phone-control/cp.sh && cp_snap --filter chat
```

| | `amctl` | `cp.sh` |
|---|---|---|
| `status` | 254 ms | **14 ms** |
| `snap` | 224 ms | **89 ms** |
| varias órdenes | `amctl a; amctl b` | `cp_batch "a; b"` |

Ordenes disponibles: `cp_snap [--filter T] [-a]`, `cp_tap X Y`,
`cp_swipe X1 Y1 X2 Y2 [ms]`, `cp_scroll up|down`, `cp_back`, `cp_home`,
`cp_recents`, `cp_click "texto"`, `cp_clickid id`, `cp_tapref @pN`,
`cp_type "texto"`, `cp_cleartext`, `cp_paste`, `cp_waitfor "texto" [ms]`,
`cp_launch pkg`, `cp_openurl url [pkg]`, `cp_see [fichero]`,
`cp_batch "cmd1; cmd2"`, `cp_status`.

Salida **idéntica** a `amctl` (mismo formato `@pN [Clase] 'texto' #id (x,y)`).
`amctl` sigue siendo la interfaz completa (`help`, `ocr`, `display`, `float`,
`list`, `find`).

## ⚡ Velocidad: las tres reglas que más ahorran tiempo

1. **Una sola llamada de bash por paso del bucle.** Cada tool-call cuesta
   segundos de ida y vuelta. Encadena con `;` o `&&`:
   ```bash
   amctl launch com.whatsapp; amctl waitfor "Preguntar a Meta AI" 8000; amctl snap
   ```
   Tres comandos, una llamada, una respuesta al modelo.
   Y si usas `cp.sh`: `cp_batch "launch com.whatsapp; waitfor 'Preguntar a Meta AI' 8000; snap"`.
2. **Nada de `sleep` a ciegas.** `amctl waitfor "texto" [ms]` espera dentro del
   servicio y vuelve en cuanto aparece (o avisa al expirar). Es 5-10x más
   rápido que `sleep 6` + `snap` de prueba.
3. **Prefiere `click`/`clickid` a `snap`+`tapref` cuando tengas el id.** Un
   click por id es una orden (0.2-0.3s); el ciclo snapshot+tap son dos y además
   hay que re-snapshotar después.

Tiempos **medidos** en el dispositivo de referencia (Android 16, 10
iteraciones, p50).

| Orden | Coste real | Notas |
|---|---|---|
| `tap`, `longpress`, `swipe`, `scroll`, `back`, `home`, `recents` | 0.15-0.3s | vuelan y no devuelven nada que leer |
| `click "texto"`, `clickid <id>`, `type`, `focus`, `paste` | 0.2-0.3s | el servicio confirma si funcionó |
| `snap`, `tree` | 0.22-0.27s | vuelca la pantalla; usa `--filter` si hay mucho |
| `tapref` | 0.2-0.3s | re-resuelve la ref en el servidor (evita el toque en sitio equivocado) |
| `see` (captura) | 0.7s | solo si de verdad necesitas la imagen |
| `launch` | 0.29s | por nombre: `launch whatsapp` (lo resuelve la app) |
| `waitfor` | lo que tarde el elemento | mejor que cualquier `sleep` |
| `batch "a; b; c"` | ~0.15s **por orden** | un solo proceso para todas |

Regla práctica: **el coste real de una orden es ~0.2-0.3s**, así que lo que
manda es el número de tool-calls, no el tiempo dentro de cada orden.

### ❌ Anti-patrones (los que se comen el tiempo)

| ❌ Lento (y frágil) | ✅ Rápido | Por qué |
|---|---|---|
| `amctl launch X && sleep 8 && amctl snap` | `amctl launch X; amctl waitfor "texto" 10000` | el sleep es a ciegas: 8s siempre, o 0.5s si `waitfor` |
| `amctl snap \| head -40` | `amctl snap --filter "lo que buscas"` + `head -8` | 40 líneas de ruido = 40 líneas de contexto para nada |
| `amctl launch com.android.chrome` y luego buscar la URL | `amctl openurl "https://…"` | Chrome **restaura la sesión anterior** (te deja en otra página) y además hay que pelear con el omnibox |
| `snap` → `tapref` → `snap` → `tapref` | `clickid <id>` o `click "texto"` cuando el id se sepa | dos órdenes y dos tool-calls por acción |
| un `amctl` por tool-call | `amctl batch "a; b; c"` en una sola llamada | un solo proceso en vez de N |
| `amctl` en un bucle | `. cp.sh && cp_*` | 0 execs: 254 ms → 14 ms |
| `sleep 3` "por si acaso" entre dos acciones | `amctl tapref @pN --settle 500` | espera real, no adivinada (y la duerme el servidor) |

**Antes de la primera orden de cada tarea, comprueba el canal (0.5s):**

```bash
amctl status | head -1     # "canal rápido: OK (127.0.0.1, respuesta: pong)" = todo lo de arriba
```

Si dice que no está disponible: `amctl enable` (re-activa el servicio tras una
actualización de la app) y reintenta.

## Arranque de tarea (los primeros 30 segundos)

Cada tarea empieza igual — no te saltes el paso 1, decide cómo de rápido vas:

```bash
amctl status | head -1                    # 1. ¿canal rápido? (0.5s). Si no: amctl enable
amctl launch com.<app>                    # 2. abrir la app   (0.6-0.9s)
amctl waitfor "<texto que esperas>" 10000 # 3. esperar de verdad (0.6-2.4s) — NUNCA sleep
amctl snap --filter "<lo que buscas>"     # 4. leer solo lo que importa (0.5s)
amctl clickid <id> | amctl tapref @pN     # 5. actuar (0.5-1.4s)
```

Para una web, el paso 2 es `amctl openurl "<url>"` en vez de `launch` (Chrome
restaura la sesión anterior si lo lanzas a pelo).

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

`tapref` es inteligente: si la pantalla no ha cambiado desde el snapshot, toca
directamente; si ha cambiado, busca el mismo elemento y, si ya no existe,
contesta **"obsoleta — re-snapshot"** en vez de tocar una posición equivocada.
Si te dice eso, `amctl snap` y sigue.

## Leer la pantalla

| Comando | Qué hace |
|---|---|
| `amctl snap` | Solo interactivos (clicables, editables, check) con refs @pN |
| `amctl snap -a` | Interactivos + todos los nodos con texto (lectura completa) |
| `amctl snap --filter "chat"` | Filtra por texto o id (úsalo en pantallas densas) |
| `amctl waitfor "texto" [ms]` | Espera a que aparezca un texto y devuelve el snapshot de ese instante (con refs) |
| `amctl tree` | Árbol crudo completo (formato largo: úsalo solo para depurar) |
| `amctl see` | Captura JPEG → /sdcard/Download/accessdroid-shot.jpg (léela con read: es una imagen) |

El snapshot trae: ref, clase, texto, id corto y **coordenadas del centro**.
Si el teclado o una barra flotante (Seleccionar todo/Pegar) están abiertos,
sus elementos aparecen también como refs, marcados **[ime]** (teclado — Enter,
borrar ⌫, stickers...), **[ovl]** (toolbar flotante) o **[sys]** (overlays del
sistema). Ya no hay que adivinar coordenadas del teclado: Enter y ⌫ son refs.

## Actuar

| Comando | Qué hace |
|---|---|
| `amctl tapref @p3` | Toca el centro del elemento (ref del último snap) |
| `amctl tapref @p3 --settle 500` | Idem + espera a que la pantalla se asiente |
| `amctl tap X Y` | Toque crudo en coordenadas (p.ej. de `snap -a`) |
| `amctl tapshot X Y [W H]` | Toca coords vistas en la captura (W H = tamaño con el que viste la imagen); escalea a coords reales |
| `amctl longpress X Y [ms]` | Pulsación larga |
| `amctl swipe X1 Y1 X2 Y2 [ms]` | Deslizar/arrastrar |
| `amctl scroll down\|up [x y]` | Scroll **semántico** (la app desplaza su propia lista; si no hay nada scrolleable cae al gesto) |
| `amctl click "texto"` | Click por texto visible — **más rápido que snapshot+tapref**. Elige el nodo más específico (el botón, no el contenedor que concatena el texto de sus hijos) y si el nodo no acepta el click hace un toque real en su centro |
| `amctl clickid <id>` | Click por resource-id — idem, la vía más rápida. Acepta el **id corto** tal como lo muestra el snapshot (`#send` → `clickid send`) o el completo (`com.whatsapp:id/send`) |
| `amctl type "texto"` | Escribe en el campo con foco ([edt][FOCUS] en snap). **REEMPLAZA todo el contenido** (setText), desconocido no appenda. Si el campo lo rechaza (input web/React), cae solo al **teclado en pantalla** |
| `amctl cleartext` | Vacía el campo con foco |
| `amctl paste` | Pega el clipboard en el campo con foco |
| `amctl focus "texto"` | Da foco a un campo por texto (o resource-id) |
| `amctl openurl <url>` | Navega al URL en el Chrome default — SIN pelear con el omnibox (mejor que tap URL + type + Enter) |
| `amctl float <texto> [pkg]` | Pon la app en ventana flotante (recents → long-press → "ventanas flotantes" de MIUI). `float X pkg Y split` = pantalla dividida |
| `amctl back` / `home` / `recents` | Botones del sistema |
| `amctl launch <pkg>` | Abre app |
| `amctl scrollto "texto"` | Busca y hace scroll a un texto |

**Cuidado con el orden tap → snap:** el gesto tarda ~80ms y la app tarda en
reaccionar. Si encadenas `tapref && snap` en la misma llamada, el snapshot
puede salir **antes** del cambio. Usa `amctl tapref @p3 --settle 500` o mete un
`; sleep 0.5` entre el tap y el snap.

Para escribir: `tapref` al campo `[edt]` (o `clickid ...entry`) → `amctl type`
→ `tapref` al botón enviar (`'Enviar'` / `#send`) → `snap` para verificar.

**Si `type` dice "el campo aceptó el texto pero no lo muestra":** ese campo no
es un campo de escritura, es un **desplegable/combobox** (por ejemplo el
selector de categoría de la Biblioteca de Anuncios de Meta). Elige la opción
con `click "texto"` en vez de escribir. Si dice "el campo rechazó el texto
(ni setText ni teclado)", es un input web/React: el servicio cae entonces al
**teclado en pantalla** y escribe pulsando las teclas (`escrito (teclado): …`);
si el teclado no está visible, ábrelo antes (toca el campo y espera a que
salgan las teclas `[ime]`) y reintenta. Con `type --settle` o un `tapref`
previo con más margen, el campo suele aceptar el texto.

**Regla:** desde v1.3 `type` **verifica** que el texto aparezca en pantalla
antes de decir "escrito". Si te dice que escribió, está escrito.

Para navegar (Chrome/otro navegador): SIEMPRE primero
`amctl openurl "https://..."` — saltarse el omnibox evita autocompletados,
Enter fallido a ciegas, y páginas erróneas.

## YouTube: buscar y reproducir (receta verificada)

Los atajos que fallan en YouTube, y lo que funciona:

| Atajo | Qué pasa | ✅ Lo que funciona |
|---|---|---|
| `scroll down` para bajar en los resultados | el gesto aterriza en System UI (panel de notificaciones) | `amctl swipe 540 1700 540 700 700` — un arrastre, como un dedo |
| `waitfor "Buscar"` tras abrir la app | si la app restauró la página anterior, no existe ese botón | `waitfor` de lo que de verdad quieres ver, o `snap` y decide |
| `click "Saltar anuncio"` | el texto vive en el overlay **padre** y en el botón: el clic cae en el padre y no salta nada | `amctl clickid skip_ad_button` (id corto, tal cual lo muestra el snapshot) |
| `waitfor "<título del video>"` | el overlay del reproductor **se oculta a los ~3 s**: si el waitfor empieza después, expira aunque el video esté cargado | verifica con la **barra de progreso**: `snap --filter "minutos"` → `"0 minutos 14 segundos de 3 minutos y 33 segundos"` (duración real) |
| tocar el reproductor para "despertar" los controles | en YouTube un toque en la superficie **pausa** el video | usa `back` o `clickid` sobre el botón de play real |

Pista de oro: **una duración de ≤60 s = es el anuncio**; la duración del video real
(3 min 33 s en Poker Face) es la prueba de que la reproducción es la buena.

Flujo natural completo (sin deep-links):

```bash
amctl launch com.google.android.youtube
amctl click "Buscar"; amctl waitfor "Buscar en YouTube" 6000
amctl type "poker face lady gaga"                     # verificado: "escrito: …"
amctl tapref @p4 --settle 1500                       # @p4 = fila de sugerencia (autocompletado)
amctl swipe 540 1700 540 700 700                     # baja a los vídeos (2 swipes)
amctl snap --filter "Official"                       # @p1 = "Lady Gaga - Poker Face (Official Music Video)"
amctl tapref @p1 --settle 1200                       # abre el video
amctl clickid skip_ad_button                         # salta el anuncio (si lo hay)
amctl snap --filter "minutos"                        # verifica: "... de 3 minutos y 33 segundos"
```

El autocompletado de YouTube **tapa los resultados**: el `waitfor "<consulta>"`
da falso positivo porque la sugerencia ya contiene ese texto. Toca la fila de
sugerencia para de verdad buscar, y verifica con el `snap` de resultados.



```bash
amctl launch com.whatsapp
amctl waitfor "buscar" 10000              # barra "Preguntar a Meta AI o buscar"
amctl snap                                  # @p1 = barra de búsqueda
amctl tapref @p1
amctl type "+57 310 4618120"
amctl waitfor "310 4618120" 10000           # el campo ya tiene el número
amctl snap --filter "4618120"              # @pN = fila del contacto (row_container)
amctl tapref @pN                            # abre el chat
amctl waitfor "Mensaje" 8000
amctl type "Hola"
amctl snap --filter "Enviar"                # @pN = botón #send
amctl tapref @pN
amctl snap --filter "Hola" -a               # verificar la burbuja enviada
```

Notas del caso real: el `type` **reemplaza** el contenido del campo, así que
para escribir un número en un buscador que ya tenía texto, `type` vale.
El contacto del ejemplo era el propio número ("+57 310 4618120 (Tú)").

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

## Transporte y estado

- **Todo va por un socket TCP local (127.0.0.1).** La orden y su respuesta
  viajan en el mismo viaje: sin ficheros de por medio, sin esperas, y **el
  servicio te dice si la orden funcionó** ("escrito: hola", "no se encontró el
  texto 'X'", "ref obsoleta"). Si una orden no está disponible o falla, la
  respuesta te lo dice: no hay un canal "alternativo" más lento al que
  recurrir.
- Si `amctl` avisa de que el canal no está disponible: `amctl enable`
  (re-activa el servicio tras una actualización de la app) y reintenta.
- `launch` de apps de terceros: la app lo resuelve; si hace falta, pasa el
  paquete completo (ej: `com.android.chrome`).
- El árbol y el snapshot exigen el servicio activado: Ajustes → Accesibilidad
  → CostPhone ON.
- Las refs [ime]/[ovl] requieren app >= v1.1 (dump multi-ventana).
- `launch <nombre>` (resolver "whatsapp" → "com.whatsapp") y el `settle` en el
  servidor requieren app >= v1.4; con una app anterior el CLI cae al camino
  lento (nunca se rompe, solo va más despacio).

## Recetas

- **Navegar a una página:** `amctl openurl "https://github.com/user/repo/settings/access"`.
  Nunca construir la navegación escribiendo en el omnibox (autocompletado + Enter ciego).
  **Y nunca `amctl launch com.android.chrome` antes:** Chrome restaura la pestaña
  o sesión anterior y te deja en una página que no te sirve (te toca perder un
  `back` extra). `openurl` va directo a la URL, en el navegador por defecto.
  Si necesitas el navegador de verdad (menú, pestañas), entonces sí `launch`,
  y entonces `waitfor` a lo que buscas.
- **Forzar el navegador:** `amctl openurl "<url>" com.android.chrome` abre la URL
  en Chrome aunque otra app sea la predeterminada. Útil cuando la app nativa
  (Facebook para la Biblioteca de Anuncios) usa un WebView que rechaza
  `setText`: en Chrome el mismo campo sí acepta el texto.
- **Esperar a que una app cargue:** `amctl waitfor "texto que aparece" 15000`
  en vez de `sleep 6` + snap.
- **Formulario web largo / muchos campos en diálogos:** `snap` al campo [edt] →
  tapref → si ya tiene texto: `amctl cleartext` (o directamente `type` que
  reemplaza). Seleccionar todo/Pegar están en toolbars [ovl] cuando están
  visibles.
- **Enter / borrar sin teclado físico:** con el teclado abierto, `amctl snap`
  y buscar las refs [ime] (fila final: Enter y ⌫). Tapref a esas refs.
- **Tocs desde una captura:** leer la imagen con read; si viene redimensionada,
  `amctl tapshot X Y W H` con el tamaño con el que la viste.
- **Elemento que NO sale en el árbol** (canvas, WebView, botón custom de MIUI):
  `amctl ocr "<texto>"` lo localiza en la captura por OCR y devuelve sus
  píxeles. Añade `--region x,y,w,h` con la zona aproximada: baja de 19s a 2,6s.
  Ejemplo real: el botón "Actualizar" del instalador de MIUI no existe en el
  árbol de accesibilidad, y `amctl ocr "Actualizar" --region 700,1300,300,120`
  lo encontró en (850,1380) en 2,6s.
  ⚠ Ojo: en MIUI ese botón además **ignora los toques inyectados** (sí acepta
  "Cancelar"), así que hay que tocarlo a mano. La visión no arregla eso.
- **Pantalla densa (chat largo):** `amctl snap --filter "lo que buscas"` en vez
  de `snap -a` completo: menos ruido, menos tokens, más rápido.

## Seguridad

- Confirmar con el usuario antes de acciones destructivas o sensibles: enviar
  dinero, borrar chats/archivos, publicar, comprar, cambiar ajustes críticos.
- Navegar/leer/responder mensajes pedidos: OK. Ante duda, preguntar primero.
- No cerrar sesiones de cuentas del usuario ni tocar credenciales.

## Troubleshooting

- ⚠ **IMPORTANTE — al instalar/actualizar la app, Android DESACTIVA el servicio
  de accesibilidad.** Tras cada update hay que reactivarlo: `amctl enable`
  (la app tiene `WRITE_SECURE_SETTINGS` concedido y se auto-activa; si algún
  día no lo tuviera, el comando **abre directamente la pantalla de
  Accesibilidad** y avisa de que hay que activar el interruptor).
- "canal no disponible" → el servicio está apagado: `amctl enable` y reintenta.
  (`amctl status` lo dice en una línea.)
- `snap` vacío o sin refs → servicio apagado: `amctl enable`, luego `amctl snap`.
- "ref @pN no existe" o "obsoleta" → la pantalla cambió: `amctl snap` de nuevo.
- Elemento no visible → `amctl scroll down` / `swipe` y re-snap.
- Play Protect bloquea instalar APKs propios ("Aplicación bloqueada") → tocar
  Entendido y reintentar, o desactivar temporalmente el análisis en Play Store
  → Play Protect, instalar, y reactivar. NO es APK corrupto.
