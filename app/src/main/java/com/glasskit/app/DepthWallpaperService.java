package com.glasskit.app;

import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class DepthWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new DepthEngine();
    }

    class DepthEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        // lukisan berat di thread sendiri: thread utama tidak pernah tersekat (elak ANR & wallpaper ditetap semula)
        private android.os.HandlerThread drawThread;
        private Handler bg2;
        private volatile boolean drawBusy, drawAgain;
        private SharedPreferences prefs;
        private KeyguardManager km;
        private Bitmap bg, fg;
        private long loadedVer = Long.MIN_VALUE;
        private volatile boolean visible;
        private volatile int width, height;
        private boolean registered;
        private Bitmap frame;

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                drawFrame();
                // USER_PRESENT kadang sampai sebelum keyguard betul-betul hilang
                handler.postDelayed(DepthEngine.this::drawFrame, 400);
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            prefs = Prefs.get(DepthWallpaperService.this);
            prefs.registerOnSharedPreferenceChangeListener(this);
            drawThread = new android.os.HandlerThread("gk-wall", android.os.Process.THREAD_PRIORITY_DISPLAY);
            drawThread.start();
            bg2 = new Handler(drawThread.getLooper());
            km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, f);
            }
            registered = true;
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            handler.removeCallbacksAndMessages(null);
            if (drawThread != null) drawThread.quitSafely();
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
            if (registered) {
                try { unregisterReceiver(receiver); } catch (Exception ignored) {}
                registered = false;
            }
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) drawFrame();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            // hanya tetapan yang mengubah gambar (bukan setiap tetapan yang ditulis)
            if (key == null || key.startsWith("clock") || key.equals(Prefs.IMG_VER) || key.equals(Prefs.POP_STYLE)
                    || key.equals(Prefs.WALL_MOOD) || key.equals(Prefs.LOCK_ONLY) || key.equals(Prefs.LOCK_HIJRI)
                    || key.equals(Prefs.HIJRI_ADJ) || key.equals(Prefs.LOCK_TOP_BLUR)) drawFrame();
        }

        @Override
        public WallpaperColors onComputeColors() {
            ensureLoaded();
            if (bg != null) {
                try { return WallpaperColors.fromBitmap(bg); } catch (Exception ignored) {}
            }
            return null;
        }

        private synchronized void ensureLoaded() {
            long ver = prefs.getLong(Prefs.IMG_VER, 0);
            if (ver == loadedVer) return;
            loadedVer = ver;
            bg = DepthRenderer.load(DepthRenderer.bgFile(DepthWallpaperService.this));
            fg = DepthRenderer.load(DepthRenderer.fgFile(DepthWallpaperService.this));
            try { notifyColorsChanged(); } catch (Exception ignored) {}
        }

        /** Jadualkan lukisan di thread latar (digabung: jika sibuk, lukis sekali lagi selepas siap). */
        private void drawFrame() {
            if (!visible || width == 0 || height == 0 || bg2 == null) return;
            if (drawBusy) {
                drawAgain = true;
                return;
            }
            drawBusy = true;
            bg2.post(() -> {
                try {
                    drawNow();
                } catch (Throwable ignored) {
                }
                drawBusy = false;
                if (drawAgain) {
                    drawAgain = false;
                    handler.post(this::drawFrame);
                }
            });
        }

        private void drawNow() {
            if (!visible || width == 0 || height == 0) return;
            ensureLoaded();
            boolean locked = km != null && km.isKeyguardLocked();
            boolean showClock = !prefs.getBoolean(Prefs.LOCK_ONLY, true) || locked || isPreview();

            // Lukis dalam Bitmap perisian (kesan kaca guna BlurMaskFilter), kemudian salin ke skrin
            try {
                if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                    frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                }
                DepthRenderer.draw(DepthWallpaperService.this, new Canvas(frame), width, height, bg, fg, prefs, showClock);
            } catch (Throwable t) {
                return;
            }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockHardwareCanvas();
                if (c != null) c.drawBitmap(frame, 0, 0, null);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) {}
                }
            }
        }
    }
}
