package com.accessdroid.termux;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import com.accessdroid.termux.shell.ICostPhoneShell;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Shizuku como backend preferido de CostPhone.
 *
 * Da identidad root/shell para los gestos y teclas que hoy hace el servicio de
 * accesibilidad, que es más lento y se cancela cuando la app pierde el foco.
 * El shell corre en un proceso aparte enlazado con
 * {@link Shizuku#bindUserService} (no con newProcess, que es interno y está
 * terminado de usar en versiones recientes).
 *
 * La regla importante: {@link #runShell} devuelve false en cuanto Shizuku no
 * está disponible, no tiene permiso o el UserService todavía no está enlazado.
 * El llamador cae entonces a accesibilidad. Shizuku es una mejora, nunca un
 * requisito: sin él, CostPhone sigue haciendo exactamente lo que siempre hizo.
 */
public final class ShizukuManager {

    private static final String TAG = "CostPhone";

    /** Shizuku no está corriendo (no instalado, o apagado en este arranque). */
    public static final int UNAVAILABLE = 0;
    /** Shizuku corre, pero CostPhone aún no tiene su permiso. */
    public static final int NO_PERMISSION = 1;
    /** Permiso concedido: se puede enlazar el UserService. */
    public static final int READY = 2;

    private static final ComponentName SHELL_SERVICE = new ComponentName(
            "com.accessdroid.termux", "com.accessdroid.termux.ShizukuShellService");

    /** Margen antes de reintentar un enlace que no llegó a completarse. */
    private static final long BIND_RETRY_MS = 5000L;

    /** Código de petición que usa Shizuku para el permiso. */
    private static final int REQ_PERMISSION = 41;
    /** Intentos de ejecución por comando antes de darlo por fallido. */
    private static final int MAX_EXEC_ATTEMPTS = 3;
    /** Pausa entre intentos. */
    private static final long EXEC_RETRY_DELAY_MS = 200L;
    /** Si un comando de shell no vuelve en esto, se avisa (no se mata). */
    private static final long EXEC_TIMEOUT_MS = 30000L;

    private static ShizukuManager sInstance;

    /** A quién avisar cuando cambia el estado: el panel lo escucha. */
    public interface StateListener { void onStateChanged(int state); }

    private static final List<StateListener> listeners = new ArrayList<>();

    private final Shizuku.UserServiceArgs args =
            new Shizuku.UserServiceArgs(SHELL_SERVICE).daemon(false);

    // Un solo hilo: amctl manda ráfagas de comandos y deben ejecutarse en orden.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile ICostPhoneShell shell;
    private volatile boolean binding;
    private volatile long bindStartedAt;
    /** true cuando alguien pidió el permiso: si Shizuku vuelve, se vuelve a pedir. */
    private static volatile boolean wantsPermission;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            shell = ICostPhoneShell.Stub.asInterface(service);
            binding = false;
            Log.i(TAG, "Shizuku: UserService conectado");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            shell = null;
            Log.w(TAG, "Shizuku: UserService desconectado");
        }
    };

    private final Shizuku.OnBinderDeadListener deadListener = new Shizuku.OnBinderDeadListener() {
        @Override public void onBinderDead() {
            shell = null;
            binding = false;
            Log.w(TAG, "Shizuku: binder muerto (Shizuku se reinició o paró)");
            notifyStateChanged(UNAVAILABLE);   // cae a accesibilidad, sin ruido
        }
    };

    private final Shizuku.OnBinderReceivedListener receivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    // Shizuku arrancó con la app ya abierta.
                    int st = state();
                    if (st == NO_PERMISSION && wantsPermission) {
                        // El usuario ya quiso Shizuku una vez: si vuelve y sigue
                        // sin permiso, se lo volvemos a pedir en vez de dejar
                        // el panel en ámbar para siempre.
                        ConnectionLog.add("Shizuku volvió a estar disponible (sin permiso)");
                        requestPermission(REQ_PERMISSION);
                    } else if (st == READY) {
                        ensureBound();
                    }
                    notifyStateChanged(st);
                }
            };

    /** Suscriptor de cambios de estado (el panel). */
    public static void registerStateListener(StateListener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public static void unregisterStateListener(StateListener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    /** Avisa en el hilo principal: los listeners tocan la UI. */
    private static void notifyStateChanged(final int st) {
        final List<StateListener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        if (copy.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                for (StateListener l : copy) {
                    try { l.onStateChanged(st); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private ShizukuManager() { }

    /** No hace falta un Context: bindUserService localiza la app por el ComponentName. */
    static synchronized ShizukuManager get() {
        if (sInstance == null) {
            sInstance = new ShizukuManager();
            try {
                // Sticky: si el binder ya llegó, dispara el listener al momento.
                Shizuku.addBinderReceivedListenerSticky(sInstance.receivedListener);
                Shizuku.addBinderDeadListener(sInstance.deadListener);
            } catch (Throwable t) {
                Log.w(TAG, "no se pudieron registrar los listeners: " + t);
            }
        }
        return sInstance;
    }

    /** El gestor ya creado, o null. Para soltar recursos sin crearlo. */
    static synchronized ShizukuManager peek() {
        return sInstance;
    }

    /**
     * Estado actual. Es una foto: el binder lo entrega Shizuku de forma
     * asíncrona al arrancar, así que {@link UNAVAILABLE} al principio no
     * significa que Shizuku no esté instalado.
     */
    public static int state() {
        try {
            if (!Shizuku.pingBinder()) return UNAVAILABLE;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return NO_PERMISSION;
            }
            return READY;
        } catch (Throwable t) {
            ConnectionLog.add("Shizuku no disponible: " + t);
            return UNAVAILABLE;
        }
    }

    /** Pide el permiso de Shizuku. El resultado llega al listener del panel. */
    public static boolean requestPermission(int requestCode) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            // Acusamos recibo, para que si Shizuku arranca más tarde y sigue sin
            // permiso se lo pidamos de nuevo en vez de quedarnos en ámbar.
            wantsPermission = true;
            Shizuku.requestPermission(requestCode);
            ConnectionLog.add("Shizuku: permiso pedido");
            return true;
        } catch (Throwable t) {
            ConnectionLog.add("Shizuku: error al pedir permiso: " + t);
            Log.w(TAG, "requestPermission: " + t);
            return false;
        }
    }

    /**
     * Intenta ejecutar una operación tipada por Shizuku.
     *
     * @return true si Shizuku ejecutó la operación; false si no hay que esperar
     *         nada y el llamador debe usar accesibilidad.
     */
    public static boolean runShell(int displayId, String action, Object... args) {
        // Las operaciones de input no saben dirigir un display secundario: en la pantalla virtual
        // (-d N) seguimos con los gestos de accesibilidad, que sí llevan displayId.
        if (displayId != Display.DEFAULT_DISPLAY) return false;
        ShizukuManager m = peek();
        if (m == null) return false;
        return m.execTyped(action, args);
    }

    private boolean execTyped(final String action, final Object[] args) {
        final ICostPhoneShell s = shell;
        if (s == null) {
            ensureBound();
            ConnectionLog.add("Shizuku: no hay binder, usando accesibilidad");
            return false;   // aún no hay binder: accesibilidad, sin esperar
        }
        final String[] result = { null };
        final Object gate = new Object();
        try {
            worker.execute(new Runnable() {
                @Override public void run() {
                    int attempt = 0;
                    try {
                        while (attempt < MAX_EXEC_ATTEMPTS) {
                            try {
                                String resultStr = null;
                                switch (action) {
                                    case "input_tap":
                                        if (args == null || args.length < 3) throw new IllegalArgumentException("input_tap requires 3 args");
                                        // Validar coordenadas no negativas y duración razonable
                                        int x = (int) args[0];
                                        int y = (int) args[1];
                                        long tapDuration = (long) args[2];
                                        if (x < 0 || y < 0 || tapDuration < 0 || tapDuration > 8000) {
                                            throw new IllegalArgumentException("input_tap: invalid coords or duration");
                                        }
                                        resultStr = s.inputTap((int) args[0], (int) args[1], (long) args[2]);
                                        break;
                                    case "input_swipe":
                                        if (args == null || args.length < 5) throw new IllegalArgumentException("input_swipe requires 5 args");
                                        int x1 = (int) args[0];
                                        int y1 = (int) args[1];
                                        int x2 = (int) args[2];
                                        int y2 = (int) args[3];
                                        long swipeDuration = (long) args[4];
                                        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || swipeDuration < 0 || swipeDuration > 8000) {
                                            throw new IllegalArgumentException("input_swipe: invalid coords or duration");
                                        }
                                        resultStr = s.inputSwipe((int) args[0], (int) args[1], (int) args[2], (int) args[3], (long) args[4]);
                                        break;
                                    case "input_keyevent":
                                        if (args == null || args.length < 1) throw new IllegalArgumentException("input_keyevent requires 1 arg");
                                        resultStr = s.inputKeyevent((int) args[0]);
                                        break;
                                    case "screencap":
                                        if (args == null || args.length < 1) throw new IllegalArgumentException("screencap requires 1 arg");
                                        resultStr = s.screencap((String) args[0]);
                                        break;
                                    case "am_start":
                                        if (args == null || args.length < 3) throw new IllegalArgumentException("am_start requires 3 args");
                                        resultStr = s.amStart((String) args[0], (String) args[1], (String) args[2]);
                                        break;
                                    case "global_back":
                                        resultStr = s.globalActionBack();
                                        break;
                                    case "global_home":
                                        resultStr = s.globalActionHome();
                                        break;
                                    case "global_recents":
                                        resultStr = s.globalActionRecents();
                                        break;
                                    case "global_lock":
                                        resultStr = s.globalActionLockScreen();
                                        break;
                                    case "global_screenshot":
                                        resultStr = s.globalActionScreenshot();
                                        break;
                                    case "global_power":
                                        resultStr = s.globalActionPowerDialog();
                                        break;
                                    default:
                                        resultStr = "error: acción desconocida";
                                        Log.w(TAG, "Shizuku: acción desconocida: " + action);
                                        break;
                                }
                                result[0] = resultStr;
                                return;
                            } catch (Throwable t) {
                                attempt++;
                                // El UserService puede haber muerto entre medias.
                                // Reintentamos enlazar una vez más y probamos de
                                // nuevo; si no se puede, caemos a accesibilidad.
                                if (attempt >= MAX_EXEC_ATTEMPTS) {
                                    shell = null;
                                    ConnectionLog.add("Shizuku: exec('" + action + "') falló tras " + attempt + " intentos");
                                    Log.w(TAG, "exec('" + action + "') falló: " + t);
                                    return;
                                }
                                synchronized (gate) { gate.wait(EXEC_RETRY_DELAY_MS); }
                            }
                        }
                    } catch (InterruptedException ignored) {
                        ConnectionLog.add("Shizuku: exec interrumpido");
                    } finally {
                        synchronized (gate) { gate.notifyAll(); }
                    }
                }
            });
            // Esperamos a que vuelva, con tope, para no bloquear el hilo de
            // broadcast de accesibilidad para siempre.
            synchronized (gate) {
                gate.wait(EXEC_TIMEOUT_MS);
            }
            if (result[0] == null) {
                ConnectionLog.add("Shizuku: timeout (" + EXEC_TIMEOUT_MS
                        + "ms) en '" + action + "', usando accesibilidad");
                return false;
            }
            return true;
        } catch (Throwable t) {
            ConnectionLog.add("Shizuku: no se pudo encolar '" + action + "'");
            Log.w(TAG, "no se pudo encolar '" + action + "': " + t);
            return false;
        }
    }

    private static String shortCmd(String cmd) {
        if (cmd == null) return "";
        return cmd.length() > 40 ? cmd.substring(0, 37) + "…" : cmd;
    }

    private void ensureBound() {
        if (shell != null) return;
        if (state() != READY) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // Si el enlace se quedara colgado (el servidor devuelve false y nunca
        // llama a onServiceConnected), reintentamos pasado un rato en vez de
        // quedarnos sin shell para siempre.
        if (binding && now - bindStartedAt < BIND_RETRY_MS) return;
        try {
            binding = true;
            bindStartedAt = now;
            // daemon(false): el proceso del shell muere con CostPhone, así que
            // no dejamos procesos root huérfanos por el móvil.
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            binding = false;
            Log.w(TAG, "bindUserService falló: " + t);
        }
    }

    /** Suelta el UserService. Lo llama el servicio de accesibilidad al morir. */
    static void release() {
        ShizukuManager m;
        synchronized (ShizukuManager.class) {
            m = sInstance;
            sInstance = null;
        }
        if (m == null) return;
        try {
            Shizuku.removeBinderReceivedListener(m.receivedListener);
            Shizuku.removeBinderDeadListener(m.deadListener);
        } catch (Throwable ignored) { }
        try {
            if (m.shell != null) Shizuku.unbindUserService(m.args, m.conn, true);
        } catch (Throwable ignored) { }
        m.shell = null;
        m.worker.shutdown();
    }
}
