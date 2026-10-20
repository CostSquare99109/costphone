# Auditoría de velocidad — costphone / phone-control

> Medición, hallazgos y cambios aplicados para reducir la latencia percibida
> por el agente. Todos los números son **p50 de 8-12 iteraciones** medidos en
> el propio dispositivo (Android 16, Termux, 8 núcleos).

---

## 1. La hipótesis que había (y era falsa)

El enunciado daba por hecho que el cuello de botella era el **número de
tool-calls** (ida y vuelta agente↔CLI) y no el tiempo de la orden en sí.

La primera medición cambió el diagnóstico:

```
comando                      p50      desglose
amctl snap                   695 ms   ├─ exec de bash (el agente lanza amctl)   ~150 ms
                                       ├─ exec de mkdir                          ~190 ms
                                       ├─ exec de base64                         ~190 ms
                                       ├─ exec de tr                             ~150 ms
                                       └─ socket + servicio (lo de verdad)        ~70 ms
```

**El 90% de la latencia NO era el servicio: eran los `exec` de binarios que
hacía el propio CLI.** En un teléfono con Termux, *cada* `fork()+exec()` de
un binario cuesta ~150-200 ms (el enlazador dinámico de Android no es
trivial). Medido directamente:

```
/bin/true ................ 135-221 ms      (¡un ejecutable de 9 bytes!)
base64 .................. 174-237 ms
bash -c 'exit 0' ........ 146 ms
echo (builtin, sin exec) .   1.3 ms
```

El `fork` sin `exec` es barato (~1,2 ms). Lo caro es el `exec`.

Y el suelo real del protocolo, medido con bash puro (0 execs):

```
ping .......... 15 ms      snap ........... 70 ms      tap .......... 19 ms
snap --filter . 48 ms      waitfor ........ 64 ms
```

Es decir: **el trabajo real del servicio son 15-70 ms.** Todo lo demás era
sobrecarga del envoltorio.

---

## 2. Baseline (antes de tocar nada)

| Orden | p50 |
|---|---|
| `status` (ping + snapshot) | 595 ms |
| `snap` | 695 ms |
| `snap --filter` | 620 ms |
| `tree` | 671 ms |
| `click "texto"` | 597 ms |
| `clickid id` | 580 ms |
| `tap x y` | 553 ms |
| `launch com.pkg` | 449 ms |
| `launch whatsapp` | 376 ms (fallaba: sin Termux:API) |
| `waitfor` | 3 642 ms (espera real) |
| `see` (captura) | 1 227 ms |
| **3 órdenes en 1 shell** | **2 202 ms** |

---

## 3. Hallazgos, por ROI

### 1. [Muy alto] El CLI ejecuta 3 binarios por orden → 0 execs
**Archivos**: `termux/amctl`
**Qué**: `mkdir -p "$CACHE"` al arrancar, `base64 | tr -d '\n'` para codificar
el payload de argumentos y `date +%s` para la caché de transporte.
**Cambio**:
- `cp_b64()`: base64 en bash puro (carácter → code point → bytes UTF-8 →
  base64). Verificado byte a byte contra el `base64` del sistema, incluidos
  emoji y caracteres astral (𝔘, 𐍈).
- `mkdir` de la caché: perezoso, solo cuando hay que escribir.
- `date +%s` → `printf '%(%s)T' -1` (builtin).
- `resolve_pkg`: caché de `pm list packages` + filtrado en bash puro
  (antes eran `sed`+`awk`+`grep`+`echo|grep` = 5 execs).
- `tapref --settle`: sin `awk` (fracciones) ni `sleep`.
- `see`: tamaño del fichero calculado desde el base64, sin `stat`.
**Ganancia**: **−390 ms por orden** (snap 695 → 307 ms).
**Riesgo**: bajo. El base64 se verificó contra el del sistema.

### 2. [Muy alto] `cp.sh`: el agente habla el protocolo sin lanzar nada
**Archivo**: `skill/phone-control/cp.sh` (nuevo)
**Qué**: si el agente hace `source` de la librería, **no hay ningún `exec`**:
solo builtins de bash contra `/dev/tcp`.
```
. ~/.config/opencode/skills/android/phone-control/cp.sh && cp_snap --filter chat
```
**Ganancia**: `cp_snap` 89 ms, `cp_status` 14 ms (frente a 254/307 ms).
**Riesgo**: bajo. Mismo protocolo CP/1, mismo formato de salida.

### 3. [Alto] Etiquetas del snapshot: O(n²) → O(n)
**Archivos**: `app/src/Snap.java`
**Qué**: `labelOf()` llamaba a `childText()`, que recorría **todos** los
descendientes **por cada nodo**. Con 3500 nodos son ~6 M de comparaciones de
cadenas.
**Cambio**: `Snap.Index` precalcula los textos de los descendientes de abajo
arriba en una sola pasada.
**Prueba**: test diferencial (`app/tests/SnapDiffTest.java`): 181 528
etiquetas de 3 000 árboles aleatorios → **0 discrepancias** (6 semillas más:
0 más).
**Riesgo**: medio, mitigado por el test diferencial.

### 4. [Alto] Caché del árbol de accesibilidad
**Archivo**: `app/AccessibilityServiceImpl.java`
**Qué**: recorrer el árbol son miles de llamadas al framework (cada getter es
un IPC). Con `launch → waitfor → snap` se recorría **tres veces**.
**Cambio**: `uiVersion` (contador que sube con cada evento de accesibilidad)
+ caché del árbol. Si la UI no ha cambiado, el recorrido se reutiliza.
**Ganancia**: el flujo típico recorre el árbol **una** vez.
**Riesgo**: medio (semántica de "caché"). Mitigado: la versión se lee *antes*
del recorrido, así que un evento durante el recorrido invalida la caché.

### 5. [Alto] `waitfor` no construye el snapshot en cada sondeo
**Archivos**: `app/src/Snap.java`, `app/AccessibilityServiceImpl.java`
**Qué**: el sondeo (cada 120 ms) construía el snapshot entero (todas las
etiquetas) para comparar un texto.
**Cambio**: `Snap.findMatch()` busca el texto directamente y solo se
construye el snapshot con refs cuando ya apareció.
**Riesgo**: bajo (mismo criterio de coincidencia: etiqueta + " " + id).

### 6. [Medio] `batch`: N órdenes en un proceso
**Archivo**: `termux/amctl`
**Qué**: `amctl a; amctl b; amctl c` = 3 execs de bash = ~450 ms solo de
arranque.
**Cambio**: `amctl batch "a; b; c"` → 1 exec, 1 carga del script.
**Ganancia**: 3 órdenes: 2 202 ms → 444-822 ms.
**Riesgo**: bajo.

### 7. [Medio] `settle` en el servidor y espera adaptativa en `type`
**Archivos**: `app/AccessibilityServiceImpl.java`, `termux/amctl`
**Qué**: `tapref --settle` dormía en el CLI (exec de `sleep`, ~150 ms) y
`type` dormía **150 ms fijos** tras el `setText`.
**Cambio**: el servidor duerme el `settle` y lo marca (`[settled N]`); si la
app es vieja, el CLI duerme igual (compatibilidad). `type` comprueba primero
y solo espera si hace falta (fracciones de 30 ms, plazo configurable).
**Riesgo**: bajo.

### 8. [Medio] El servicio resuelve el nombre de las apps
**Archivos**: `app/AccessibilityServiceImpl.java`, `termux/amctl`
**Qué**: `amctl launch whatsapp` fallaba sin el socket de Termux:API (que en
este dispositivo no está). `pm list packages` + `sed` + `awk` + `grep` eran
además 4 execs.
**Cambio**: `ACTION_RESOLVE_PKG` en el propio servicio (paquete exacto →
etiqueta → subcadena). El CLI pregunta primero y solo cae a `pm` si la app
es vieja.
**Riesgo**: bajo.

### 9. [Bajo] `type` por teclado: `nap(90)` por carácter
**Qué**: `typeWithIme` duerme 90 ms **por carácter** (25 caracteres = 2,2 s).
**Evaluado, no aplicado**: el teclado en pantalla necesita ese margen entre
pulsaciones; bajarlo provoca teclas perdidas. El camino rápido es `setText`
(que es el que se usa). **Se documenta como limitación consciente.**

### 10. [Bajo] Captura: `Bitmap.wrapHardwareBuffer` + JPEG
**Qué**: la captura pasa por un `copy(ARGB_8888)` y un encode JPEG.
**Evaluado, no aplicado**: el encode JPEG es de ~30 ms; el resto es el
`takeScreenshot` del framework (no se puede acelerar sin API nueva). Con
`--scale 0.5` el encode baja a ~10 ms.

### 11. [Descartado] Keep-alive / pipelining en `CpServer`
**Evaluado, no aplicado**: con el CLI ya sin execs, el handshake TCP de
loopback (~0,1 ms) es irrelevante frente a los 15-70 ms de trabajo real.
El pipelining solo tiene sentido si el socket se mantiene entre tool-calls,
y cada tool-call es un shell nuevo. **El coste (protocolo v2 + negociación)
no compensaba el beneficio.** La conexión se sigue cerrando tras cada
orden.

### 12. [Descartado] `CP/2` con el tamaño en caracteres
**Motivo**: `read -N` de bash cuenta **caracteres**, no bytes, así que con
UTF-8 el cliente lee de más (o bloquea hasta el EOF). Añadir el número de
caracteres a la cabecera lo arreglaría, pero hoy no hay ningún fallo
observable: el servidor cierra la conexión tras cada respuesta, así que el
cuerpo siempre llega completo. Se deja documentado en el código.

---

## 4. Resultado

| Orden | Antes | Después (amctl) | Después (cp.sh, 0 execs) |
|---|---|---|---|
| `status` | 595 ms | 254 ms | **14 ms** |
| `snap` | 695 ms | 224 ms | **89 ms** |
| `snap --filter` | 620 ms | 254 ms | **99 ms** |
| `tree` | 671 ms | 270 ms | — |
| `click "texto"` | 597 ms | 237 ms | — |
| `tap x y` | 553 ms | 148 ms | — |
| `launch com.pkg` | 449 ms | 288 ms | — |
| `see` | 1 227 ms | 715 ms | — |
| 3 órdenes / 1 shell | 2 202 ms | 1 526 ms (3 `amctl`) | — |
| 3 órdenes / 1 `batch` | 2 202 ms | **444 ms** | — |

El suelo físico es el exec de bash que hace el agente (`~150-220 ms`) más el
trabajo del servicio (`15-70 ms`). **`cp.sh` elimina el primer término**: con
la librería, el coste de la orden es *solo* el trabajo del servicio.

---

## 5. Commits aplicados

| # | Commit | Qué |
|---|---|---|
| 1 | `5423b6f` | `perf(cli)`: camino caliente sin execs (base64 puro en bash) |
| 2 | `cd71257` | `feat(cli)`: `batch` — varias órdenes en un proceso |
| 3 | `f612b73` | `test(cli)`: `bench.sh`, `rawbench.sh`, `test_amctl.sh` |
| 4 | `f8063c3` | `perf(server)`: etiquetas del snapshot O(n) en vez de O(n²) |
| 5 | `3cc0443` | `perf(server)`: caché del árbol + búsqueda directa en `waitfor` |
| 6 | `909c22e` | `cleanup(cli)`: eliminar el canal legacy (broadcast + ficheros) |
| 7 | `0e3a6ce` | `feat(server)`: resolver nombre de app + settle en el servidor |

(Los cambios de `cp.sh` y de la documentación van en commits aparte.)

---

## 6. Cómo validar

```bash
# regresión funcional (32 comprobaciones)
./test_amctl.sh

# latencia por comando
./bench.sh 10

# suelo del protocolo (0 execs) — el objetivo a batir
./rawbench.sh 10

# equivalencia de las etiquetas del snapshot (O(n) vs O(n²))
cd app && javac -encoding UTF-8 -d /tmp/snaptest src/Snap.java tests/SnapDiffTest.java
java -cp /tmp/snaptest com.accessdroid.termux.SnapDiffTest 12345 3000
```

### Test manual por commit

| Commit | Cómo validar |
|---|---|
| 1 | `amctl snap --filter X` debe devolver **exactamente** lo mismo que antes (mismo formato `@pN [Clase] 'texto' #id (x,y)`). Probar con texto con `ñ` y con emoji. |
| 2 | `amctl batch "home; back"` → imprime `home`, `---`, `back`. Con `-d`: `amctl batch "-d 2 snap"`. |
| 3 | — |
| 4 | `amctl snap` y `amctl snap -a`: comparar el texto de las etiquetas con la versión anterior (el test diferencial lo cubre automáticamente). |
| 5 | `amctl launch com.whatsapp; amctl waitfor "Chats" 8000; amctl snap` → los refs `@pN` del `waitfor` deben servir para el `tapref` siguiente. |
| 6 | Con la app v1.3+ instalada, todo sigue funcionando. Con la app parada, el error debe ser claro (no un silencio de 5 s). |
| 7 | `amctl launch whatsapp` → debe resolver a `com.whatsapp`. `amctl tapref @p3 --settle 500` → la respuesta debe incluir `[settled 500]`. |

---

## 7. Lo que queda fuera (y por qué)

| Idea | Por qué no |
|---|---|
| Cliente nativo en C/Rust para el CLI | Rompe "sin dependencias nuevas" y exige compilar en el dispositivo. `cp.sh` (bash puro) ya está en el suelo del protocolo. |
| `subscribe` (streaming de snapshots) | El agente necesita *decidir* cuándo mirar; un push le quitaría ese control y complicaría el protocolo. `waitfor` ya resuelve el caso "espera a que aparezca". |
| `alias` de ids estables | `tapref @pN` + `clickid` cubren el caso; los aliases añadirían estado que se invalida igual que las refs. |
| Índice de texto/id en `collect()` | El filtro del snapshot busca sobre la **etiqueta** (que incluye el texto de los descendientes), así que no se puede filtrar durante el recorrido. Con el índice O(n) el coste ya es bajo. |
| Pre-warm del servicio al arrancar | El servicio arranca con el sistema; `amctl warmup` (nuevo) cachea el puerto por si acaso. |

---

## 8. Nota sobre el protocolo

El protocolo sigue siendo **CP/1** (petición de una línea, respuesta de una
línea + cuerpo). No se cambió ni un byte del formato:

```
Petición  : CP/1 <ACTION> [tipo:clave=valor_base64]...
Respuesta : CP/1 <ok|fail|empty> <longitud> <raw|b64>\n<cuerpo>
```

Las órdenes nuevas (`ACTION_RESOLVE_PKG`) y los argumentos nuevos
(`settle`) son *aditivos*: un cliente viejo sigue funcionando contra una app
nueva, y una app vieja contra un cliente nuevo cae al camino lento (documentado
en el código, con marcas de versión en las respuestas).
