package com.accessdroid.termux;

import android.util.Log;

import com.accessdroid.termux.shell.ICostPhoneShell;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * UserService de Shizuku: ejecuta comandos de shell con identidad root/shell.
 *
 * Esta clase NO se ejecuta en el proceso de CostPhone. Shizuku la instancia en
 * un proceso aparte y nos pasa su binder por
 * {@link rikka.shizuku.Shizuku#bindUserService}; por eso necesita constructor
 * público sin argumentos y el método destroy() reservado (ver ICostPhoneShell).
 */
public class ShizukuShellService extends ICostPhoneShell.Stub {

    private static final String TAG = "CostPhone";

    @Override
    public String exec(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder out = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            p.waitFor();
            return out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "shell exec('" + cmd + "') falló: " + t);
            return "error: " + t;
        } finally {
            if (p != null) p.destroy();
        }
    }

    @Override
    public void exit() {
        destroy();
    }

    /** Reservado por Shizuku: lo llama el servidor cuando hay que tirar el servicio. */
    @Override
    public void destroy() {
        System.exit(0);
    }
}
