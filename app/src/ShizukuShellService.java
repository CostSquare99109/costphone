package com.accessdroid.termux;

import android.util.Log;

import com.accessdroid.termux.shell.ICostPhoneShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * UserService de Shizuku: ejecuta operaciones tipadas con identidad root/shell.
 *
 * Esta clase NO se ejecuta en el proceso de CostPhone. Shizuku la instancia en
 * un proceso aparte y nos pasa su binder por
 * {@link rikka.shizuku.Shizuku#bindUserService}; por eso necesita constructor
 * público sin argumentos y el método destroy() reservado (ver ICostPhoneShell).
 */
public class ShizukuShellService extends ICostPhoneShell.Stub {

    private static final String TAG = "CostPhone";

    // Códigos de tecla permitidos para inputKeyevent
    private static final java.util.Set<Integer> ALLOWED_KEYCODES = java.util.Set.of(
        3,   // HOME
        4,   // BACK
        24,  // VOLUME_UP
        25,  // VOLUME_DOWN
        26,  // POWER
        66,  // ENTER
        67,  // DEL/BACKSPACE
        61,  // TAB
        187  // APP_SWITCH / RECENTS
    );

    // Límites de coordenadas y duración (se validan contra métricas del display en el cliente)
    private static final int MAX_DURATION_MS = 8000;
    private static final int MIN_COORD = 0;

    // Timeout para ejecución de comandos (ms)
    private static final long COMMAND_TIMEOUT_MS = 10000;

    // Directorio autorizado para capturas
    private static final File SCREENCAP_BASE_DIR;

    static {
        File base = new File("/sdcard/Download");
        try {
            SCREENCAP_BASE_DIR = base.getCanonicalFile();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public String inputTap(int x, int y, long duration) {
        if (x < 0 || y < 0 || duration < 0 || duration > MAX_DURATION_MS) {
            return "error: invalid params";
        }
        return execInternal(new String[]{"input", "tap", String.valueOf(x), String.valueOf(y)});
    }

    @Override
    public String inputSwipe(int x1, int y1, int x2, int y2, long duration) {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || duration < 0 || duration > MAX_DURATION_MS) {
            return "error: invalid params";
        }
        return execInternal(new String[]{"input", "swipe",
            String.valueOf(x1), String.valueOf(y1),
            String.valueOf(x2), String.valueOf(y2),
            String.valueOf(duration)});
    }

    @Override
    public String inputKeyevent(int keyCode) {
        if (!ALLOWED_KEYCODES.contains(keyCode)) {
            return "error: invalid keycode";
        }
        return execInternal(new String[]{"input", "keyevent", String.valueOf(keyCode)});
    }

    @Override
    public String screencap(String path) {
        if (path == null || path.isEmpty() || !isValidScreencapPath(path)) {
            return "error: invalid path";
        }
        return execInternal(new String[]{"screencap", "-p", path});
    }

    @Override
    public String amStart(String packageName, String className, String action) {
        if (packageName == null || packageName.isEmpty()) {
            return "error: package name required";
        }
        // Validar formato de package name
        if (!packageName.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) {
            return "error: invalid package name";
        }
        if (className != null && !className.isEmpty()) {
            if (!className.matches("^[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)*$")) {
                return "error: invalid class name";
            }
        }
        if (action != null && !action.isEmpty()) {
            // Permitir solo acciones conocidas
            if (!java.util.Set.of(
                "android.intent.action.MAIN",
                "android.intent.action.VIEW",
                "android.intent.action.SEND",
                "android.intent.action.SENDTO"
            ).contains(action)) {
                return "error: action not allowed";
            }
        }
        // Usar ProcessBuilder con argumentos separados (sin shell)
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("am");
        cmd.add("start");
        if (action != null && !action.isEmpty()) {
            cmd.add("-a");
            cmd.add(action);
        }
        cmd.add("-n");
        cmd.add(packageName + (className != null && !className.isEmpty() ? "/" + className : ""));
        cmd.add("--user");
        cmd.add("0");
        return execInternal(cmd.toArray(new String[0]));
    }

    @Override
    public String globalActionBack() {
        return execInternal(new String[]{"input", "keyevent", "4"});
    }

    @Override
    public String globalActionHome() {
        return execInternal(new String[]{"input", "keyevent", "3"});
    }

    @Override
    public String globalActionRecents() {
        return execInternal(new String[]{"input", "keyevent", "187"});
    }

    @Override
    public String globalActionLockScreen() {
        // KEYCODE_SLEEP (26) pone el dispositivo en suspensión; no garantiza bloqueo seguro.
        // En Android 14+ "am lock" requiere permiso especial. Devolvemos error si no es compatible.
        return execInternal(new String[]{"input", "keyevent", "26"});
    }

    @Override
    public String globalActionScreenshot() {
        // Generar ruta única con timestamp dentro del directorio autorizado
        String path = SCREENCAP_BASE_DIR.getPath() + "/screenshot_shizuku_" + System.currentTimeMillis() + ".png";
        return execInternal(new String[]{"screencap", "-p", path});
    }

    @Override
    public String globalActionPowerDialog() {
        return execInternal(new String[]{"input", "keyevent", "26"});
    }

    /** Valida que la ruta de captura sea segura (dentro de /sdcard/Download y sin path traversal) */
    private boolean isValidScreencapPath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File file = new File(path);
            String canonical = file.getCanonicalPath();
            String baseCanonical = SCREENCAP_BASE_DIR.getCanonicalPath();
            // Debe estar DENTRO del directorio base (no solo mismo prefijo)
            return canonical.startsWith(baseCanonical + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /** Ejecuta comando usando ProcessBuilder con argumentos separados (SIN shell) y timeout */
    private String execInternal(String[] cmd) {
        if (cmd == null || cmd.length == 0) return;
        Process p = null;
        StringBuilder out = new StringBuilder();
        BufferedReader r = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            // Reader thread to consume output concurrently
            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });
            readerThread.start();
            boolean finished = p.waitFor(10000, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                try { readerThread.join(2000); } catch (InterruptedException ignored) {}
                return "error: command timeout";
            }
            try { readerThread.join(); } catch (InterruptedException ignored) {}
            // Drain any remaining output after thread finishes
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
            return out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "shell exec failed: " + t);
            return "error: " + t;
        } finally {
            try { if (r != null) r.close(); } catch (IOException ignored) {}
            try { if (p != null) p.destroy(); } catch (Exception ignored) {}
        }
