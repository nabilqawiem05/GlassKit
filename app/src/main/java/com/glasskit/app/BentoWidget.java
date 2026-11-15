package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RectF;

import java.util.List;

import static com.glasskit.app.GlassEngine.AL;
import static com.glasskit.app.GlassEngine.AC;
import static com.glasskit.app.GlassEngine.AR;
import static com.glasskit.app.GlassEngine.text;

/**
 * B · Home bento: jubin kaca kecil — jam, solat seterusnya, cuaca, langkah, dan acara/bateri.
 * Menggantikan widget jam + solat yang besar supaya wallpaper lebih kelihatan.
 */
public class BentoWidget extends GlassWidget {
    @Override
    boolean bare() { return true; }

    @Override
    Intent clickIntent(Context c) { return calendar(); }

    @Override
    void render(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) {
        float g = 8 * d;
        float half = (r.width() - g) / 2f;
        float top = Math.min(half, r.height() - g - 50 * d);
        boolean bottomRow = r.height() - top - g >= 44 * d;
        if (!bottomRow) top = r.height();

        // 1. jam (kiri, besar)
        RectF a = new RectF(r.left, r.top, r.left + half, r.top + top);
        GlassEngine.card(cv, a, t, d, false);
        float p = 12 * d;
        float ts = Math.min(a.width() * 0.34f, a.height() * 0.36f);
        text(cv, Cards.fmt(android.text.format.DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm"),
                a.left + p, a.centerY() + ts * 0.32f, ts, 700, t.primary, AL, a.width() - 2 * p);
        text(cv, Cards.fmt("EEE, d MMM"), a.left + p, a.bottom - p, 11.5f * d, 600, t.secondary, AL, a.width() - 2 * p);

        // 2. solat seterusnya (kanan atas)
        float rx = r.left + half + g;
        float th = (top - g) / 2f;
        RectF b = new RectF(rx, r.top, r.right, r.top + th);
        GlassEngine.card(cv, b, t, d, false);
        Object[] np = Cards.nextPrayerInfo(c);
        if (np != null) {
            text(cv, ((String) np[0]).toUpperCase(java.util.Locale.ROOT), b.left + p, b.top + 16 * d, 10 * d, 700, 0xFF30D158, AL, b.width() - 2 * p);
            text(cv, (String) np[1], b.left + p, b.bottom - p, Math.min(20 * d, b.height() * 0.42f), 700, t.primary, AL, b.width() - 2 * p);
            text(cv, (String) np[2], b.right - p, b.bottom - p, 10.5f * d, 600, t.secondary, AR, b.width() / 2f);
        } else {
            GlassEngine.icon(c, cv, Icon.PIN, b.centerX(), b.centerY(), 18 * d, t.muted);
        }

        // 3 & 4. cuaca & langkah (kanan bawah, dua kecil)
        float sw = (r.right - rx - g) / 2f;
        RectF w1 = new RectF(rx, b.bottom + g, rx + sw, r.top + top);
        RectF w2 = new RectF(w1.right + g, b.bottom + g, r.right, r.top + top);
        GlassEngine.card(cv, w1, t, d, false);
        GlassEngine.card(cv, w2, t, d, false);
        float is = Math.min(w1.width(), w1.height());
        Data.Weather wx = Data.weather(c);
        if (wx != null) {
            Draw3D.weather(cv, wx.code, wx.day, w1.centerX(), w1.centerY() - is * 0.14f, is * 0.42f);
            text(cv, Math.round(wx.temp) + "°", w1.centerX(), w1.bottom - is * 0.12f, is * 0.22f, 700, t.primary, AC, w1.width());
        } else {
            Draw3D.weather(cv, 2, true, w1.centerX(), w1.centerY(), is * 0.45f);
        }
        int steps = Data.steps(c);
        int goal = Math.max(1000, Prefs.get(c).getInt("stepGoal", 10000));
        float rr = Math.min(w2.width(), w2.height()) * 0.32f;
        GlassEngine.ring(cv, w2.centerX(), w2.centerY(), rr, rr * 0.24f, steps / (float) goal, 0xFF30D158, t);
        text(cv, steps >= 10000 ? (steps / 1000) + "k" : String.valueOf(steps), w2.centerX(), w2.centerY() + rr * 0.22f,
                rr * 0.55f, 700, t.primary, AC, w2.width());

        // 5. baris bawah: acara seterusnya, atau bateri
        if (bottomRow) {
            RectF e = new RectF(r.left, r.top + top + g, r.right, r.bottom);
            GlassEngine.card(cv, e, t, d, false);
            List<Data.Event> ev = Data.has(c, android.Manifest.permission.READ_CALENDAR) ? Data.events(c, 7, 1) : null;
            float cyy = e.centerY();
            if (ev != null && !ev.isEmpty()) {
                Data.Event x = ev.get(0);
                android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bp.setColor(x.color);
                cv.drawRoundRect(new RectF(e.left + p, cyy - 12 * d, e.left + p + 4 * d, cyy + 12 * d), 2 * d, 2 * d, bp);
                text(cv, x.title, e.left + p + 12 * d, cyy - 1 * d, 13.5f * d, 700, t.primary, AL, e.width() - 2 * p - 12 * d);
                text(cv, Data.eventTime(c, x), e.left + p + 12 * d, cyy + 14 * d, 11 * d, 600, t.secondary, AL, e.width() - 2 * p - 12 * d);
            } else {
                int lvl = Math.max(0, Ui.battery(c));
                text(cv, c.getString(R.string.w_battery_line, lvl), e.left + p, cyy + 5 * d, 13.5f * d, 700, t.primary, AL, e.width() * 0.6f);
                float bw = e.width() * 0.32f, bx = e.right - p - bw;
                android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bp.setColor(t.hairline);
                cv.drawRoundRect(new RectF(bx, cyy - 4 * d, bx + bw, cyy + 4 * d), 4 * d, 4 * d, bp);
                bp.setColor(lvl <= 20 ? 0xFFFF453A : 0xFF30D158);
                cv.drawRoundRect(new RectF(bx, cyy - 4 * d, bx + bw * lvl / 100f, cyy + 4 * d), 4 * d, 4 * d, bp);
            }
        }
    }
}
