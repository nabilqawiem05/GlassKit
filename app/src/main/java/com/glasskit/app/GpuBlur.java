package com.glasskit.app;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Kabur di GPU pada resolusi rendah: gambar skrin dikecilkan dan dikaburkan oleh GPU,
 * hanya hasil kecil dibaca balik. Jauh lebih ringan daripada kabur CPU pada skrin penuh.
 */
final class GpuBlur {
    private GpuBlur() {}

    private static Handler bg;

    /** Benang latar khas untuk kerja kabur (tak ganggu UI). */
    static synchronized Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("gk-blur", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    /**
     * @param src    boleh bitmap HARDWARE (terus dari tangkapan skrin)
     * @param outW   lebar hasil
     * @param radius jejari kabur dalam piksel hasil (0 = tiada kabur, hanya kecilkan)
     */
    static Bitmap blur(Bitmap src, int outW, float radius) {
        if (src == null) return null;
        float sc = Math.min(1f, (float) outW / src.getWidth());
        int w = Math.max(2, Math.round(src.getWidth() * sc)), h = Math.max(2, Math.round(src.getHeight() * sc));
        ImageReader ir = null;
        HardwareRenderer r = null;
        Image img = null;
        try {
            ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            RenderNode node = new RenderNode("gk-blur");
            node.setPosition(0, 0, w, h);
            if (radius > 0.5f) node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            RecordingCanvas c = node.beginRecording();
            c.drawBitmap(src, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            node.endRecording();
            r = new HardwareRenderer();
            r.setSurface(ir.getSurface());
            r.setContentRoot(node);
            r.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            img = ir.acquireNextImage();
            if (img == null) return null;
            HardwareBuffer hb = img.getHardwareBuffer();
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB));
            Bitmap out = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, true);
            if (hb != null) hb.close();
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (img != null) try { img.close(); } catch (Throwable ignored) {}
            if (r != null) try { r.destroy(); } catch (Throwable ignored) {}
            if (ir != null) try { ir.close(); } catch (Throwable ignored) {}
        }
    }

    /** Kabur dengan GPU, jatuh balik ke CPU jika gagal. Radius ikut skala GlassArt (kotak 3 laluan). */
    static Bitmap blurOrCpu(Bitmap src, int outW, int boxRadius) {
        // kotak 3 laluan berjejari r ~ gaussian sigma sqrt(r(r+1)); RenderEffect radius ~ sigma / 0.577
        float sigma = (float) Math.sqrt(boxRadius * (boxRadius + 1.0));
        Bitmap b = Prefs.perf() ? blur(src, outW, boxRadius <= 0 ? 0 : sigma / 0.577f) : null;
        if (b != null) return b;
        Bitmap sw = src.getConfig() == Bitmap.Config.HARDWARE ? src.copy(Bitmap.Config.ARGB_8888, false) : src;
        return GlassArt.blur(sw, outW, boxRadius);
    }
}
