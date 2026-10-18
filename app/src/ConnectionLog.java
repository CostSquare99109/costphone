package com.accessdroid.termux;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Registro de eventos de conexión, pensado para depurar con la app abierta.
 *
 * Dos capas: una cola circular en memoria (para el panel, que se lee al
 * instante) y un fichero en el directorio externo de la app (que sobrevive al
 * reinicio del proceso y se puede leer con adb o desde Termux).
 *
 * Sin estado global raro: la cola es estática y el fichero se elige una vez con
 * {@link #init(android.content.Context)}. Si no se inicializa, todo cae a
 * memoria y el panel sigue funcionando.
 */
public final class ConnectionLog {

    /** Líneas que se muestran en el panel. */
    private static final int PANEL_LINES = 60;
    /** Tope del fichero: al llegar, se descarta la mitad más vieja. */
    private static final int FILE_LINES = 400;

    private static final List<String> LINES = new ArrayList<>();
    private static File file;

    private ConnectionLog() { }

    /** Fija el fichero de registro. Se puede llamar varias veces, sin efecto. */
    public static synchronized void init(android.content.Context ctx) {
        if (file != null) return;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            file = new File(dir, "connection.log");
            // Semilla la memoria con lo que ya había, para que el panel no empiece
            // vacío tras un reinicio del proceso.
            try {
                BufferedReader r = new BufferedReader(new FileReader(file));
                String l;
                while ((l = r.readLine()) != null) {
                    if (!l.isEmpty()) LINES.add(l);
                }
                r.close();
            } catch (Exception ignored) { }
            if (LINES.size() > FILE_LINES) trim();
        } catch (Throwable ignored) {
            file = null;
        }
    }

    /** Añade una línea con la hora delante. */
    public static synchronized void add(String msg) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LINES.add(stamp + "  " + msg);
        if (LINES.size() > FILE_LINES) trim();
        if (file != null) {
            try {
                FileWriter w = new FileWriter(file, false);
                int from = Math.max(0, LINES.size() - FILE_LINES);
                for (int i = from; i < LINES.size(); i++) w.write(LINES.get(i) + "\n");
                w.close();
            } catch (Exception ignored) { }
        }
    }

    /** Últimas líneas, de más vieja a más nueva. */
    public static synchronized List<String> dump() {
        int from = Math.max(0, LINES.size() - PANEL_LINES);
        return new ArrayList<>(LINES.subList(from, LINES.size()));
    }

    /** Ruta del fichero, o null si no hay fichero. */
    public static synchronized String path() {
        return file == null ? null : file.getAbsolutePath();
    }

    /** Vacía registro y fichero. */
    public static synchronized void clear() {
        LINES.clear();
        if (file != null) file.delete();
    }

    private static void trim() {
        int cut = LINES.size() - FILE_LINES / 2;
        for (int i = 0; i < cut; i++) LINES.remove(0);
    }
}
