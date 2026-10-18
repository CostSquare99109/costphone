package com.accessdroid.termux;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;

import com.accessdroid.termux.shell.ICostPhoneShell;

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

    private static ShizukuManager sInstance;

    private final Shizuku.UserServiceArgs args =
            new Shizuku.UserServiceArgs(SHELL_SERVICE).daemon(false);

    // Un solo hilo: amctl manda ráfagas de comandos y deben ejecutarse en orden.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile ICostPhoneShell shell;
    private volatile boolean binding;
    private volatile long bindStartedAt;

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
        }
    };

    private final Shizuku.OnBinderReceivedListener receivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    // Shizuku arrancó con la app ya abierta: enlazamos si aún no
                    // lo estábamos. ensureBound() sale solo si ya hay shell.
                    ensureBound();
                }
            };

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
            return UNAVAILABLE;
        }
    }

    /** Pide el permiso de Shizuku. El resultado llega al listener del panel. */
    public static boolean requestPermission(int requestCode) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            Shizuku.requestPermission(requestCode);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission: " + t);
            return false;
        }
    }

    /**
     * Intenta ejecutar {@code cmd} por shell.
     *
     * @return true si Shizuku se quedó el comando; false si no hay que esperar
     *         nada y el llamador debe usar accesibilidad.
     */
    public static boolean runShell(int displayId, String cmd) {
        // `input` no sabe dirigir un display secundario: en la pantalla virtual
        // (-d N) seguimos con los gestos de accesibilidad, que sí llevan displayId.
        if (displayId != Display.DEFAULT_DISPLAY) return false;
        ShizukuManager m = peek();
        if (m == null) return false;
        return m.exec(cmd);
    }

    private boolean exec(final String cmd) {
        final ICostPhoneShell s = shell;
        if (s == null) {
            ensureBound();
            return false;   // aún no hay binder: accesibilidad, sin esperar
        }
        try {
            worker.execute(new Runnable() {
                @Override public void run() {
                    try {
                        s.exec(cmd);
                    } catch (Throwable t) {
                        // El UserService murió entre medias. El siguiente comando
                        // reintentará enlazar y, si no puede, irá a accesibilidad.
                        shell = null;
                        Log.w(TAG, "exec('" + cmd + "') falló: " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo encolar '" + cmd + "': " + t);
            return false;
        }
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
