package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Poster kenalan: gambar & warna khas untuk pemanggil tertentu (dipadankan ikut nama). */
final class Posters {
    private Posters() {}

    static final int[] COLORS = {0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFF0A84FF, 0xFFBF5AF2, 0xFF64D2FF, 0xFF8E8E93};

    static final class Poster {
        long id;
        String name;
        int color;
        File file(Context c) { return new File(dir(c), id + ".jpg"); }
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "posters");
        d.mkdirs();
        return d;
    }

    static List<Poster> all(Context c) {
        List<Poster> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c).getString("posters", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Poster p = new Poster();
                p.id = o.getLong("id");
                p.name = o.optString("name");
                p.color = o.optInt("color", COLORS[0]);
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveAll(Context c, List<Poster> l) {
        JSONArray a = new JSONArray();
        try {
            for (Poster p : l) a.put(new JSONObject().put("id", p.id).put("name", p.name).put("color", p.color));
        } catch (Exception ignored) {
        }
        Prefs.get(c).edit().putString("posters", a.toString()).apply();
    }

    static void add(Context c, String name, int color, Bitmap photo) throws Exception {
        Poster p = new Poster();
        p.id = System.currentTimeMillis();
        p.name = name.trim();
        p.color = color;
        float sc = Math.min(1f, 1600f / Math.max(photo.getWidth(), photo.getHeight()));
        Bitmap b = sc < 1f ? Bitmap.createScaledBitmap(photo, Math.round(photo.getWidth() * sc), Math.round(photo.getHeight() * sc), true) : photo;
        try (FileOutputStream out = new FileOutputStream(p.file(c))) {
            b.compress(Bitmap.CompressFormat.JPEG, 92, out);
        }
        List<Poster> l = all(c);
        l.add(p);
        saveAll(c, l);
    }

    static void delete(Context c, long id) {
        List<Poster> l = all(c);
        for (Poster p : l) if (p.id == id) p.file(c).delete();
        l.removeIf(p -> p.id == id);
        saveAll(c, l);
    }

    static String norm(CharSequence s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        String t = s.toString().toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    /** Cari poster untuk nama pemanggil (sama, atau nama poster terkandung dalam nama pemanggil). */
    static Poster match(Context c, CharSequence caller) {
        String n = norm(caller);
        if (n.isEmpty()) return null;
        Poster best = null;
        for (Poster p : all(c)) {
            String k = norm(p.name);
            if (k.isEmpty()) continue;
            if (n.equals(k)) return p;
            if (n.contains(k) && (best == null || k.length() > norm(best.name).length())) best = p;
        }
        return best;
    }

    static Bitmap photo(Context c, Poster p) {
        try { return BitmapFactory.decodeFile(p.file(c).getPath()); } catch (Exception e) { return null; }
    }

    /** Latar kad panggilan: gambar poster memenuhi kad + rona warna poster di bawah + bingkai kaca. */
    static Drawable background(Context c, Bitmap photo, int color, float radiusPx) {
        return new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            @Override
            public void draw(Canvas cv) {
                RectF r = new RectF(getBounds());
                BitmapShader bs = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float sc = Math.max(r.width() / photo.getWidth(), r.height() / photo.getHeight());
                Matrix m = new Matrix();
                m.setScale(sc, sc);
                m.postTranslate(r.centerX() - photo.getWidth() * sc / 2f, r.top + r.height() * 0.42f - photo.getHeight() * sc * 0.42f);
                bs.setLocalMatrix(m);
                p.setShader(bs);
                cv.drawRoundRect(r, radiusPx, radiusPx, p);
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                        new int[]{Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0),
                                (color & 0x00FFFFFF) | 0xB0000000, (color & 0x00FFFFFF) | 0xE6000000},
                        new float[]{0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
                cv.drawRoundRect(r, radiusPx, radiusPx, p);
                p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                        new int[]{Color.argb(200, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(120, 255, 255, 255)},
                        null, Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, radiusPx * 0.04f));
                RectF b = new RectF(r);
                b.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
                cv.drawRoundRect(b, radiusPx, radiusPx, p);
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);
            }

            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
