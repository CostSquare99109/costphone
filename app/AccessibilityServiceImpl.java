package com.accessdroid.termux;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class AccessibilityServiceImpl extends AccessibilityService {

    private static final String TAG = "AccessDroid";
    private static final String ACTION_TAP   = "com.accessdroid.termux.ACTION_TAP";
    private static final String ACTION_SWIPE = "com.accessdroid.termux.ACTION_SWIPE";
    private static final String ACTION_LONG_PRESS = "com.accessdroid.termux.ACTION_LONG_PRESS";
    private static final String ACTION_SCROLL_UP   = "com.accessdroid.termux.ACTION_SCROLL_UP";
    private static final String ACTION_SCROLL_DOWN = "com.accessdroid.termux.ACTION_SCROLL_DOWN";
    private static final String ACTION_BACK  = "com.accessdroid.termux.ACTION_BACK";
    private static final String ACTION_HOME  = "com.accessdroid.termux.ACTION_HOME";
    private static final String ACTION_RECENTS = "com.accessdroid.termux.ACTION_RECENTS";
    private static final String ACTION_TYPE  = "com.accessdroid.termux.ACTION_TYPE";
    private static final String ACTION_CLICK_TEXT = "com.accessdroid.termux.ACTION_CLICK_TEXT";
    private static final String ACTION_CLICK_ID  = "com.accessdroid.termux.ACTION_CLICK_ID";
    private static final String ACTION_FIND_ID   = "com.accessdroid.termux.ACTION_FIND_ID";
    private static final String ACTION_SCROLL_TO = "com.accessdroid.termux.ACTION_SCROLL_TO";
    private static final String ACTION_PRESS_KEY = "com.accessdroid.termux.ACTION_PRESS_KEY";
    private static final String ACTION_SCREENDUMP = "com.accessdroid.termux.ACTION_SCREENDUMP";
    private static final String ACTION_GET_UI_TREE = "com.accessdroid.termux.ACTION_GET_UI_TREE";
    // Extra actions for AI agent control
    private static final String ACTION_PINCH = "com.accessdroid.termux.ACTION_PINCH";
    private static final String ACTION_DOUBLE_TAP = "com.accessdroid.termux.ACTION_DOUBLE_TAP";
    private static final String ACTION_CLEAR_TEXT = "com.accessdroid.termux.ACTION_CLEAR_TEXT";
    private static final String ACTION_PASTE = "com.accessdroid.termux.ACTION_PASTE";
    private static final String ACTION_FOCUS = "com.accessdroid.termux.ACTION_FOCUS";
    private static final String ACTION_NOTIFICATIONS = "com.accessdroid.termux.ACTION_NOTIFICATIONS";
    private static final String ACTION_QUICK_SETTINGS = "com.accessdroid.termux.ACTION_QUICK_SETTINGS";
    private static final String ACTION_LOCK = "com.accessdroid.termux.ACTION_LOCK";
    private static final String ACTION_SCREENSHOT = "com.accessdroid.termux.ACTION_SCREENSHOT";
    private static final String ACTION_OPEN_SETTINGS = "com.accessdroid.termux.ACTION_OPEN_SETTINGS";
    private static final String ACTION_START_ACTIVITY = "com.accessdroid.termux.ACTION_START_ACTIVITY";
    private static final String ACTION_OPEN_URI = "com.accessdroid.termux.ACTION_OPEN_URI";
    private static final String ACTION_POWER = "com.accessdroid.termux.ACTION_POWER";
    private static final String ACTION_DISPLAY = "com.accessdroid.termux.ACTION_DISPLAY";
    private static final String ACTION_DISPLAY_BACK = "com.accessdroid.termux.ACTION_DISPLAY_BACK";
    // Canal rápido (socket local) y snapshot calculado en el servidor
    private static final String ACTION_GET_SNAPSHOT = "com.accessdroid.termux.ACTION_GET_SNAPSHOT";
    private static final String ACTION_TAPREF = "com.accessdroid.termux.ACTION_TAPREF";
    private static final String ACTION_WAITFOR = "com.accessdroid.termux.ACTION_WAITFOR";
    private static final String ACTION_TAP_VERIFY = "com.accessdroid.termux.ACTION_TAP_VERIFY";
    public static final String ACTION_ENABLE_SERVICE = "com.accessdroid.termux.ACTION_ENABLE_SERVICE";
    private static final String ACTION_RESOLVE_PKG = "com.accessdroid.termux.ACTION_RESOLVE_PKG";
    private static final String ACTION_PING = "com.accessdroid.termux.ACTION_PING";

    /** Pantalla virtual (display secundario). */
    static final String SETTING_OVERLAY_DISPLAYS = "overlay_display_devices";
    static final String FILE_DISPLAYS = "accessdroid-displays.txt";
    static final String FILE_ERROR = "accessdroid-error.txt";
    static final String FILE_SHOT_ERROR = "accessdroid-shot-error.txt";

    private static final int MAX_NODES = 3500;       // tope del volcado
    private static final long DUMP_BUDGET_MS = 5000;  // presupuesto de tiempo del volcado
    private static final int MAX_DEPTH = 25;

    /**
     * Flags que acepta AOSP en overlay_display_devices. Allowlist estricta a
     * propósito: un flag mal escrito hace que Android rechace el ajuste entero
     * sin crear ningún display y sin decir por qué.
     */
    private static final List<String> OVERLAY_FLAGS = java.util.Arrays.asList(
        "secure", "own_content_only", "should_show_system_decorations",
        "gravity_top_left", "gravity_top_right", "gravity_bottom_left", "gravity_bottom_right");

    static AccessibilityServiceImpl sInstance;
    int screenW, screenH;
    Handler handler = new Handler(Looper.getMainLooper());
    // Shared secret – set via build-time constant or default. amctl must send it.
    public static final String SECRET = "termux-accessdroid-2025";
    // Screenshot callback state
    interface ScreenshotCB { void onResult(android.graphics.Bitmap bmp); }
    ScreenshotCB pendingCb = null;

    /** Canal rápido por TCP loopback. */
    private CpServer server;

    /** Último snapshot calculado aquí (para tapref sin volver a descargar). */
    private static final class RefCache {
        int display;
        boolean allMode;
        List<Snap.Pick> picks = new ArrayList<>();
        long stamp;
    }
    private RefCache refCache;

    /** La UI ha cambiado desde el último snapshot (eventos de accesibilidad). */
    private volatile boolean uiDirty = true;

    /** Versión de la UI: se incrementa con cada evento de accesibilidad. */
    private volatile int uiVersion = 1;

    /** Árbol recorrido la última vez, reutilizable si la UI no ha cambiado. */
    private static final class TreeCache {
        int display;
        int version;
        String pkg = "";
        List<Snap.Node> nodes = new ArrayList<>();
    }
    private volatile TreeCache treeCache;

    public boolean isConnected() { return sInstance != null; }

    public void captureBitmap(final ScreenshotCB cb) {
        if (Build.VERSION.SDK_INT < 33) { if (cb != null) cb.onResult(null); return; }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    android.graphics.Bitmap bmp = null;
                    try { bmp = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace()); } catch (Exception e) { Log.w(TAG, "wrapHB failed", e); }
                    if (cb != null) cb.onResult(bmp);
                }
                @Override public void onFailure(int errCode) {
                    Log.e(TAG, "captureBitmap fail " + errCode);
                    if (cb != null) cb.onResult(null);
                }
            });
    }

    /** Nombre del PNG por display: el 0 conserva accessdroid-shot.png (compat). */
    static String shotFile(int displayId) {
        return displayId == android.view.Display.DEFAULT_DISPLAY
            ? "accessdroid-shot.png"
            : "accessdroid-shot-d" + displayId + ".png";
    }

    /** Nombre de la captura en el canal rápido (JPEG, mucho más rápido de escribir). */
    static String shotFileJpg(int displayId) {
        return displayId == android.view.Display.DEFAULT_DISPLAY
            ? "accessdroid-shot.jpg"
            : "accessdroid-shot-d" + displayId + ".jpg";
    }

    /** Nombre del árbol por display (evita que un snap en dN pise el de d0). */
    static String treeFile(int displayId) {
        return displayId == android.view.Display.DEFAULT_DISPLAY
            ? "accessdroid-tree.txt"
            : "accessdroid-tree-d" + displayId + ".txt";
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        // Canal rápido: la app sirve las órdenes por TCP de loopback y el CLI
        // las manda por ahí. Si esto falla, el CLI lo dice claro y no hay
        // alternativa: el canal legacy (broadcast + fichero) se eliminó en
        // la v1.4 porque costaba 1-5 s por orden y hacía fallar en silencio.
        try {
            server = new CpServer(SECRET, new CpServer.Handler() {
                @Override public CpRes handle(String action, Map<String, Object> args) {
                    return executeFromArgs(action, args);
                }
            });
            server.start();
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo abrir el canal rápido", t);
        }
        Log.i(TAG, "AccessibilityService connected – ready");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // Marca la UI como cambiada: permite saber si las refs del último
        // snapshot siguen siendo válidas sin recorrer el árbol.
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED:
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                uiDirty = true;
                uiVersion++;   // invalida la caché del árbol
                break;
            default:
                break;
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (server != null) { try { server.stop(); } catch (Exception ignored) { } server = null; }
        sInstance = null;
    }

    /* -------------------------------------------------------------- */
    /*                        Command dispatcher                      */
    /* -------------------------------------------------------------- */

    /** Traduce los argumentos del canal rápido a extras de Intent y ejecuta. */    private CpRes executeFromArgs(String action, Map<String, Object> args) {
        // El cliente puede mandar el nombre corto ("ACTION_TAP") o el completo
        // ("com.accessdroid.termux.ACTION_TAP"). Antes se prefijaba siempre y
        // el nombre completo acababa duplicado ("com.accessdroid.termux.com...").
        String full = action != null && action.startsWith("com.accessdroid.termux.")
                ? action : "com.accessdroid.termux." + action;
        Intent intent = new Intent(full);
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if ("secret".equals(k)) continue;
            if (v instanceof Integer) intent.putExtra(k, (Integer) v);
            else if (v instanceof Long) intent.putExtra(k, (Long) v);
            else if (v instanceof Float) intent.putExtra(k, (Float) v);
            else if (v instanceof String) intent.putExtra(k, (String) v);
        }
        return execute(intent);
    }

    private CpRes execute(Intent intent) {
        String action = intent.getAction();
        if (action == null) return CpRes.fail("sin acción");

        switch (action) {
            case ACTION_RESOLVE_PKG: {
                // Resolver el nombre de una app ("whatsapp" -> "com.whatsapp")
                // desde el propio servicio: el CLI no depende así del socket de
                // Termux:API (que además no siempre está disponible).
                String q = intent.getStringExtra("query");
                if (q == null || q.isEmpty()) return CpRes.fail("consulta vacía");
                return resolvePackage(q);
            }
            case ACTION_ENABLE_SERVICE:
                return enableSelf(this);
            case ACTION_PING:
                return CpRes.ok("pong");

            case ACTION_TAP: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                long dur = intent.getLongExtra("duration", 80);
                if (x < 0 || y < 0) return CpRes.fail("coordenadas inválidas");
                run(makeTap(x, y, dur, disp(intent)), "tap");
                return CpRes.ok("tap (" + x + "," + y + ")");
            }
            case ACTION_TAP_VERIFY: {
                // Toque + comprobación de que la pantalla reaccionó.
                // Sin esto, un tap que cae en el vacío devuelve "ok" y el
                // agente sigue creyendo que navegó. La firma incluye textos
                // y posiciones, así que también detecta el toolbar que
                // colapsa (mismos textos, otras coordenadas).
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                long dur = intent.getLongExtra("duration", 80);
                long timeout = intent.getLongExtra("timeout", 1500);
                if (x < 0 || y < 0) return CpRes.fail("coordenadas inválidas");
                int d = disp(intent);
                String before = uiSignature(d);
                run(makeTap(x, y, dur, d), "tap");
                long t0 = System.currentTimeMillis();
                long limit = Math.max(200, Math.min(timeout, 6000));
                boolean changed = false;
                while (System.currentTimeMillis() - t0 < limit) {
                    nap(60);
                    if (!before.equals(uiSignature(d))) { changed = true; break; }
                }
                long ms = System.currentTimeMillis() - t0;
                if (changed) {
                    return CpRes.ok("tap (" + x + "," + y + ") [cambió en " + ms + " ms]");
                }
                return CpRes.ok("tap (" + x + "," + y + ") [sin cambios en " + ms
                        + " ms — tap probablemente perdido]");
            }
            case ACTION_LONG_PRESS: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                long dur = intent.getLongExtra("duration", 500);
                if (x < 0 || y < 0) return CpRes.fail("coordenadas inválidas");
                run(makeTap(x, y, dur, disp(intent)), "longpress");
                return CpRes.ok("longpress (" + x + "," + y + ")");
            }
            case ACTION_SWIPE: {
                int x1 = intent.getIntExtra("x1", -1);
                int y1 = intent.getIntExtra("y1", -1);
                int x2 = intent.getIntExtra("x2", -1);
                int y2 = intent.getIntExtra("y2", -1);
                long dur = intent.getLongExtra("duration", 300);
                if (x1 < 0 || x2 < 0) return CpRes.fail("coordenadas inválidas");
                run(makeSwipe(x1, y1, x2, y2, dur, disp(intent)), "swipe");
                return CpRes.ok("swipe (" + x1 + "," + y1 + ")->(" + x2 + "," + y2 + ")");
            }
            case ACTION_SCROLL_UP: {
                int d = disp(intent);
                android.util.DisplayMetrics dm = metricsOf(d);
                // x/y <= 0 = "no los mandó el cliente": usar el centro.
                // (Antes el CLI mandaba 0,0 siempre y el gesto acababa con
                // coordenadas negativas: "Path bounds must not be negative").
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                if (x <= 0) x = dm.widthPixels / 2;
                if (y <= 0) y = (int)(dm.heightPixels * 0.5f);
                boolean sem = scrollSemantic(x, y, false, d);
                // Gesto: el dedo baja => el contenido sube (se ven los
                // anteriores). Antes iba al revés y "scroll up" en una lista
                // ya arriba no hacía nada.
                if (!sem) run(makeScroll(x, y, 0.4f, d), "scrollUp");
                return CpRes.ok(sem ? "scroll up (semántico)" : "scroll up");
            }
            case ACTION_SCROLL_DOWN: {
                int d = disp(intent);
                android.util.DisplayMetrics dm = metricsOf(d);
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                if (x <= 0) x = dm.widthPixels / 2;
                if (y <= 0) y = (int)(dm.heightPixels * 0.5f);
                boolean sem = scrollSemantic(x, y, true, d);
                if (!sem) run(makeScroll(x, y, -0.4f, d), "scrollDown");
                return CpRes.ok(sem ? "scroll down (semántico)" : "scroll down");
            }
            case ACTION_BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                return CpRes.ok("back");
            case ACTION_HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                return CpRes.ok("home");
            case ACTION_RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                return CpRes.ok("recents");
            case ACTION_TYPE: {
                String text = intent.getStringExtra("text");
                if (text == null || text.isEmpty()) return CpRes.fail("texto vacío");
                long settle = intent.getLongExtra("settle", 400);
                AccessibilityNodeInfo focus = focusIn(disp(intent));
                if (focus != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    // setText puede devolver true y no escribir nada (combobox,
                    // inputs React/web): hay que comprobar el texto de verdad.
                    if (ok) {
                        // El setText es síncrono, pero el nodo puede tardar un
                        // poco en reflejarlo. Antes había una espera FIJA de
                        // 150 ms en cada escritura; ahora se comprueba primero
                        // (caso normal: ya está escrito) y solo se espera si
                        // hace falta, en fracciones cortas.
                        long deadline = System.currentTimeMillis() + Math.max(0, Math.min(settle, 2000));
                        while (true) {
                            // nodo fresco: el anterior ya está obsoleto y
                            // devuelve el texto viejo
                            if (textLanded(focusIn(disp(intent)), text)) return CpRes.ok("escrito: " + text);
                            if (System.currentTimeMillis() >= deadline) break;
                            nap(30);
                        }
                        if (typeWithIme(text, disp(intent)) && textLanded(focusIn(disp(intent)), text))
                            return CpRes.ok("escrito (teclado): " + text);
                        return CpRes.fail("el campo aceptó el texto pero no lo muestra: "
                            + "probablemente es un desplegable (elige una opción con click) "
                            + "y no un campo de escritura");
                    }
                    // setText rechazado: pasa a ser un input web/React (Meta Ad Library,
                    // algunas WebViews). Ahí solo funciona el teclado en pantalla.
                    if (typeWithIme(text, disp(intent))) return CpRes.ok("escrito (teclado): " + text);
                    return CpRes.fail("el campo rechazó el texto (ni setText ni teclado)");
                }
                if (typeWithIme(text, disp(intent))) return CpRes.ok("escrito (teclado): " + text);
                return CpRes.fail("no hay ningún campo de texto con el foco");
            }
            case ACTION_CLICK_TEXT: {
                String txt = intent.getStringExtra("text");
                if (txt == null || txt.isEmpty()) return CpRes.fail("texto vacío");
                AccessibilityNodeInfo root = rootFor(disp(intent));
                if (root == null) return CpRes.fail("no hay ventana activa");
                AccessibilityNodeInfo target = findBestByText(root, txt);
                if (target == null) return CpRes.fail("no se encontró el texto '" + txt + "'");
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!ok) {
                    // Algunos nodos no aceptan ACTION_CLICK (TextView dentro de
                    // una fila): se cae a un toque real en su centro.
                    ok = tapCenter(target, disp(intent));
                }
                Log.i(TAG, "click '" + txt + "' → " + ok);
                return ok ? CpRes.ok("click '" + txt + "'") : CpRes.fail("el elemento '" + txt + "' no aceptó el click");
            }
            case ACTION_CLICK_ID: {
                String rid = intent.getStringExtra("id");
                if (rid == null || rid.isEmpty()) return CpRes.fail("id vacío");
                AccessibilityNodeInfo root = rootFor(disp(intent));
                if (root == null) return CpRes.fail("no hay ventana activa");
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                AccessibilityNodeInfo target = nodes.isEmpty() ? null : nodes.get(0);
                // El snapshot muestra el id corto ("#skip_ad_button") pero
                // findAccessibilityNodeInfosByViewId exige el completo
                // ("pkg:id/skip_ad_button"): se acepta también el corto.
                if (target == null) target = findByShortId(root, rid);
                if (target == null) return CpRes.fail("no existe el id " + rid);
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!ok) ok = tapCenter(target, disp(intent));
                Log.i(TAG, "click id=" + rid + " → " + ok);
                return ok ? CpRes.ok("click " + rid) : CpRes.fail("el elemento " + rid + " no aceptó el click");
            }
            case ACTION_SCROLL_TO: {
                String txt = intent.getStringExtra("text");
                if (txt == null || txt.isEmpty()) return CpRes.fail("texto vacío");
                return scrollUntil(txt, disp(intent), intent.getIntExtra("max", 10));
            }
            case ACTION_GET_UI_TREE: {
                int d = disp(intent);
                List<Snap.Node> nodes = new ArrayList<>();
                collect(d, nodes, System.currentTimeMillis() + DUMP_BUDGET_MS);
                if (nodes.isEmpty()) {
                    String mark = "# d" + d + ": " + (d == android.view.Display.DEFAULT_DISPLAY
                        ? "sin ventana activa" : "sin ventanas propias (vacío)");
                    return CpRes.empty(mark).withText(mark + "\n").withFile(treeFile(d));
                }
                String tree = Snap.renderTree(pkgOf(d), nodes, d);
                return CpRes.ok("árbol").withText(tree).withFile(treeFile(d));
            }
            case ACTION_GET_SNAPSHOT: {
                int d = disp(intent);
                boolean all = intent.getIntExtra("mode", 0) == 1;
                String filter = intent.getStringExtra("filter");
                List<Snap.Node> nodes = new ArrayList<>();
                collect(d, nodes, System.currentTimeMillis() + DUMP_BUDGET_MS);
                if (nodes.isEmpty()) {
                    int dd = d;
                    return CpRes.empty("Pantalla — display " + dd + ": vacío (sin ventanas propias). "
                        + "El servicio va bien: no hay nada en esa pantalla todavía. "
                        + "Lánzale algo con: amctl -d " + dd + " launch <pkg>");
                }
                List<Snap.Pick> picks = Snap.select(nodes, all, filter);
                if (picks.isEmpty()) {
                    return CpRes.empty("Pantalla: " + pkgOf(d) + " — sin elementos interactivos "
                        + "(¿servicio activado / app >= v1.0.8?)");
                }
                String snapText = Snap.render(pkgOf(d), nodes, picks, all, d);
                RefCache cache = new RefCache();
                cache.display = d;
                cache.allMode = all;
                cache.picks = picks;
                cache.stamp = System.currentTimeMillis();
                refCache = cache;
                uiDirty = false;
                return CpRes.ok("snapshot").withText(snapText);
            }
            case ACTION_TAPREF: {
                String ref = intent.getStringExtra("ref");
                if (ref == null || ref.isEmpty()) return CpRes.fail("ref vacío");
                long settle = intent.getLongExtra("settle", 0);
                boolean fast = intent.getIntExtra("fast", 0) == 1;
                long verify = intent.getLongExtra("verify", 0);
                return tapRef(ref, disp(intent), settle, fast, verify);
            }
            case ACTION_WAITFOR: {
                // Espera a que aparezca un elemento: el bucle va en el servicio,
                // así que el agente no gasta ida y vuelta ni duerme a ciegas.
                int d = disp(intent);
                String needle = intent.getStringExtra("text");
                long timeout = intent.getLongExtra("timeout", 5000);
                boolean all = intent.getIntExtra("mode", 0) == 1;
                if (needle == null || needle.isEmpty()) return CpRes.fail("texto vacío");
                long deadline = System.currentTimeMillis() + Math.max(200, Math.min(timeout, 60000));
                while (System.currentTimeMillis() < deadline) {
                    List<Snap.Node> nodes = new ArrayList<>();
                    collect(d, nodes, System.currentTimeMillis() + 1500);
                    if (!nodes.isEmpty()) {
                        // Búsqueda directa: no se construye el snapshot entero
                        // en cada sondeo (costaba O(n) etiquetas por vuelta,
                        // y el snapshot solo hace falta cuando ya apareció).
                        Snap.Index idx = Snap.index(nodes);
                        int hit = Snap.findMatch(idx, needle);
                        if (hit >= 0) {
                            // allMode=true: el waitfor tiene que ver también el texto
                            // NO interactivo (títulos de reproductor, rótulos, celdas).
                            List<Snap.Pick> picks = Snap.select(idx, true, null);
                            Snap.Pick match = null;
                            for (Snap.Pick p : picks) {
                                if (p.node == nodes.get(hit)) { match = p; break; }
                            }
                            if (match != null) {
                                RefCache cache = new RefCache();
                                cache.display = d;
                                cache.allMode = true;
                                cache.picks = picks;
                                cache.stamp = System.currentTimeMillis();
                                refCache = cache;
                                uiDirty = false;
                                return CpRes.ok("encontrado @" + match.ref)
                                    .withText(Snap.render(pkgOf(d), nodes, picks, true, d));
                            }
                        }
                    }
                    try { Thread.sleep(120); } catch (InterruptedException e) { break; }
                }
                return CpRes.fail("timeout: '" + needle + "' no apareció en " + timeout + "ms");
            }
            case ACTION_SCREENDUMP: {
                int displayId = intent.getIntExtra("displayId", android.view.Display.DEFAULT_DISPLAY);
                float scale = intent.getFloatExtra("scale", 0f);
                int quality = intent.getIntExtra("quality", 85);
                boolean png = "png".equalsIgnoreCase(intent.getStringExtra("format"));
                byte[] img = captureImage(displayId, scale, quality, png);
                if (img == null) {
                    return CpRes.fail("no se pudo capturar la pantalla (API 33+ requerido)")
                        .withFile(FILE_SHOT_ERROR);
                }
                String dims = imageSize(img, png);
                return CpRes.ok("captura " + dims).withBinary(img)
                    .withFile(png ? shotFile(displayId) : shotFileJpg(displayId));
            }
            case ACTION_PRESS_KEY: {
                int keyCode = intent.getIntExtra("keyCode", 0);
                int global = -1;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_BACK:        global = GLOBAL_ACTION_BACK; break;
                    case KeyEvent.KEYCODE_HOME:        global = GLOBAL_ACTION_HOME; break;
                    case KeyEvent.KEYCODE_APP_SWITCH:  global = GLOBAL_ACTION_RECENTS; break;
                    case KeyEvent.KEYCODE_VOLUME_UP:   global = -2; break; // handled below
                    case KeyEvent.KEYCODE_VOLUME_DOWN: global = -3; break;
                    default:
                        Log.w(TAG, "unsupported keycode via a11y: " + keyCode);
                        return CpRes.fail("keycode no soportado: " + keyCode);
                }
                if (global == -2 || global == -3) {
                    // Volume via audio manager fallback
                    try {
                        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            if (global == -2) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
                            else am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                        }
                        return CpRes.ok("volumen");
                    } catch (Exception e) { Log.w(TAG, "volume adjust fail", e); }
                    return CpRes.fail("no se pudo ajustar el volumen");
                }
                performGlobalAction(global);
                return CpRes.ok("key " + keyCode);
            }
            case ACTION_PINCH: {
                int dispId = disp(intent);
                android.util.DisplayMetrics dmp = metricsOf(dispId);
                int cx = intent.getIntExtra("cx", dmp.widthPixels / 2);
                int cy = intent.getIntExtra("cy", dmp.heightPixels / 2);
                float scale = intent.getFloatExtra("scale", 0.5f);
                int dist = Math.min(dmp.widthPixels, dmp.heightPixels) / 3;
                int d = (int)(dist * (1 - scale));
                Path p1 = new Path(); p1.moveTo(cx - dist/2, cy); p1.lineTo(cx - d/2, cy);
                Path p2 = new Path(); p2.moveTo(cx + dist/2, cy); p2.lineTo(cx + d/2, cy);
                GestureDescription gd = build(java.util.Arrays.asList(
                        new GestureDescription.StrokeDescription(p1, 0, 400),
                        new GestureDescription.StrokeDescription(p2, 0, 400)), dispId);
                run(gd, scale < 1 ? "pinchIn" : "pinchOut");
                return CpRes.ok("pinch");
            }
            case ACTION_DOUBLE_TAP: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                if (x < 0 || y < 0) return CpRes.fail("coordenadas inválidas");
                Path p1 = new Path(); p1.moveTo(x, y);
                GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p1, 0, 40);
                Path p2 = new Path(); p2.moveTo(x, y);
                GestureDescription.StrokeDescription s2 = new GestureDescription.StrokeDescription(p2, 120, 40);
                GestureDescription gd = build(java.util.Arrays.asList(s1, s2), disp(intent));
                run(gd, "doubleTap");
                return CpRes.ok("doble tap (" + x + "," + y + ")");
            }
            case ACTION_CLEAR_TEXT: {
                AccessibilityNodeInfo f = focusIn(disp(intent));
                if (f == null) return CpRes.fail("no hay ningún campo de texto con el foco");
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                boolean ok = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                return ok ? CpRes.ok("campo vaciado") : CpRes.fail("no se pudo vaciar el campo");
            }
            case ACTION_PASTE: {
                AccessibilityNodeInfo f2 = focusIn(disp(intent));
                if (f2 == null) return CpRes.fail("no hay ningún campo de texto con el foco");
                boolean ok = f2.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                return ok ? CpRes.ok("pegado") : CpRes.fail("no se pudo pegar");
            }
            case ACTION_FOCUS: {
                String txt = intent.getStringExtra("text");
                String rid = intent.getStringExtra("id");
                AccessibilityNodeInfo root = rootFor(disp(intent));
                if (root == null) return CpRes.fail("no hay ventana activa");
                AccessibilityNodeInfo target = null;
                if (txt != null && !txt.isEmpty()) target = findByText(root, txt);
                else if (rid != null && !rid.isEmpty()) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                    if (!nodes.isEmpty()) target = nodes.get(0);
                }
                if (target == null) return CpRes.fail("no se encontró el campo a enfocar");
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                return ok ? CpRes.ok("foco puesto") : CpRes.fail("el campo no aceptó el foco");
            }
            case ACTION_NOTIFICATIONS:
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                return CpRes.ok("notificaciones");
            case ACTION_QUICK_SETTINGS:
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                return CpRes.ok("ajustes rápidos");
            case ACTION_LOCK:
                if (Build.VERSION.SDK_INT >= 28) { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); return CpRes.ok("bloqueo"); }
                return CpRes.fail("requiere API 28+");
            case ACTION_SCREENSHOT:
                if (Build.VERSION.SDK_INT >= 28) { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); return CpRes.ok("captura del sistema"); }
                return CpRes.fail("requiere API 28+");
            case ACTION_POWER:
                if (Build.VERSION.SDK_INT >= 30) { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); return CpRes.ok("diálogo de apagado"); }
                return CpRes.fail("requiere API 30+");
            case ACTION_OPEN_SETTINGS: {
                try {
                    String page = intent.getStringExtra("page");
                    Intent launch;
                    if (page != null && !page.isEmpty()) {
                        launch = new Intent(page);
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    } else {
                        launch = new Intent(android.provider.Settings.ACTION_SETTINGS);
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    startActivity(launch);
                    return CpRes.ok("ajustes");
                } catch (Exception e) { Log.w(TAG, "openSettings fail", e); return CpRes.fail("no se pudieron abrir los ajustes"); }
            }
            case ACTION_START_ACTIVITY: {
                try {
                    String pkg = intent.getStringExtra("package");
                    String cls = intent.getStringExtra("class");
                    String action2 = intent.getStringExtra("action");
                    int dispId = disp(intent);
                    Intent launch2 = null;
                    if (action2 != null && !action2.isEmpty()) {
                        launch2 = new Intent(action2);
                    }
                    if (pkg != null && !pkg.isEmpty()) {
                        android.content.pm.PackageManager pm = getPackageManager();
                        if (launch2 == null) launch2 = pm.getLaunchIntentForPackage(pkg);
                        else launch2.setPackage(pkg);
                    }
                    if (cls != null && !cls.isEmpty() && pkg != null) {
                        launch2 = new Intent();
                        launch2.setClassName(pkg, cls);
                    }
                    if (launch2 == null) return CpRes.fail("no se pudo resolver la app a lanzar");
                    launch2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (dispId != android.view.Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= 26) {
                        // lanzar la app EN la pantalla virtual, no en la del usuario
                        startActivity(launch2, android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(dispId).toBundle());
                    } else {
                        startActivity(launch2);
                    }
                    return CpRes.ok("lanzada" + (pkg != null ? " " + pkg : ""));
                } catch (Exception e) { Log.w(TAG, "startActivity fail", e); return CpRes.fail("no se pudo lanzar la app: " + e); }
            }
            case ACTION_OPEN_URI: {
                try {
                    String uri = intent.getStringExtra("uri");
                    String mime = intent.getStringExtra("mime");
                    if (uri == null || uri.isEmpty()) return CpRes.fail("URL vacía");
                    int dispId = disp(intent);
                    Intent open = new Intent(Intent.ACTION_VIEW);
                    open.setData(android.net.Uri.parse(uri));
                    if (mime != null && !mime.isEmpty()) open.setType(mime);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    // El selector de apps ("Abrir con") es una activity del
                    // SISTEMA y se abre siempre en el display 0: ignora
                    // setLaunchDisplayId. Si la URL resuelve a una sola app,
                    // la apuntamos directamente y así sí podemos dirigirla.
                    boolean ambiguous = true;
                    String pkg = intent.getStringExtra("package");
                    if (pkg != null && !pkg.isEmpty()) {
                        // navegador forzado (amctl openurl <url> <paquete>)
                        try {
                            Intent probe = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
                            probe.setPackage(pkg);
                            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(probe, 0);
                            if (ri != null && ri.activityInfo != null) {
                                open.setComponent(new android.content.ComponentName(
                                    ri.activityInfo.packageName, ri.activityInfo.name));
                                ambiguous = false;
                            } else {
                                return CpRes.fail("el paquete '" + pkg + "' no abre esa URL");
                            }
                        } catch (Exception e) {
                            return CpRes.fail("no se pudo forzar '" + pkg + "': " + e.getMessage());
                        }
                    } else try {
                        android.content.pm.ResolveInfo ri =
                            getPackageManager().resolveActivity(open, 0);
                        if (ri != null && ri.activityInfo != null
                                && !"android".equals(ri.activityInfo.packageName)) {
                            open.setComponent(new android.content.ComponentName(
                                ri.activityInfo.packageName, ri.activityInfo.name));
                            ambiguous = false;
                        }
                    } catch (Exception ignored) { }

                    String warn = null;
                    if (dispId != android.view.Display.DEFAULT_DISPLAY && ambiguous
                            && Build.VERSION.SDK_INT >= 26) {
                        warn = "display=" + dispId + ": esa URL tiene varias apps y el selector del "
                             + "sistema se abrirá en la pantalla 0 (no admite display). "
                             + "Usa amctl -d " + dispId + " launch <paquete>";
                    }

                    if (dispId != android.view.Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= 26) {
                        startActivity(open, android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(dispId).toBundle());
                    } else {
                        startActivity(open);
                    }
                    if (warn != null) {
                        return CpRes.fail(warn).withFile(FILE_ERROR);
                    }
                    return CpRes.ok("url abierta");
                } catch (Exception e) { Log.w(TAG, "openUri fail", e); return CpRes.fail("no se pudo abrir la URL: " + e); }
            }
            case ACTION_DISPLAY:
                return handleDisplayOp(intent);
            case ACTION_DISPLAY_BACK: {
                int dispId = disp(intent);
                if (dispId == android.view.Display.DEFAULT_DISPLAY) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return CpRes.ok("back");
                }
                // GLOBAL_ACTION_BACK siempre es del display 0: buscamos el botón
                // de retroceso de la app en la pantalla virtual.
                AccessibilityNodeInfo root = rootFor(dispId);
                AccessibilityNodeInfo btn = findBackNode(root, 0);
                if (btn != null) {
                    boolean ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "back en display " + dispId + " → " + ok);
                    return ok ? CpRes.ok("back en display " + dispId) : CpRes.fail("el botón atrás no aceptó el click");
                }
                Log.w(TAG, "sin botón atrás en display " + dispId);
                return CpRes.fail("display=" + dispId
                    + ": la app no tiene botón de retroceso reconocible en ese display")
                    .withFile(FILE_ERROR);
            }
            default:
                return CpRes.fail("acción desconocida: " + action);
        }
    }

    /* -------------------------------------------------------------- */
    /*                 Snapshot, refs y capturas (rápido)               */
    /* -------------------------------------------------------------- */

    /** Paquete de la ventana activa de un display (para las cabeceras). */
    private String pkgOf(int displayId) {
        // si el árbol ya está recorrido, se reutiliza su paquete: pedir la
        // raíz otra vez es otra llamada al framework de accesibilidad.
        TreeCache cache = treeCache;
        if (cache != null && cache.display == displayId && cache.version == uiVersion
                && !cache.pkg.isEmpty()) {
            return cache.pkg;
        }
        AccessibilityNodeInfo root = rootFor(displayId);
        if (root == null) return "";
        CharSequence p = root.getPackageName();
        return p == null ? "" : p.toString();
    }

    /**
     * Recorre las ventanas del display y guarda los nodos con contenido.
     * Con presupuesto de tiempo: una pantalla patológica no puede dejar el
     * receptor de broadcast colgado (y por tanto matar la app).
     *
     * CACHE: recorrer el árbol son miles de llamadas al framework de
     * accesibilidad (cada getter es un IPC). Si la UI no ha cambiado desde
     * el último recorrido, se reutiliza el árbol anterior en vez de volver
     * a pagarlo. Con esto, el "launch -> waitfor -> snap" típico solo
     * recorre el árbol UNA vez (el waitfor lo deja cacheado y el snap lo
     * reutiliza), y el sondeo del waitfor es casi gratis.
     */
    private void collect(int displayId, List<Snap.Node> out, long deadline) {
        collect(displayId, out, deadline, false);
    }

    /**
     * Igual que {@link #collect}, pero con `force` se salta la caché.
     *
     * uiSignature() lo necesita: si la app no emite eventos de accesibilidad
     * (DocumentsUI es el caso clásico), uiVersion no cambia y la caché
     * devolvería el mismo árbol, con lo que un tap que SÍ funcionó se
     * reportaría como "sin cambios". Para verificar hay que mirar de verdad.
     */
    private void collect(int displayId, List<Snap.Node> out, long deadline, boolean force) {
        TreeCache cache = treeCache;
        if (!force && cache != null && cache.display == displayId && cache.version == uiVersion) {
            out.addAll(cache.nodes);
            return;
        }
        int v0 = uiVersion;      // si un evento salta durante el recorrido, el
                                // árbol queda marcado como viejo y se rehace
        AccessibilityNodeInfo root = rootFor(displayId);
        if (root == null) return;
        List<Snap.Node> fresh = new ArrayList<>();
        collectNode(root, 0, MAX_DEPTH, "", "", fresh, deadline);
        List<AccessibilityWindowInfo> wins = windowsOf(displayId);
        String rootPkg = "";
        if (wins != null) {
            Rect mainRect = new Rect();
            root.getBoundsInScreen(mainRect);
            String mainPkg = String.valueOf(root.getPackageName());
            rootPkg = mainPkg;
            for (int i = 0; i < wins.size(); i++) {
                if (System.currentTimeMillis() > deadline) break;
                AccessibilityWindowInfo aw = wins.get(i);
                if (aw == null) continue;
                if (aw.getDisplayId() != displayId) continue;   // ventanas de otra pantalla
                AccessibilityNodeInfo wroot = null;
                try { wroot = aw.getRoot(); } catch (Exception e) { continue; }
                if (wroot == null) continue;
                Rect wr = new Rect();
                wroot.getBoundsInScreen(wr);
                String wpkg = String.valueOf(wroot.getPackageName());
                if (wr.equals(mainRect) && wpkg.equals(mainPkg)) continue;
                String kind = windowTag(aw.getType());
                if (kind == null) continue;   // TYPE_APPLICATION duplicada: omitir
                collectNode(wroot, 0, MAX_DEPTH, kind, wpkg, fresh, deadline);
            }
        } else {
            rootPkg = String.valueOf(root.getPackageName());
        }
        out.addAll(fresh);
        TreeCache tc = new TreeCache();
        tc.display = displayId;
        tc.version = v0;
        tc.pkg = rootPkg;
        tc.nodes = fresh;
        treeCache = tc;
    }

    private void collectNode(AccessibilityNodeInfo node, int depth, int maxDepth,
                             String win, String winPkg, List<Snap.Node> out, long deadline) {
        if (depth > maxDepth || node == null) return;
        if (out.size() >= MAX_NODES) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        String vid = node.getViewIdResourceName();
        int cc = node.getChildCount();
        boolean meaningful = (txt != null && txt.length() > 0)
                || (desc != null && desc.length() > 0)
                || vid != null
                || (cc == 0 && !r.isEmpty());
        if (meaningful) {
            Snap.Node n = new Snap.Node();
            n.depth = depth;
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            n.cls = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
            n.vid = vid == null ? "" : vid;
            n.txt = txt == null ? "" : txt.toString();
            n.desc = desc == null ? "" : desc.toString();
            n.l = r.left; n.t = r.top; n.r = r.right; n.b = r.bottom;
            n.clk = node.isClickable();
            n.scr = node.isScrollable();
            n.edt = node.isEditable();
            n.chk = node.isCheckable();
            n.foc = node.isFocused();
            n.win = win;
            n.winPkg = winPkg;
            out.add(n);
        }
        for (int i = 0; i < cc; i++) {
            if ((i & 0x1f) == 0 && System.currentTimeMillis() > deadline) return;
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectNode(child, depth + 1, maxDepth, win, winPkg, out, deadline);
            try { child.recycle(); } catch (Exception ignored) { }
        }
    }

    /**
     * Toca la referencia @pN del último snapshot.
     *
     * Antes solo se re-resolvía cuando `uiDirty` estaba puesto, y varias apps
     * (DocumentsUI es el caso típico) no emiten eventos al colapsar el
     * toolbar: la ref seguía "limpia" con coordenadas viejas y el tap caía
     * una fila más abajo. Ahora, por defecto, se vuelve a resolver el
     * elemento contra el árbol VIVO antes de tocar; `fast` recupera el atajo.
     *
     * Si el elemento ya no existe, se falla en vez de tocar a ciegas.
     */
    private CpRes tapRef(String ref, int displayId, long settleMs, boolean fast, long verifyMs) {
        if (ref.startsWith("@")) ref = ref.substring(1);
        if (!ref.startsWith("p")) ref = "p" + ref;
        RefCache cache = refCache;
        if (cache == null || cache.picks.isEmpty()) {
            return CpRes.fail("no hay snapshot: ejecuta primero 'amctl snap'");
        }
        if (cache.display != displayId) {
            return CpRes.fail("la ref @" + ref + " es de otro display (usa -d " + cache.display + ")");
        }
        int idx = -1;
        for (int i = 0; i < cache.picks.size(); i++) {
            if (cache.picks.get(i).ref.equals(ref)) { idx = i; break; }
        }
        if (idx < 0) {
            return CpRes.fail("ref @" + ref + " no existe — ejecuta: amctl snap");
        }
        Snap.Pick pick = cache.picks.get(idx);

        int x = pick.node.cx();
        int y = pick.node.cy();
        String note = "";

        if (!fast) {
            List<Snap.Node> nodes = new ArrayList<>();
            collect(displayId, nodes, System.currentTimeMillis() + DUMP_BUDGET_MS);
            if (nodes.isEmpty()) {
                return CpRes.fail("ref @" + ref + " obsoleta: la pantalla está vacía — re-snapshot");
            }
            List<Snap.Pick> fresh = Snap.select(nodes, cache.allMode, null);
            Snap.Pick best = matchPick(pick, fresh);
            if (best == null) {
                return CpRes.fail("ref @" + ref + " ya no está en pantalla (la UI cambió)"
                        + " — re-snapshot antes de tocar");
            }
            int nx = best.node.cx();
            int ny = best.node.cy();
            if (nx != x || ny != y) {
                note = " [re-resuelto " + (nx - x >= 0 ? "+" : "") + (nx - x)
                        + "," + (ny - y >= 0 ? "+" : "") + (ny - y) + "]";
            }
            x = nx;
            y = ny;
        }

        String before = verifyMs > 0 ? uiSignature(displayId) : null;
        run(makeTap(x, y, 80L, displayId), "tapref");
        // El "settle" se duerme AQUI, no en el cliente: si el CLI tuviera que
        // dormir, pagaría un exec de 'sleep' (~150 ms en un teléfono).
        if (settleMs > 0) nap(Math.min(settleMs, 3000));

        if (verifyMs > 0) {
            long t0 = System.currentTimeMillis();
            long limit = Math.max(200, Math.min(verifyMs, 6000));
            boolean changed = false;
            while (System.currentTimeMillis() - t0 < limit) {
                nap(60);
                if (!before.equals(uiSignature(displayId))) { changed = true; break; }
            }
            note += changed
                    ? " [cambió en " + (System.currentTimeMillis() - t0) + " ms]"
                    : " [sin cambios — tap probablemente perdido]";
        }

        return CpRes.ok("tap @" + ref + " -> (" + x + "," + y + ")" + note
                + (settleMs > 0 ? " [settled " + settleMs + "]" : ""));
    }

    /**
     * Firma barata de la pantalla: textos + posiciones + ids.
     *
     * Sirve para dos cosas: saber si un tap hizo algo, y saber si un scroll
     * movió de verdad la lista (fin de lista). Incluye `top`/`left`, así que
     * detecta el caso que rompía los taps: mismos textos, otras coordenadas
     * (toolbar que colapsa, banner que aparece, lista que se desplaza).
     */
    private String uiSignature(int displayId) {
        List<Snap.Node> nodes = new ArrayList<>();
        collect(displayId, nodes, System.currentTimeMillis() + 1500, true);
        long h = 1125899906842597L;
        for (int i = 0; i < nodes.size(); i++) {
            Snap.Node n = nodes.get(i);
            h = 31 * h + n.depth;
            h = 31 * h + (n.txt.isEmpty() ? n.desc : n.txt).hashCode();
            h = 31 * h + n.vid.hashCode();
            h = 31 * h + n.l * 7L + n.t;
        }
        return pkgOf(displayId) + "/" + nodes.size() + "/" + Long.toHexString(h);
    }

    /**
     * Desplaza la lista hasta que aparezca `needle` (o hasta el final).
     *
     * Hace falta porque las listas de Android son virtualizadas: el elemento
     * que buscas muchas veces no existe en el árbol hasta que la lista lo
     * crea. Buscar una sola vez y rendirse (lo que hacía antes) fallaba con
     * cualquier lista larga.
     */
    private CpRes scrollUntil(String needle, int displayId, int maxScrolls) {
        int max = Math.max(1, Math.min(maxScrolls, 40));
        for (int i = 0; i <= max; i++) {
            List<Snap.Node> nodes = new ArrayList<>();
            collect(displayId, nodes, System.currentTimeMillis() + DUMP_BUDGET_MS, true);
            if (nodes.isEmpty()) return CpRes.fail("no hay ventana activa");
            Snap.Index idx = Snap.index(nodes);
            int hit = Snap.findMatch(idx, needle);
            if (hit >= 0) {
                Snap.Node nd = nodes.get(hit);
                return CpRes.ok("scroll hasta '" + needle + "' -> (" + nd.cx() + "," + nd.cy()
                        + ") tras " + i + " scroll(s)");
            }
            String sig = sigOf(nodes, displayId);
            // El punto importa: scrollSemantic(-1,-1) se queda con el nodo
            // desplazable de mayor área, que en pantallas con cabecera
            // (MIUI) no es la lista. Con el centro acierta, igual que
            // 'amctl scroll down'.
            android.util.DisplayMetrics dm = metricsOf(displayId);
            int cx = dm.widthPixels / 2;
            int cy = (int)(dm.heightPixels * 0.5f);
            if (!scrollSemantic(cx, cy, true, displayId)) {
                // Muchas apps no marcan su lista como scrollable en el árbol:
                // sin este respaldo, scrollto se rendía en la primera pantalla.
                run(makeScroll(cx, (int)(dm.heightPixels * 0.6f), -0.45f, displayId), "scrollTo");
            }
            // Esperar a que la lista REDIBUJE, no a un tiempo fijo: comparar
            // la firma 180 ms después daba "fin de lista" en la primera
            // pantalla porque el RecyclerView aún no había creado las filas.
            boolean moved = false;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 1200) {
                nap(80);
                List<Snap.Node> chk = new ArrayList<>();
                collect(displayId, chk, System.currentTimeMillis() + 1500, true);
                if (!sigOf(chk, displayId).equals(sig)) { moved = true; break; }
            }
            if (!moved) {
                return CpRes.fail("no se encontró '" + needle + "': fin de la lista tras "
                        + i + " scroll(s)");
            }
        }
        return CpRes.fail("no se encontró '" + needle + "' tras " + max + " scroll(s)");
    }

    /**
     * Firma del CONTENIDO (textos + ids), sin posiciones.
     *
     * Para saber si una lista se movió no sirven las coordenadas: el toolbar
     * que colapsa cambia los bounds de todo sin que la lista avance, y eso
     * hacía que scrollto diese 25 vueltas sin moverse. Lo que cambia al
     * desplazar son los textos visibles.
     */
    private String sigOf(List<Snap.Node> nodes, int displayId) {
        long h = 1125899906842597L;
        int n = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Snap.Node nd = nodes.get(i);
            String t = nd.txt.isEmpty() ? nd.desc : nd.txt;
            if (t.isEmpty() && nd.vid.isEmpty()) continue;
            h = 31 * h + t.hashCode();
            h = 31 * h + nd.vid.hashCode();
            n++;
        }
        return pkgOf(displayId) + "/" + n + "/" + Long.toHexString(h);
    }

    /** Busca en los elementos frescos el equivalente al de la ref. */
    private Snap.Pick matchPick(Snap.Pick old, List<Snap.Pick> fresh) {
        Snap.Pick byLabel = null;
        Snap.Pick byId = null;
        Snap.Pick byPrefix = null;
        for (Snap.Pick p : fresh) {
            boolean sameLabel = p.label.equals(old.label);
            boolean sameId = !old.node.vid.isEmpty() && old.node.vid.equals(p.node.vid);
            if (sameLabel && sameId) return p;
            if (sameId && byId == null) byId = p;
            if (sameLabel && byLabel == null) byLabel = p;
            // Filas con hora o contador ("2. Actividad .pdf · 12:03") cambian
            // de etiqueta entre dumps: el prefijo sigue identificándolas.
            if (byPrefix == null && old.label.length() >= 8
                    && p.label.startsWith(old.label.substring(0, 8))) {
                byPrefix = p;
            }
        }
        if (byId != null) return byId;
        if (byLabel != null) return byLabel;
        if (byPrefix != null) return byPrefix;
        // Último recurso: el elemento más cercano al punto anterior. Solo si
        // está a menos de media pantalla, para no tocar "lo que sea".
        Snap.Pick near = null;
        long bestD = Long.MAX_VALUE;
        for (Snap.Pick p : fresh) {
            int dx = p.node.cx() - old.node.cx();
            int dy = p.node.cy() - old.node.cy();
            long dist = (long) dx * dx + (long) dy * dy;
            if (dist < bestD) { bestD = dist; near = p; }
        }
        if (near != null && bestD <= 400L * 400L) return near;
        return null;
    }

    /**
     * Captura la pantalla en JPEG. El PNG a calidad máxima tardaba segundos en
     * comprimirse y ocupaba cientos de KB; el JPEG es ~10x más rápido de
     * producir y suficiente para leer la pantalla.
     */
    private byte[] captureImage(int displayId, float scale, int quality, boolean png) {
        if (Build.VERSION.SDK_INT < 33) {
            Log.w(TAG, "takeScreenshot requires API 33+");
            return null;
        }
        final Object lock = new Object();
        final android.graphics.Bitmap[] holder = new android.graphics.Bitmap[1];
        final boolean[] done = new boolean[1];
        try {
            takeScreenshot(displayId, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        // envolver el buffer es barato; la copia y el JPEG
                        // se hacen en el hilo de trabajo, no en el principal.
                        holder[0] = android.graphics.Bitmap.wrapHardwareBuffer(
                            r.getHardwareBuffer(), r.getColorSpace());
                    } catch (Exception e) { Log.w(TAG, "wrapHB failed", e); }
                    try { r.getHardwareBuffer().close(); } catch (Exception ignored) { }
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
                @Override public void onFailure(int errCode) {
                    Log.e(TAG, "screenshot fail: " + errCode + " (display " + displayId + ")");
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "takeScreenshot threw", e);
            return null;
        }
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 8000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { lock.wait(50); } catch (InterruptedException e) { break; }
            }
        }
        android.graphics.Bitmap hw = holder[0];
        if (hw == null) return null;
        try {
            android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            if (sw == null) return null;
            android.graphics.Bitmap out = sw;
            if (scale > 0.01f && scale < 0.99f) {
                int nw = Math.max(1, Math.round(sw.getWidth() * scale));
                int nh = Math.max(1, Math.round(sw.getHeight() * scale));
                out = android.graphics.Bitmap.createScaledBitmap(sw, nw, nh, true);
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            out.compress(png ? android.graphics.Bitmap.CompressFormat.PNG
                             : android.graphics.Bitmap.CompressFormat.JPEG, quality, baos);
            if (out != sw) out.recycle();
            sw.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "screenshot encode fail", e);
            return null;
        } finally {
            try { hw.recycle(); } catch (Exception ignored) { }
        }
    }

    /** Dimensiones de una imagen JPEG (para el escalado de tapshot). */
    private String imageSize(byte[] jpg, boolean png) {
        if (png) return "png";
        try {
            int i = 2;
            while (i < jpg.length - 9) {
                if ((jpg[i] & 0xFF) != 0xFF) { i++; continue; }
                int marker = jpg[i + 1] & 0xFF;
                int len = ((jpg[i + 2] & 0xFF) << 8) | (jpg[i + 3] & 0xFF);
                boolean sof = (marker >= 0xC0 && marker <= 0xCF
                        && marker != 0xC4 && marker != 0xC8 && marker != 0xCC);
                if (sof) {
                    int h = ((jpg[i + 5] & 0xFF) << 8) | (jpg[i + 6] & 0xFF);
                    int w = ((jpg[i + 7] & 0xFF) << 8) | (jpg[i + 8] & 0xFF);
                    return w + "x" + h;
                }
                i += 2 + len;
            }
        } catch (Exception e) { /* sin dimensiones */ }
        return "?";
    }

    /* -------------------------------------------------------------- */
    /*                    Resolver nombre de app                     */
    /* -------------------------------------------------------------- */

    /**
     * Convierte un nombre en un paquete: "whatsapp" -> "com.whatsapp".
     *
     * Busca primero coincidencia exacta del paquete, luego por etiqueta
     * (el nombre que ve el usuario: "WhatsApp") y por último por
     * subcadena del paquete. Si hay varias, las devuelve todas para que el
     * agente elija.
     */
    private CpRes resolvePackage(String query) {
        String q = query.trim();
        if (q.isEmpty()) return CpRes.fail("consulta vacía");
        String ql = q.toLowerCase();
        android.content.pm.PackageManager pm = getPackageManager();
        if (pm == null) return CpRes.fail("no hay PackageManager");
        List<android.content.pm.ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Exception e) {
            return CpRes.fail("no se pudieron listar las apps: " + e);
        }
        // 1) paquete exacto
        for (android.content.pm.ApplicationInfo ai : apps) {
            if (ai.packageName.equals(q)) return CpRes.ok(ai.packageName);
        }
        // 2) etiqueta exacta (case-insensitive)
        List<String> byLabel = new ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : apps) {
            String label = labelOf(pm, ai);
            if (label != null && label.toLowerCase().equals(ql)) byLabel.add(ai.packageName);
        }
        if (byLabel.size() == 1) return CpRes.ok(byLabel.get(0));
        // 3) subcadena de la etiqueta
        List<String> fuzzy = new ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : apps) {
            String label = labelOf(pm, ai);
            if (label != null && label.toLowerCase().contains(ql)) fuzzy.add(ai.packageName);
        }
        if (fuzzy.size() == 1) return CpRes.ok(fuzzy.get(0));
        if (fuzzy.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (String p : fuzzy) { if (sb.length() > 0) sb.append(' '); sb.append(p); }
            return CpRes.fail("'" + q + "' coincide con varias: " + sb);
        }
        // 4) subcadena del paquete
        List<String> byPkg = new ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : apps) {
            if (ai.packageName.toLowerCase().contains(ql)) byPkg.add(ai.packageName);
        }
        if (byPkg.size() == 1) return CpRes.ok(byPkg.get(0));
        if (byPkg.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (String p : byPkg) { if (sb.length() > 0) sb.append(' '); sb.append(p); }
            return CpRes.fail("'" + q + "' coincide con varios paquetes: " + sb);
        }
        return CpRes.fail("no hay ninguna app que sea '" + q + "'");
    }

    /** Etiqueta visible de una app (o null si no se puede leer). */
    private String labelOf(android.content.pm.PackageManager pm,
                           android.content.pm.ApplicationInfo ai) {
        try {
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) {
                String s = cs.toString().trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /* -------------------------------------------------------------- */
    /*                    Multi-display (pantalla virtual)             */
    /* -------------------------------------------------------------- */

    /** Display de la orden (0 = pantalla principal del teléfono). */
    private int disp(Intent intent) {
        return intent.getIntExtra("display", android.view.Display.DEFAULT_DISPLAY);
    }

    /** Métricas de un display (para scroll/pinch y para reports). */
    private android.util.DisplayMetrics metricsOf(int displayId) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        android.hardware.display.DisplayManager dman =
            (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        try {
            android.view.Display d = dman == null ? null : dman.getDisplay(displayId);
            if (d != null) { d.getRealMetrics(dm); return dm; }
        } catch (Exception e) { Log.w(TAG, "metricsOf d" + displayId, e); }
        dm.setTo(getResources().getDisplayMetrics());
        return dm;
    }

    /** Árbol raíz de un display cualquiera (0 = el de siempre). */
    private AccessibilityNodeInfo rootFor(int displayId) {
        if (displayId == android.view.Display.DEFAULT_DISPLAY) {
            // Con una pantalla virtual encima, getRootInActiveWindow() devuelve la
            // ventana del overlay (está encima de la real). Para el display 0
            // devolvemos la ventana que REALMENTE es de esta pantalla.
            if (Build.VERSION.SDK_INT >= 30) {
                AccessibilityNodeInfo own = topmostAppRoot(displayId);
                if (own != null) return own;
            }
            return getRootInActiveWindow();
        }
        if (Build.VERSION.SDK_INT < 30) return null;
        // CANDADO DE AISLAMIENTO: si ese display no tiene ventanas propias, la
        // respuesta honesta es "vacío". getRootInActiveWindow(displayId) NO sirve
        // de atajo: con un display virtual vacío el framework devuelve la
        // ventana activa GLOBAL (la de la pantalla 0), que es justo la fuga que
        // este display debe impedir.
        List<AccessibilityWindowInfo> wins = windowsOf(displayId);
        if (wins == null || wins.isEmpty()) return null;
        // primero las ventanas que SÍ pertenecen a este display: si no hay
        // ninguna, getRootInActiveWindow() puede devolver una de la pantalla 0.
        AccessibilityNodeInfo own = topmostAppRoot(displayId);
        if (own != null) return own;
        try {
            return getRootInActiveWindow(displayId);
        } catch (IllegalArgumentException e) {
            // "There can be no more than one descendant prefetching strategy":
            // el framework no admite dos prefetches de accesibilidad a la vez y
            // el conflicto es transitorio. Un reintento lo resuelve casi siempre.
            Log.w(TAG, "prefetch de accesibilidad ocupado, reintento d" + displayId);
            try { Thread.sleep(80); } catch (InterruptedException ignored) { }
            try {
                return getRootInActiveWindow(displayId);
            } catch (Exception e2) {
                Log.w(TAG, "getRootInActiveWindow(" + displayId + ") también falló", e2);
                return null;
            }
        } catch (Exception e) { Log.w(TAG, "getRootInActiveWindow(" + displayId + ")", e); return null; }
    }

    /** ¿Existe ese display ahora mismo? (para no mentir en los informes) */
    private boolean displayExists(int displayId) {
        try {
            android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            return dm != null && dm.getDisplay(displayId) != null;
        } catch (Exception e) { return false; }
    }

    /** Ventana de aplicación más alta que pertenece realmente a ese display. */
    private AccessibilityNodeInfo topmostAppRoot(int displayId) {
        List<AccessibilityWindowInfo> wins = windowsOf(displayId);
        if (wins == null) return null;
        for (int i = wins.size() - 1; i >= 0; i--) {
            AccessibilityWindowInfo aw = wins.get(i);
            if (aw == null) continue;
            if (aw.getDisplayId() != displayId) continue;
            if (aw.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            try {
                AccessibilityNodeInfo r = aw.getRoot();
                if (r != null) return r;
            } catch (Exception e) { Log.w(TAG, "topmostAppRoot d" + displayId, e); }
        }
        return null;
    }

    /** Ventanas de accesibilidad de un display (API 30+). */
    private List<AccessibilityWindowInfo> windowsOf(int displayId) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            return all == null ? null : all.get(displayId);
        } catch (Exception e) { Log.w(TAG, "getWindowsOnAllDisplays", e); return null; }
    }

    /** Campo de texto con foco, buscando en el display indicado. */
    private AccessibilityNodeInfo focusIn(int displayId) {
        if (Build.VERSION.SDK_INT >= 30) {
            // solo entre las ventanas que pertenecen de verdad a este display:
            // si no, escribiría en el campo de la pantalla virtual.
            List<AccessibilityWindowInfo> wins = windowsOf(displayId);
            for (int i = wins != null ? wins.size() - 1 : -1; i >= 0; i--) {
                AccessibilityWindowInfo aw = wins.get(i);
                if (aw == null || aw.getDisplayId() != displayId) continue;
                try {
                    AccessibilityNodeInfo r = aw.getRoot();
                    if (r == null) continue;
                    AccessibilityNodeInfo f = findEditableFocus(r, 0);
                    if (f != null) return f;
                } catch (Exception e) { Log.w(TAG, "focusIn d" + displayId, e); }
            }
        }
        if (displayId == android.view.Display.DEFAULT_DISPLAY) {
            return findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        return findEditableFocus(rootFor(displayId), 0);
    }

    private AccessibilityNodeInfo findEditableFocus(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 15) return null;
        if (node.isFocused() && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findEditableFocus(child, depth + 1);
            if (found != null) return found;
            child.recycle();
        }
        return null;
    }

    /** Botón "atrás" de una app en el display indicado (el global BACK es del display 0). */
    private AccessibilityNodeInfo findBackNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return null;
        if (node.isClickable() || node.isVisibleToUser()) {
            CharSequence cs = node.getText();
            CharSequence cd = node.getContentDescription();
            String hay = ((cs == null ? "" : cs) + " " + (cd == null ? "" : cd)).toLowerCase();
            if (hay.contains("back") || hay.contains("atrás") || hay.contains("atras")
                    || hay.contains("regresar") || hay.contains("navigate up")
                    || hay.contains("volver")) {
                if (node.isClickable()) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findBackNode(child, depth + 1);
            if (found != null) return found;
            child.recycle();
        }
        return null;
    }

    /* -------------------------------------------------------------- */
    /*                    Pantalla virtual (overlay)                    */
    /* -------------------------------------------------------------- */

    private boolean canWriteSettings() {
        return getPackageManager().checkPermission("android.permission.WRITE_SECURE_SETTINGS",
                getPackageName()) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Activa el propio servicio de accesibilidad escribiendo el ajuste seguro.
     *
     * Android desactiva el servicio de accesibilidad cada vez que se actualiza
     * la app, y normally eso obliga a ir a Ajustes a tocar el interruptor. Si
     * la app tiene WRITE_SECURE_SETTINGS (se concede una vez por adb), puede
     * restaurarse sola. No hace nada más: solo se activa a sí misma.
     */
    static CpRes enableSelf(Context ctx) {
        try {
            if (ctx.getPackageManager().checkPermission("android.permission.WRITE_SECURE_SETTINGS",
                    ctx.getPackageName()) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return CpRes.fail("no puedo activarme solo: falta WRITE_SECURE_SETTINGS. "
                    + "Concede una vez con: adb shell pm grant com.accessdroid.termux "
                    + "android.permission.WRITE_SECURE_SETTINGS — o actívalo en "
                    + "Ajustes → Accesibilidad → CostPhone");
            }
            String comp = ctx.getPackageName() + "/" + AccessibilityServiceImpl.class.getName();
            String cur = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder sb = new StringBuilder();
            if (cur != null && !cur.isEmpty()) {
                for (String s : cur.split(":")) {
                    String t = s.trim();
                    if (t.isEmpty() || t.equalsIgnoreCase(comp)) continue;
                    if (sb.length() > 0) sb.append(":");
                    sb.append(t);
                }
            }
            if (sb.length() > 0) sb.append(":");
            sb.append(comp);
            android.provider.Settings.Secure.putString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
            android.provider.Settings.Secure.putInt(ctx.getContentResolver(),
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return CpRes.ok("servicio activado (tarda unos segundos en conectar)");
        } catch (Exception e) {
            return CpRes.fail("no se pudo activar: " + e);
        }
    }

    /** Reporta todos los displays (id, tamaño, dpi, ventanas) como texto. */
    private String buildDisplaysReport(String errorLine) {
        List<String> out = new ArrayList<>();
        if (errorLine != null) out.add("error=" + errorLine);

        android.hardware.display.DisplayManager dman =
            (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        if (dman != null) {
            try { for (android.view.Display d : dman.getDisplays()) ids.add(d.getDisplayId()); }
            catch (Exception e) { Log.w(TAG, "getDisplays", e); }
        }
        // los displays que ve el servicio de accesibilidad también cuentan
        for (int i = 0; i < windowsAllCount(); i++) {
            try { ids.add(allDisplays().keyAt(i)); } catch (Exception e) { break; }
        }

        for (int id : ids) {
            android.view.Display d = dman == null ? null : dman.getDisplay(id);
            android.util.DisplayMetrics dm = metricsOf(id);
            int nwins = 0;
            List<AccessibilityWindowInfo> ws = windowsOf(id);
            if (ws != null) nwins = ws.size();
            out.add(String.format("display=%d name=%s size=%dx%d dpi=%d state=%s wins=%d",
                id,
                d == null ? "?" : String.valueOf(d.getName()),
                dm.widthPixels, dm.heightPixels, dm.densityDpi,
                d == null ? "?" : (d.getState() == android.view.Display.STATE_ON ? "on" : "off"),
                nwins));
        }

        String raw = null;
        try { raw = android.provider.Settings.Global.getString(getContentResolver(), SETTING_OVERLAY_DISPLAYS); }
        catch (Exception ignored) { }
        out.add("overlay=" + (raw == null || raw.isEmpty() ? "none" : raw));
        return joinLines(out);
    }

    private android.util.SparseArray<List<AccessibilityWindowInfo>> allDisplays() {
        if (Build.VERSION.SDK_INT < 30) return null;
        try { return getWindowsOnAllDisplays(); } catch (Exception e) { return null; }
    }

    private int windowsAllCount() {
        android.util.SparseArray<List<AccessibilityWindowInfo>> a = allDisplays();
        return a == null ? 0 : a.size();
    }

    private CpRes handleDisplayOp(Intent intent) {
        String op = intent.getStringExtra("op");
        if (op == null || op.isEmpty()) op = "status";
        if ("status".equals(op)) {
            return CpRes.ok("displays").withText(buildDisplaysReport(null)).withFile(FILE_DISPLAYS);
        }

        if (!canWriteSettings()) {
            String msg = "WRITE_SECURE_SETTINGS no concedido a CostPhone. Concede una vez con: "
                + "adb shell pm grant com.accessdroid.termux android.permission.WRITE_SECURE_SETTINGS";
            return CpRes.fail(msg).withText(buildDisplaysReport(msg)).withFile(FILE_DISPLAYS);
        }
        if ("off".equals(op)) {
            // borrar el ajuste apaga todas las pantallas virtuales
            android.provider.Settings.Global.putString(getContentResolver(), SETTING_OVERLAY_DISPLAYS, null);
            Log.i(TAG, "pantalla virtual apagada");
            return CpRes.ok("apagada").withText(buildDisplaysReport(null)).withFile(FILE_DISPLAYS);
        }
        if ("on".equals(op)) {
            int w = intent.getIntExtra("width", 1080);
            int h = intent.getIntExtra("height", 1920);
            int dpi = intent.getIntExtra("density", 320);

            // overlay_display_devices NO es JSON:
            //   [ancho]x[alto]/[dpi][,flag1][,flag2];[display2];...
            if (w < 100 || w > 4096 || h < 100 || h > 4096 || dpi < 120 || dpi > 640) {
                String msg = "fuera de rango (100-4096 px, 120-640 dpi): " + w + "x" + h + "@" + dpi;
                return CpRes.fail(msg).withText(buildDisplaysReport(msg)).withFile(FILE_DISPLAYS);
            }

            // Flags: allowlist estricta. Un flag desconocido hace fallar TODO el
            // ajuste en AOSP (no crea display y no avisa), así que aquí se
            // rechaza antes de escribir y se dice cuál es el malo.
            LinkedHashSet<String> flags = new LinkedHashSet<>();
            boolean mirror = false;
            String raw = intent.getStringExtra("flags");
            if (raw != null) {
                for (String t : raw.split(",")) {
                    String f = t.trim().toLowerCase(java.util.Locale.ROOT);
                    if (f.isEmpty()) continue;
                    if (f.equals("mirror")) { mirror = true; continue; }  // pseudo-flag, no va al ajuste
                    if (!OVERLAY_FLAGS.contains(f)) {
                        String msg = "flag no soportado: '" + f + "'. Válidos: "
                            + String.join(", ", OVERLAY_FLAGS) + " (o 'mirror')";
                        return CpRes.fail(msg).withText(buildDisplaysReport(msg)).withFile(FILE_DISPLAYS);
                    }
                    flags.add(f);
                }
            }

            // SUELO DE SEGURIDAD: own_content_only va siempre salvo 'mirror'
            // explícito. Sin él el overlay ESPEJA la pantalla 0 y el agente
            // acaba leyendo y escribiendo en lo que el usuario está usando.
            if (mirror) {
                flags.remove("own_content_only");
            } else {
                flags.add("own_content_only");
            }

            String value = w + "x" + h + "/" + dpi
                + (flags.isEmpty() ? "" : "," + String.join(",", flags));
            try {
                android.provider.Settings.Global.putString(getContentResolver(), SETTING_OVERLAY_DISPLAYS, value);
                Log.i(TAG, "pantalla virtual pedida: " + value);
                String warn = mirror
                    ? "aviso: modo espejo (sin own_content_only). El overlay muestra d0 y el "
                      + "agente puede escribir en la pantalla del usuario: úsalo solo para mirar."
                    : null;
                return CpRes.ok("pantalla virtual encendida")
                    .withText(buildDisplaysReport(warn)).withFile(FILE_DISPLAYS);
            } catch (Exception e) {
                String msg = "fallo al escribir overlay_display_devices: " + e;
                return CpRes.fail(msg).withText(buildDisplaysReport(msg)).withFile(FILE_DISPLAYS);
            }
        }
        String msg = "op desconocido: " + op;
        return CpRes.fail(msg).withText(buildDisplaysReport(msg)).withFile(FILE_DISPLAYS);
    }

    /* -------------------------------------------------------------- */
    /*                        Gesture builders                        */
    /* -------------------------------------------------------------- */
    private void run(GestureDescription g, String name) {
        dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, name + " ok");
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, name + " cancelled");
            }
        }, null);
    }

    /** GestureDescription dirigido a un display (0 = pantalla principal). */
    private GestureDescription build(List<GestureDescription.StrokeDescription> strokes, int displayId) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (GestureDescription.StrokeDescription s : strokes) b.addStroke(s);
        if (displayId != android.view.Display.DEFAULT_DISPLAY) b.setDisplayId(displayId);
        return b.build();
    }

    private GestureDescription makeTap(long x, long y, long duration, int displayId) {
        Path p = new Path();
        p.moveTo(x, y);
        return build(java.util.Arrays.asList(new GestureDescription.StrokeDescription(p, 0, duration)), displayId);
    }

    private GestureDescription makeSwipe(int x1, int y1, int x2, int y2, long duration, int displayId) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return build(java.util.Arrays.asList(new GestureDescription.StrokeDescription(p, 0, duration)), displayId);
    }

    private GestureDescription makeScroll(int cx, int cy, float fraction, int displayId) {
        // vertical scroll gesture: from center, flick (negative = scroll content down)
        android.util.DisplayMetrics dm = metricsOf(displayId);
        long dy = (long)(dm.heightPixels * fraction);
        // Clamp a la pantalla: un Path con coordenadas negativas lanza
        // "IllegalArgumentException: Path bounds must not be negative".
        long y1 = Math.max(4, Math.min(dm.heightPixels - 4, cy));
        long y2 = Math.max(4, Math.min(dm.heightPixels - 4, cy + dy));
        long x  = Math.max(4, Math.min(dm.widthPixels - 4, cx));
        Path p = new Path();
        p.moveTo(x, y1);
        p.lineTo(x, y2);
        return build(java.util.Arrays.asList(new GestureDescription.StrokeDescription(p, 0, 200)), displayId);
    }

    /**
     * Scroll semántico: pide a la app que desplace su lista (ACTION_SCROLL_*).
     *
     * El gesto rápido (flick) de 200 ms en el centro de la pantalla a veces lo
     * intercepta el sistema (panel de notificaciones) y el scroll acaba en
     * System UI. Con la acción semántica no hay gesto que robar: la app mueve
     * su propio contenido. Si no hay nodo scrolleable, se cae al gesto.
     */
    private boolean scrollSemantic(int x, int y, boolean down, int displayId) {
        AccessibilityNodeInfo root = rootFor(displayId);
        if (root == null) return false;
        List<AccessibilityNodeInfo> scrollables = new ArrayList<>();
        collectScrollables(root, scrollables);
        if (scrollables.isEmpty()) return false;
        // el que contenga el punto pedido; si ninguno, el de mayor área
        AccessibilityNodeInfo pick = null;
        long bestArea = -1;
        for (AccessibilityNodeInfo n : scrollables) {
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            boolean hit = r.contains(x, y);
            long area = (long) r.width() * r.height();
            if (hit) { pick = n; break; }
            if (area > bestArea) { bestArea = area; pick = n; }
        }
        if (pick == null) return false;
        int action = down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                          : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        try {
            return pick.performAction(action);
        } catch (Exception e) {
            return false;
        }
    }

    private void collectScrollables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        try {
            if (node.isScrollable()) out.add(node);
        } catch (Exception e) { /* sigue */ }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectScrollables(child, out);
                child.recycle();
            }
        }
    }

    public void typeText(String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
            Log.d(TAG, "typeText: " + text);
        } else {
            Log.w(TAG, "typeText: no focused input field");
        }
    }

    /* -------------------------------------------------------------- */
    /*             escritura por teclado en pantalla (IME)             */
    /* -------------------------------------------------------------- */

    /**
     * Escribe el texto pulsando las teclas del teclado en pantalla.
     *
     * Los inputs web/React (Biblioteca de Anuncios de Meta, ciertas WebViews)
     * rechazan ACTION_SET_TEXT, pero el teclado en pantalla sí entrega los
     * caracteres: es la vía que funciona en universal.
     */
    private boolean typeWithIme(String text, int displayId) {
        java.util.Map<String, AccessibilityNodeInfo> keys = imeKeys(displayId);
        if (keys.isEmpty()) {
            // Algunas apps (WebView con ALWAYS_HIDDEN) no levantan el teclado
            // al tocar el campo: se fuerza a mostrarlo.
            try {
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.toggleSoftInput(0, 0);
            } catch (Exception ignored) { }
            nap(700);
            keys = imeKeys(displayId);
        }
        if (keys.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') continue;
            AccessibilityNodeInfo key = null;
            if (c == ' ') {
                key = lookup(keys, " ", "espacio", "space");
            } else {
                key = keys.get(String.valueOf(c));
                if (key == null && Character.isUpperCase(c)) {
                    AccessibilityNodeInfo shift = findShiftKey(keys);
                    if (shift != null) {
                        tapNode(shift, displayId);
                        nap(140);
                        keys = imeKeys(displayId);
                        key = keys.get(String.valueOf(c));
                    }
                }
                if (key == null) key = keys.get(String.valueOf(Character.toLowerCase(c)));
            }
            if (key == null) {
                Log.w(TAG, "typeWithIme: no hay tecla para '" + c + "'");
                return false;
            }
            if (!tapNode(key, displayId)) return false;
            nap(90);
        }
        return true;
    }

    /** Mapa etiqueta -> tecla del teclado en pantalla. */
    private java.util.Map<String, AccessibilityNodeInfo> imeKeys(int displayId) {
        java.util.HashMap<String, AccessibilityNodeInfo> map = new java.util.HashMap<>();
        List<AccessibilityWindowInfo> wins = getWindows();
        for (int i = 0; i < wins.size(); i++) {
            AccessibilityWindowInfo w = wins.get(i);
            if (w == null) continue;
            AccessibilityNodeInfo root;
            try { root = w.getRoot(); } catch (Exception e) { continue; }
            if (root == null) continue;
            String cn = String.valueOf(root.getClassName());
            boolean isIme = cn.contains("inputmethodservice")
                    || (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD);
            if (!isIme) continue;
            collectImeKeys(root, map, 0);
        }
        return map;
    }

    private void collectImeKeys(AccessibilityNodeInfo node,
                                java.util.Map<String, AccessibilityNodeInfo> map, int depth) {
        if (node == null || depth > 14) return;
        String cn = String.valueOf(node.getClassName());
        if (cn.contains("KeyboardKey")) {
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) map.put(t.toString(), node);
            CharSequence d = node.getContentDescription();
            if (d != null && d.length() > 0) map.put(d.toString(), node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            collectImeKeys(c, map, depth + 1);
        }
    }

    private AccessibilityNodeInfo findShiftKey(java.util.Map<String, AccessibilityNodeInfo> keys) {
        String[] cand = {"Shift", "⇧", "MAYÚSCULAS", "Mayusculas", "shift"};
        for (int i = 0; i < cand.length; i++) {
            AccessibilityNodeInfo n = keys.get(cand[i]);
            if (n != null) return n;
        }
        for (java.util.Map.Entry<String, AccessibilityNodeInfo> e : keys.entrySet()) {
            if (e.getKey().toLowerCase().contains("shift")) return e.getValue();
        }
        return null;
    }

    private AccessibilityNodeInfo lookup(java.util.Map<String, AccessibilityNodeInfo> keys, String... names) {
        for (int i = 0; i < names.length; i++) {
            AccessibilityNodeInfo n = keys.get(names[i]);
            if (n != null) return n;
        }
        return null;
    }

    /** Toca un nodo: primero ACTION_CLICK y, si no, gesto en su centro. */
    private boolean tapNode(AccessibilityNodeInfo node, int displayId) {
        if (node == null) return false;
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Exception ignored) { }
        Rect b = new Rect();
        try { node.getBoundsInScreen(b); } catch (Exception e) { return false; }
        if (b.width() <= 0 || b.height() <= 0) return false;
        run(makeTap(b.centerX(), b.centerY(), 40, displayId), "imekey");
        return true;
    }

    private void nap(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /**
     * ¿El campo muestra de verdad el texto escrito?
     *
     * Necesario porque ACTION_SET_TEXT puede devolver true sin escribir nada
     * (combobox de categorías, inputs React): el agente se creería que escribió
     * y seguiría con una pantalla que no cambió.
     */
    private boolean textLanded(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        String shown = null;
        try {
            CharSequence t = node.getText();
            if (t != null) shown = t.toString();
            if ((shown == null || shown.isEmpty()) && node.isEditable()) {
                CharSequence d = node.getContentDescription();
                if (d != null) shown = d.toString();
            }
        } catch (Exception e) { return false; }
        if (shown == null) return false;
        String a = shown.trim().toLowerCase();
        String b = text.trim().toLowerCase();
        return a.contains(b) || b.contains(a);
    }

    public void pressBack()  { performGlobalAction(GLOBAL_ACTION_BACK); }
    public void pressHome()  { performGlobalAction(GLOBAL_ACTION_HOME); }
    public void pressRecents() { performGlobalAction(GLOBAL_ACTION_RECENTS); }

    /* -------------------------------------------------------------- */
    /*                      UI-tree / find helpers                    */
    /* -------------------------------------------------------------- */

    private String windowTag(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:        return "ime"; // teclado
            case AccessibilityWindowInfo.TYPE_SYSTEM:              return "sys"; // system overlays
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "ovl"; // toolbars flotantes
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "div";
            case AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY: return "mag";
            default: return null;                                   // TYPE_APPLICATION: duplica la raíz
        }
    }

    private String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        CharSequence cs = node.getText();
        CharSequence desc = node.getContentDescription();
        if ((cs != null && cs.toString().contains(text)) ||
            (desc != null && desc.toString().contains(text))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByText(child, text);
                if (found != null) return found;
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Como findByText, pero eligiendo el nodo MÁS ESPECÍFICO.
     *
     * findByText devuelve el primer match en pre-orden, y ese casi siempre es
     * el contenedor: un overlay de YouTube con el texto de sus hijos
     * concatenados ("Saltar anuncio · Saltar · Patrocinado…"). El clic caía
     * en el padre y no hacía nada. Aquí se puntúa cada coincidencia y gana la
     * más concreta: texto propio exacto, nodo pulsable y más profundo.
     */
    private AccessibilityNodeInfo findBestByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> hits = new ArrayList<>();
        collectTextHits(root, text, hits);
        if (hits.isEmpty()) return null;
        // Prioridad: si hay algún nodo PULSABLE que coincide, se elige entre
        // esos (un TextView con el texto exacto no es pulsable: su ACTION_CLICK
        // se rechaza). Entre los pulsables gana el texto más exacto y el más
        // profundo. Sin pulsables, el de texto más exacto.
        List<AccessibilityNodeInfo> pool = new ArrayList<>();
        for (AccessibilityNodeInfo n : hits) {
            try { if (n.isClickable() && n.isEnabled()) pool.add(n); } catch (Exception e) { }
        }
        if (pool.isEmpty()) pool = hits;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : pool) {
            int score = 0;
            CharSequence own = n.getText();
            String ownTxt = own == null ? "" : own.toString().trim();
            if (ownTxt.equalsIgnoreCase(text)) score += 100;          // texto propio exacto
            else if (!ownTxt.isEmpty() && ownTxt.toLowerCase().contains(text.toLowerCase())) score += 50;
            score += Math.min(20, depthOf(n));                          // más profundo = más concreto
            if (score > bestScore) { bestScore = score; best = n; }
        }
        return best;
    }

    private void collectTextHits(AccessibilityNodeInfo node, String text, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cs = node.getText();
        CharSequence desc = node.getContentDescription();
        if ((cs != null && cs.toString().contains(text)) ||
            (desc != null && desc.toString().contains(text))) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextHits(child, text, out);
                child.recycle();
            }
        }
    }

    /** Toque real en el centro de un nodo (fallback de ACTION_CLICK). */
    private boolean tapCenter(AccessibilityNodeInfo n, int displayId) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            if (r.isEmpty()) return false;
            int cx = r.centerX(), cy = r.centerY();
            run(makeTap(cx, cy, 60, displayId), "tapCenter");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int depthOf(AccessibilityNodeInfo n) {        int d = 0;
        AccessibilityNodeInfo p = n.getParent();
        while (p != null && d < 30) { d++; p = p.getParent(); }
        return d;
    }

    /**
     * Busca un nodo por el id corto que muestra el snapshot ("#skip_ad_button")
     * o por el completo ("pkg:id/skip_ad_button"). Devuelve el más profundo
     * (el botón concreto) y no el contenedor que concatena el texto de sus
     * hijos, que es donde suelen fallar los clics por texto.
     */
    private AccessibilityNodeInfo findByShortId(AccessibilityNodeInfo node, String wanted) {
        if (node == null) return null;
        String w = wanted.toLowerCase();
        int slash = w.lastIndexOf('/');
        String shortId = slash >= 0 ? w.substring(slash + 1) : w;
        AccessibilityNodeInfo hit = matchId(node, shortId);
        if (hit != null) return hit;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByShortId(child, wanted);
                if (found != null) return found;
                child.recycle();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo matchId(AccessibilityNodeInfo node, String shortId) {
        CharSequence vid = node.getViewIdResourceName();
        if (vid != null) {
            String v = vid.toString().toLowerCase();
            int slash = v.lastIndexOf('/');
            String tail = slash >= 0 ? v.substring(slash + 1) : v;
            if (tail.equals(shortId)) return node;
        }
        return null;
    }
}
