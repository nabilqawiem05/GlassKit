package com.glasskit.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Ikon kunci di lock screen dengan animasi buka gaya Face ID atau cap jari. */
class UnlockAnim extends View {
    static final int LOCKED = 0, FACE = 1, FINGER = 2, OPEN = 3;

    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Icon lock, unlock;
    private int state = LOCKED;
    private float t = 1f;
    private ValueAnimator anim;
    private Runnable after;

    UnlockAnim(Context c) {
        super(c);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        s.setShadowLayer(6, 0, 2, Color.argb(120, 0, 0, 0));
        f.setShadowLayer(6, 0, 2, Color.argb(120, 0, 0, 0));
        lock = new Icon(c, Icon.LOCK, Color.WHITE);
        unlock = new Icon(c, Icon.UNLOCK, 0xFF30D158);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    int state() { return state; }

    void setLocked() {
        if (anim != null) anim.cancel();
        state = LOCKED;
        t = 1f;
        invalidate();
    }

    /** Main animasi (FACE atau FINGER) kemudian tunjuk kunci terbuka. */
    void play(int kind, Runnable then) {
        if (state == FACE || state == FINGER) return;
        after = then;
        state = kind;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (state == FACE || state == FINGER) {
                    state = OPEN;
                    t = 0f;
                    ValueAnimator b = ValueAnimator.ofFloat(0f, 1f);
                    b.setDuration(Ui.SPRING_MS);
                    b.setInterpolator(Ui.SPRING);
                    b.addUpdateListener(x -> {
                        t = (float) x.getAnimatedValue();
                        invalidate();
                    });
                    b.start();
                    if (after != null) after.run();
                }
            }
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float sz = Math.min(w, h);
        switch (state) {
            case LOCKED:
                drawIcon(c, lock, cx, cy, sz * 0.42f, 1f);
                break;
            case OPEN:
                drawIcon(c, unlock, cx, cy, sz * 0.42f * (0.7f + 0.3f * t), 1f);
                break;
            case FACE:
                face(c, cx, cy, sz * 0.8f);
                break;
            case FINGER:
                finger(c, cx, cy, sz * 0.8f);
                break;
        }
    }

    private void drawIcon(Canvas c, Icon ic, float cx, float cy, float size, float alpha) {
        int s0 = Math.max(1, Math.round(size));
        ic.layout(0, 0, s0, s0);
        c.save();
        c.translate(cx - s0 / 2f, cy - s0 / 2f);
        ic.draw(c);
        c.restore();
    }

    /** Bingkai sudut Face ID yang berpusing, muka, kemudian tanda semak. */
    private void face(Canvas c, float cx, float cy, float size) {
        float half = size / 2f;
        float p1 = Math.min(1f, t / 0.55f), p2 = Math.max(0f, (t - 0.55f) / 0.45f);
        int col = p2 > 0 ? 0xFF30D158 : Color.WHITE;
        s.setColor(col);
        s.setStrokeWidth(size * 0.06f);
        c.save();
        c.rotate((1 - p1) * 90f, cx, cy);
        float k = half * (1.15f - 0.15f * p1), r = half * 0.35f, l = half * 0.45f;
        for (int i = 0; i < 4; i++) {
            c.save();
            c.rotate(90 * i, cx, cy);
            Path p = new Path();
            p.moveTo(cx - k, cy - k + l);
            p.lineTo(cx - k, cy - k + r);
            p.arcTo(new RectF(cx - k, cy - k, cx - k + 2 * r, cy - k + 2 * r), 180, 90, false);
            p.lineTo(cx - k + l, cy - k);
            c.drawPath(p, s);
            c.restore();
        }
        c.restore();
        if (p2 <= 0) {
            f.setColor(Color.WHITE);
            c.drawCircle(cx - half * 0.3f, cy - half * 0.18f, size * 0.04f, f);
            c.drawCircle(cx + half * 0.3f, cy - half * 0.18f, size * 0.04f, f);
            c.drawArc(new RectF(cx - half * 0.32f, cy - half * 0.1f, cx + half * 0.32f, cy + half * 0.38f), 20, 140, false, s);
        } else {
            check(c, cx, cy, half * 0.8f, p2);
        }
    }

    /** Lengkung cap jari yang dilukis berperingkat, kemudian tanda semak. */
    private void finger(Canvas c, float cx, float cy, float size) {
        float p1 = Math.min(1f, t / 0.6f), p2 = Math.max(0f, (t - 0.6f) / 0.4f);
        float half = size / 2f;
        s.setStrokeWidth(size * 0.045f);
        if (p2 <= 0) {
            for (int i = 0; i < 5; i++) {
                float r = half * (0.2f + i * 0.19f);
                s.setColor(Color.argb(Math.round(255 * Math.min(1f, p1 * 1.6f - i * 0.15f + 0.3f)), 255, 255, 255));
                float sweep = (200 - i * 12) * Math.min(1f, p1 * 1.3f);
                c.drawArc(new RectF(cx - r, cy - r * 1.15f, cx + r, cy + r * 1.15f), 200 + i * 6, sweep, false, s);
            }
            f.setColor(Color.argb(Math.round(70 * p1), 48, 209, 88));
            c.drawCircle(cx, cy, half * p1, f);
        } else {
            s.setColor(0xFF30D158);
            for (int i = 0; i < 5; i++) {
                float r = half * (0.2f + i * 0.19f) * (1 - p2 * 0.3f);
                s.setAlpha(Math.round(255 * (1 - p2)));
                c.drawArc(new RectF(cx - r, cy - r * 1.15f, cx + r, cy + r * 1.15f), 200 + i * 6, 200 - i * 12, false, s);
            }
            s.setAlpha(255);
            check(c, cx, cy, half * 0.8f, p2);
        }
    }

    private void check(Canvas c, float cx, float cy, float r, float p) {
        s.setColor(0xFF30D158);
        Path path = new Path();
        float x0 = cx - r * 0.5f, y0 = cy, x1 = cx - r * 0.1f, y1 = cy + r * 0.4f, x2 = cx + r * 0.55f, y2 = cy - r * 0.4f;
        path.moveTo(x0, y0);
        if (p < 0.4f) {
            float q = p / 0.4f;
            path.lineTo(x0 + (x1 - x0) * q, y0 + (y1 - y0) * q);
        } else {
            float q = (p - 0.4f) / 0.6f;
            path.lineTo(x1, y1);
            path.lineTo(x1 + (x2 - x1) * q, y1 + (y2 - y1) * q);
        }
        c.drawPath(path, s);
    }
}
