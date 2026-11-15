package com.glasskit.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

import java.util.Calendar;

/**
 * Mod Solat: telefon disenyapkan sendiri pada setiap waktu solat (tanpa perlu disentuh),
 * kemudian bunyi asal dipulihkan. Syuruk dilangkau; Zohor hari Jumaat diberi sekurang-kurangnya 60 minit.
 */
final class SolatMode {
    private SolatMode() {}

    static final int[] DELAYS = {0, 5, 10, 15};
    static final int[] DURS = {15, 20, 30, 45};
    private static final int RC_ON = 81, RC_OFF = 82;

    private static SharedPreferences p(Context c) { return Prefs.get(c); }

    static boolean enabled(Context c) { return p(c).getBoolean(Prefs.SOLAT_SILENT, false); }

    static boolean active(Context c) { return p(c).getLong("solatUntil", 0) > System.currentTimeMillis(); }

    /** Tetingkap senyap [mula, tamat] seterusnya (atau yang sedang berlaku). */
    static long[] window(Context c) {
        Prayer.Day d = Prayer.today(c);
        if (d == null) return null;
        int delay = p(c).getInt(Prefs.SOLAT_DELAY, 5), dur = p(c).getInt(Prefs.SOLAT_DUR, 30);
        long now = System.currentTimeMillis();
        Calendar k = Calendar.getInstance();
        boolean friday = k.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        for (int i = 0; i < 6; i++) {
            if (i == Prayer.SUNRISE) continue;
            long st = d.t[i] + delay * 60_000L;
            int mins = (i == Prayer.DHUHR && friday) ? Math.max(dur, 60) : dur;
            long en = st + mins * 60_000L;
            if (en > now) return new long[]{st, en, i};
        }
        // Subuh esok
        k.add(Calendar.DAY_OF_MONTH, 1);
        long[] t = Prayer.compute(p(c).getFloat("lat", 0), p(c).getFloat("lon", 0), k);
        long st = t[Prayer.FAJR] + delay * 60_000L;
        return new long[]{st, st + dur * 60_000L, Prayer.FAJR};
    }

    private static PendingIntent pi(Context c, String action, int rc, long end, int idx) {
        Intent i = new Intent(c, Prayer.Receiver.class).setAction(action).putExtra("end", end).putExtra("idx", idx);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void alarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) {
        }
    }

    /** Jadualkan senyap seterusnya (atau mula sekarang jika sudah dalam tetingkap). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c, "solat_on", RC_ON, 0, 0));
        if (!enabled(c)) {
            if (active(c)) off(c);
            return;
        }
        if (active(c)) return; // tamat dijadualkan oleh on()
        long[] w = window(c);
        if (w == null) return;
        if (w[0] <= System.currentTimeMillis()) on(c, w[1], (int) w[2]);
        else alarm(c, w[0], pi(c, "solat_on", RC_ON, w[1], (int) w[2]));
    }

    /** Sandaran setiap minit (jika penggera ditangguh oleh Doze). */
    static void tick(Context c) {
        if (!enabled(c)) return;
        long until = p(c).getLong("solatUntil", 0);
        if (until > 0 && until <= System.currentTimeMillis()) {
            off(c);
            return;
        }
        if (until == 0) {
            long[] w = window(c);
            if (w != null && w[0] <= System.currentTimeMillis() && w[1] > System.currentTimeMillis()
                    && p(c).getLong("solatDone", 0) != w[0]) on(c, w[1], (int) w[2]);
        }
    }

    static void on(Context c, long end, int idx) {
        if (!enabled(c) || end <= System.currentTimeMillis()) {
            schedule(c);
            return;
        }
        long[] w = window(c);
        SharedPreferences sp = p(c);
        AudioManager am = c.getSystemService(AudioManager.class);
        int target = sp.getInt(Prefs.SOLAT_MODE, 0) == 1 ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT;
        if (sp.getLong("solatUntil", 0) == 0) sp.edit().putInt("solatPrev", am.getRingerMode()).apply();
        sp.edit().putLong("solatUntil", end).putInt("solatSet", target).putLong("solatDone", w != null ? w[0] : 0).apply();
        setRinger(c, target);
        notifyOn(c, end, idx);
        alarm(c, end, pi(c, "solat_off", RC_OFF, end, idx));
    }

    static void off(Context c) {
        SharedPreferences sp = p(c);
        if (sp.getLong("solatUntil", 0) == 0) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        int prev = sp.getInt("solatPrev", AudioManager.RINGER_MODE_NORMAL), set = sp.getInt("solatSet", -1);
        // pulihkan hanya jika pengguna tidak menukar mod sendiri sepanjang solat
        if (am.getRingerMode() == set && prev != set) setRinger(c, prev);
        sp.edit().putLong("solatUntil", 0).apply();
        c.getSystemService(AlarmManager.class).cancel(pi(c, "solat_off", RC_OFF, 0, 0));
        c.getSystemService(NotificationManager.class).cancel(78);
        schedule(c);
    }

    static void setRinger(Context c, int mode) {
        AudioManager am = c.getSystemService(AudioManager.class);
        try {
            am.setRingerMode(mode);
        } catch (SecurityException e) {
            if (Shell.granted()) {
                Shell.run("cmd notification allow_dnd " + c.getPackageName(), (ok, out) -> {
                    try { am.setRingerMode(mode); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void notifyOn(Context c, long end, int idx) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("solat", c.getString(R.string.solat_title), NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        Intent stop = new Intent(c, Prayer.Receiver.class).setAction("solat_end");
        Notification n = new Notification.Builder(c, "solat")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentTitle(c.getString(R.string.solat_on_title, c.getString(Prayer.NAMES[idx])))
                .setContentText(c.getString(R.string.solat_on_body, Prayer.fmt(c, end)))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.solat_end_now),
                        PendingIntent.getBroadcast(c, 83, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
        nm.notify(78, n);
    }
}
