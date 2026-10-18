package com.accessdroid.termux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Servicio en primer plano que mantiene a CostPhone con vida.
 *
 * Android mata procesos enseguida cuando una app no tiene nada visible, y el
 * servicio de accesibilidad es justo el que más necesita no morir. Una
 * notificación persistente (imposible de quitar sin desactivar el servicio) es
 * el precio de eso: el sistema ve trabajo en primer plano y deja el proceso en
 * paz.
 *
 * Target SDK 34: el tipo es `specialUse` y hace falta declarar también el
 * permiso FOREGROUND_SERVICE_SPECIAL_USE, no basta con FOREGROUND_SERVICE.
 */
public class KeepAliveService extends Service {

    private static final String TAG = "CostPhone";
    public static final String CHANNEL_ID = "costphone_keep";
    public static final int NOTIF_ID = 1001;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n;
        try {
            n = buildNotification();
        } catch (Throwable t) {
            // Si la notificación falla, el servicio no puede quedar en primer
            // plano sin ella: paramos en vez de dejarlo roto a medias.
            Log.e(TAG, "no se pudo construir la notificación", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, n);
        ConnectionLog.add("Notificación persistente activa");
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        // IMPORTANCE_LOW: silenciosa. Es una notificación de "fondo", no tiene
        // que molestar con sonido ni vibración.
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "CostPhone activo", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Mantiene el control remoto de CostPhone disponible.");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        // Botón de la notificación: abrir la app.
        Intent open = new Intent(this, SettingsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("CostPhone activo")
         .setContentText("El control remoto sigue disponible para Termux.")
         .setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(openPi)
         .setOngoing(true)                       // no se puede deslizar
         .setCategory(Notification.CATEGORY_SERVICE)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW)
             .setDefaults(0);
        }

        // Acción explícita: abrir la app sin tener que tocar la notificación.
        Intent action = new Intent(this, SettingsActivity.class);
        action.setAction(Intent.ACTION_MAIN);
        action.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent actionPi = PendingIntent.getActivity(
                this, 2, action, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                null, "Abrir app", actionPi).build());

        return b.build();
    }

    @Override public void onDestroy() {
        // Al irse, se acabó: el servicio de accesibilidad se apaga y no hay
        // nada que mantener.
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // STOP_FOREGROUND_REMOVE no existe en API < 24.
            try { stopForeground(true); } catch (Throwable ignored2) { }
        }
        ConnectionLog.add("Notificación persistente retirada");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Arranca el servicio en primer plano si no está ya activo. */
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo arrancar el servicio en primer plano: " + t);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) { }
    }
}
