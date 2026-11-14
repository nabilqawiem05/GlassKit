package com.glasskit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Widget kecil: gelombang muzik, slider menegak kaca, bar kemajuan. */
final class Widgets {
    private Widgets() {}

    /** Gelombang muzik 5 bar (ala Live Activity iOS). */
    static class Wave extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;

        Wave(Context c) {
            super(c);
            p.setColor(Color.WHITE);
        }

        void setColor(int c) { p.setColor(c); invalidate(); }

        void setRunning(boolean r) { running = r; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            int n = 5;
            float w = getWidth(), h = getHeight();
            float gap = w / (n * 2f - 1);
            long t = SystemClock.uptimeMillis();
            for (int i = 0; i < n; i++) {
                double ph = t / 210.0 + i * 0.9 + Math.sin(t / 530.0 + i) * 0.8;
                float f = running ? (float) (0.28 + 0.72 * Math.abs(Math.sin(ph))) : 0.22f;
                float bh = Math.max(gap, h * f);
                float x = i * gap * 2;
                c.drawRoundRect(x, (h - bh) / 2f, x + gap, (h + bh) / 2f, gap / 2f, gap / 2f, p);
            }
            if (running && isShown()) postInvalidateOnAnimation();
        }
    }

    /** Slider menegak seperti Pusat Kawalan iOS. */
    static class VSlider extends View {
        interface Cb { void on(float v, boolean done); }

        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final Icon icon;
        private float value = 0.5f, startVal, startY;
        private Cb cb;

        VSlider(Context c, int iconKind) {
            super(c);
            bg.setColor(Color.argb(46, 255, 255, 255));
            fill.setColor(Color.argb(240, 255, 255, 255));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.dp(c, 0.8f));
            edge.setColor(Color.argb(60, 255, 255, 255));
            icon = new Icon(c, iconKind, Color.WHITE);
        }

        void setCb(Cb cb) { this.cb = cb; }

        void setValue(float v) {
            value = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.42f;
            RectF all = new RectF(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(all, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(all, bg);
            c.drawRect(0, h * (1 - value), w, h, fill);
            c.restore();
            c.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, r, edge);

            float is = w * 0.36f;
            int save = c.save();
            c.translate((w - is) / 2f, h - is - w * 0.3f);
            icon.layout(0, 0, (int) is, (int) is);
            icon.setColor(value > (w * 0.3f + is) / h ? Color.rgb(60, 60, 67) : Color.WHITE);
            icon.draw(c);
            c.restoreToCount(save);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    startVal = value;
                    startY = e.getY();
                    animate().scaleX(1.06f).scaleY(1.03f).setDuration(120).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setValue(startVal + (startY - e.getY()) / getHeight());
                    if (cb != null) cb.on(value, false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(420).start();
                    if (cb != null) cb.on(value, true);
                    return true;
            }
            return false;
        }
    }

    /** Bar kemajuan nipis dengan hujung bulat. */
    static class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;

        Bar(Context c) {
            super(c);
            track.setColor(Color.argb(70, 255, 255, 255));
            fill.setColor(Color.WHITE);
        }

        void setValue(float v) { value = Math.max(0f, Math.min(1f, v)); invalidate(); }

        void setColor(int c) { fill.setColor(c); invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            if (value > 0) c.drawRoundRect(0, 0, Math.max(h, w * value), h, r, r, fill);
        }
    }
}
