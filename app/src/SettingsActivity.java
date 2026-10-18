package com.accessdroid.termux;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * CostPhone — panel principal.
 * Minimalista y premium: logo, estado del servicio y acceso a Ajustes.
 */
public class SettingsActivity extends Activity {

    // ── Paleta ──
    private static final int BG_DARK      = 0xFF0B1220;
    private static final int BG_CARD      = 0xFF131E33;
    private static final int ACCENT_BLUE  = 0xFF0A66E0;
    private static final int ACCENT_GREEN = 0xFF34C759;
    private static final int ACCENT_RED   = 0xFFFF453A;
    private static final int ACCENT_AMBER = 0xFFFFCC00;
    private static final int TEXT_MAIN    = 0xFFF2F5FA;
    private static final int TEXT_DIM     = 0xFF8B97AD;

    private View statusDot;
    private TextView tvStatus;

    private View shizukuDot;
    private TextView tvShizuku;
    private Button btnShizuku;

    private static final int REQ_SHIZUKU_PERMISSION = 41;

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == REQ_SHIZUKU_PERMISSION) refreshShizuku();
                }
            };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(52), dp(28), dp(40));
        scroll.addView(root);

        // ── Logo ──
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        root.addView(logo, lp(dp(118), dp(118), 0));

        // ── Título ──
        TextView title = new TextView(this);
        title.setText("CostPhone");
        title.setTextSize(30);
        title.setTextColor(TEXT_MAIN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lpWRAP(18));

        TextView sub = new TextView(this);
        sub.setText("Control remoto desde Termux");
        sub.setTextSize(14);
        sub.setTextColor(TEXT_DIM);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, lpWRAP(6));

        // ── Tarjeta de estado ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(BG_CARD, 24, 0));
        card.setPadding(dp(22), dp(24), dp(22), dp(24));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new View(this);
        row.addView(statusDot, lp(dp(14), dp(14), 0));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(17);
        tvStatus.setTextColor(TEXT_MAIN);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(12);
        row.addView(tvStatus, tlp);
        card.addView(row);

        TextView desc = new TextView(this);
        desc.setText("Servicio de accesibilidad. Permite a CostPhone leer la pantalla y "
                + "ejecutar toques, gestos y texto cuando Termux se lo pide. "
                + "Es lo único que necesitas activar.");
        desc.setTextSize(14);
        desc.setTextColor(TEXT_DIM);
        desc.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        card.addView(desc, dlp);

        Button btn = new Button(this);
        btn.setText("Abrir Ajustes de Accesibilidad");
        btn.setAllCaps(false);
        btn.setTextSize(16);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(rounded(ACCENT_BLUE, 16, 0));
        btn.setPadding(dp(18), dp(14), dp(18), dp(14));
        btn.setStateListAnimator(null);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(22);
        card.addView(btn, blp);

        root.addView(card, lpMATCH(34));

        // ── Tarjeta de Shizuku (opcional) ──
        root.addView(shizukuCard(), lpMATCH(14));

        // ── Pie ──
        TextView tip = new TextView(this);
        tip.setText("Consejo: si MIUI lo desactiva solo, vuelve aquí y reactívalo.");
        tip.setTextSize(12);
        tip.setTextColor(TEXT_DIM);
        tip.setGravity(Gravity.CENTER);
        root.addView(tip, lpWRAP(24));

        TextView ver = new TextView(this);
        ver.setText("CostPhone · v1.2");
        ver.setTextSize(12);
        ver.setTextColor(0xFF546E7A);
        ver.setGravity(Gravity.CENTER);
        root.addView(ver, lpWRAP(0));

        setContentView(scroll);

        // El resultado del permiso vuelve por aquí, no por onActivityResult.
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) { }
    }

    @Override protected void onDestroy() {
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshShizuku();
    }

    private void refreshStatus() {
        boolean on = isServiceOn();
        statusDot.setBackground(rounded(on ? ACCENT_GREEN : ACCENT_RED, 999, 0));
        tvStatus.setText(on ? "Servicio activo" : "Servicio desactivado");
        tvStatus.setTextColor(on ? ACCENT_GREEN : ACCENT_RED);
    }

    private boolean isServiceOn() {
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : list) {
            String id = info.getId();
            if (id != null && id.contains(getPackageName())) return true;
        }
        return false;
    }

    // ── Tarjeta de Shizuku ──
    private View shizukuCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(BG_CARD, 24, 0));
        card.setPadding(dp(22), dp(24), dp(22), dp(24));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        shizukuDot = new View(this);
        row.addView(shizukuDot, lp(dp(14), dp(14), 0));

        tvShizuku = new TextView(this);
        tvShizuku.setTextSize(17);
        tvShizuku.setTextColor(TEXT_MAIN);
        tvShizuku.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(12);
        row.addView(tvShizuku, tlp);
        card.addView(row);

        TextView desc = new TextView(this);
        desc.setText("Opcional. Con Shizuku, CostPhone hace toques y teclas con "
                + "permisos de shell en vez de con el servicio de accesibilidad: va más "
                + "rápido y no se cancela si pierdes el foco. Si no lo usas, todo sigue "
                + "funcionando exactamente igual.");
        desc.setTextSize(14);
        desc.setTextColor(TEXT_DIM);
        desc.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        card.addView(desc, dlp);

        btnShizuku = new Button(this);
        btnShizuku.setAllCaps(false);
        btnShizuku.setTextSize(16);
        btnShizuku.setTextColor(0xFFFFFFFF);
        btnShizuku.setTypeface(Typeface.DEFAULT_BOLD);
        btnShizuku.setBackground(rounded(ACCENT_BLUE, 16, 0));
        btnShizuku.setPadding(dp(18), dp(14), dp(18), dp(14));
        btnShizuku.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(22);
        card.addView(btnShizuku, blp);

        return card;
    }

    private void refreshShizuku() {
        switch (ShizukuManager.state()) {
            case ShizukuManager.READY:
                shizukuDot.setBackground(rounded(ACCENT_GREEN, 999, 0));
                tvShizuku.setText("Shizuku activo");
                tvShizuku.setTextColor(ACCENT_GREEN);
                btnShizuku.setVisibility(View.GONE);
                break;

            case ShizukuManager.NO_PERMISSION:
                shizukuDot.setBackground(rounded(ACCENT_AMBER, 999, 0));
                tvShizuku.setText("Shizuku sin permiso");
                tvShizuku.setTextColor(ACCENT_AMBER);
                btnShizuku.setVisibility(View.VISIBLE);
                btnShizuku.setText("Pedir permiso");
                btnShizuku.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ShizukuManager.requestPermission(REQ_SHIZUKU_PERMISSION);
                    }
                });
                break;

            default:
                shizukuDot.setBackground(rounded(ACCENT_RED, 999, 0));
                tvShizuku.setText("Shizuku no disponible");
                tvShizuku.setTextColor(TEXT_DIM);
                btnShizuku.setVisibility(View.VISIBLE);
                btnShizuku.setText("Cómo instalarlo");
                btnShizuku.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://shizuku.rikka.app/download/")));
                    }
                });
                break;
        }
    }

    // ── Utilidades de UI ──
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topMarginDp) {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private LinearLayout.LayoutParams lpWRAP(int topMarginDp) {
        return lp(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, topMarginDp);
    }

    private LinearLayout.LayoutParams lpMATCH(int topMarginDp) {
        return lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, topMarginDp);
    }
}
