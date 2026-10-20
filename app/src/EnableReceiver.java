package com.accessdroid.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receptor en el manifest (no en el servicio) para poder activar el servicio
 * de accesibilidad aunque esté apagado — que es justo lo que pasa después de
 * cada actualización de la app, cuando Android lo desactiva por seguridad.
 *
 * Solo sabe hacer una cosa: activar el propio servicio de CostPhone, y
 * únicamente si la orden trae el secreto compartido.
 */
public class EnableReceiver extends BroadcastReceiver {

    /** Fichero donde se deja el resultado, para que el CLI pueda leerlo. */
    static final String REPORT = "accessdroid-enable.txt";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (!AccessibilityServiceImpl.ACTION_ENABLE_SERVICE.equals(intent.getAction())) return;
        String secret = intent.getStringExtra("secret");
        if (secret == null || !secret.equals(AccessibilityServiceImpl.SECRET)) return;
        CpRes res = AccessibilityServiceImpl.enableSelf(ctx);
        String report = (res.code == CpRes.OK ? "OK " : "FAIL ") + res.message;
        try {
            ctx.getContentResolver().delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{REPORT});
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, REPORT);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = ctx.getContentResolver().insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                if (os != null) { os.write(report.getBytes("UTF-8")); os.close(); }
            }
        } catch (Exception e) {
            android.util.Log.w("CostPhone", "enable report fail", e);
        }
    }
}
