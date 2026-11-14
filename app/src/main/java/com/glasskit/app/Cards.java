package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import static com.glasskit.app.GlassEngine.AC;
import static com.glasskit.app.GlassEngine.AL;
import static com.glasskit.app.GlassEngine.AR;
import static com.glasskit.app.GlassEngine.LARGE;
import static com.glasskit.app.GlassEngine.MEDIUM;
import static com.glasskit.app.GlassEngine.SMALL;
import static com.glasskit.app.GlassEngine.text;

/**
 * Kandungan widget Liquid Glass. Setiap pelukis menerima kawasan kandungan (r) di dalam kad,
 * tema semasa (t), skala piksel per dp (d) dan kelas saiz (kecil / sederhana / besar).
 */
final class Cards {
    private Cards() {}

    static String fmt(String p) { return new SimpleDateFormat(p, Locale.getDefault()).format(new Date()); }

    static String fmt(String p, long t, TimeZone z) {
        SimpleDateFormat f = new SimpleDateFormat(p, Locale.getDefault());
        if (z != null) f.setTimeZone(z);
        return f.format(new Date(t));
    }

    static String time(Context c) { return time(c, null); }

    static String time(Context c, TimeZone z) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return fmt(is24 ? "HH:mm" : "h:mm", System.currentTimeMillis(), z);
    }

    private static String ampm(Context c, TimeZone z) {
        if (android.text.format.DateFormat.is24HourFormat(c)) return null;
        return fmt("a", System.currentTimeMillis(), z);
    }

    private static String up(String s) { return s.toUpperCase(Locale.getDefault()); }

    // ======================================================================
    // JAM DIGITAL
    // ======================================================================
    static void digital(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        String alarm = Data.nextAlarm(c);
        if (size == SMALL) {
            text(cv, up(fmt("EEEE")), r.left, r.top + 12 * d, 12 * d, 700, t.accent, AL, r.width());
            float fs = GlassEngine.fit(time(c), 700, 46 * d, r.width());
            text(cv, time(c), r.left - 1 * d, r.top + 18 * d + fs * 0.95f, fs, 700, t.primary, AL);
            text(cv, fmt("d MMMM"), r.left, r.top + 30 * d + fs, 14 * d, 600, t.secondary, AL, r.width());
            if (alarm != null) alarmLine(c, cv, r.left, r.bottom - 2 * d, alarm, t, d);
            return;
        }
        if (size == MEDIUM) {
            float lw = r.width() * 0.56f;
            text(cv, up(fmt("EEEE")), r.left, r.top + 12 * d, 12 * d, 700, t.accent, AL, lw);
            float fs = GlassEngine.fit(time(c), 700, 58 * d, lw);
            text(cv, time(c), r.left - 1 * d, r.centerY() + fs * 0.36f, fs, 700, t.primary, AL);
            String ap = ampm(c, null);
            text(cv, fmt("d MMMM yyyy") + (ap != null ? "  ·  " + ap : ""), r.left, r.bottom - 2 * d, 13 * d, 600, t.secondary, AL, lw);
            // kanan: jam dunia
            worldList(c, cv, new RectF(r.left + lw + 10 * d, r.top, r.right, r.bottom), t, d, 2, alarm);
            return;
        }
        // besar
        text(cv, up(fmt("EEEE, d MMMM")), r.left, r.top + 13 * d, 13 * d, 700, t.accent, AL, r.width());
        float fs = GlassEngine.fit(time(c), 700, 92 * d, r.width());
        text(cv, time(c), r.left - 2 * d, r.top + 20 * d + fs * 0.92f, fs, 700, t.primary, AL);
        float y = r.top + 34 * d + fs;
        if (alarm != null) {
            alarmLine(c, cv, r.left, y, alarm, t, d);
            y += 10 * d;
        }
        hair(cv, r.left, r.right, y, t);
        worldList(c, cv, new RectF(r.left, y + 8 * d, r.right, r.bottom), t, d, 4, null);
    }

    private static void alarmLine(Context c, Canvas cv, float x, float y, String alarm, GlassEngine.Theme t, float d) {
        GlassEngine.icon(c, cv, Icon.BELL, x + 7 * d, y - 5 * d, 13 * d, t.muted);
        text(cv, alarm, x + 18 * d, y, 13 * d, 600, t.muted, AL);
    }

    private static void hair(Canvas cv, float x0, float x1, float y, GlassEngine.Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(t.hairline);
        p.setStrokeWidth(1f);
        cv.drawLine(x0, y, x1, y, p);
    }

    // ======================================================================
    // JAM DUNIA
    // ======================================================================
    static List<String> zones(Context c) {
        String s = Prefs.get(c).getString(Prefs.W_ZONES, "Asia/Makkah,Europe/London,America/New_York,Asia/Tokyo");
        List<String> out = new java.util.ArrayList<>();
        for (String z : s.split(",")) if (!z.trim().isEmpty()) out.add(z.trim());
        return out;
    }

    static String city(String zone) {
        String n = zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' ');
        return n;
    }

    /** Beza jam berbanding zon tempatan, contoh "+5J" / "Esok". */
    static String offset(Context c, TimeZone z) {
        long now = System.currentTimeMillis();
        int diff = (z.getOffset(now) - TimeZone.getDefault().getOffset(now)) / 60000;
        String h = (diff >= 0 ? "+" : "−") + Math.abs(diff / 60) + (diff % 60 != 0 ? ":" + Math.abs(diff % 60) : "")
                + c.getString(R.string.hour_abbr);
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance(z);
        int dd = b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
        if (b.get(Calendar.YEAR) != a.get(Calendar.YEAR)) dd = b.get(Calendar.YEAR) > a.get(Calendar.YEAR) ? 1 : -1;
        String day = dd > 0 ? c.getString(R.string.tomorrow) : dd < 0 ? c.getString(R.string.yesterday) : c.getString(R.string.today);
        return day + ", " + h;
    }

    private static void worldList(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int max, String alarm) {
        List<String> zs = zones(c);
        int n = Math.min(max, zs.size());
        if (n == 0) return;
        float rowH = Math.min(46 * d, r.height() / n);
        for (int i = 0; i < n; i++) {
            TimeZone z = TimeZone.getTimeZone(zs.get(i));
            float y = r.top + rowH * i;
            text(cv, city(zs.get(i)), r.left, y + rowH * 0.42f, 14 * d, 650, t.primary, AL, r.width() * 0.55f);
            text(cv, offset(c, z), r.left, y + rowH * 0.42f + 15 * d, 11 * d, 500, t.muted, AL, r.width() * 0.6f);
            text(cv, time(c, z), r.right, y + rowH * 0.42f + 7 * d, 22 * d, 600, t.primary, AR);
        }
    }

    static void world(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        List<String> zs = zones(c);
        if (size == SMALL) {
            worldList(c, cv, r, t, d, 2, null);
            return;
        }
        if (size == MEDIUM) {
            int n = Math.min(4, zs.size());
            float cw = r.width() / Math.max(1, n);
            float rad = Math.min(cw * 0.4f, r.height() * 0.32f);
            for (int i = 0; i < n; i++) {
                TimeZone z = TimeZone.getTimeZone(zs.get(i));
                float cx = r.left + cw * (i + 0.5f);
                float cy = r.top + rad + 2 * d;
                Calendar k = Calendar.getInstance(z);
                int hr = k.get(Calendar.HOUR_OF_DAY);
                dial(cv, cx, cy, rad, k, hr >= 6 && hr < 18, t, d);
                text(cv, city(zs.get(i)), cx, cy + rad + 17 * d, 12 * d, 650, t.primary, AC, cw - 4 * d);
                String off = offset(c, z);
                text(cv, off.substring(off.indexOf(',') + 2), cx, cy + rad + 31 * d, 11 * d, 500, t.muted, AC, cw - 4 * d);
            }
            return;
        }
        worldList(c, cv, r, t, d, Math.min(6, zs.size()), null);
    }

    /** Muka jam kecil (siang = cerah, malam = gelap) untuk jam dunia. */
    private static void dial(Canvas cv, float cx, float cy, float rad, Calendar k, boolean day, GlassEngine.Theme t, float d) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int face = day ? 0xFFF5F6F8 : 0xFF191B20;
        int ink = day ? 0xFF14161A : 0xFFFFFFFF;
        p.setShader(new LinearGradient(0, cy - rad, 0, cy + rad, face, GlassEngine.mix(face, day ? 0xFFDDE1E8 : 0xFF050505, 0.7f),
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 0.8f * d));
        p.setColor(day ? Color.argb(40, 0, 0, 0) : Color.argb(60, 255, 255, 255));
        cv.drawCircle(cx, cy, rad, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(GlassEngine.a(ink, 160));
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            float rr = rad * 0.82f;
            cv.drawCircle(cx + (float) Math.sin(a) * rr, cy - (float) Math.cos(a) * rr, Math.max(1f, rad * (i % 3 == 0 ? 0.045f : 0.025f)), p);
        }
        float min = k.get(Calendar.MINUTE), hr = k.get(Calendar.HOUR) + min / 60f;
        hand(cv, cx, cy, hr / 12f, rad * 0.5f, rad * 0.075f, ink);
        hand(cv, cx, cy, min / 60f, rad * 0.75f, rad * 0.055f, ink);
        p.setColor(t.accent);
        cv.drawCircle(cx, cy, rad * 0.07f, p);
    }

    private static void hand(Canvas cv, float cx, float cy, float frac, float len, float wid, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeWidth(wid);
        p.setStrokeCap(Paint.Cap.ROUND);
        double a = Math.PI * 2 * frac;
        cv.drawLine(cx, cy, cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
    }

    // ======================================================================
    // JAM ANALOG
    // ======================================================================
    static void analog(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        RectF face = r;
        if (size == MEDIUM) face = new RectF(r.left, r.top, r.left + r.height(), r.bottom);
        float rad = Math.min(face.width(), face.height()) / 2f, cx = face.centerX(), cy = face.centerY();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // tanda minit & jam
        for (int i = 0; i < 60; i++) {
            double a = Math.PI * 2 * i / 60;
            boolean h = i % 5 == 0;
            float r1 = rad * (h ? 0.86f : 0.9f), r2 = rad * 0.96f;
            p.setColor(h ? t.primary : GlassEngine.a(t.muted, 150));
            p.setStrokeWidth(rad * (h ? 0.028f : 0.012f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            cv.drawLine(cx + sx * r1, cy + sy * r1, cx + sx * r2, cy + sy * r2, p);
        }
        for (int i = 1; i <= 12; i++) {
            double a = Math.PI * 2 * i / 12;
            float rr = rad * 0.69f, fs = rad * 0.19f;
            text(cv, String.valueOf(i), cx + (float) Math.sin(a) * rr, cy - (float) Math.cos(a) * rr + fs * 0.36f, fs, 600, t.primary, AC);
        }
        Calendar now = Calendar.getInstance();
        text(cv, up(fmt("EEE d")), cx, cy + rad * 0.38f, rad * 0.12f, 700, t.accent, AC);
        float min = now.get(Calendar.MINUTE), hr = now.get(Calendar.HOUR) + min / 60f;
        // bayang jarum
        int shc = Color.argb(t.dark ? 120 : 50, 0, 0, 0);
        hand(cv, cx + rad * 0.015f, cy + rad * 0.03f, hr / 12f, rad * 0.5f, rad * 0.075f, shc);
        hand(cv, cx + rad * 0.015f, cy + rad * 0.03f, min / 60f, rad * 0.78f, rad * 0.05f, shc);
        hand(cv, cx, cy, hr / 12f, rad * 0.5f, rad * 0.075f, t.primary);
        hand(cv, cx, cy, min / 60f, rad * 0.78f, rad * 0.05f, t.primary);
        p.setColor(t.accent);
        cv.drawCircle(cx, cy, rad * 0.06f, p);
        p.setColor(t.dark ? 0xFF101114 : Color.WHITE);
        cv.drawCircle(cx, cy, rad * 0.025f, p);

        if (size == MEDIUM) {
            float x = face.right + 16 * d, w = r.right - x;
            text(cv, up(fmt("EEEE")), x, r.top + 14 * d, 12 * d, 700, t.accent, AL, w);
            text(cv, fmt("d MMMM"), x, r.top + 36 * d, 20 * d, 700, t.primary, AL, w);
            String alarm = Data.nextAlarm(c);
            if (alarm != null) alarmLine(c, cv, x, r.top + 58 * d, alarm, t, d);
            List<Data.Event> ev = Data.events(c, 1, 1);
            if (!ev.isEmpty()) eventRow(c, cv, x, r.bottom - 30 * d, w, ev.get(0), t, d);
        }
    }

    // ======================================================================
    // JAM + KALENDAR
    // ======================================================================
    static void clockCal(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        float lw = size == SMALL ? r.width() : r.width() * 0.44f;
        text(cv, up(fmt("EEEE")), r.left, r.top + 12 * d, 12 * d, 700, t.accent, AL, lw);
        float fs = GlassEngine.fit(time(c), 700, 48 * d, lw);
        text(cv, time(c), r.left - 1 * d, r.top + 16 * d + fs * 0.95f, fs, 700, t.primary, AL);
        text(cv, fmt("d MMMM"), r.left, r.top + 30 * d + fs, 14 * d, 600, t.secondary, AL, lw);
        if (size == SMALL) {
            List<Data.Event> ev = Data.events(c, 1, 1);
            if (!ev.isEmpty()) eventRow(c, cv, r.left, r.bottom - 30 * d, r.width(), ev.get(0), t, d);
            return;
        }
        RectF er = new RectF(r.left + lw + 12 * d, r.top, r.right, r.bottom);
        agenda(c, cv, er, t, d, size == LARGE ? 6 : 3, false);
    }

    // ======================================================================
    // KALENDAR BULAN
    // ======================================================================
    static void month(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        RectF g = r;
        if (size == MEDIUM) {
            float lw = r.width() * 0.42f;
            text(cv, up(fmt("EEEE")), r.left, r.top + 12 * d, 12 * d, 700, t.accent, AL, lw);
            text(cv, fmt("d"), r.left - 1 * d, r.top + 58 * d, 48 * d, 600, t.primary, AL);
            agenda(c, cv, new RectF(r.left, r.top + 70 * d, r.left + lw, r.bottom), t, d, 2, true);
            g = new RectF(r.left + lw + 10 * d, r.top, r.right, r.bottom);
        } else if (size == LARGE) {
            g = new RectF(r.left, r.top, r.right, r.top + r.height() * 0.62f);
            hair(cv, r.left, r.right, g.bottom + 6 * d, t);
            agenda(c, cv, new RectF(r.left, g.bottom + 14 * d, r.right, r.bottom), t, d, 3, false);
        }
        grid(c, cv, g, t, d);
    }

    private static void grid(Context c, Canvas cv, RectF g, GlassEngine.Theme t, float d) {
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_MONTH);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int first = (cal.get(Calendar.DAY_OF_WEEK) - cal.getFirstDayOfWeek() + 7) % 7;
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (first + days + 6) / 7;
        Set<Integer> busy = Data.eventDaysThisMonth(c);
        float head = 18 * d;
        text(cv, up(fmt("MMMM")), g.left + 2 * d, g.top + 12 * d, 12 * d, 750, t.accent, AL);
        float gw = g.width() / 7f, gh = (g.height() - head) / (rows + 1);
        float fs = Math.min(gw * 0.42f, gh * 0.55f);
        SimpleDateFormat dn = new SimpleDateFormat("EEEEE", Locale.getDefault());
        Calendar k = Calendar.getInstance();
        k.set(Calendar.DAY_OF_WEEK, k.getFirstDayOfWeek());
        for (int i = 0; i < 7; i++) {
            text(cv, dn.format(k.getTime()), g.left + gw * (i + 0.5f), g.top + head + gh * 0.6f, fs * 0.82f, 650, t.muted, AC);
            k.add(Calendar.DAY_OF_MONTH, 1);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int day = 1; day <= days; day++) {
            int idx = first + day - 1;
            float x = g.left + gw * (idx % 7 + 0.5f), y = g.top + head + gh * (idx / 7 + 1.5f);
            boolean isToday = day == today;
            if (isToday) {
                p.setColor(t.accent);
                cv.drawCircle(x, y, Math.min(gw, gh) * 0.46f, p);
            }
            boolean past = day < today;
            text(cv, String.valueOf(day), x, y + fs * 0.36f, fs, isToday ? 750 : 550,
                    isToday ? Color.WHITE : past ? t.muted : t.primary, AC);
            if (busy.contains(day) && !isToday) {
                p.setColor(GlassEngine.a(t.accent, 220));
                cv.drawCircle(x, y + fs * 0.75f, Math.max(1.5f, 1.6f * d), p);
            }
        }
    }

    // ======================================================================
    // ACARA / AGENDA / KIRA DETIK
    // ======================================================================
    private static void eventRow(Context c, Canvas cv, float x, float y, float w, Data.Event e, GlassEngine.Theme t, float d) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(e.color);
        cv.drawRoundRect(new RectF(x, y, x + 3.5f * d, y + 30 * d), 2 * d, 2 * d, p);
        text(cv, e.title, x + 10 * d, y + 12 * d, 13 * d, 650, t.primary, AL, w - 12 * d);
        text(cv, Data.eventTime(c, e), x + 10 * d, y + 27 * d, 11.5f * d, 500, t.secondary, AL, w - 12 * d);
    }

    /** Senarai acara; compact = tanpa tajuk hari. */
    private static void agenda(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int max, boolean compact) {
        if (!Data.has(c, android.Manifest.permission.READ_CALENDAR)) {
            text(cv, c.getString(R.string.w_need_calendar), r.left, r.top + 16 * d, 12.5f * d, 600, t.muted, AL, r.width());
            return;
        }
        int fit = Math.max(1, (int) (r.height() / (38 * d)));
        List<Data.Event> ev = Data.events(c, 7, Math.min(max, fit));
        if (ev.isEmpty()) {
            GlassEngine.icon(c, cv, Icon.NOTE, r.left + 9 * d, r.top + 12 * d, 16 * d, t.muted);
            text(cv, c.getString(R.string.w_no_events), r.left + 24 * d, r.top + 17 * d, 13 * d, 600, t.muted, AL, r.width() - 24 * d);
            return;
        }
        float y = r.top + 2 * d;
        String lastDay = null;
        for (Data.Event e : ev) {
            String day = Data.dayLabel(c, Math.max(e.begin, System.currentTimeMillis()));
            if (!compact && !day.equals(lastDay) && ev.size() > 1) {
                if (y + 50 * d > r.bottom && lastDay != null) break;
                text(cv, up(day), r.left, y + 10 * d, 10.5f * d, 700, t.muted, AL, r.width());
                y += 15 * d;
                lastDay = day;
            }
            if (y + 32 * d > r.bottom + 2 * d) break;
            eventRow(c, cv, r.left, y, r.width(), e, t, d);
            y += 38 * d;
        }
    }

    static String countdown(Context c, long to) {
        long m = Math.max(0, (to - System.currentTimeMillis()) / 60000);
        if (m < 1) return c.getString(R.string.w_now);
        if (m < 60) return c.getString(R.string.w_in_min, (int) m);
        long h = m / 60;
        if (h < 24) return c.getString(R.string.w_in_hm, (int) h, (int) (m % 60));
        return c.getString(R.string.w_in_days, (int) (h / 24));
    }

    static void upNext(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        if (size == SMALL) {
            text(cv, up(fmt("EEEE")), r.left, r.top + 12 * d, 12 * d, 700, t.accent, AL, r.width());
            text(cv, fmt("d"), r.left - 1 * d, r.top + 50 * d, 40 * d, 600, t.primary, AL);
            List<Data.Event> ev = Data.has(c, android.Manifest.permission.READ_CALENDAR) ? Data.events(c, 7, 1) : null;
            if (ev == null) {
                text(cv, c.getString(R.string.w_need_calendar), r.left, r.bottom - 4 * d, 11.5f * d, 600, t.muted, AL, r.width());
            } else if (ev.isEmpty()) {
                text(cv, c.getString(R.string.w_no_events), r.left, r.bottom - 4 * d, 12 * d, 600, t.muted, AL, r.width());
            } else {
                Data.Event e = ev.get(0);
                boolean live = e.begin <= System.currentTimeMillis();
                text(cv, live ? c.getString(R.string.w_now) : countdown(c, e.begin), r.left, r.bottom - 38 * d, 11.5f * d, 700,
                        e.color, AL, r.width());
                eventRow(c, cv, r.left, r.bottom - 32 * d, r.width(), e, t, d);
            }
            return;
        }
        if (size == MEDIUM) {
            float lw = r.width() * 0.3f;
            text(cv, up(fmt("EEE")), r.left, r.top + 12 * d, 12 * d, 700, t.accent, AL, lw);
            text(cv, fmt("d"), r.left - 1 * d, r.top + 54 * d, 44 * d, 600, t.primary, AL);
            List<Data.Event> ev = Data.events(c, 7, 1);
            if (!ev.isEmpty() && ev.get(0).begin > System.currentTimeMillis()) {
                text(cv, countdown(c, ev.get(0).begin), r.left, r.bottom - 4 * d, 12 * d, 700, ev.get(0).color, AL, lw);
            }
            agenda(c, cv, new RectF(r.left + lw + 6 * d, r.top, r.right, r.bottom), t, d, 3, true);
            return;
        }
        text(cv, up(fmt("EEEE")), r.left, r.top + 13 * d, 13 * d, 700, t.accent, AL, r.width());
        text(cv, fmt("d MMMM"), r.left, r.top + 40 * d, 26 * d, 700, t.primary, AL, r.width());
        agenda(c, cv, new RectF(r.left, r.top + 56 * d, r.right, r.bottom), t, d, 8, false);
    }

    // ======================================================================
    // GAMBAR
    // ======================================================================
    /** Gambar mengisi seluruh kad (dipotong ikut bucu), dengan kilauan kaca di atasnya. */
    static void photo(Context c, Canvas cv, RectF card, GlassEngine.Theme t, float d, int id) {
        Bitmap b = Data.currentPhoto(c, id);
        float rad = Math.min(t.radiusDp * d, Math.min(card.width(), card.height()) / 2f);
        if (b == null) {
            GlassEngine.icon(c, cv, Icon.CAMERA, card.centerX(), card.centerY() - 10 * d, 30 * d, t.secondary);
            text(cv, c.getString(R.string.photo_pick_hint), card.centerX(), card.centerY() + 26 * d, 12.5f * d, 600,
                    t.secondary, AC, card.width() - 24 * d);
            return;
        }
        Path p = new Path();
        RectF in = new RectF(card);
        in.inset(1.5f * d, 1.5f * d);
        p.addRoundRect(in, rad - 1.5f * d, rad - 1.5f * d, Path.Direction.CW);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader bs = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float sc = Math.max(in.width() / b.getWidth(), in.height() / b.getHeight());
        Matrix m = new Matrix();
        m.setScale(sc, sc);
        m.postTranslate(in.centerX() - b.getWidth() * sc / 2f, in.centerY() - b.getHeight() * sc / 2f);
        bs.setLocalMatrix(m);
        pp.setShader(bs);
        cv.drawPath(p, pp);
        // kilauan kaca di atas gambar
        Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
        float refl = t.reflect / 100f;
        gl.setShader(new LinearGradient(in.left, in.top, in.left + in.width() * 0.8f, in.top + in.height() * 0.8f,
                new int[]{Color.argb(Math.round(70 * refl), 255, 255, 255), 0, 0},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        cv.drawPath(p, gl);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.2f * d));
        rim.setShader(new LinearGradient(in.left, in.top, in.right, in.bottom,
                Color.argb(170, 255, 255, 255), Color.argb(40, 255, 255, 255), Shader.TileMode.CLAMP));
        cv.drawPath(p, rim);
    }

    // ======================================================================
    // AKTIVITI / LANGKAH
    // ======================================================================
    static void steps(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        int steps = Data.steps(c);
        int goal = Math.max(1000, Prefs.get(c).getInt("stepGoal", 10000));
        float km = steps * 0.00075f;
        int kcal = Math.round(steps * 0.04f);
        int green = t.dark ? 0xFF34E07A : 0xFF20B85E;
        if (!Data.has(c, android.Manifest.permission.ACTIVITY_RECOGNITION)) {
            text(cv, c.getString(R.string.w_need_activity), r.centerX(), r.centerY(), 12.5f * d, 600, t.muted, AC, r.width());
            return;
        }
        float ringR = size == SMALL ? Math.min(r.width(), r.height()) * 0.36f : r.height() * 0.42f;
        float cx = size == SMALL ? r.centerX() : r.left + ringR + 4 * d, cy = r.centerY() + (size == SMALL ? -6 * d : 0);
        GlassEngine.ring(cv, cx, cy, ringR, ringR * 0.22f, steps / (float) goal, green, t);
        if (steps > goal) GlassEngine.ring(cv, cx, cy, ringR, ringR * 0.22f, (steps - goal) / (float) goal, 0xFF7FF0A8, t);
        text(cv, String.format(Locale.getDefault(), "%,d", steps), cx, cy + ringR * 0.12f, ringR * 0.42f, 700, t.primary, AC, ringR * 1.4f);
        text(cv, c.getString(R.string.steps_unit), cx, cy + ringR * 0.42f, ringR * 0.2f, 600, t.muted, AC);
        if (size == SMALL) {
            text(cv, String.format(Locale.getDefault(), "%.1f km  ·  %d kcal", km, kcal), r.centerX(), r.bottom - 1 * d,
                    11.5f * d, 600, t.secondary, AC, r.width());
            return;
        }
        float x = cx + ringR + 20 * d, w = r.right - x;
        float y = r.top + 6 * d;
        String[][] rows = {
                {c.getString(R.string.w_distance), String.format(Locale.getDefault(), "%.2f km", km)},
                {c.getString(R.string.w_calories), kcal + " kcal"},
                {c.getString(R.string.w_goal), Math.round(100f * steps / goal) + "%"}};
        float rowH = Math.min(44 * d, r.height() / 3f);
        for (String[] row : rows) {
            text(cv, row[0], x, y + 12 * d, 11.5f * d, 600, t.muted, AL, w);
            text(cv, row[1], x, y + 32 * d, 20 * d, 700, t.primary, AL, w);
            y += rowH;
        }
    }

    // ======================================================================
    // CUACA
    // ======================================================================
    static void weather(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        Data.Weather wx = Data.weather(c);
        if (wx == null) {
            GlassEngine.weather(cv, 2, true, r.centerX(), r.centerY() - 14 * d, 44 * d, t);
            text(cv, c.getString(R.string.wx_need), r.centerX(), r.centerY() + 30 * d, 12 * d, 600, t.muted, AC, r.width());
            return;
        }
        String city = wx.city == null || wx.city.isEmpty() ? c.getString(R.string.wx_here) : wx.city;
        float topW = size == SMALL ? r.width() : (size == MEDIUM ? r.width() * 0.4f : r.width() * 0.6f);
        // blok utama
        GlassEngine.icon(c, cv, Icon.PIN, r.left + 6 * d, r.top + 8 * d, 11 * d, t.secondary);
        text(cv, city, r.left + 14 * d, r.top + 13 * d, 13.5f * d, 700, t.primary, AL, topW - 40 * d);
        float tf = size == LARGE ? 54 * d : 44 * d;
        text(cv, Math.round(wx.temp) + "°", r.left - 2 * d, r.top + 18 * d + tf * 0.9f, tf, 300, t.primary, AL);
        float iconS = size == SMALL ? 30 * d : 34 * d;
        if (size == SMALL) {
            GlassEngine.weather(cv, wx.code, wx.day, r.right - iconS * 0.5f, r.top + iconS * 0.55f, iconS, t);
            text(cv, Data.condition(c, wx.code), r.left, r.bottom - 18 * d, 12.5f * d, 650, t.primary, AL, r.width());
            text(cv, "H:" + Math.round(wx.hi) + "°  L:" + Math.round(wx.lo) + "°", r.left, r.bottom - 2 * d, 12 * d, 600,
                    t.secondary, AL, r.width());
            return;
        }
        float condY = r.top + 22 * d + tf;
        GlassEngine.weather(cv, wx.code, wx.day, r.left + 9 * d, condY + 3 * d, 18 * d, t);
        text(cv, Data.condition(c, wx.code), r.left + 22 * d, condY + 8 * d, 12.5f * d, 650, t.primary, AL, topW - 22 * d);
        text(cv, "H:" + Math.round(wx.hi) + "°  L:" + Math.round(wx.lo) + "°", r.left, condY + 25 * d, 12 * d, 600, t.secondary, AL, topW);

        if (size == MEDIUM) {
            hourly(c, cv, new RectF(r.left + topW + 4 * d, r.top, r.right, r.bottom), wx, t, d, 5);
            return;
        }
        // besar: butiran di kanan atas, jam di tengah, 5 hari di bawah
        float bx = r.left + topW + 6 * d;
        details(c, cv, new RectF(bx, r.top, r.right, r.top + 92 * d), wx, t, d);
        float hy = r.top + 104 * d;
        hair(cv, r.left, r.right, hy - 6 * d, t);
        hourly(c, cv, new RectF(r.left, hy, r.right, hy + 74 * d), wx, t, d, 6);
        hair(cv, r.left, r.right, hy + 80 * d, t);
        daily(c, cv, new RectF(r.left, hy + 88 * d, r.right, r.bottom), wx, t, d);
    }

    private static void hourly(Context c, Canvas cv, RectF r, Data.Weather wx, GlassEngine.Theme t, float d, int n) {
        if (wx.hCode.length == 0) {
            // data lama: guna ramalan harian
            int m = Math.min(n, wx.dCode.length - 1);
            if (m <= 0) return;
            float cw = r.width() / m;
            for (int i = 0; i < m; i++) {
                float cx = r.left + cw * (i + 0.5f);
                text(cv, wx.dName[i + 1], cx, r.top + 14 * d, 11.5f * d, 650, t.secondary, AC);
                GlassEngine.weather(cv, wx.dCode[i + 1], true, cx, r.centerY(), Math.min(cw * 0.7f, 26 * d), t);
                text(cv, Math.round(wx.dHi[i + 1]) + "°", cx, r.bottom - 2 * d, 13 * d, 650, t.primary, AC);
            }
            return;
        }
        int m = Math.min(n, wx.hCode.length);
        float cw = r.width() / m;
        for (int i = 0; i < m; i++) {
            float cx = r.left + cw * (i + 0.5f);
            text(cv, wx.hName[i], cx, r.top + 14 * d, 11.5f * d, 650, i == 0 ? t.primary : t.secondary, AC, cw);
            float iy = r.centerY() + 1 * d;
            GlassEngine.weather(cv, wx.hCode[i], wx.hDay[i], cx, iy - (wx.hRain[i] >= 30 ? 4 * d : 0), Math.min(cw * 0.7f, 24 * d), t);
            if (wx.hRain[i] >= 30) text(cv, wx.hRain[i] + "%", cx, iy + 14 * d, 9.5f * d, 700, 0xFF4FA8FF, AC);
            text(cv, Math.round(wx.hTemp[i]) + "°", cx, r.bottom - 2 * d, 13.5f * d, 650, t.primary, AC);
        }
    }

    private static void details(Context c, Canvas cv, RectF r, Data.Weather wx, GlassEngine.Theme t, float d) {
        String[][] items = {
                {c.getString(R.string.w_humidity), wx.humidity >= 0 ? wx.humidity + "%" : "—"},
                {c.getString(R.string.w_wind), wx.wind >= 0 ? Math.round(wx.wind) + " km/j" : "—"},
                {c.getString(R.string.w_uv), wx.uv >= 0 ? String.valueOf(wx.uv) : "—"},
                {c.getString(R.string.w_rain), wx.rainNow >= 0 ? wx.rainNow + "%" : (wx.dRain.length > 0 && wx.dRain[0] >= 0 ? wx.dRain[0] + "%" : "—")}};
        float cw = (r.width() - 6 * d) / 2f, ch = (r.height() - 6 * d) / 2f;
        for (int i = 0; i < 4; i++) {
            float x = r.left + (i % 2) * (cw + 6 * d), y = r.top + (i / 2) * (ch + 6 * d);
            RectF cr = new RectF(x, y, x + cw, y + ch);
            GlassEngine.chip(cv, cr, 12 * d, t);
            text(cv, items[i][0], x + 8 * d, y + 15 * d, 10 * d, 650, t.muted, AL, cw - 12 * d);
            text(cv, items[i][1], x + 8 * d, y + ch - 8 * d, 15 * d, 700, t.primary, AL, cw - 12 * d);
        }
    }

    private static void daily(Context c, Canvas cv, RectF r, Data.Weather wx, GlassEngine.Theme t, float d) {
        int n = Math.min(5, wx.dCode.length);
        if (n == 0) return;
        float rowH = Math.min(30 * d, r.height() / n);
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, wx.dLo[i]);
            hi = Math.max(hi, wx.dHi[i]);
        }
        float span = Math.max(1f, hi - lo);
        for (int i = 0; i < n; i++) {
            float y = r.top + rowH * i + rowH * 0.66f;
            text(cv, i == 0 ? c.getString(R.string.today) : wx.dName[i], r.left, y, 13 * d, 650, t.primary, AL, 64 * d);
            GlassEngine.weather(cv, wx.dCode[i], true, r.left + 84 * d, y - 4 * d, 20 * d, t);
            if (wx.dRain.length > i && wx.dRain[i] >= 30) text(cv, wx.dRain[i] + "%", r.left + 100 * d, y, 10 * d, 700, 0xFF4FA8FF, AL);
            float bx0 = r.left + r.width() * 0.5f, bx1 = r.right - 34 * d;
            text(cv, Math.round(wx.dLo[i]) + "°", bx0 - 6 * d, y, 13 * d, 600, t.muted, AR);
            text(cv, Math.round(wx.dHi[i]) + "°", r.right, y, 13 * d, 650, t.primary, AR);
            RectF track = new RectF(bx0, y - 7 * d, bx1, y - 3 * d);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(GlassEngine.a(t.primary, t.dark ? 36 : 26));
            cv.drawRoundRect(track, 2 * d, 2 * d, p);
            float a = (wx.dLo[i] - lo) / span, b = (wx.dHi[i] - lo) / span;
            RectF fill = new RectF(bx0 + track.width() * a, track.top, bx0 + track.width() * b, track.bottom);
            p.setShader(new LinearGradient(fill.left, 0, fill.right, 0, 0xFF5AC8FA, 0xFFFFB340, Shader.TileMode.CLAMP));
            cv.drawRoundRect(fill, 2 * d, 2 * d, p);
        }
    }

    // ======================================================================
    // BATERI
    // ======================================================================
    static void battery(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        int lvl = Math.max(0, Ui.battery(c));
        boolean ch = Ui.charging(c);
        int green = t.dark ? 0xFF34E07A : 0xFF20B85E;
        int col = ch ? green : lvl <= 20 ? 0xFFFF453A : (t.dark ? 0xFFFFFFFF : 0xFF14161A);
        List<Data.Device> bt = size == SMALL ? java.util.Collections.emptyList() : Data.btBatteries(c);
        if (size == SMALL) {
            float rad = Math.min(r.width(), r.height()) * 0.34f, cx = r.centerX(), cy = r.centerY() - 8 * d;
            GlassEngine.ring(cv, cx, cy, rad, rad * 0.2f, lvl / 100f, col, t);
            GlassEngine.icon(c, cv, ch ? Icon.BOLT : Icon.PHONE, cx, cy, rad * 0.75f, ch ? green : t.primary);
            text(cv, lvl + "%", r.left, r.bottom - 1 * d, 22 * d, 700, t.primary, AL);
            String st = ch ? chargeText(c) : (lvl <= 20 ? c.getString(R.string.w_low) : "");
            text(cv, st, r.right, r.bottom - 4 * d, 11 * d, 650, ch ? green : 0xFFFF453A, AR, r.width() * 0.55f);
            return;
        }
        if (size == MEDIUM) {
            int n = 1 + Math.min(3, bt.size());
            float cw = r.width() / 4f;
            for (int i = 0; i < 4; i++) {
                float cx = r.left + cw * (i + 0.5f), cy = r.top + r.height() * 0.4f, rad = Math.min(cw * 0.36f, r.height() * 0.3f);
                if (i >= n) {
                    GlassEngine.ring(cv, cx, cy, rad, rad * 0.2f, 0, t.muted, t);
                    continue;
                }
                int lv = i == 0 ? lvl : bt.get(i - 1).level;
                int cc = i == 0 ? col : lv <= 20 ? 0xFFFF453A : (t.dark ? 0xFFFFFFFF : 0xFF14161A);
                GlassEngine.ring(cv, cx, cy, rad, rad * 0.2f, lv / 100f, cc, t);
                int ic = i == 0 ? (ch ? Icon.BOLT : Icon.PHONE) : (bt.get(i - 1).headset ? Icon.SPEAKER : Icon.BT);
                GlassEngine.icon(c, cv, ic, cx, cy, rad * 0.75f, i == 0 && ch ? green : t.primary);
                text(cv, lv + "%", cx, r.bottom - 2 * d, 14 * d, 700, t.primary, AC);
            }
            return;
        }
        // besar: senarai
        float rowH = Math.min(52 * d, r.height() / Math.max(4, 1 + bt.size()));
        float y = r.top;
        batteryRow(c, cv, r, y, rowH, Build_MODEL(), lvl, col, ch ? Icon.BOLT : Icon.PHONE, ch ? chargeText(c) : null, green, t, d);
        y += rowH;
        for (Data.Device dv : bt) {
            if (y + rowH > r.bottom + 1) break;
            int cc = dv.level <= 20 ? 0xFFFF453A : (t.dark ? 0xFFFFFFFF : 0xFF14161A);
            batteryRow(c, cv, r, y, rowH, dv.name, dv.level, cc, dv.headset ? Icon.SPEAKER : Icon.BT, null, green, t, d);
            y += rowH;
        }
        if (bt.isEmpty()) {
            text(cv, c.getString(R.string.w_no_bt), r.left, y + rowH * 0.55f, 12 * d, 600, t.muted, AL, r.width());
        }
    }

    private static String Build_MODEL() { return android.os.Build.MODEL; }

    private static String chargeText(Context c) {
        try {
            android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
            long ms = bm.computeChargeTimeRemaining();
            if (ms > 0) {
                long m = ms / 60000;
                return c.getString(R.string.w_full_in, m >= 60 ? (m / 60) + c.getString(R.string.hour_abbr) + " " + (m % 60) + "m" : m + "m");
            }
        } catch (Exception ignored) {
        }
        return c.getString(R.string.charging);
    }

    private static void batteryRow(Context c, Canvas cv, RectF r, float y, float h, String name, int lvl, int col, int icon,
                                   String sub, int green, GlassEngine.Theme t, float d) {
        GlassEngine.icon(c, cv, icon, r.left + 12 * d, y + h * 0.42f, 20 * d, icon == Icon.BOLT ? green : t.primary);
        text(cv, name, r.left + 30 * d, y + h * 0.38f, 13.5f * d, 650, t.primary, AL, r.width() * 0.55f);
        if (sub != null) text(cv, sub, r.left + 30 * d, y + h * 0.38f + 15 * d, 11 * d, 600, green, AL, r.width() * 0.55f);
        text(cv, lvl + "%", r.right, y + h * 0.38f + 4 * d, 17 * d, 700, t.primary, AR);
        GlassEngine.bar(cv, new RectF(r.left + r.width() * 0.58f, y + h * 0.3f, r.right - 46 * d, y + h * 0.3f + 6 * d), lvl / 100f, col, t);
    }
}
