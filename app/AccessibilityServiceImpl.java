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
                run(makeTap(x, y, 80L), "tap");
                break;
            }
            case ACTION_LONG_PRESS: {
                int x = intent.getIntExtra("x", -1);
                int y = intent.getIntExtra("y", -1);
                long dur = intent.getLongExtra("duration", 500);
                if (x < 0 || y < 0) return;
                run(makeTap(x, y, dur), "longpress");
                break;
            }
            case ACTION_SWIPE: {
                int x1 = intent.getIntExtra("x1", -1);
                int y1 = intent.getIntExtra("y1", -1);
                int x2 = intent.getIntExtra("x2", -1);
                int y2 = intent.getIntExtra("y2", -1);
                long dur = intent.getLongExtra("duration", 300);
                if (x1 < 0 || x2 < 0) return;
                run(makeSwipe(x1, y1, x2, y2, dur), "swipe");
                break;
            }
            case ACTION_SCROLL_UP: {
                int x = intent.getIntExtra("x", screenW / 2);
                int y = intent.getIntExtra("y", (int)(screenH * 0.5f));
                run(makeScroll(x, y, -0.4f), "scrollUp");
                break;
            }
            case ACTION_SCROLL_DOWN: {
                int x = intent.getIntExtra("x", screenW / 2);
                int y = intent.getIntExtra("y", (int)(screenH * 0.5f));
                run(makeScroll(x, y, 0.4f), "scrollDown");
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
                    AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
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
                    AccessibilityNodeInfo root = getRootInActiveWindow();
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
                    AccessibilityNodeInfo root = getRootInActiveWindow();
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
                    AccessibilityNodeInfo root = getRootInActiveWindow();
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
                dumpAllWindows(lines);
                if (lines.isEmpty()) break;
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
                                            saveToDownloads("accessdroid-shot.png", "image/png", baos.toByteArray());
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
                                Log.e(TAG, "screenshot fail: " + errCode);
                                saveToDownloads("accessdroid-shot-error.txt", "text/plain",
                                    ("onFailure errCode=" + errCode).getBytes());
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "takeScreenshot threw", e);
                        try {
                            saveToDownloads("accessdroid-shot-error.txt", "text/plain",
                                ("takeScreenshot threw: " + e).getBytes("UTF-8"));
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
                int cx = intent.getIntExtra("cx", screenW/2);
                int cy = intent.getIntExtra("cy", screenH/2);
                float scale = intent.getFloatExtra("scale", 0.5f);
                int dist = Math.min(screenW, screenH) / 3;
                int d = (int)(dist * (1 - scale));
                Path p1 = new Path(); p1.moveTo(cx - dist/2, cy); p1.lineTo(cx - d/2, cy);
                Path p2 = new Path(); p2.moveTo(cx + dist/2, cy); p2.lineTo(cx + d/2, cy);
                GestureDescription gd = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p1, 0, 400))
                    .addStroke(new GestureDescription.StrokeDescription(p2, 0, 400))
                    .build();
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
                GestureDescription gd = new GestureDescription.Builder()
                    .addStroke(s1).addStroke(s2).build();
                run(gd, "doubleTap");
                break;
            }
            case ACTION_CLEAR_TEXT: {
                AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null) {
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                }
                break;
            }
            case ACTION_PASTE: {
                AccessibilityNodeInfo f2 = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f2 != null) f2.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                break;
            }
            case ACTION_FOCUS: {
                String txt = intent.getStringExtra("text");
                String rid = intent.getStringExtra("id");
                AccessibilityNodeInfo root = getRootInActiveWindow();
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
                    if (launch2 != null) { launch2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch2); }
                } catch (Exception e) { Log.w(TAG, "startActivity fail", e); }
                break;
            }
            case ACTION_OPEN_URI: {
                try {
                    String uri = intent.getStringExtra("uri");
                    String mime = intent.getStringExtra("mime");
                    if (uri != null) {
                        Intent open = new Intent(Intent.ACTION_VIEW);
                        open.setData(android.net.Uri.parse(uri));
                        if (mime != null && !mime.isEmpty()) open.setType(mime);
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(open);
                    }
                } catch (Exception e) { Log.w(TAG, "openUri fail", e); }
                break;
            }
        }
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

    private GestureDescription makeTap(long x, long y, long duration) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s =
            new GestureDescription.StrokeDescription(p, 0, duration);
        return new GestureDescription.Builder().addStroke(s).build();
    }

    private GestureDescription makeSwipe(int x1, int y1, int x2, int y2, long duration) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription s =
            new GestureDescription.StrokeDescription(p, 0, duration);
        return new GestureDescription.Builder().addStroke(s).build();
    }

    private GestureDescription makeScroll(int cx, int cy, float fraction) {
        // vertical scroll gesture: from center, flick upward (negative = scroll content down)
        long dy = (long)(screenH * fraction);
        Path p = new Path();
        p.moveTo(cx, cy);
        p.lineTo(cx, cy + dy);
        GestureDescription.StrokeDescription s =
            new GestureDescription.StrokeDescription(p, 0, 200);
        return new GestureDescription.Builder().addStroke(s).build();
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
     *  become part of the tree (amctl-snap tags them [ime]/[ovl]/[sys]). */
    private void dumpAllWindows(List<String> out) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        out.add("# pkg=" + root.getPackageName());
        dumpNode(root, out, 0, 25);
        Log.d(TAG, "root window: " + root.getPackageName());

        List<AccessibilityWindowInfo> wins = null;
        try { wins = getWindows(); } catch (Exception e) { Log.w(TAG, "getWindows fail", e); }
        if (wins == null) return;

        Rect mainRect = new Rect();
        root.getBoundsInScreen(mainRect);
        String mainPkg = String.valueOf(root.getPackageName());
        for (int i = 0; i < wins.size(); i++) {
            AccessibilityWindowInfo aw = wins.get(i);
            if (aw == null) continue;
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