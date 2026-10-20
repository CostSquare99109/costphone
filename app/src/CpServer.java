package com.accessdroid.termux;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Canal rápido Termux &lt;-&gt; app sobre TCP de loopback.
 *
 * Por qué existe: el canal clásico (broadcast -&gt; fichero en /sdcard -&gt; sondeo)
 * cuesta varios segundos por comando (broadcast + MediaStore + FUSE + sleep).
 * Con un socket local la orden y su respuesta viajan en un solo ida y vuelta:
 * sin ficheros, sin esperas y con errores incluidos en la respuesta.
 *
 * Protocolo (una línea de petición, una de respuesta):
 *
 *   Petición  : CP/1 &lt;ACTION&gt; [tipo:clave=valor_base64]...
 *               tipo = i (int) | l (long) | f (float) | s (string)
 *               "secret" es obligatorio y va como string normal.
 *
 *   Respuesta : CP/1 &lt;ok|fail|empty&gt; &lt;longitud&gt; &lt;raw|b64&gt;\n
 *               &lt;cuerpo&gt; — longitud bytes. "raw" = texto UTF-8, "b64" = base64.
 *
 * El cuerpo de texto va en crudo (se lee directo en bash); el binario (captura)
 * va en base64 porque bash no puede meter bytes NUL en una variable.
 */
final class CpServer {

    private static final String TAG = "AccessDroid";
    private static final String HELLO = "CP/1";
    private static final int[] PORTS = {39871, 39872, 39873, 39874, 39875};
    private static final int MAX_REQUEST = 256 * 1024;
    private static final int MAX_RESPONSE = 24 * 1024 * 1024;

    interface Handler {
        CpRes handle(String action, Map<String, Object> args);
    }

    private final String secret;
    private final Handler handler;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;

    CpServer(String secret, Handler handler) {
        this.secret = secret;
        this.handler = handler;
    }

    boolean start() {
        // Ojo con InetAddress.getLoopbackAddress(): en Android 16 devuelve ::1
        // (IPv6), así que el CLI que connecte a 127.0.0.1 no lo encontraría.
        // Nos atamos al IPv4 de loopback y, si no deja, al IPv6.
        InetAddress[] addrs = new InetAddress[2];
        try {
            addrs[0] = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (Exception e) { addrs[0] = InetAddress.getLoopbackAddress(); }
        try {
            addrs[1] = InetAddress.getLoopbackAddress();
        } catch (Exception e) { addrs[1] = addrs[0]; }
        for (InetAddress addr : addrs) {
            for (int port : PORTS) {
                try {
                    server = new ServerSocket(port, 8, addr);
                    server.setReuseAddress(true);
                    running = true;
                    thread = new Thread(new Runnable() {
                        @Override public void run() { acceptLoop(); }
                    }, "cp-server");
                    thread.setDaemon(true);
                    thread.start();
                    Log.i(TAG, "canal rápido: " + addr.getHostAddress() + ":" + port);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "puerto " + addr.getHostAddress() + ":" + port + " no disponible: " + e);
                }
            }
        }
        Log.w(TAG, "no se pudo abrir el canal rápido (puertos ocupados)");
        return false;
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                final Socket sock = server.accept();
                sock.setSoTimeout(20000);
                sock.setTcpNoDelay(true);
                // una conexión por hilo: un volcado lento no bloquea las demás
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { serve(sock); } catch (Exception e) { Log.w(TAG, "canal: " + e); }
                        finally { try { sock.close(); } catch (Exception ignored) { } }
                    }
                }, "cp-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) Log.w(TAG, "canal: " + e);
            }
        }
    }

    private void serve(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        String line = readLine(in);
        if (line == null || line.isEmpty()) return;

        CpRes res = dispatch(line);

        byte[] body;
        String enc;
        if (res.binary && res.payload != null) {
            body = Base64.encode(res.payload, Base64.NO_WRAP);
            enc = "b64";
        } else if (res.text != null) {
            body = res.text.getBytes(StandardCharsets.UTF_8);
            enc = "raw";
        } else if (res.message != null && !res.message.isEmpty()) {
            // sin carga útil, el mensaje ES la respuesta (errores, "vacío"…)
            body = res.message.getBytes(StandardCharsets.UTF_8);
            enc = "raw";
        } else {
            body = new byte[0];
            enc = "raw";
        }

        String head = HELLO + " " + codeName(res.code) + " " + body.length + " " + enc + "\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static String codeName(int code) {
        if (code == CpRes.FAIL) return "fail";
        if (code == CpRes.EMPTY) return "empty";
        return "ok";
    }

    /** Pide la orden y la ejecuta. Devuelve siempre una respuesta válida. */
    private CpRes dispatch(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || !HELLO.equals(parts[0])) {
            return CpRes.fail("petición inválida");
        }
        String action = parts[1];

        Map<String, Object> args = new HashMap<>();
        String rawArgs = null;
        for (int i = 2; i < parts.length; i++) {
            String tok = parts[i];
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String key = tok.substring(0, eq);
            String raw = tok.substring(eq + 1);
            String type = "s";
            if (key.length() > 2 && key.charAt(1) == ':') {
                type = key.substring(0, 1);
                key = key.substring(2);
            }
            String val = raw;
            if ("b".equals(type)) {
                try {
                    val = new String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    continue;
                }
            }
            if ("args".equals(key)) { rawArgs = val; continue; }
            putTyped(args, key, type, val);
        }
        if (rawArgs != null) parseArgsPayload(rawArgs, args);

        Object sec = args.get("secret");
        if (secret == null || !(sec instanceof String) || !secret.equals(sec)) {
            Log.w(TAG, "petición rechazada: secreto incorrecto");
            return CpRes.fail("secreto incorrecto");
        }

        try {
            CpRes res = handler.handle(action, args);
            return res == null ? CpRes.ok("") : res;
        } catch (Throwable t) {
            Log.w(TAG, "orden " + action + " falló", t);
            return CpRes.fail("error: " + t);
        }
    }

    /** Guarda un argumento con su tipo: i/l/f/s. */
    private static void putTyped(Map<String, Object> args, String key, String type, String val) {
        try {
            if ("i".equals(type)) args.put(key, Integer.parseInt(val));
            else if ("l".equals(type)) args.put(key, Long.parseLong(val));
            else if ("f".equals(type)) args.put(key, Float.parseFloat(val));
            else args.put(key, val);
        } catch (Exception ignored) { }
    }

    /**
     * Payload de argumentos: una línea por argumento, "tipo:clave=valor".
     * Los saltos de línea dentro de un valor van escapados como \n.
     */
    private static void parseArgsPayload(String payload, Map<String, Object> args) {
        for (String raw : payload.split("\n")) {
            if (raw.isEmpty()) continue;
            int eq = raw.indexOf('=');
            if (eq <= 0) continue;
            String key = raw.substring(0, eq);
            String val = raw.substring(eq + 1);
            String type = "s";
            if (key.length() > 2 && key.charAt(1) == ':') {
                type = key.substring(0, 1);
                key = key.substring(2);
            }
            putTyped(args, key, type, unescape(val));
        }
    }

    /** Convierte \n y \\ escapados en sus caracteres reales. */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n') { sb.append('\n'); i++; continue; }
                if (n == '\\') { sb.append('\\'); i++; continue; }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Lee una línea terminada en \n con tope de tamaño. */
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            buf.write(c);
            if (buf.size() > MAX_REQUEST) break;
        }
        if (buf.size() == 0 && c < 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
