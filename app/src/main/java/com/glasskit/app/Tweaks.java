package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import java.util.Locale;

/**
 * Tetapan sistem melalui Shizuku (tanpa root). Setiap perubahan menyimpan nilai asal dahulu supaya
 * "Pulihkan asal" boleh mengembalikan semuanya.
 */
final class Tweaks {
    private Tweaks() {}

    interface Done { void on(boolean ok, String msg); }

    private static SharedPreferences p(Context c) { return Prefs.get(c); }

    /** Simpan nilai asal satu tetapan (sekali sahaja), kemudian jalankan cmd. */
    private static void saveThen(Context c, String ns, String key, String cmd, Done d) {
        String k = "orig_" + ns + "_" + key;
        if (p(c).contains(k)) {
            Shell.run(cmd, (ok, out) -> { if (d != null) d.on(ok, out); });
            return;
        }
        Shell.run("settings get " + ns + " " + key, (ok, out) -> {
            p(c).edit().putString(k, out == null ? "null" : out.trim()).apply();
            Shell.run(cmd, (ok2, out2) -> { if (d != null) d.on(ok2, out2); });
        });
    }

    private static String restoreCmd(Context c, String ns, String key) {
        String v = p(c).getString("orig_" + ns + "_" + key, null);
        if (v == null) return "";
        p(c).edit().remove("orig_" + ns + "_" + key).apply();
        return v.equals("null") || v.isEmpty() ? "settings delete " + ns + " " + key + "; "
                : "settings put " + ns + " " + key + " '" + v.replace("'", "") + "'; ";
    }

    // ======================= Warna sistem (Material You) =======================

    static final int[] PALETTE = {0, 0xFF0A84FF, 0xFFBF5AF2, 0xFF30D158, 0xFFFF375F, 0xFFFF9F0A, 0xFF64D2FF};

    /** color 0 = kembali ikut wallpaper (nilai asal). */
    static void systemColor(Context c, int color, Done d) {
        if (color == 0) {
            Shell.run(restoreCmd(c, "secure", "theme_customization_overlay_packages"), (ok, o) -> { if (d != null) d.on(ok, o); });
            p(c).edit().putInt(Prefs.SYS_COLOR, 0).apply();
            return;
        }
        String hex = String.format(Locale.US, "%06X", color & 0xFFFFFF);
        String json = "{\\\"android.theme.customization.theme_style\\\":\\\"TONAL_SPOT\\\","
                + "\\\"android.theme.customization.system_palette\\\":\\\"" + hex + "\\\","
                + "\\\"android.theme.customization.accent_color\\\":\\\"" + hex + "\\\","
                + "\\\"android.theme.customization.color_source\\\":\\\"preset\\\","
                + "\\\"_applied_timestamp\\\":" + System.currentTimeMillis() + "}";
        saveThen(c, "secure", "theme_customization_overlay_packages",
                "settings put secure theme_customization_overlay_packages \"" + json + "\"", d);
        p(c).edit().putInt(Prefs.SYS_COLOR, color).apply();
    }

    // ======================= Skala kelabu =======================

    static void grayscale(boolean on) {
        Shell.run(on ? "settings put secure accessibility_display_daltonizer 0; settings put secure accessibility_display_daltonizer_enabled 1"
                : "settings put secure accessibility_display_daltonizer_enabled 0");
    }

    // ======================= Kadar segar semula =======================

    static void refresh120(Context c, boolean on, Done d) {
        if (on) {
            saveThen(c, "system", "min_refresh_rate", "settings put system min_refresh_rate 120.0", null);
            saveThen(c, "system", "peak_refresh_rate", "settings put system peak_refresh_rate 120.0", d);
        } else {
            Shell.run(restoreCmd(c, "system", "min_refresh_rate") + restoreCmd(c, "system", "peak_refresh_rate") + "true",
                    (ok, o) -> { if (d != null) d.on(ok, o); });
        }
        p(c).edit().putBoolean(Prefs.T_120, on).apply();
    }

    // ======================= Kelajuan animasi =======================

    static final float[] ANIM = {0.5f, 0.75f, 1f};

    static void animSpeed(Context c, int idx, Done d) {
        String v = String.valueOf(ANIM[Math.max(0, Math.min(2, idx))]);
        saveThen(c, "global", "window_animation_scale",
                "settings put global window_animation_scale " + v + "; settings put global transition_animation_scale " + v
                        + "; settings put global animator_duration_scale " + v, d);
        p(c).edit().putInt(Prefs.T_ANIM, idx).apply();
    }

    // ======================= Paksa gelap =======================

    static void forceDark(Context c, boolean on) {
        Shell.run("setprop debug.hwui.force_dark " + on);
        p(c).edit().putBoolean(Prefs.T_FORCE_DARK, on).apply();
    }

    // ======================= Saiz paparan =======================

    /** step: -1 padat, 0 asal, 1 besar. */
    static void density(Context c, int step, Done d) {
        if (step == 0) {
            Shell.run("wm density reset", (ok, o) -> { if (d != null) d.on(ok, o); });
            return;
        }
        Shell.run("wm density", (ok, out) -> {
            int phys = 0;
            if (out != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("Physical density:\\s*(\\d+)").matcher(out);
                if (m.find()) phys = Integer.parseInt(m.group(1));
            }
            if (phys <= 0) phys = Math.round(c.getResources().getDisplayMetrics().densityDpi);
            int target = Math.round(phys * (step < 0 ? 0.88f : 1.12f));
            Shell.run("wm density " + target, (ok2, o2) -> { if (d != null) d.on(ok2, o2); });
        });
    }

    // ======================= Notifikasi lock screen stock =======================

    static void stockLockNotifs(Context c, boolean show) {
        if (!show) saveThen(c, "secure", "lock_screen_show_notifications", "settings put secure lock_screen_show_notifications 0", null);
        else Shell.run(restoreCmd(c, "secure", "lock_screen_show_notifications") + "settings put secure lock_screen_show_notifications 1");
        p(c).edit().putBoolean(Prefs.T_HIDE_STOCK_NOTIF, !show).apply();
    }

    // ======================= Pulihkan semua =======================

    static void restoreAll(Context c, Done d) {
        String cmd = restoreCmd(c, "secure", "theme_customization_overlay_packages")
                + restoreCmd(c, "system", "min_refresh_rate") + restoreCmd(c, "system", "peak_refresh_rate")
                + restoreCmd(c, "global", "window_animation_scale")
                + "settings put global transition_animation_scale 1; settings put global animator_duration_scale 1; "
                + restoreCmd(c, "secure", "lock_screen_show_notifications")
                + "settings put secure accessibility_display_daltonizer_enabled 0; setprop debug.hwui.force_dark false; wm density reset; ";
        StringBuilder un = new StringBuilder(cmd);
        for (String pkg : AppTools.restricted(c)) un.append("cmd appops set ").append(pkg).append(" RUN_ANY_IN_BACKGROUND allow; ");
        for (String pkg : AppTools.hidden(c)) un.append("pm enable ").append(pkg).append("; ");
        p(c).edit().putInt(Prefs.SYS_COLOR, 0).putBoolean(Prefs.T_120, false).putInt(Prefs.T_ANIM, 2)
                .putBoolean(Prefs.T_FORCE_DARK, false).putBoolean(Prefs.T_HIDE_STOCK_NOTIF, false)
                .putString("restrictedApps", "").putString("hiddenApps", "").apply();
        Shell.run(un.toString() + "true", (ok, o) -> { if (d != null) d.on(ok, o); });
    }

    /** Dipanggil bila servis bermula: setprop tidak kekal selepas but semula. */
    static void reapply(Context c) {
        if (p(c).getBoolean(Prefs.T_FORCE_DARK, false) && Shell.granted()) Shell.run("setprop debug.hwui.force_dark true");
    }

    // ======================= Bateri =======================

    static final class Batt {
        int level, cycles = -1, health, plugged;
        float temp;
        int capacityMah = -1;
    }

    static Batt battery(Context c) {
        Batt b = new Batt();
        Intent i = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return b;
        b.level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        b.temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        b.health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
        b.plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        b.cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1);
        try {
            BatteryManager bm = c.getSystemService(BatteryManager.class);
            int cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER); // µAh
            if (cc > 0 && b.level > 5) b.capacityMah = Math.round(cc / 1000f * 100f / b.level);
        } catch (Exception ignored) {
        }
        return b;
    }

    static int healthText(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return R.string.bh_good;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return R.string.bh_hot;
            case BatteryManager.BATTERY_HEALTH_DEAD: return R.string.bh_dead;
            case BatteryManager.BATTERY_HEALTH_COLD: return R.string.bh_cold;
            default: return R.string.bh_unknown;
        }
    }
}
