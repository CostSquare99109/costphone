# Pantalla virtual — un display secundario solo para el agente

CostPhone puede crear una **pantalla virtual** (un segundo monitor) dentro del
teléfono y controlarla con los mismos comandos de siempre. El usuario sigue
usando la pantalla principal (display 0) mientras el agente trabaja en la
virtual, sin pisarse.

```
┌──────────────────────────────┐         ┌────────────────────┐
│  display 0 — la del usuario  │         │  display 2 — agente │
│  WhatsApp, llamadas, MIUI... │         │  1080x1920 @320dpi │
│                              │  ┌────┐ │  la app que el agente│
│                              │  │vd  │ │  lanzó con -d 2     │
└──────────────────────────────┘  └────┘ └────────────────────┘
```

## Bitácora original (con ADB, antes de CostPhone v1.2)

Así se llegó a la idea, paso a paso, y qué falla fuera de ADB:

**Objetivo:** crear una pantalla virtual (display secundario) en tu Android
desde Termux, y que no dependa de ADB cada vez.

**Lo que hicimos:**

1. **Explicación de los comandos:** `overlay_display_devices` crea una ventana
   flotante que actúa como segundo monitor (1080x1920 a 320 dpi), y
   `dumpsys display` verifica que exista.
2. **Problemas iniciales:** `pm grant` y `settings` fallaban en Termux por falta
   de permisos, y `rish` no estaba instalado (viene de Shizuku, que decidiste no
   usar).
3. **ADB inalámbrico desde Termux:** instalaste `android-tools`, emparejaste con el
   código (el primer intento falló por el puerto, el segundo funcionó) y
   conectaste.
4. **Permisos concedidos:** `WRITE_SECURE_SETTINGS` y `DUMP` a Termux, ambos sin
   error.
5. **Prueba exitosa con ADB:** la pantalla virtual se creó (display ID 3,
   1080x1920) y abriste Ajustes en ella.
6. **Apagado:** pusiste el ajuste en `none`, desconectaste ADB.

**Resultado de la prueba sin ADB:**

- `dumpsys display` **sí funciona** directo en Termux (gracias al permiso `DUMP`).
- `settings put` **sigue fallando** (`Failed transaction`), porque el comando
  `settings` solo acepta llamadas del usuario shell o root, aunque Termux tenga
  `WRITE_SECURE_SETTINGS`.

**Siguiente paso pendiente:** darle `WRITE_SECURE_SETTINGS` a una app que escriba
el ajuste desde su propio código (SetEdit, MacroDroid o Tasker) con un solo
`pm grant` por ADB. Después ya no necesitarías ADB para activar o quitar la
pantalla virtual.

> Ese "siguiente paso" es exactamente lo que hace CostPhone v1.2: el propio
> AccessibilityService escribe el ajuste. `settings` (el comando de shell)
> rechaza a Termux, pero `Settings.Global.putString()` desde una app con
> `WRITE_SECURE_SETTINGS` no pasa por esa comprobación.

## Cómo funciona

Android permite crear displays virtuales de "overlay" escribiendo el ajuste
global `overlay_display_devices`.

**No es JSON** (esa fue la primera sorpresa): es un texto con este formato:

```
[display1];[display2];...
```

y cada display es `[modo1]|[modo2]|...[,flag1][,flag2]...`, donde un modo es
`[ancho]x[alto]/[dpi]`.

| Ejemplo | Qué hace |
|---|---|
| `1080x1920/320` | un overlay de 1080x1920 a 320 dpi |
| `1920x1080/320,secure` | ese overlay, además marcado como seguro |
| `1920x1080/320\|3840x2160/640` | un overlay que el sistema puede escalar a otro modo |

Flags válidos: `secure`, `own_content_only`, `should_show_system_decorations`,
`gravity_top_left`, `gravity_top_right`, `gravity_bottom_left`,
`gravity_bottom_right`. Máximo 4 displays, 100–4096 px, 120–640 dpi. Con el
valor vacío no se crea ninguno. Un flag mal escrito hace que Android **rechace
el ajuste entero** sin crear display y sin avisar, así que `amctl` valida antes
de escribir y dice cuál es el malo.

> **La trampa que costó una hora:** el formato JSON
> `{"Nombre":"{\"uniqueId\":...}"}` es de la API de displays virtuales
> "presentation" y **no** es lo que lee `OverlayDisplayAdapter`. Con el JSON,
> Android no creaba nada y solo escribía en logcat:
> `Malformed overlay display devices setting: ...`
> (tag `OverlayDisplayAdapter`, no `DisplayManagerService` — por eso no aparecía
> en los filtros habituales). Si alguna vez esto no crea el display, mira
> `adb logcat -s OverlayDisplayAdapter:*`.

### `own_content_only` es un suelo, no un valor por defecto

- **Sin** `own_content_only`, la ventana overlay **espeja** la pantalla
  principal cuando el display virtual está vacío. Es bonito, pero rompe el
  aislamiento: el agente acaba leyendo y **escribiendo en lo que el usuario
  está usando** (nos pasó: un `type` se fue al campo de texto de Termux).
- **Con** `own_content_only`, el display virtual solo muestra lo que hay en él.

Por eso CostPhone lo **impone siempre**, venga como venga la orden: es un
suelo de seguridad, no una opción. La primera versión solo lo ponía si no le
pasabas otros flags, y `display on … gravity_bottom_right` lo borraba — el
espejo volvía sin avisar. Si de verdad quieres el modo espejo (para ver en tu
pantalla lo que hace el agente), se pide explícitamente y avisa:

```sh
amctl display on 1080 1920 320 mirror
# ✗ aviso: modo espejo (sin own_content_only). El overlay muestra d0 y el
#   agente puede escribir en la pantalla del usuario: úsalo solo para mirar.
```

### El candado de aislamiento

Un display virtual recién encendido **no tiene ventanas**, y ahí
`getRootInActiveWindow(displayId)` devuelve la ventana activa **global**: la de
la pantalla 0. Es decir, el atajo devolvía justo lo que no debe. Por eso, si
un display no tiene ventanas propias, la respuesta es "vacío" y nunca lo que
el usuario tiene delante:

```
$ amctl -d 5 snap          # display recién encendido, sin nada dentro
Pantalla — display 5: vacío (sin ventanas propias). El servicio va bien: no hay
nada en esa pantalla todavía. Lánzale algo con: amctl -d 5 launch <pkg>
```

Antes de arreglar esto, ese mismo caso devolvía el árbol entero de la pantalla
del usuario, y si el display estaba vacío el servicio no escribía nada y el CLI
decía "el servicio no responde" — un fallo que manda a debuggear algo que no
está roto.

Quien escribe el ajuste necesita `WRITE_SECURE_SETTINGS`. Ese permiso no se
puede conceder desde el propio teléfono (ni desde la app), así que **hay que
concederlo una única vez por ADB** — a partir de ahí, nunca más:

```sh
adb shell pm grant com.accessdroid.termux android.permission.WRITE_SECURE_SETTINGS
```

Es el mismo trick que se usa con Shizuku: un permiso de por vida, y después
todo el trabajo lo hace la app.

## Uso

```sh
amctl display                          # qué displays hay ahora mismo
amctl display on 1080 1920 320         # crea la pantalla virtual (arriba izquierda)
amctl display on 1080 1920 320 gravity_bottom_right   # ...en otra esquina
amctl display on 1080 1920 320 gravity_bottom_right secure  # varios flags
amctl display off                      # la apaga
```

`amctl display on` **reemplaza** el ajuste completo: siempre deja un solo
display. Al encenderlo ya imprime la lista, con el id que le tocó. Los flags
aceptan coma o argumentos sueltos, y `own_content_only` se añade siempre por
detrás salvo que pidas `mirror`.

```sh
amctl displays                         # el id NO es fijo: 2, 3, 5, 6...
amctl -d 2 launch com.android.chrome   # abre Chrome EN la pantalla 2
amctl -d 2 snap                        # snapshot solo de la pantalla 2
amctl -d 2 tapref @p3                  # toca ese elemento en la pantalla 2
```

El flag `-d N` (o `--display N`) se puede poner delante o detrás del subcomando
y vale para todo: `tap`, `swipe`, `scroll`, `type`, `click`, `clickid`,
`focus`, `snap`, `tree`, `see`, `launch`, `back`, `openurl`… Sin `-d`, todo va
al display 0 como siempre.

Ejemplo real (verificado en un Redmi Note 14, Android 16):

```
$ amctl display on 1080 1920 320
  d0   Pantalla       1080x2400  454   on   ventanas:4
  d6   Superposición 1080x1920  320   on   ventanas:0
  ajuste overlay_display_devices: 1080x1920/320,own_content_only

$ amctl -d 6 launch com.android.settings
$ amctl -d 6 snap
Pantalla — display 6: com.android.settings — 2 elementos (interactivos) de 100 nodos
@p1 [FrameLayout] 'Buscar en Ajustes' #header_view (540,282)

$ amctl -d 6 tapref @p1 && amctl -d 6 type "depuracion"
$ amctl -d 6 snap
@p3 [EditText] 'depuracion' #input (485,52) [edt][FOCUS]

$ amctl snap            # la pantalla del usuario, intacta, con Termux delante
Pantalla: com.termux — 51 elementos (interactivos) de 144 nodos
```

### Lo que hace distinto a `-d N`

| Cosa | Cómo se resuelve en `-d N` |
|---|---|
| Lectura del árbol | ventanas de ese display, nunca las de la pantalla 0 (display vacío → "vacío") |
| Toques / gestos / scroll | `GestureDescription.setDisplayId(N)` — el display 0 no se toca |
| Escribir texto | busca el campo con foco **entre las ventanas de ese display** |
| Lanzar apps | `ActivityOptions.setLaunchDisplayId(N)` |
| Abrir URLs | se resuelve la URL a su app y se lanza con display; si hay varias, avisa (ver abajo) |
| Captura | `accessdroid-shot-d<N>.png` (la del 0 sigue siendo `accessdroid-shot.png`) |
| `back` | busca el botón de retroceso de la app en ese pantalla |

Los refs `@pN` son **por display**: `snap -d 2` guarda su mapa aparte, así que
un `@p3` de la pantalla 2 nunca se confunde con uno de la 0.

## Limitaciones (leídas, no supuestas)

- **`openurl -d N` y el selector de apps.** El "Abrir con" es una activity del
  **sistema**, y esa se abre siempre en el display 0: ignora
  `setLaunchDisplayId`. Por eso CostPhone **resuelve la URL a una sola app**
  (`PackageManager.resolveActivity`) y lanza esa directamente con su display;
  así `-d N openurl https://…` sí abre en la pantalla virtual. Si la URL de
  verdad tiene varias apps y no se puede elegir, el selector se abrirá en la
  pantalla 0 y `amctl` lo avisa en vez de fingir que ha ido bien.
- **`back` no es un keyevent.** `GLOBAL_ACTION_BACK` siempre es del display 0, así
  que `-d N back` busca y toca el botón de retroceso de la app en esa pantalla
  (busca texto/descripción con *back*, *atrás*, *regresar*, *navigate up*,
  *volver*). Si la pantalla actual no tiene uno —un buscador con "Cancelar", por
  ejemplo— no lo hace y lo dice: `amctl` imprime
  `back (display 6): la app no tiene botón de retroceso reconocible en ese
  display`. En ese caso, `amctl -d N click "Cancelar"` o un `tapref`.
- **La pantalla virtual es una ventana flotante encima de la real.** El usuario
  la ve y también podría tocarla sin querer. Por eso el flag de gravedad al
  crearla.
- **Requiere Android 11+ (API 30)** para el control multi-display del
  AccessibilityService. En Android 10 se puede crear el display, pero no
  controlarlo con gestos dirigidos.
- **`amctl display on/off` necesita el permiso concedido.** Sin él, `amctl
  display` responde con el `adb shell pm grant` exacto que falta. Todo lo demás
  de CostPhone sigue funcionando sin ese permiso.
- **El id del display no es fijo.** Puede ser 2, 3, 5, 6… según lo que ya exista
  en el dispositivo y cuántas veces se haya reencendido. Por eso existe
  `amctl display` en vez de asumir un id: siempre se pregunta primero.
- **Una app que ya tiene su tarea en la pantalla virtual** seguirá yendo allí
  cuando se lance sin `-d` (el sistema reutiliza la tarea). Para mandarla de
  vuelta a la pantalla 0 hay que pararla antes.

## Alternativa sin ningún permiso: Shizuku

Si prefieres no conceder nada por ADB, la otra vía es `rish`/Shizuku, que
delega en el sistema. No se implementó aquí porque Shizuku pide una app extra y
su propio ciclo de vida, y `pm grant` una sola vez ya resuelve el caso de uso.