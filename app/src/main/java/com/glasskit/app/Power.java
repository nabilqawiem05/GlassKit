package com.glasskit.app;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Mod jimat GlassKit: hidup sendiri bila Penjimat Bateri aktif, atau bateri ≤ 15% dan tidak dicas.
 * Dalam mod ini animasi berterusan dihentikan dan lukisan berat dikurangkan supaya tidak sekat / crash.
 */
final class Power {
    private Power() {}

    private static volatile boolean cached;
    private static volatile long at;

    static boolean low(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - at < 5000) return cached; // semak sekurang-kurangnya setiap 5 saat (murah)
        boolean low = false;
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            if (pm != null && pm.isPowerSaveMode()) low = true;
            if (!low) {
                int lvl = Ui.battery(c);
                low = lvl >= 0 && lvl <= 15 && !Ui.charging(c);
            }
        } catch (Throwable ignored) {
        }
        cached = low;
        at = now;
        return low;
    }
}
