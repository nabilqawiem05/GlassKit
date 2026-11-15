package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Peta kedalaman wallpaper (256x256, 0 = jauh, 1 = dekat). Dikira sekali dalam telefon dengan
 * MiDaS v2.1 small (TFLite, tanpa internet). Jika model tiada, jatuh balik kepada 2 lapis
 * (subjek dekat, latar jauh) dari potongan subjek sedia ada.
 */
final class DepthMap {
    private DepthMap() {}

    static final int N = 256;

    static File file(Context c) { return new File(c.getFilesDir(), "depth.png"); }

    static final String MODEL_URL = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite";

    static File modelFile(Context c) { return new File(c.getFilesDir(), "midas.tflite"); }

    static boolean hasModel(Context c) { return modelFile(c).length() > 1_000_000; }

    interface Progress { void on(int pct, boolean done, boolean ok); }

    /** Muat turun model sekali (≈66 MB) ke storan app. Dipanggil di benang latar. */
    static void download(Context c, Progress cb) {
        File out = modelFile(c), part = new File(c.getFilesDir(), "midas.part");
        boolean ok = false;
        try {
            java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new java.net.URL(MODEL_URL).openConnection();
            hc.setInstanceFollowRedirects(true);
            hc.setConnectTimeout(15000);
            hc.setReadTimeout(30000);
            int code = hc.getResponseCode();
            // GitHub mengubah hala ke hos lain (https -> https)
            for (int i = 0; i < 5 && code / 100 == 3; i++) {
                String loc = hc.getHeaderField("Location");
                hc.disconnect();
                hc = (java.net.HttpURLConnection) new java.net.URL(loc).openConnection();
                hc.setConnectTimeout(15000);
                hc.setReadTimeout(30000);
                code = hc.getResponseCode();
            }
            long total = hc.getContentLengthLong(), got = 0;
            int last = -1;
            try (InputStream in = hc.getInputStream(); FileOutputStream fo = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    got += n;
                    int pct = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pct != last && cb != null) {
                        last = pct;
                        cb.on(pct, false, false);
                    }
                }
            }
            hc.disconnect();
            ok = part.length() > 1_000_000 && (total <= 0 || part.length() == total) && part.renameTo(out);
            if (ok) file(c).delete(); // kira semula kedalaman dengan model sebenar
        } catch (Throwable t) {
            CrashLog.write(c, t);
        } finally {
            part.delete();
        }
        if (cb != null) cb.on(100, true, ok);
    }

    /** Pastikan peta kedalaman terkini untuk wallpaper semasa; kembalikan bitmap (atau null). */
    static synchronized Bitmap ensure(Context c, Bitmap bg, Bitmap fg) {
        if (bg == null) return null;
        File out = file(c), src = DepthRenderer.bgFile(c), sub = DepthRenderer.fgFile(c);
        long need = Math.max(Math.max(src.lastModified(), sub.exists() ? sub.lastModified() : 0), modelFile(c).lastModified());
        if (out.exists() && out.lastModified() >= need) {
            Bitmap b = BitmapFactory.decodeFile(out.getAbsolutePath());
            if (b != null) return b;
        }
        float[] d = null;
        try {
            if (hasModel(c)) d = midas(c, bg);
        } catch (Throwable t) {
            CrashLog.write(c, t);
        }
        float[] mask = mask(fg);
        if (d == null) {
            // 2 lapis: latar jauh dengan sedikit kecerunan (atas jauh, bawah dekat), subjek dekat
            d = new float[N * N];
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) d[y * N + x] = 0.15f + 0.35f * y / (N - 1f);
        }
        normalize(d);
        if (mask != null) {
            // subjek mesti di hadapan supaya jam "terselit" di belakangnya
            for (int i = 0; i < d.length; i++) d[i] = d[i] + (Math.max(d[i], 0.92f) - d[i]) * mask[i];
        }
        blur(d, 2);
        blur(d, 2);
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        int[] px = new int[N * N];
        for (int i = 0; i < px.length; i++) {
            int v = Math.max(0, Math.min(255, Math.round(d[i] * 255)));
            px[i] = Color.rgb(v, v, v);
        }
        b.setPixels(px, 0, N, 0, 0, N, N);
        try (FileOutputStream fo = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        } catch (Exception ignored) {
        }
        return b;
    }

    private static float[] midas(Context c, Bitmap bg) throws Exception {
        ByteBuffer model;
        try (InputStream in = new java.io.FileInputStream(modelFile(c))) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] all = bo.toByteArray();
            model = ByteBuffer.allocateDirect(all.length).order(ByteOrder.nativeOrder());
            model.put(all);
            model.rewind();
        }
        org.tensorflow.lite.Interpreter.Options o = new org.tensorflow.lite.Interpreter.Options();
        o.setNumThreads(4);
        org.tensorflow.lite.Interpreter it = new org.tensorflow.lite.Interpreter(model, o);
        try {
            int[] ish = it.getInputTensor(0).shape();
            int ih = ish[1], iw = ish[2];
            Bitmap s = Bitmap.createScaledBitmap(bg.getConfig() == Bitmap.Config.HARDWARE
                    ? bg.copy(Bitmap.Config.ARGB_8888, false) : bg, iw, ih, true);
            int[] px = new int[iw * ih];
            s.getPixels(px, 0, iw, 0, 0, iw, ih);
            ByteBuffer inB = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder());
            float[] mean = {0.485f, 0.456f, 0.406f}, std = {0.229f, 0.224f, 0.225f};
            for (int v : px) {
                inB.putFloat(((Color.red(v) / 255f) - mean[0]) / std[0]);
                inB.putFloat(((Color.green(v) / 255f) - mean[1]) / std[1]);
                inB.putFloat(((Color.blue(v) / 255f) - mean[2]) / std[2]);
            }
            inB.rewind();
            int[] osh = it.getOutputTensor(0).shape();
            int oh = osh.length >= 3 ? osh[1] : ih, ow = osh.length >= 3 ? osh[2] : iw;
            int ch = osh.length >= 4 ? osh[3] : 1;
            ByteBuffer outB = ByteBuffer.allocateDirect(oh * ow * ch * 4).order(ByteOrder.nativeOrder());
            it.run(inB, outB);
            outB.rewind();
            float[] raw = new float[oh * ow];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = outB.getFloat();
                for (int k = 1; k < ch; k++) outB.getFloat();
            }
            // sampel semula ke N x N
            float[] d = new float[N * N];
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int sx = Math.min(ow - 1, x * ow / N), sy = Math.min(oh - 1, y * oh / N);
                    d[y * N + x] = raw[sy * ow + sx];
                }
            }
            return d;
        } finally {
            it.close();
        }
    }

    private static float[] mask(Bitmap fg) {
        if (fg == null) return null;
        try {
            Bitmap s = Bitmap.createScaledBitmap(fg, N, N, true);
            int[] px = new int[N * N];
            s.getPixels(px, 0, N, 0, 0, N, N);
            float[] m = new float[N * N];
            for (int i = 0; i < m.length; i++) m[i] = Color.alpha(px[i]) / 255f;
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void normalize(float[] d) {
        // potong 2% hujung supaya titik pelik tidak merosakkan julat
        float[] s = d.clone();
        java.util.Arrays.sort(s);
        float lo = s[(int) (s.length * 0.02f)], hi = s[(int) (s.length * 0.98f) - 1];
        float r = Math.max(1e-6f, hi - lo);
        for (int i = 0; i < d.length; i++) d[i] = Math.max(0f, Math.min(1f, (d[i] - lo) / r));
    }

    private static void blur(float[] d, int r) {
        float[] t = new float[d.length];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float s = 0;
                int n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = Math.max(0, Math.min(N - 1, x + k));
                    s += d[y * N + xx];
                    n++;
                }
                t[y * N + x] = s / n;
            }
        }
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float s = 0;
                int n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = Math.max(0, Math.min(N - 1, y + k));
                    s += t[yy * N + x];
                    n++;
                }
                d[y * N + x] = s / n;
            }
        }
    }
}
