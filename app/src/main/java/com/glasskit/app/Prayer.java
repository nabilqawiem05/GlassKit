package com.glasskit.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Waktu solat dikira di telefon mengikut kriteria JAKIM (Subuh 18°, Isyak 18°, Asar Syafie, ihtiyati),
 * berdasarkan lokasi semasa. Disemak dengan jadual JAKIM November 2026 (beza ≤ 1 minit).
 */
final class Prayer {
    private Prayer() {}

    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    static final int[] NAMES = {R.string.p_fajr, R.string.p_sunrise, R.string.p_dhuhr, R.string.p_asr, R.string.p_maghrib, R.string.p_isha};

    static final class Day {
        long[] t = new long[6];
        boolean estimated;
        String date;
    }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    static boolean hasLocation(Context c) {
        SharedPreferences p = Prefs.get(c);
        return p.contains("lat") && p.contains("lon");
    }

    /** Waktu untuk hari ini (daripada cache, atau dikira). Null jika lokasi belum diketahui. */
    static Day today(Context c) {
        SharedPreferences p = Prefs.get(c);
        Day d = new Day();
        d.date = today();
        String cached = p.getString("prayTimes", null);
        if (d.date.equals(p.getString("prayDate", "")) && cached != null) {
            String[] a = cached.split(",");
            if (a.length == 6) {
                for (int i = 0; i < 6; i++) d.t[i] = Long.parseLong(a[i]);
                d.estimated = p.getBoolean("prayEst", false);
                return d;
            }
        }
        if (!hasLocation(c)) return null;
        double lat = p.getFloat("lat", 0), lon = p.getFloat("lon", 0);
        d.t = compute(lat, lon, Calendar.getInstance());
        d.estimated = true;
        return d;
    }

    /** Kira & simpan waktu hari ini (di peranti, tanpa internet). */
    static boolean fetch(Context c) {
        SharedPreferences p = Prefs.get(c);
        if (!hasLocation(c)) return false;
        save(c, compute(p.getFloat("lat", 0), p.getFloat("lon", 0), Calendar.getInstance()), false);
        return true;
    }

    private static void save(Context c, long[] t, boolean est) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(i > 0 ? "," : "").append(t[i]);
        Prefs.get(c).edit().putString("prayDate", today()).putString("prayTimes", sb.toString()).putBoolean("prayEst", est).apply();
    }

    /** Solat seterusnya (tidak termasuk Syuruk). Pulangkan {indeks, masa}; selepas Isyak = Subuh esok. */
    static long[] next(Context c) {
        Day d = today(c);
        if (d == null) return null;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            if (i == SUNRISE) continue;
            if (d.t[i] > now) return new long[]{i, d.t[i]};
        }
        SharedPreferences p = Prefs.get(c);
        Calendar k = Calendar.getInstance();
        k.add(Calendar.DAY_OF_MONTH, 1);
        long[] t = compute(p.getFloat("lat", 0), p.getFloat("lon", 0), k);
        // guna jarak Subuh hari ini sebagai pelarasan (lebih hampir dengan sumber rasmi)
        return new long[]{FAJR, t[FAJR]};
    }

    static String fmt(Context c, long t) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(t));
    }

    // ========================= Penjadualan =========================

    static void schedule(Context c) {
        if (!Prefs.get(c).getBoolean(Prefs.PRAYER_ON, false)) return;
        long[] n = next(c);
        if (n == null) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, Receiver.class).putExtra("idx", (int) n[0]).putExtra("at", n[1]);
        PendingIntent pi = PendingIntent.getBroadcast(c, 71, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n[1], pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n[1], pi);
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, Receiver.class);
        am.cancel(PendingIntent.getBroadcast(c, 71, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** Dipanggil oleh penggera atau sebagai sandaran setiap minit; tidak berulang untuk waktu yang sama. */
    static void fire(Context c, int idx, long at) {
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.PRAYER_ON, false)) return;
        if (p.getLong("prayFired", 0) == at) return;
        if (Math.abs(System.currentTimeMillis() - at) > 10 * 60_000L) return; // terlalu lewat, abaikan
        p.edit().putLong("prayFired", at).apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("prayer2", c.getString(R.string.p_channel), NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null); // bunyi dimainkan sendiri supaya boleh pilih fail azan
        nm.createNotificationChannel(ch);
        String name = c.getString(NAMES[idx]);
        Intent stop = new Intent(c, Receiver.class).setAction("stop");
        Notification n = new Notification.Builder(c, "prayer2")
                .setSmallIcon(R.drawable.ic_w_play)
                .setContentTitle(c.getString(R.string.p_time_now, name))
                .setContentText(fmt(c, at) + " · " + c.getString(R.string.p_source))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.p_stop_sound),
                        PendingIntent.getBroadcast(c, 72, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .setDeleteIntent(PendingIntent.getBroadcast(c, 73, stop, PendingIntent.FLAG_IMMUTABLE))
                .build();
        nm.notify(77, n);
        play(c);
        schedule(c);
    }

    private static MediaPlayer player;

    static void play(Context c) {
        stopSound();
        String s = Prefs.get(c).getString(Prefs.PRAYER_SOUND, "default");
        if ("none".equals(s)) return;
        Uri u = "default".equals(s) ? android.provider.Settings.System.DEFAULT_NOTIFICATION_URI : Uri.parse(s);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(c.getApplicationContext(), u);
            mp.setOnCompletionListener(m -> stopSound());
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            stopSound();
        }
    }

    static void stopSound() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if ("stop".equals(i.getAction())) {
                stopSound();
                return;
            }
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_TIME_CHANGED.equals(i.getAction())
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(i.getAction())) {
                schedule(c);
                return;
            }
            fire(c, i.getIntExtra("idx", 0), i.getLongExtra("at", 0));
        }
    }

    // ========================= Pengiraan tempatan =========================

    /** Kira waktu (ms epoch). Subuh 18°, Isyak 18°, Asar Syafie, + ihtiyati; dipadankan dengan jadual JAKIM 2026. */
    static long[] compute(double lat, double lon, Calendar day) {
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        double jd = julian(y, m, d) - lon / (15 * 24.0);
        double tz = TimeZone.getDefault().getOffset(day.getTimeInMillis()) / 3600000.0;
        double dhuhr = midDay(jd, 12.0 / 24);
        double fajr = dhuhr - tAngle(jd, 18, 5.0 / 24, lat);
        double sunrise = dhuhr - tAngle(jd, 0.833, 6.0 / 24, lat);
        double asr = dhuhr + asrT(jd, 1, 13.0 / 24, lat);
        double maghrib = dhuhr + tAngle(jd, 0.833, 18.0 / 24, lat);
        double isha = dhuhr + tAngle(jd, 18, 18.0 / 24, lat);
        double[] h = {fajr, sunrise, dhuhr, asr, maghrib, isha};
        double[] adj = {2, -1, 4, 2, 2, 2}; // minit ihtiyati (dipadankan dengan jadual JAKIM)
        long[] out = new long[6];
        for (int i = 0; i < 6; i++) {
            double hours = h[i] + tz - lon / 15.0 + adj[i] / 60.0;
            Calendar k = (Calendar) day.clone();
            k.set(Calendar.HOUR_OF_DAY, 0);
            k.set(Calendar.MINUTE, 0);
            k.set(Calendar.SECOND, 0);
            k.set(Calendar.MILLISECOND, 0);
            out[i] = k.getTimeInMillis() + Math.round(hours * 3600_000.0);
            out[i] = out[i] / 60000 * 60000; // bundar ke minit
        }
        return out;
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100.0), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    private static double[] sun(double jd) {
        double D = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * D, 360), q = fix(280.459 + 0.98564736 * D, 360);
        double L = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360);
        double e = 23.439 - 0.00000036 * D;
        double RA = darctan2(dcos(e) * dsin(L), dcos(L)) / 15;
        double eqt = q / 15 - fix(RA, 24);
        double decl = darcsin(dsin(e) * dsin(L));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        double eqt = sun(jd + t)[1];
        return fix(12 - eqt, 24);
    }

    private static double tAngle(double jd, double angle, double t, double lat) {
        double decl = sun(jd + t)[0];
        double v = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat));
        v = Math.max(-1, Math.min(1, v));
        return darccos(v) / 15;
    }

    private static double asrT(double jd, double factor, double t, double lat) {
        double decl = sun(jd + t)[0];
        double angle = -darccot(factor + dtan(Math.abs(lat - decl)));
        return tAngle(jd, angle, t, lat);
    }

    private static double fix(double a, double b) { a = a - b * Math.floor(a / b); return a < 0 ? a + b : a; }
    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double darccot(double x) { return Math.toDegrees(Math.atan(1 / x)); }
}
