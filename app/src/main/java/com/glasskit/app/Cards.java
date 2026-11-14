package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
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

/** Pelukis widget gaya iPhone: tanpa bingkai, kandungan 3D timbul. */
final class Cards {
    private Cards() {}

    static final int W = Color.WHITE;
    static final int W2 = Color.argb(215, 255, 255, 255);

    static int accent(Context c) { return Ui.accent(c, false); }

    static String time(Context c) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
    }

    static String fmt(String p) { return new SimpleDateFormat(p, Locale.getDefault()).format(new Date()); }

    // ---------------- Jam digital ----------------
    static void digital(Context c, Canvas cv, int w, int h) {
        float size = Math.min(h * 0.5f, w * 0.3f);
        float cy = h * 0.5f;
        Draw3D.text(cv, fmt("EEEE").toUpperCase(Locale.getDefault()), w / 2f, cy - size * 0.62f, size * 0.17f, 700, accent(c), Draw3D.CENTER);
        Draw3D.text(cv, time(c), w / 2f, cy + size * 0.36f, size, DepthRenderer.font(Prefs.get(c).getInt(Prefs.CLOCK_FONT, 0)), W, Draw3D.CENTER);
        Draw3D.text(cv, fmt("d MMMM"), w / 2f, cy + size * 0.68f, size * 0.17f, 600, W2, Draw3D.CENTER);
    }

    // ---------------- Jam analog ----------------
    static void analog(Context c, Canvas cv, int w, int h) {
        float r = Math.min(w, h) * 0.45f, cx = w / 2f, cy = h / 2f;
        for (int i = 0; i < 60; i++) {
            double a = Math.PI * 2 * i / 60;
            boolean hour = i % 5 == 0;
            float r1 = r * (hour ? 0.86f : 0.92f), r2 = r * 0.98f;
            Path t = new Path();
            float wd = r * (hour ? 0.03f : 0.012f);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            t.moveTo(cx + sx * r1 - sy * wd, cy + sy * r1 + sx * wd);
            t.lineTo(cx + sx * r2 - sy * wd, cy + sy * r2 + sx * wd);
            t.lineTo(cx + sx * r2 + sy * wd, cy + sy * r2 - sx * wd);
            t.lineTo(cx + sx * r1 + sy * wd, cy + sy * r1 - sx * wd);
            t.close();
            Draw3D.shape(cv, t, r * 0.15f, hour ? W : Color.argb(160, 255, 255, 255));
        }
        for (int i = 1; i <= 12; i++) {
            double a = Math.PI * 2 * i / 12;
            float rr = r * 0.7f, fs = r * 0.18f;
            Draw3D.text(cv, String.valueOf(i), cx + (float) Math.sin(a) * rr, cy - (float) Math.cos(a) * rr + fs * 0.36f,
                    fs, 700, W, Draw3D.CENTER);
        }
        Calendar now = Calendar.getInstance();
        float min = now.get(Calendar.MINUTE), hr = now.get(Calendar.HOUR) + min / 60f;
        hand(cv, cx, cy, hr / 12f, r * 0.5f, r * 0.07f, W);
        hand(cv, cx, cy, min / 60f, r * 0.76f, r * 0.05f, W);
        Path dot = new Path();
        dot.addCircle(cx, cy, r * 0.07f, Path.Direction.CW);
        Draw3D.shape(cv, dot, r * 0.3f, 0xFFFF9F0A);
    }

    private static void hand(Canvas cv, float cx, float cy, float frac, float len, float wid, int color) {
        Path p = new Path();
        p.addRoundRect(new RectF(cx - wid / 2, cy - len, cx + wid / 2, cy + wid), wid / 2, wid / 2, Path.Direction.CW);
        Matrix m = new Matrix();
        m.setRotate(frac * 360f, cx, cy);
        p.transform(m);
        Draw3D.shape(cv, p, len * 0.3f, color);
    }

    // ---------------- Jam + Kalendar ----------------
    static void clockCal(Context c, Canvas cv, int w, int h) {
        List<Data.Event> ev = Data.events(c, 7, 3);
        boolean wide = w > h * 1.5f;
        if (!wide) {
            float s = Math.min(w, h);
            Draw3D.text(cv, fmt("EEEE").toUpperCase(Locale.getDefault()), w * 0.08f, s * 0.2f, s * 0.11f, 700, accent(c), Draw3D.LEFT);
            Draw3D.text(cv, time(c), w * 0.07f, s * 0.47f, s * 0.3f, 800, W, Draw3D.LEFT);
            if (!ev.isEmpty()) {
                Data.Event e = ev.get(0);
                bar(cv, w * 0.08f, s * 0.6f, s * 0.035f, s * 0.3f, e.color);
                Draw3D.text(cv, Draw3D.fit(e.title, s * 0.1f, 700, w * 0.78f), w * 0.14f, s * 0.7f, s * 0.1f, 700, W, Draw3D.LEFT);
                Draw3D.text(cv, Data.eventTime(c, e), w * 0.14f, s * 0.84f, s * 0.085f, 500, W2, Draw3D.LEFT);
            } else {
                Draw3D.text(cv, c.getString(R.string.no_events), w * 0.08f, s * 0.75f, s * 0.095f, 600, W2, Draw3D.LEFT);
            }
            return;
        }
        float lw = w * 0.42f;
        Draw3D.text(cv, fmt("EEEE").toUpperCase(Locale.getDefault()), w * 0.05f, h * 0.22f, h * 0.11f, 700, accent(c), Draw3D.LEFT);
        Draw3D.text(cv, time(c), w * 0.045f, h * 0.58f, Math.min(h * 0.36f, lw * 0.36f), 800, W, Draw3D.LEFT);
        Draw3D.text(cv, fmt("d MMMM"), w * 0.05f, h * 0.8f, h * 0.1f, 600, W2, Draw3D.LEFT);
        events(c, cv, ev, lw + w * 0.02f, h * 0.12f, w - lw - w * 0.06f, h * 0.82f, h);
    }

    private static void bar(Canvas cv, float x, float y, float wd, float hh, int color) {
        Path p = new Path();
        p.addRoundRect(new RectF(x, y, x + wd, y + hh), wd / 2, wd / 2, Path.Direction.CW);
        Draw3D.shape(cv, p, hh * 0.4f, color);
    }

    private static void events(Context c, Canvas cv, List<Data.Event> ev, float x, float y, float wMax, float hMax, int h) {
        if (ev.isEmpty()) {
            Draw3D.text(cv, c.getString(R.string.no_events), x, y + hMax * 0.45f, h * 0.1f, 600, W2, Draw3D.LEFT);
            return;
        }
        float row = Math.min(hMax / ev.size(), h * 0.3f);
        float ts = row * 0.34f;
        for (Data.Event e : ev) {
            bar(cv, x, y + row * 0.08f, row * 0.07f, row * 0.78f, e.color);
            String label = Data.dayLabel(c, e.begin);
            Draw3D.text(cv, Draw3D.fit(e.title, ts, 700, wMax - row * 0.25f), x + row * 0.22f, y + ts * 1.05f, ts, 700, W, Draw3D.LEFT);
            Draw3D.text(cv, Draw3D.fit(label + " · " + Data.eventTime(c, e), ts * 0.82f, 500, wMax - row * 0.25f),
                    x + row * 0.22f, y + ts * 2.15f, ts * 0.82f, 500, W2, Draw3D.LEFT);
            y += row;
        }
    }

    // ---------------- Kalendar bulan ----------------
    static void month(Context c, Canvas cv, int w, int h) {
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_MONTH);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int first = (cal.get(Calendar.DAY_OF_WEEK) - cal.getFirstDayOfWeek() + 7) % 7;
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        float pad = Math.min(w, h) * 0.06f;
        float head = h * 0.15f;
        Draw3D.text(cv, fmt("MMMM").toUpperCase(Locale.getDefault()), pad, head * 0.85f, head * 0.62f, 800, 0xFFFF453A, Draw3D.LEFT);
        float gw = (w - 2 * pad) / 7f, gh = (h - head - pad * 1.2f) / 7f;
        float fs = Math.min(gw, gh) * 0.48f;
        SimpleDateFormat dn = new SimpleDateFormat("EEEEE", Locale.getDefault());
        Calendar d = Calendar.getInstance();
        d.set(Calendar.DAY_OF_WEEK, d.getFirstDayOfWeek());
        for (int i = 0; i < 7; i++) {
            Draw3D.text(cv, dn.format(d.getTime()), pad + gw * (i + 0.5f), head + gh * 0.7f, fs * 0.85f, 700, W2, Draw3D.CENTER);
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
        for (int day = 1; day <= days; day++) {
            int idx = first + day - 1;
            float x = pad + gw * (idx % 7 + 0.5f), y = head + gh * (idx / 7 + 1.5f);
            if (day == today) {
                Path p = new Path();
                p.addCircle(x, y, Math.min(gw, gh) * 0.46f, Path.Direction.CW);
                Draw3D.shape(cv, p, gw, 0xFFFF453A);
            }
            Draw3D.text(cv, String.valueOf(day), x, y + fs * 0.36f, fs, day == today ? 800 : 600, W, Draw3D.CENTER);
        }
    }

    // ---------------- Up Next (senarai acara) ----------------
    static void upNext(Context c, Canvas cv, int w, int h) {
        float pad = Math.min(w, h) * 0.06f;
        float big = Math.min(h * 0.16f, w * 0.18f);
        Draw3D.text(cv, fmt("EEEE").toUpperCase(Locale.getDefault()), pad, pad + big * 0.35f, big * 0.3f, 700, 0xFFFF453A, Draw3D.LEFT);
        Draw3D.text(cv, fmt("d"), pad, pad + big * 1.25f, big, 800, W, Draw3D.LEFT);
        int max = Math.max(1, Math.min(6, (int) ((h - pad * 2 - big * 1.4f) / (h * 0.15f))));
        List<Data.Event> ev = Data.events(c, 14, max);
        events(c, cv, ev, pad, pad + big * 1.55f, w - pad * 2, h - pad - (pad + big * 1.55f), Math.round(h * 0.5f));
    }

    // ---------------- Gambar ----------------
    static void photo(Context c, Canvas cv, int w, int h, int id) {
        float pad = Math.min(w, h) * 0.05f;
        RectF r = new RectF(pad, pad, w - pad, h - pad * 1.6f);
        float rad = Math.min(r.width(), r.height()) * 0.16f;
        Path p = new Path();
        p.addRoundRect(r, rad, rad, Path.Direction.CW);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(130, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(pad * 0.9f, BlurMaskFilter.Blur.NORMAL));
        cv.save();
        cv.translate(0, pad * 0.5f);
        cv.drawPath(p, sh);
        cv.restore();
        Bitmap b = Data.currentPhoto(c, id);
        if (b == null) {
            Draw3D.shape(cv, p, r.height() * 0.3f, Color.WHITE);
            Draw3D.icon(c, cv, Icon.CAMERA, r.centerX(), r.centerY() - r.height() * 0.08f, r.height() * 0.24f, W);
            Draw3D.text(cv, c.getString(R.string.photo_pick_hint), r.centerX(), r.centerY() + r.height() * 0.22f,
                    r.height() * 0.08f, 600, W, Draw3D.CENTER);
            return;
        }
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader bs = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        Matrix m = new Matrix();
        m.setScale(sc, sc);
        m.postTranslate(r.centerX() - b.getWidth() * sc / 2f, r.centerY() - b.getHeight() * sc / 2f);
        bs.setLocalMatrix(m);
        pp.setShader(bs);
        cv.drawPath(p, pp);
        // kilauan & bayang dalam untuk rasa timbul
        Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
        gl.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                new int[]{Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0)},
                new float[]{0f, 0.25f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        cv.drawPath(p, gl);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, pad * 0.12f));
        rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                Color.argb(160, 255, 255, 255), Color.argb(20, 255, 255, 255), Shader.TileMode.CLAMP));
        cv.drawPath(p, rim);
        Draw3D.text(cv, fmt("d MMMM yyyy"), r.left + rad * 0.6f, r.bottom - rad * 0.5f, r.height() * 0.075f, 700, W, Draw3D.LEFT);
    }

    // ---------------- Langkah ----------------
    static void steps(Context c, Canvas cv, int w, int h) {
        int steps = Data.steps(c);
        int goal = Math.max(1000, Prefs.get(c).getInt("stepGoal", 10000));
        float s = Math.min(w, h), cx = w / 2f, cy = h / 2f;
        float r = s * 0.38f, th = s * 0.1f;
        Draw3D.ring(cv, cx, cy, r, th, steps / (float) goal, 0xFF30D158, Color.argb(70, 48, 209, 88));
        if (steps > goal) Draw3D.ring(cv, cx, cy, r, th, (steps - goal) / (float) goal, 0xFF8CF5A8, Color.TRANSPARENT);
        Draw3D.text(cv, String.format(Locale.getDefault(), "%,d", steps), cx, cy + s * 0.04f, s * 0.17f, 800, W, Draw3D.CENTER);
        Draw3D.text(cv, c.getString(R.string.steps_unit), cx, cy + s * 0.15f, s * 0.07f, 600, W2, Draw3D.CENTER);
        Draw3D.text(cv, String.format(Locale.getDefault(), "%.1f km", steps * 0.00075f), cx, cy - s * 0.12f, s * 0.075f, 700,
                0xFF8CF5A8, Draw3D.CENTER);
    }

    // ---------------- Cuaca ----------------
    static void weather(Context c, Canvas cv, int w, int h) {
        Data.Weather wx = Data.weather(c);
        boolean wide = w > h * 1.6f;
        float s = Math.min(w, h);
        if (wx == null) {
            Draw3D.weather(cv, 2, true, w / 2f, h * 0.4f, s * 0.45f);
            Draw3D.text(cv, c.getString(R.string.wx_need), w / 2f, h * 0.8f, s * 0.08f, 600, W, Draw3D.CENTER);
            return;
        }
        float lx = s * 0.08f;
        String city = wx.city == null || wx.city.isEmpty() ? c.getString(R.string.wx_here) : wx.city;
        Draw3D.text(cv, Draw3D.fit(city, s * 0.1f, 700, (wide ? w * 0.45f : w * 0.8f)), lx, s * 0.17f, s * 0.1f, 700, W, Draw3D.LEFT);
        Draw3D.text(cv, Math.round(wx.temp) + "°", lx - s * 0.01f, s * 0.5f, s * 0.3f, 600, W, Draw3D.LEFT);
        Draw3D.weather(cv, wx.code, wx.day, (wide ? s * 0.78f : w - s * 0.22f), s * 0.22f, s * 0.3f);
        Draw3D.text(cv, Draw3D.fit(Data.condition(c, wx.code), s * 0.085f, 600, w * 0.8f), lx, s * 0.73f, s * 0.085f, 600, W, Draw3D.LEFT);
        Draw3D.text(cv, "H:" + Math.round(wx.hi) + "°  L:" + Math.round(wx.lo) + "°", lx, s * 0.86f, s * 0.08f, 600, W2, Draw3D.LEFT);
        if (wide && wx.dCode.length > 1) {
            int n = Math.min(5, wx.dCode.length - 1);
            float x0 = s * 1.1f, cw = (w - x0 - s * 0.05f) / n;
            for (int i = 0; i < n; i++) {
                float cx = x0 + cw * (i + 0.5f);
                Draw3D.text(cv, wx.dName[i + 1], cx, h * 0.22f, s * 0.085f, 700, W2, Draw3D.CENTER);
                Draw3D.weather(cv, wx.dCode[i + 1], true, cx, h * 0.48f, Math.min(cw * 0.8f, s * 0.22f));
                Draw3D.text(cv, Math.round(wx.dHi[i + 1]) + "°", cx, h * 0.76f, s * 0.09f, 700, W, Draw3D.CENTER);
                Draw3D.text(cv, Math.round(wx.dLo[i + 1]) + "°", cx, h * 0.9f, s * 0.08f, 500, W2, Draw3D.CENTER);
            }
        }
    }

    // ---------------- Bateri ----------------
    static void battery(Context c, Canvas cv, int w, int h) {
        int lvl = Math.max(0, Ui.battery(c));
        boolean ch = Ui.charging(c);
        float s = Math.min(w, h), cx = w / 2f, cy = h / 2f;
        int col = ch ? 0xFF30D158 : (lvl <= 20 ? 0xFFFF453A : W);
        Draw3D.ring(cv, cx, cy, s * 0.36f, s * 0.085f, lvl / 100f, col, Color.argb(70, 255, 255, 255));
        Draw3D.icon(c, cv, ch ? Icon.BOLT : Icon.BATTERY, cx, cy - s * 0.08f, s * 0.2f, col);
        Draw3D.text(cv, lvl + "%", cx, cy + s * 0.15f, s * 0.15f, 800, W, Draw3D.CENTER);
    }
}
