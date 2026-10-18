# 📱 CostPhone

**Control remoto de tu teléfono Android desde Termux — pensado para que los
agentes de IA (OpenCode, Claude, etc.) puedan usarlo como si tuvieran manos.**

Sin root, sin cable USB, sin ADB inalámbrico.

![logo](app/assets/costphone-logo.png)

---

## ¿Qué incluye?

| Pieza | Qué es |
|---|---|
| **CostPhone (APK)** | App Android minimalista con un `AccessibilityService`. Recibe comandos y los convierte en toques, gestos, escritura, lectura de pantalla y capturas. (2 permisos + `WRITE_SECURE_SETTINGS` opcional, solo para la pantalla virtual.) |
| **amctl** | CLI de Termux que controla todo: lanzar apps, snapshot con referencias `@pN`, taps, escribir, capturas PNG. |
| **Skill `phone-control`** | Guía para OpenCode: enseña a cualquier agente a manejar el teléfono sin conocer los detalles internos. |

## Cómo funciona

```
┌──────────────┐    am broadcast (termux-am)    ┌───────────────────────┐
│    Termux    │ ─────────────────────────────► │  CostPhone APK        │
│              │      + secreto compartido      │  AccessibilityService │
│    amctl     │                                │                       │
│              │                                │  tap / swipe / type   │
│              │                                │  árbol UI / screenshot│
│  lee resultados ◄──────────────────────────── │                       │
│  (MediaStore) │   /sdcard/Download/*.txt|png   └───────────────────────┘
└──────────────┘
```

1. `amctl` envía un broadcast explícito al paquete `com.accessdroid.termux`
   (vía `termux-am`), con un secreto compartido.
2. El `AccessibilityService` de CostPhone ejecuta la acción pedida.
3. Los resultados (árbol de UI, PNG) se escriben en
   `/sdcard/Download/accessdroid-*` y Termux los lee.

## Requisitos

- **Termux** estable v0.118+ (también funciona en 0.119+ con socket AM).
- `pkg install termux-am python`
- **Android 7+** (capturas de pantalla requieren Android 13+).
- Opcional: OpenCode (instalado por ti) para aprovechar la skill.

## Instalación

```sh
git clone https://github.com/CostSquare99109/costphone
cd costphone
bash install.sh
```

El instalador hace **todo**:

1. Instala `termux-am` y `python`.
2. Modifica el **`am` de Termux**: activa `run-termux-am-socket-server` en
   `~/.termux/termux.properties` y agrega `~/bin` al `PATH`.
3. Copia `amctl` y `amctl-snap.py` a `~/bin`.
4. Instala la skill `phone-control` en `~/.config/opencode/skills/android/`.
5. Registra la skill en `~/.config/opencode/AGENTS.md`.
6. Abre el instalador del **APK**. En el teléfono:
   - Toca **Instalar** (si Play Protect avisa → *Entendido*).
   - **Ajustes → Accesibilidad → CostPhone → Activar** ✅

Prueba final:

```sh
amctl tree     # debería imprimir la pantalla actual como texto
amctl see      # captura PNG -> /sdcard/Download/accessdroid-shot.png
```

## Uso

| Quiero… | Comando |
|---|---|
| Ver la pantalla como texto + refs | `amctl snap` |
| Tocar una referencia | `amctl tapref @p3` |
| Árbol de UI completo | `amctl tree` |
| Snapshot incluyendo no-interactivos | `amctl snap -a` |
| Filtrar por texto | `amctl snap --filter "Enviar"` |
| Navegar a una URL (sin omnibox) | `amctl openurl https://…` |
| Captura PNG | `amctl see` |
| Lanzar app | `amctl launch com.whatsapp` |
| Escribir en el campo enfocado (reemplaza) | `amctl type "hola mundo"` |
| Vaciar el campo enfocado | `amctl cleartext` |
| Pegar clipboard en el campo | `amctl paste` |
| Enfocar un campo por texto | `amctl focus "Buscar…"` |
| Tocar desde coords del screenshot | `amctl tapshot X Y [W H]` |
| Tocar por coordenadas | `amctl tap 540 960` |
| Long-press | `amctl longpress 540 960 700` |
| Swipe | `amctl swipe 540 1800 540 600` |
| Scroll | `amctl scroll down` |
| Atrás / Inicio / Recientes | `amctl back` / `home` / `recents` |
| Clic por texto | `amctl click "Enviar"` |
| Clic por resource-id | `amctl clickid com.whatsapp:id/send` |
| Ver los displays | `amctl displays` |
| Crear/apagar la pantalla virtual del agente | `amctl display on 1080 1920 320` / `display off` |
| Todo lo anterior **en la pantalla virtual** | `amctl -d <id> <cualquier comando>` |

Extra v1.1: cuando el teclado o una toolbar flotante están abiertos, sus
botones entran al snapshot marcados `[ime]` / `[ovl]` / `[sys]` — Enter,
borrar y "Seleccionar todo" se tocan con `tapref` como cualquier elemento.

### El loop (estilo agent-browser)

```
amctl snap           →  lista interactiva con refs @pN
amctl tapref @pN     →  toca ese elemento
amctl snap           →  RE-SNAPSHOT (las refs se invalidan tras cada cambio)
```

## Pantalla virtual (v1.2) — el agente en su propio monitor

CostPhone puede crear un **display secundario** dentro del teléfono y dirigir
allí los comandos, mientras el usuario sigue con normalidad en la pantalla 0:

```sh
amctl display on 1080 1920 320   # crea la pantalla virtual
amctl displays                   # → d6 Superposición 1080x1920 320 … (el id no es fijo)
amctl -d 6 launch com.android.chrome
amctl -d 6 snap
amctl -d 6 tapref @p3            # los gestos van dirigidos, la 0 no se toca
amctl display off
```

El flag `-d N` vale para todos los comandos (`tap`, `type`, `click`, `see`,
`launch`, `back`…), los refs `@pN` son por display y la captura se guarda como
`accessdroid-shot-d<N>.png`.

Dos detalles que no son evidentes:

- El ajuste `overlay_display_devices` **no es JSON**: es `WxH/dpi[,flags]`.
  CostPhone **impone** `own_content_only` siempre, porque sin ese flag la
  pantalla virtual **espeja** la principal y el agente acabaría escribiendo en
  lo que el usuario está usando. Para verla en espejo a propósito:
  `amctl display on … mirror` (avisa de que no aisla).
- Un display recién encendido no tiene ventanas, y ahí Android devuelve la
  ventana activa *global*: por eso CostPhone devuelve "vacío" en vez de lo que
  tienes delante en la pantalla 0.
- Crear el display requiere `WRITE_SECURE_SETTINGS`, que se concede **una sola
  vez** por ADB y ya no se necesita nunca más:

```sh
adb shell pm grant com.accessdroid.termux android.permission.WRITE_SECURE_SETTINGS
```

Sin ese permiso, todo lo demás sigue funcionando; solo `display on/off` dice
qué comando falta. En `-d N openurl` la URL se resuelve a su app para poder
dirigirla, porque el selector del sistema siempre sale en la pantalla 0. Detalles, límites (`back`/`home` son globales: pertenecen a
la pantalla 0) y la bitácora de cómo se descubrió esto:
**[docs/pantalla-virtual.md](docs/pantalla-virtual.md)**.

## La skill para agentes

`skill/phone-control/SKILL.md` es el manual que hace que un agente de IA
(OpenCode, Claude, etc.) sepa operar el teléfono con este toolkit: el loop de
snapshot/tap, cómo leer el árbol, troubleshooting, etc. `install.sh` la deja
en el lugar correcto y la registra en `AGENTS.md` automáticamente.

## Compilar el APK tú mismo

```sh
cd app
bash build.sh          # -> build/CostPhone.apk
```

Necesitas: `aapt2` y `javac` (JDK 17) en el PATH, y un SDK en `~/android-sdk`
con `platforms/android-*/android.jar` + `build-tools/` (d8 + apksigner.jar).
El keystore de debug (`~/.android/debug.keystore`) se crea solo la primera vez.

Los íconos se regeneran del logo con `python3 gen-icons.py`
(fuente: `app/assets/costphone-logo.png`).

## Troubleshooting

- ⚠️ **Tras instalar/actualizar la app, Android desactiva el servicio de
  accesibilidad** (seguridad). Hay que reactivarlo:
  **Ajustes → Accesibilidad → CostPhone → Activar**. El panel de CostPhone
  lo muestra en rojo.
- **“⚠ SERVICIO NO RESPONDE”** al usar amctl → el servicio está apagado.
  Reactívalo como arriba. (amctl borra y verifica el archivo antes de leer —
  nunca te dará una pantalla vieja.)
- **Play Protect bloquea la instalación** (“Aplicación bloqueada para proteger
  tu dispositivo”) → toca *Entendido* y reintenta; si insiste, desactiva
  temporalmente el análisis en *Play Store → Play Protect*, instala, y
  reactívalo. **El APK no está corrupto.**
- **MIUI/HyperOS apaga el servicio solo** → activa Termux y CostPhone en
  *Autostart* y quítales la optimización de batería.

## Seguridad

- Los broadcasts van dirigidos explícitamente al paquete
  (`-p com.accessdroid.termux`), no son difundidos a todas las apps.
- El servicio solo ejecuta comandos que traen el secreto compartido.
- La app pide 2 permisos siempre (`BIND_ACCESSIBILITY_SERVICE`,
  `QUERY_ALL_PACKAGES`) y uno opcional (`WRITE_SECURE_SETTINGS`, solo para crear
  la pantalla virtual). Android no concede los que no sepidan; el opcional
  arranca **sin** conceder y solo habilita `amctl display on/off`.
- ⚠️ En esta v1 el secreto es fijo (visible en el APK que distribuyes).
  Protege contra comandos casuales de apps ajenas, no contra una app
  maliciosa que ya conozca el secreto. Si te preocupa, cambia `SECRET` en
  `app/AccessibilityServiceImpl.java` y en `termux/amctl`, y recompila.

## Créditos y licencia

- Basado en **AccessDroid** de
  [yashas-13](https://github.com/yashas-13/accessdroid-termux) (MIT).
- **CostPhone** agrega: rebranding + logo, panel premium en español,
  limpieza de permisos/componentes, comandos `snap`/`tapref`/`see` con refs
  estilo agent-browser, verificación anti-datos-viejos, skill para agentes,
  pantalla virtual dirigida (`amctl -d N`), y fixes varios (screenshot en
  Android 14/16, servicio tras updates).
- Licencia: **MIT** — ver [LICENSE](LICENSE).
