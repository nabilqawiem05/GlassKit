package com.glasskit.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * Mod bank: app bank Malaysia (MAE, CIMB, TNG dll.) menyekat telefon yang ada servis kebolehcapaian
 * dari luar Play Store. Bila app bank dibuka, GlassKit mematikan servisnya sendiri sementara (melalui Shizuku),
 * memulakan semula app bank supaya semakannya lulus, kemudian menghidupkan semula GlassKit bila app bank ditutup.
 */
final class BankGuard {
    private BankGuard() {}

    private static final String[] KEYS = {"maybank", "m2u", "cimb", "publicbank", "pbebank", "pbengage", "rhb",
            "hongleong", "hlb", "ambank", "bankislam", "bsn", "affin", "alliancebank", "ocbc", "uob", "hsbc",
            "bankrakyat", "muamalat", "agrobank", "gxbank", "gxs", "aeonbank", "boost", "tngdigital", "bigpay",
            "shopeepay", "standardchartered", "citibank", "kfh", "mbsb", "alrajhi"};
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean watching;
    private static int awayCount;

    static boolean isBank(Context c, String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.startsWith("com.glasskit") || p.startsWith("com.android") || p.startsWith("com.google")) return false;
        String extra = Prefs.get(c).getString(Prefs.BANK_EXTRA, "");
        for (String e : extra.split(",")) if (!e.isEmpty() && p.equals(e.trim())) return true;
        if (p.contains("bank")) return true;
        for (String k : KEYS) if (p.contains(k)) return true;
        return false;
    }

    private static String me(Context c) {
        return new ComponentName(c, PillService.class).flattenToString();
    }

    /** Senarai servis tanpa GlassKit. */
    private static String without(Context c, String list) {
        StringBuilder b = new StringBuilder();
        ComponentName mine = new ComponentName(c, PillService.class);
        if (list != null) for (String e : list.split(":")) {
            if (e.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(e);
            if (mine.equals(cn)) continue;
            if (b.length() > 0) b.append(':');
            b.append(e);
        }
        return b.toString();
    }

    /** Dipanggil oleh servis bila app bank muncul di depan. */
    static void pause(Context c, String pkg) {
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.BANK_PAUSE, true) || p.getBoolean(Prefs.BANK_PAUSED, false)) return;
        if (!Shell.granted()) {
            Toast.makeText(c, R.string.bank_need_shizuku, Toast.LENGTH_LONG).show();
            return;
        }
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String rest = without(c, list);
        p.edit().putBoolean(Prefs.BANK_PAUSED, true).putString(Prefs.BANK_PKG, pkg).commit();
        Toast.makeText(c, R.string.bank_paused, Toast.LENGTH_SHORT).show();
        String q = "'" + rest.replace("'", "") + "'";
        String cmd = "cmd statusbar send-disable-flag none; "
                + (TextUtils.isEmpty(rest) ? "settings delete secure enabled_accessibility_services; "
                : "settings put secure enabled_accessibility_services " + q + "; ")
                + "am force-stop " + pkg + "; sleep 0.5; "
                + "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1";
        Shell.run(cmd, (ok, out) -> watch(c.getApplicationContext()));
    }

    /** Pantau app di depan; bila bukan app bank lagi, hidupkan semula GlassKit. */
    static void watch(Context c) {
        if (watching) return;
        watching = true;
        awayCount = 0;
        H.postDelayed(() -> poll(c), 2500);
    }

    private static void poll(Context c) {
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.BANK_PAUSED, false)) {
            watching = false;
            return;
        }
        if (!Shell.granted()) {
            H.postDelayed(() -> poll(c), 5000);
            return;
        }
        Shell.run("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'; "
                + "dumpsys power | grep -m1 -E 'mWakefulness='", (ok, out) -> {
            String top = parsePkg(out);
            boolean asleep = out != null && out.contains("mWakefulness=Asleep");
            boolean away = asleep || (top != null && !isBank(c, top) && !top.equals("com.android.systemui"));
            awayCount = away ? awayCount + 1 : 0;
            if (awayCount >= 2) resume(c);
            else H.postDelayed(() -> poll(c), 2500);
        });
    }

    private static String parsePkg(String out) {
        if (out == null) return null;
        int i = out.indexOf(" u0 ");
        if (i < 0) return null;
        int s = i + 4, e = out.indexOf('/', s);
        return e > s ? out.substring(s, e).trim() : null;
    }

    /** Hidupkan semula servis GlassKit. */
    static void resume(Context c) {
        watching = false;
        SharedPreferences p = Prefs.get(c);
        p.edit().putBoolean(Prefs.BANK_PAUSED, false).apply();
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String rest = without(c, list);
        String all = TextUtils.isEmpty(rest) ? me(c) : rest + ":" + me(c);
        Shell.run("settings put secure enabled_accessibility_services '" + all.replace("'", "") + "'; "
                + "settings put secure accessibility_enabled 1", null);
    }

    /** Dipanggil bila proses bermula semula: jika masih dalam mod bank, sambung pantau. */
    static void onStart(Context c) {
        if (Prefs.get(c).getBoolean(Prefs.BANK_PAUSED, false)) watch(c.getApplicationContext());
    }
}
