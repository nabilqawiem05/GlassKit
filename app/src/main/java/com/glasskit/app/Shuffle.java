package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Wallpaper bertukar setiap hari daripada album pilihan. Gambar disalin ke dalam app (tidak bergantung
 * pada kebenaran galeri), kemudian kesan depth dibuat secara automatik dengan ML Kit.
 */
final class Shuffle {
    private Shuffle() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "shuffle");
        d.mkdirs();
        return d;
    }

    static int count(Context c) {
        File[] f = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        return f == null ? 0 : f.length;
    }

    /** Salin gambar terpilih (di thread latar). Pulangkan bilangan yang berjaya. */
    static int importUris(Context c, java.util.List<Uri> uris) {
        File d = dir(c);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        int n = 0;
        for (Uri u : uris) {
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                if (b == null) continue;
                float sc = Math.min(1f, 2400f / Math.max(b.getWidth(), b.getHeight()));
                if (sc < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
                try (FileOutputStream out = new FileOutputStream(new File(d, String.format(Locale.US, "%03d.jpg", n)))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                n++;
            } catch (Exception ignored) {
            }
        }
        Prefs.get(c).edit().putInt("shufIdx", -1).putString("shufDay", "").apply();
        return n;
    }

    /** Dipanggil setiap hari (dan bila diminta): pasang gambar seterusnya + potong subjek. */
    static void daily(Context ctx, boolean force) {
        final Context c = ctx.getApplicationContext();
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.SHUFFLE_ON, false)) return;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (!force && today.equals(p.getString("shufDay", ""))) return;
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        int idx = (p.getInt("shufIdx", -1) + 1) % files.length;
        p.edit().putInt("shufIdx", idx).putString("shufDay", today).apply();
        final File src = files[idx];
        new Thread(() -> {
            try {
                Bitmap bg = BitmapFactory.decodeFile(src.getPath());
                if (bg == null) return;
                try (FileOutputStream out = new FileOutputStream(DepthRenderer.bgFile(c))) {
                    bg.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }
                DepthRenderer.fgFile(c).delete();
                p.edit().putLong(Prefs.IMG_VER, System.currentTimeMillis()).apply();
                Bitmap argb = bg.copy(Bitmap.Config.ARGB_8888, false);
                new Handler(Looper.getMainLooper()).post(() -> cut(c, argb));
            } catch (Throwable t) {
                CrashLog.write(c, t);
            }
        }, "gk-shuffle").start();
    }

    private static void cut(Context c, Bitmap argb) {
        try {
            SubjectSegmenter seg = SubjectSegmentation.getClient(new SubjectSegmenterOptions.Builder()
                    .enableForegroundBitmap().build());
            seg.process(InputImage.fromBitmap(argb, 0))
                    .addOnSuccessListener(r -> {
                        Bitmap fg = r.getForegroundBitmap();
                        if (fg == null) return;
                        new Thread(() -> {
                            try (FileOutputStream out = new FileOutputStream(DepthRenderer.fgFile(c))) {
                                fg.compress(Bitmap.CompressFormat.PNG, 100, out);
                                android.graphics.Rect b = c.getSystemService(android.view.WindowManager.class)
                                        .getMaximumWindowMetrics().getBounds();
                                DepthRenderer.autoPlace(c, Prefs.get(c), fg, b.width(), b.height());
                                Prefs.get(c).edit().putLong(Prefs.IMG_VER, System.currentTimeMillis()).apply();
                                Faces.saveCurrent(c);
                            } catch (Throwable t) {
                                CrashLog.write(c, t);
                            }
                        }, "gk-shuffle-save").start();
                    })
                    .addOnFailureListener(e -> CrashLog.write(c, e));
        } catch (Throwable t) {
            CrashLog.write(c, t);
        }
    }
}
