package com.glasskit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Calendar;
import java.util.Random;

/**
 * Wallpaper ikut waktu & cuaca: rona keemasan waktu matahari terbit / terbenam, biru malam yang lembut,
 * dan titisan hujan pada kaca bila hujan. Dilukis sekali seminit (tiada animasi berterusan, jimat bateri).
 */
final class Mood {
    private Mood() {}

    static void apply(Context c, Canvas cv, int w, int h) {
        long now = System.currentTimeMillis();
        long rise, set;
        Prayer.Day d = Prayer.today(c);
        if (d != null) {
            rise = d.t[Prayer.SUNRISE];
            set = d.t[Prayer.MAGHRIB];
        } else {
            Calendar k = Calendar.getInstance();
            k.set(Calendar.HOUR_OF_DAY, 7);
            k.set(Calendar.MINUTE, 0);
            rise = k.getTimeInMillis();
            k.set(Calendar.HOUR_OF_DAY, 19);
            set = k.getTimeInMillis();
        }
        float min = 60_000f;
        float toRise = (now - rise) / min, toSet = (now - set) / min;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // malam: biru gelap lembut + vignet
        float night = 0f;
        if (toSet > 20) night = Math.min(1f, (toSet - 20) / 50f);
        if (toRise < -20) night = Math.max(night, Math.min(1f, (-toRise - 20) / 50f));
        if (now > set || now < rise) night = Math.max(night, 0.0f);
        if (night > 0) {
            p.setColor(Color.argb(Math.round(110 * night), 8, 16, 48));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w / 2f, h * 0.45f, Math.max(w, h) * 0.75f,
                    Color.argb(0, 0, 0, 0), Color.argb(Math.round(120 * night), 0, 0, 10), Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
        // waktu keemasan: 40 min sekitar terbit, 60 min sebelum hingga 20 min selepas terbenam
        float gold = 0f;
        if (toRise > -30 && toRise < 50) gold = 1f - Math.abs(toRise - 10) / 40f;
        if (toSet > -60 && toSet < 25) gold = Math.max(gold, 1f - Math.abs(toSet + 18) / 42f);
        gold = Math.max(0f, Math.min(1f, gold));
        if (gold > 0) {
            p.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Color.argb(Math.round(95 * gold), 255, 120, 60), Color.argb(Math.round(55 * gold), 255, 170, 90),
                            Color.argb(0, 255, 200, 120)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
        // hujan: kelabu sejuk + titisan pada kaca
        Data.Weather wx = Data.weather(c);
        if (wx != null && System.currentTimeMillis() - wx.time < 3 * 3600_000L) {
            int code = wx.code;
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
            if (rain) {
                p.setColor(Color.argb(60, 60, 75, 95));
                cv.drawRect(0, 0, w, h, p);
                drops(cv, w, h, code >= 63 || code >= 81 ? 70 : 40);
            }
        }
    }

    private static void drops(Canvas cv, int w, int h, int n) {
        Random r = new Random(System.currentTimeMillis() / (15 * 60_000L)); // susunan bertukar setiap 15 min
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), hi = new Paint(Paint.ANTI_ALIAS_FLAG), sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        float unit = Math.min(w, h) / 100f;
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * w, y = r.nextFloat() * h;
            float rad = unit * (0.6f + r.nextFloat() * 1.6f);
            float stretch = 1f + r.nextFloat() * 0.5f;
            sh.setColor(Color.argb(45, 0, 0, 0));
            cv.drawOval(x - rad, y - rad * stretch + rad * 0.25f, x + rad, y + rad * stretch + rad * 0.25f, sh);
            body.setShader(new RadialGradient(x, y + rad * 0.3f, rad * 1.2f, Color.argb(30, 255, 255, 255), Color.argb(90, 220, 235, 255),
                    Shader.TileMode.CLAMP));
            cv.drawOval(x - rad, y - rad * stretch, x + rad, y + rad * stretch, body);
            hi.setColor(Color.argb(170, 255, 255, 255));
            cv.drawCircle(x - rad * 0.35f, y - rad * stretch * 0.4f, rad * 0.22f, hi);
        }
    }
}
