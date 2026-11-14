package com.glasskit.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Aktiviti langsung" untuk Dynamic Island: navigasi (Google Maps, Waze), perjalanan / penghantaran
 * (Grab, Foodpanda), muat turun & muat naik, pemasa dan jam randik. Diambil daripada notifikasi berterusan.
 */
final class Live {
    private Live() {}

    static final int K_NAV = 1, K_RIDE = 2, K_PROGRESS = 3, K_TIMER = 4, K_STOPWATCH = 5, K_PRAYER = 6, K_OTHER = 7;

    static final class Item {
        String key, pkg;
        int kind;
        CharSequence title, text, sub;
        int progress = -1, max = 0;
        boolean indeterminate;
        long when;           // asas pemasa / jam randik
        boolean countDown;
        Drawable icon;       // ikon app
        Drawable big;        // ikon besar (contoh: anak panah Maps, gambar pemandu)
        PendingIntent open;
        long updated;
        int color;
    }

    static final Map<String, Item> items = new LinkedHashMap<>();

    private static final String[] NAV = {"com.google.android.apps.maps", "com.waze", "com.here.app.maps",
            "com.google.android.apps.mapslite"};
    private static final String[] RIDE = {"com.grabtaxi.passenger", "com.global.foodpanda.android", "com.ubercab",
            "com.ubercab.eats", "my.airasia.ride",
            "com.gojek.app", "sinet.startup.inDriver", "com.maxim.taxi"};
    private static final String[] CLOCK = {"com.google.android.deskclock", "com.android.deskclock"};

    private static boolean in(String p, String[] list) {
        for (String s : list) if (s.equals(p)) return true;
        return false;
    }

    /** Kembalikan Item jika notifikasi ini patut dipaparkan sebagai aktiviti langsung, atau null. */
    static Item from(Context c, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return null;
        Bundle ex = n.extras;
        if (ex == null) return null;
        String pkg = sbn.getPackageName();
        if (c.getPackageName().equals(pkg)) return null;
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null;
        if (CallOverlay.isCall(n)) return null;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;

        boolean ongoing = sbn.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        int prog = ex.getInt(Notification.EXTRA_PROGRESS, 0);
        boolean indet = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        boolean chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
        boolean countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false);
        String cat = n.category;
        String tpl = ex.getString(Notification.EXTRA_TEMPLATE, "");
        boolean promoted = (n.flags & 0x40000) != 0 || tpl.contains("ProgressStyle"); // Android 16: Live Updates

        int kind = 0;
        if (in(pkg, NAV) || Notification.CATEGORY_NAVIGATION.equals(cat)) kind = ongoing ? K_NAV : 0;
        else if (in(pkg, RIDE) || Notification.CATEGORY_TRANSPORT.equals(cat)) kind = (ongoing || promoted) ? K_RIDE : 0;
        else if (in(pkg, CLOCK) && ongoing) {
            kind = chrono ? (countDown ? K_TIMER : K_STOPWATCH) : K_OTHER;
        } else if (Notification.CATEGORY_STOPWATCH.equals(cat) && ongoing) kind = K_STOPWATCH;
        else if (ongoing && (max > 0 || indet) && (Notification.CATEGORY_PROGRESS.equals(cat) || max > 0)) kind = K_PROGRESS;
        else if (promoted && ongoing) kind = K_OTHER;
        if (kind == 0) return null;

        CharSequence t0 = ex.getCharSequence(Notification.EXTRA_TITLE), x0 = ex.getCharSequence(Notification.EXTRA_TEXT);
        String all = (t0 == null ? "" : t0) + " " + (x0 == null ? "" : x0);
        if (kind == K_NAV && !Notification.CATEGORY_NAVIGATION.equals(cat)
                && !all.matches("(?s).*\\d+([.,]\\d+)?\\s?(m|km|ft|mi|yd)\\b.*")) return null; // bukan navigasi (cth: kongsi lokasi)
        if (kind == K_RIDE && max <= 0 && !promoted && !Notification.CATEGORY_TRANSPORT.equals(cat)) {
            String low = all.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("running") || low.contains("berjalan") || low.trim().isEmpty()) return null;
        }

        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text) && !chrono && max <= 0) return null;

        Item it = new Item();
        it.key = sbn.getKey();
        it.pkg = pkg;
        it.kind = kind;
        it.title = title;
        it.text = text;
        it.sub = sub;
        it.max = max;
        it.progress = max > 0 ? Math.round(100f * prog / max) : -1;
        it.indeterminate = indet;
        it.countDown = countDown;
        it.when = n.when;
        it.open = n.contentIntent;
        it.updated = System.currentTimeMillis();
        it.color = n.color;
        try { it.icon = c.getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) {}
        try {
            if (n.getLargeIcon() != null) it.big = n.getLargeIcon().loadDrawable(c);
        } catch (Exception ignored) {
        }
        return it;
    }

    /** Aktiviti yang paling penting / terkini. */
    static Item current() {
        Item best = null;
        for (Item i : items.values()) {
            if (best == null || rank(i) > rank(best) || (rank(i) == rank(best) && i.updated > best.updated)) best = i;
        }
        return best;
    }

    private static int rank(Item i) {
        switch (i.kind) {
            case K_NAV: return 6;
            case K_RIDE: return 5;
            case K_TIMER: return 4;
            case K_PRAYER: return 3;
            case K_STOPWATCH: return 2;
            default: return 1;
        }
    }

    /** Teks pendek untuk island padat. */
    static String compactText(Context c, Item i) {
        long now = System.currentTimeMillis();
        if (i.kind == K_TIMER || i.kind == K_STOPWATCH || i.kind == K_PRAYER) {
            long ms = i.countDown || i.kind == K_TIMER || i.kind == K_PRAYER ? i.when - now : now - i.when;
            if (i.when > 0) return Ui.mmss(Math.max(0, ms));
        }
        if (i.progress >= 0 && i.kind == K_PROGRESS) return i.progress + "%";
        CharSequence t = i.kind == K_NAV ? i.title : (i.text != null ? i.text : i.title);
        if (i.kind == K_RIDE) {
            String m = findMinutes(i);
            if (m != null) return m;
        }
        if (t == null) return "";
        String s = t.toString();
        return s.length() > 12 ? s.substring(0, 11) + "…" : s;
    }

    /** Cari "X min" dalam teks (contoh Grab: "Arriving in 6 minutes"). */
    static String findMinutes(Item i) {
        String all = (i.title == null ? "" : i.title) + " " + (i.text == null ? "" : i.text) + " " + (i.sub == null ? "" : i.sub);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*(min|minit|mins|minutes)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(all);
        return m.find() ? m.group(1) + " min" : null;
    }

    static Bitmap bmp(Drawable d, int size) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(cv);
        return b;
    }
}
