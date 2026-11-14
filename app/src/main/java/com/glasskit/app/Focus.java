package com.glasskit.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mod Fokus: satu tekan tukar Jangan Ganggu, notifikasi island dan rupa kaca.
 * Keadaan asal disimpan dan dipulihkan bila fokus dimatikan.
 */
final class Focus {
    private Focus() {}

    static final int OFF = 0, WORK = 1, HOME = 2, SLEEP = 3, DRIVE = 4;
    static final int[] NAMES = {R.string.f_off, R.string.f_work, R.string.f_home, R.string.f_sleep, R.string.f_drive};

    static int current(Context c) { return Prefs.get(c).getInt(Prefs.FOCUS, OFF); }

    static void set(Context c, int mode) {
        SharedPreferences p = Prefs.get(c);
        int prev = p.getInt(Prefs.FOCUS, OFF);
        if (prev == mode) return;
        SharedPreferences.Editor e = p.edit();
        if (prev == OFF && mode != OFF) {
            // simpan keadaan asal
            e.putInt("fx_glass", Ui.glassMode(c))
                    .putInt("fx_appear", p.getInt(Prefs.W_APPEAR, 2))
                    .putBoolean("fx_noti", p.getBoolean(Prefs.PILL_NOTI, true))
                    .putInt("fx_dnd", filter(c));
        }
        int dnd;
        switch (mode) {
            case WORK:
                dnd = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                e.putInt(Prefs.GLASS_MODE, 0).putInt(Prefs.W_APPEAR, 0).putBoolean(Prefs.PILL_NOTI, true);
                break;
            case HOME:
                dnd = NotificationManager.INTERRUPTION_FILTER_ALL;
                e.putInt(Prefs.GLASS_MODE, 2).putInt(Prefs.W_APPEAR, 2).putBoolean(Prefs.PILL_NOTI, true);
                break;
            case SLEEP:
                dnd = NotificationManager.INTERRUPTION_FILTER_ALARMS;
                e.putInt(Prefs.GLASS_MODE, 1).putInt(Prefs.W_APPEAR, 1).putBoolean(Prefs.PILL_NOTI, false);
                break;
            case DRIVE:
                dnd = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                e.putInt(Prefs.GLASS_MODE, 1).putInt(Prefs.W_APPEAR, 1).putBoolean(Prefs.PILL_NOTI, false)
                        .putBoolean(Prefs.LIVE_ON, true);
                break;
            default:
                dnd = p.getInt("fx_dnd", NotificationManager.INTERRUPTION_FILTER_ALL);
                e.putInt(Prefs.GLASS_MODE, p.getInt("fx_glass", 0)).putInt(Prefs.W_APPEAR, p.getInt("fx_appear", 2))
                        .putBoolean(Prefs.PILL_NOTI, p.getBoolean("fx_noti", true));
        }
        e.putBoolean(Prefs.GLASS_DARK, (mode == SLEEP || mode == DRIVE)).putInt(Prefs.FOCUS, mode).apply();
        setFilter(c, dnd);
        GlassWidget.refreshAll(c);
        TasksWidget.refresh(c);
        NotesWidget.refresh(c);
        PillService s = PillService.inst;
        if (s != null) s.h.post(() -> s.focusChanged(mode));
        if (p.getBoolean(Prefs.FACE_FOCUS, false)) {
            int f = Faces.forFocus(mode);
            if (f >= 0) Faces.switchTo(c, f, null);
        }
    }

    private static int filter(Context c) {
        try {
            return c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        } catch (Exception e) {
            return NotificationManager.INTERRUPTION_FILTER_ALL;
        }
    }

    private static void setFilter(Context c, int f) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try {
            if (nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(f);
                return;
            }
        } catch (Exception ignored) {
        }
        if (Shell.granted()) {
            String v = f == NotificationManager.INTERRUPTION_FILTER_ALL ? "off"
                    : f == NotificationManager.INTERRUPTION_FILTER_ALARMS ? "alarms" : "priority";
            Shell.run("cmd notification set_dnd " + v);
        }
    }
}
