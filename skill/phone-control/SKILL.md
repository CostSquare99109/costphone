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
Cuando falla `see`, el motivo exacto queda en
/sdcard/Download/accessdroid-shot-error.txt.

## Actuar

| Comando | Qué hace |
|---|---|
| `amctl tapref @p3` | Toca el centro del elemento (ref del último snap) |
| `amctl tap X Y` | Toque crudo en coordenadas (p.ej. de `snap -a`) |
| `amctl longpress X Y [ms]` | Pulsación larga |
| `amctl swipe X1 Y1 X2 Y2 [ms]` | Deslizar/arrastrar |
| `amctl scroll down\|up [x y]` | Scroll |
| `amctl click "texto"` | Click por texto visible (fallback sin snapshot) |
| `amctl clickid <id>` | Click por resource-id (p.ej. `com.whatsapp:id/send`) |
| `amctl type "texto"` | Escribe en el campo con foco ([edt][FOCUS] en snap) |
| `amctl back` / `home` / `recents` | Botones del sistema |
| `amctl launch <pkg>` | Abre app (terceros: vía servicio CostPhone) |
| `amctl scrollto "texto"` | Busca y hace scroll a un texto |

Para escribir: `tapref` al campo `[edt]` (o `clickid ...entry`) → `amctl type` →
`tapref` al botón enviar (`'Enviar'` / `#send`) → `snap` para verificar.

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

## Transporte y limitaciones (Termux estable v0.118.3)

- Transporte `am` clásico (socket AM no existe en v0.118.x). No funcionan los
  comandos `list/find/stop/info/perms/grant/revoke/clear` (necesitan socket).
- `launch` de apps de terceros va vía el servicio (hay límite de visibilidad de
  paquetes del `am` clásico). Si falla: `am start -n <pkg>/<Activity>` explícito.
- El árbol exige el servicio activado: Ajustes → Accesibilidad → CostPhone ON,
  y app >= v1.0.8 (versión con flags clk/scr/edt/foc y fix de captura).

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
