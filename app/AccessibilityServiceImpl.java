package com.accessdroid.termux;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    /** Pantalla virtual (display secundario). */
    static final String SETTING_OVERLAY_DISPLAYS = "overlay_display_devices";
    static final String FILE_DISPLAYS = "accessdroid-displays.txt";

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
    BroadcastReceiver receiver;
    Handler handler = new Handler(Looper.getMainLooper());
    // Shared secret – set via build-time constant or default. amctl must send it.
    static final String SECRET = "termux-accessdroid-2025";
    // Screenshot callback state
    interface ScreenshotCB { void onResult(android.graphics.Bitmap bmp); }
    ScreenshotCB pendingCb = null;

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

    /* Save a file to the public Downloads collection (no storage permission needed). */
    private void saveToDownloads(String name, String mime, byte[] data) {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            try {
                getContentResolver().delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{name});
            } catch (Exception ignored) { }
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { Log.w(TAG, "saveToDownloads: insert failed for " + name); return; }
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) { os.write(data); os.close(); }
            Log.i(TAG, "saved Download/" + name + " (" + data.length + " bytes)");
        } catch (Exception e) { Log.w(TAG, "saveToDownloads fail: " + name, e); }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleCommand(intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TAP);
        filter.addAction(ACTION_SWIPE);
        filter.addAction(ACTION_LONG_PRESS);
        filter.addAction(ACTION_SCROLL_UP);
        filter.addAction(ACTION_SCROLL_DOWN);
        filter.addAction(ACTION_BACK);
        filter.addAction(ACTION_HOME);
        filter.addAction(ACTION_RECENTS);
        filter.addAction(ACTION_TYPE);
        filter.addAction(ACTION_CLICK_TEXT);
        filter.addAction(ACTION_CLICK_ID);
        filter.addAction(ACTION_FIND_ID);
        filter.addAction(ACTION_SCROLL_TO);
        filter.addAction(ACTION_PRESS_KEY);
        filter.addAction(ACTION_SCREENDUMP);
        filter.addAction(ACTION_GET_UI_TREE);
        filter.addAction(ACTION_PINCH);
        filter.addAction(ACTION_DOUBLE_TAP);
        filter.addAction(ACTION_CLEAR_TEXT);
        filter.addAction(ACTION_PASTE);
        filter.addAction(ACTION_FOCUS);
        filter.addAction(ACTION_NOTIFICATIONS);
        filter.addAction(ACTION_QUICK_SETTINGS);
        filter.addAction(ACTION_LOCK);
        filter.addAction(ACTION_SCREENSHOT);
        filter.addAction(ACTION_OPEN_SETTINGS);
        filter.addAction(ACTION_START_ACTIVITY);
        filter.addAction(ACTION_OPEN_URI);
        filter.addAction(ACTION_POWER);
        filter.addAction(ACTION_DISPLAY);
        filter.addAction(ACTION_DISPLAY_BACK);
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        Log.i(TAG, "AccessibilityService connected – ready");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception e) { }
        }
        sInstance = null;
    }

    /* -------------------------------------------------------------- */
    /*                        Command dispatcher                      */
    /* -------------------------------------------------------------- */
    private void handleCommand(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        // Basic safety: require the shared secret from amctl.
        String secret = intent.getStringExtra("secret");
        if (secret == null || !secret.equals(SECRET)) {
            Log.w(TAG, "rejected command with bad/missing secret: " + action);
            return;
        }
        Log.d(TAG, "cmd: " + action);

        switch (action) {
            case ACTION_TAP: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                if (x < 0 || y < 0) return;
                run(makeTap(x, y, 80L, disp(intent)), "tap");
                break;
            }
            case ACTION_LONG_PRESS: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                long dur = intent.getLongExtra("duration", 500);
                if (x < 0 || y < 0) return;
                run(makeTap(x, y, dur, disp(intent)), "longpress");
                break;
            }
            case ACTION_SWIPE: {
                int x1 = intent.getIntExtra("x1", -1);
                int y1 = intent.getIntExtra("y1", -1);
                int x2 = intent.getIntExtra("x2", -1);
                int y2 = intent.getIntExtra("y2", -1);
                long dur = intent.getLongExtra("duration", 300);
                if (x1 < 0 || x2 < 0) return;
                run(makeSwipe(x1, y1, x2, y2, dur, disp(intent)), "swipe");
                break;
            }
            case ACTION_SCROLL_UP: {
                android.util.DisplayMetrics dm = metricsOf(disp(intent));
                int x = intent.getIntExtra("x", dm.widthPixels / 2);
                int y = intent.getIntExtra("y", (int)(dm.heightPixels * 0.5f));
                run(makeScroll(x, y, -0.4f, disp(intent)), "scrollUp");
                break;
            }
            case ACTION_SCROLL_DOWN: {
                android.util.DisplayMetrics dm = metricsOf(disp(intent));
                int x = intent.getIntExtra("x", dm.widthPixels / 2);
                int y = intent.getIntExtra("y", (int)(dm.heightPixels * 0.5f));
                run(makeScroll(x, y, 0.4f, disp(intent)), "scrollDown");
                break;
            }
            case ACTION_BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case ACTION_HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case ACTION_RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case ACTION_TYPE: {
                String text = intent.getStringExtra("text");
                if (text != null && !text.isEmpty()) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    AccessibilityNodeInfo focus = focusIn(disp(intent));
                    if (focus != null) {
                        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                        Log.i(TAG, "typed: " + text);
                    } else {
                        Log.w(TAG, "no focused input field");
                    }
                }
                break;
            }
            case ACTION_CLICK_TEXT: {
                String txt = intent.getStringExtra("text");
                if (txt != null && !txt.isEmpty()) {
                    AccessibilityNodeInfo root = rootFor(disp(intent));
                    if (root != null) {
                        AccessibilityNodeInfo target = findByText(root, txt);
                        if (target != null) {
                            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.i(TAG, "click '" + txt + "' → " + ok);
                        }
                    }
                }
                break;
            }
            case ACTION_CLICK_ID: {
                String rid = intent.getStringExtra("id");
                if (rid != null && !rid.isEmpty()) {
                    AccessibilityNodeInfo root = rootFor(disp(intent));
                    if (root != null) {
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                        if (!nodes.isEmpty()) {
                            boolean ok = nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.i(TAG, "click id=" + rid + " → " + ok);
                        }
                    }
                }
                break;
            }
            case ACTION_SCROLL_TO: {
                String txt = intent.getStringExtra("text");
                if (txt != null && !txt.isEmpty()) {
                    AccessibilityNodeInfo root = rootFor(disp(intent));
                    if (root != null) {
                        AccessibilityNodeInfo target = findByText(root, txt);
                        if (target != null) {
                            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                            Log.i(TAG, "scrollTo '" + txt + "' → " + ok);
                        }
                    }
                }
                break;
            }
            case ACTION_GET_UI_TREE: {
                List<String> lines = new ArrayList<>();
                int d = disp(intent);
                dumpAllWindows(lines, d);
                if (lines.isEmpty()) {
                    // SIEMPRE se escribe el fichero. Antes, un display sin
                    // ventanas no escribía nada y el CLI se quejaba de que "el
                    // servicio no responde", que es mentira y manda a debuggear
                    // lo que no está roto.
                    lines.add("# d" + d + ": " + (d == android.view.Display.DEFAULT_DISPLAY
                        ? "sin ventana activa" : "sin ventanas propias (vacío)"));
                }
                if (lines.size() > 3500) lines = lines.subList(0, 3500);
                try { saveToDownloads("accessdroid-tree.txt", "text/plain", joinLines(lines).getBytes("UTF-8")); }
                catch (Exception e) { Log.w(TAG, "tree encode fail", e); }
                break;
            }
            case ACTION_SCREENDUMP: {
                int displayId = intent.getIntExtra("displayId", android.view.Display.DEFAULT_DISPLAY);
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                    takeScreenshot(displayId, getMainExecutor(),
                        new TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(ScreenshotResult r) {
                                Log.i(TAG, "screenshot ok (displayId=" + displayId + ")");
                                try {
                                    android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                                    if (hw != null) {
                                        android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                        hw.recycle();
                                        if (sw != null) {
                                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                            sw.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
                                            sw.recycle();
                                            saveToDownloads(shotFile(displayId), "image/png", baos.toByteArray());
                                            try {
                                                getContentResolver().delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                                    android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{"accessdroid-shot-error.txt"});
                                            } catch (Exception ignored) { }
                                        }
                                    }
                                    try { r.getHardwareBuffer().close(); } catch (Exception ignored) { }
                                } catch (Exception e) { Log.w(TAG, "screenshot save fail", e); }
                                if (pendingCb != null) {
                                    pendingCb.onResult(null);
                                    pendingCb = null;
                                }
                            }
                            @Override
                            public void onFailure(int errCode) {
                                Log.e(TAG, "screenshot fail: " + errCode + " (display " + displayId + ")");
                                saveToDownloads("accessdroid-shot-error.txt", "text/plain",
                                    ("display=" + displayId + " onFailure errCode=" + errCode).getBytes());
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "takeScreenshot threw", e);
                        try {
                            saveToDownloads("accessdroid-shot-error.txt", "text/plain",
                                ("display=" + displayId + " takeScreenshot threw: " + e).getBytes("UTF-8"));
                        } catch (Exception ignored) { }
                    }
                } else {
                    Log.w(TAG, "takeScreenshot requires API 33+");
                }
                break;
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
                        return;
                }
                if (global == -2 || global == -3) {
                    // Volume via audio manager fallback
                    try {
                        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            if (global == -2) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
                            else am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                        }
                    } catch (Exception e) { Log.w(TAG, "volume adjust fail", e); }
                } else {
                    performGlobalAction(global);
                }
                break;
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
                break;
            }
            case ACTION_DOUBLE_TAP: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                if (x < 0 || y < 0) return;
                Path p1 = new Path(); p1.moveTo(x, y);
                GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p1, 0, 40);
                Path p2 = new Path(); p2.moveTo(x, y);
                GestureDescription.StrokeDescription s2 = new GestureDescription.StrokeDescription(p2, 120, 40);
                GestureDescription gd = build(java.util.Arrays.asList(s1, s2), disp(intent));
                run(gd, "doubleTap");
                break;
            }
            case ACTION_CLEAR_TEXT: {
                AccessibilityNodeInfo f = focusIn(disp(intent));
                if (f != null) {
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                }
                break;
            }
            case ACTION_PASTE: {
                AccessibilityNodeInfo f2 = focusIn(disp(intent));
                if (f2 != null) f2.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                break;
            }
            case ACTION_FOCUS: {
                String txt = intent.getStringExtra("text");
                String rid = intent.getStringExtra("id");
                AccessibilityNodeInfo root = rootFor(disp(intent));
                if (root == null) break;
                AccessibilityNodeInfo target = null;
                if (txt != null && !txt.isEmpty()) target = findByText(root, txt);
                else if (rid != null && !rid.isEmpty()) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                    if (!nodes.isEmpty()) target = nodes.get(0);
                }
                if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                break;
            }
            case ACTION_NOTIFICATIONS:
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case ACTION_QUICK_SETTINGS:
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case ACTION_LOCK:
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                break;
            case ACTION_SCREENSHOT:
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
                break;
            case ACTION_POWER:
                if (Build.VERSION.SDK_INT >= 30) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                break;
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
                } catch (Exception e) { Log.w(TAG, "openSettings fail", e); }
                break;
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
                    if (launch2 != null) {
                        launch2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (dispId != android.view.Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= 26) {
                            // lanzar la app EN la pantalla virtual, no en la del usuario
                            startActivity(launch2, android.app.ActivityOptions.makeBasic()
                                .setLaunchDisplayId(dispId).toBundle());
                        } else {
                            startActivity(launch2);
                        }
                    }
                } catch (Exception e) { Log.w(TAG, "startActivity fail", e); }
                break;
            }
            case ACTION_OPEN_URI: {
                try {
                    String uri = intent.getStringExtra("uri");
                    String mime = intent.getStringExtra("mime");
                    if (uri != null) {
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
                        try {
                            android.content.pm.ResolveInfo ri =
                                getPackageManager().resolveActivity(open, 0);
                            if (ri != null && ri.activityInfo != null
                                    && !"android".equals(ri.activityInfo.packageName)) {
                                open.setComponent(new android.content.ComponentName(
                                    ri.activityInfo.packageName, ri.activityInfo.name));
                                ambiguous = false;
                            }
                        } catch (Exception ignored) { }

                        if (dispId != android.view.Display.DEFAULT_DISPLAY && ambiguous
                                && Build.VERSION.SDK_INT >= 26) {
                            try { saveToDownloads("accessdroid-error.txt", "text/plain",
                                ("display=" + dispId + ": esa URL tiene varias apps y el selector del "
                                 + "sistema se abrirá en la pantalla 0 (no admite display). "
                                 + "Usa amctl -d " + dispId + " launch <paquete>").getBytes("UTF-8")); }
                            catch (Exception ignored) { }
                        }

                        if (dispId != android.view.Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= 26) {
                            startActivity(open, android.app.ActivityOptions.makeBasic()
                                .setLaunchDisplayId(dispId).toBundle());
                        } else {
                            startActivity(open);
                        }
                    }
                } catch (Exception e) { Log.w(TAG, "openUri fail", e); }
                break;
            }
            case ACTION_DISPLAY:
                handleDisplayOp(intent);
                break;
            case ACTION_DISPLAY_BACK: {
                int dispId = disp(intent);
                if (dispId == android.view.Display.DEFAULT_DISPLAY) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    break;
                }
                // GLOBAL_ACTION_BACK siempre es del display 0: buscamos el botón
                // de retroceso de la app en la pantalla virtual.
                AccessibilityNodeInfo root = rootFor(dispId);
                AccessibilityNodeInfo btn = findBackNode(root, 0);
                if (btn != null) {
                    boolean ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "back en display " + dispId + " → " + ok);
                } else {
                    Log.w(TAG, "sin botón atrás en display " + dispId);
                    try { saveToDownloads("accessdroid-error.txt", "text/plain",
                        ("display=" + dispId + ": la app no tiene botón de retroceso reconocible en ese display").getBytes("UTF-8")); }
                    catch (Exception ignored) { }
                }
                break;
            }
        }
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

    /** Reporta todos los displays (id, tamaño, dpi, ventanas) a /sdcard/Download. */
    private void saveDisplays(String errorLine) {
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
        try {
            saveToDownloads(FILE_DISPLAYS, "text/plain", joinLines(out).getBytes("UTF-8"));
        } catch (Exception e) { Log.w(TAG, "displays encode fail", e); }
    }

    private android.util.SparseArray<List<AccessibilityWindowInfo>> allDisplays() {
        if (Build.VERSION.SDK_INT < 30) return null;
        try { return getWindowsOnAllDisplays(); } catch (Exception e) { return null; }
    }

    private int windowsAllCount() {
        android.util.SparseArray<List<AccessibilityWindowInfo>> a = allDisplays();
        return a == null ? 0 : a.size();
    }

    private void handleDisplayOp(Intent intent) {
        String op = intent.getStringExtra("op");
        if (op == null || op.isEmpty()) op = "status";
        if ("status".equals(op)) { saveDisplays(null); return; }

        if (!canWriteSettings()) {
            saveDisplays("WRITE_SECURE_SETTINGS no concedido a CostPhone. Concede una vez con: "
                + "adb shell pm grant com.accessdroid.termux android.permission.WRITE_SECURE_SETTINGS");
            return;
        }
        if ("off".equals(op)) {
            // borrar el ajuste apaga todas las pantallas virtuales
            android.provider.Settings.Global.putString(getContentResolver(), SETTING_OVERLAY_DISPLAYS, null);
            Log.i(TAG, "pantalla virtual apagada");
            saveDisplays(null);
            return;
        }
        if ("on".equals(op)) {
            int w = intent.getIntExtra("width", 1080);
            int h = intent.getIntExtra("height", 1920);
            int dpi = intent.getIntExtra("density", 320);

            // overlay_display_devices NO es JSON:
            //   [ancho]x[alto]/[dpi][,flag1][,flag2];[display2];...
            if (w < 100 || w > 4096 || h < 100 || h > 4096 || dpi < 120 || dpi > 640) {
                saveDisplays("fuera de rango (100-4096 px, 120-640 dpi): " + w + "x" + h + "@" + dpi);
                return;
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
                        saveDisplays("flag no soportado: '" + f + "'. Válidos: "
                            + String.join(", ", OVERLAY_FLAGS) + " (o 'mirror')");
                        return;
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
                saveDisplays(mirror
                    ? "aviso: modo espejo (sin own_content_only). El overlay muestra d0 y el "
                      + "agente puede escribir en la pantalla del usuario: úsalo solo para mirar."
                    : null);
            } catch (Exception e) {
                saveDisplays("fallo al escribir overlay_display_devices: " + e);
            }
            return;
        }
        saveDisplays("op desconocido: " + op);
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
        Path p = new Path();
        p.moveTo(cx, cy);
        p.lineTo(cx, cy + dy);
        return build(java.util.Arrays.asList(new GestureDescription.StrokeDescription(p, 0, 200)), displayId);
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

    public void pressBack()  { performGlobalAction(GLOBAL_ACTION_BACK); }
    public void pressHome()  { performGlobalAction(GLOBAL_ACTION_HOME); }
    public void pressRecents() { performGlobalAction(GLOBAL_ACTION_RECENTS); }

    /* -------------------------------------------------------------- */
    /*                      UI-tree / find helpers                    */
    /* -------------------------------------------------------------- */

    /** Dump main window + IME/overlay windows: keyboard and floating toolbars
     *  become part of the tree (amctl-snap tags them [ime]/[ovl]/[sys]).
     *  Con displayId != 0 solo se vuelca ese display (pantalla virtual). */
    private void dumpAllWindows(List<String> out, int displayId) {
        AccessibilityNodeInfo root = rootFor(displayId);
        if (root == null) return;
        out.add("# display=" + displayId);
        out.add("# pkg=" + root.getPackageName());
        dumpNode(root, out, 0, 25);
        Log.d(TAG, "root window: " + root.getPackageName() + " (display " + displayId + ")");

        List<AccessibilityWindowInfo> wins = windowsOf(displayId);
        if (wins == null) return;

        Rect mainRect = new Rect();
        root.getBoundsInScreen(mainRect);
        String mainPkg = String.valueOf(root.getPackageName());
        for (int i = 0; i < wins.size(); i++) {
            AccessibilityWindowInfo aw = wins.get(i);
            if (aw == null) continue;
            if (aw.getDisplayId() != displayId) continue;   // ventanas de otra pantalla
            AccessibilityNodeInfo wroot = null;
            try { wroot = aw.getRoot(); } catch (Exception e) { continue; }
            if (wroot == null) continue;
            // skip: root window itself (same pkg + same screen bounds)
            Rect wr = new Rect();
            wroot.getBoundsInScreen(wr);
            String wpkg = String.valueOf(wroot.getPackageName());
            if (wr.equals(mainRect) && wpkg.equals(mainPkg)) continue;
            String kind = windowTag(aw.getType());
            if (kind == null) continue;   // TYPE_APPLICATION duplicada: omitir
            out.add("# win=" + kind + " pkg=" + wpkg);
            int before = out.size();
            dumpNode(wroot, out, 0, 25);
            Log.d(TAG, "extra window " + kind + " nodes=" + (out.size() - before));
        }
    }

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

    private void dumpNode(AccessibilityNodeInfo node, List<String> out, int depth, int maxDepth) {
        if (depth > maxDepth || node == null) return;
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
            String indent = new String(new char[depth]).replace("\0", "  ");
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            String shortClass = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
            StringBuilder flags = new StringBuilder();
            if (node.isClickable()) flags.append(" clk=1");
            if (node.isScrollable()) flags.append(" scr=1");
            if (node.isEditable()) flags.append(" edt=1");
            if (node.isCheckable()) flags.append(" chk=1");
            if (node.isFocused()) flags.append(" foc=1");
            out.add(String.format("%s[%s] id=%s bounds=%s txt=%s desc=%s%s",
                indent, shortClass, vid, r,
                txt == null ? "" : txt, desc == null ? "" : desc, flags));
        }
        for (int i = 0; i < cc; i++) {
            dumpNode(node.getChild(i), out, depth + 1, maxDepth);
        }
    }
}