package com.glasskit.app;

import android.view.Choreographer;

/**
 * Spring fizik sebenar (jisim-pegas-peredam). Satu sumber gerakan untuk seluruh GlassKit:
 * menyasar semula di tengah jalan mengekalkan halaju, jadi gerakan sentiasa "mengalir".
 */
final class Spring implements Choreographer.FrameCallback {
    interface Listener { void onUpdate(float value, float velocity); }

    /** Tetapan seragam (respons dalam saat, redaman 0..1). */
    static final float UI_RESPONSE = 0.5f, UI_DAMP = 0.8f;        // butang, kad, tile
    static final float BOUNCY_RESPONSE = 0.46f, BOUNCY_DAMP = 0.66f; // island, notifikasi masuk
    static final float PANEL_RESPONSE = 0.42f, PANEL_DAMP = 0.78f;   // panel ikut jari

    float value, velocity, target;
    private float k, c;
    private final Listener l;
    private Runnable end;
    private boolean running;
    private long last;
    private float minRange = 1f;

    Spring(float response, float damping, Listener l) {
        this.l = l;
        set(response, damping);
    }

    static Spring ui(Listener l) { return new Spring(UI_RESPONSE, UI_DAMP, l); }

    static Spring bouncy(Listener l) { return new Spring(BOUNCY_RESPONSE, BOUNCY_DAMP, l); }

    static Spring panel(Listener l) { return new Spring(PANEL_RESPONSE, PANEL_DAMP, l); }

    void set(float response, float damping) {
        float w = (float) (2 * Math.PI / response);
        k = w * w;
        c = 2 * damping * w;
    }

    /** Skala nilai (contoh piksel) supaya ambang berhenti sesuai. */
    Spring range(float r) {
        minRange = Math.max(0.001f, r);
        return this;
    }

    void snap(float v) {
        stop();
        value = target = v;
        velocity = 0;
        l.onUpdate(value, 0);
    }

    void to(float t) { to(t, velocity, null); }

    void to(float t, Runnable onEnd) { to(t, velocity, onEnd); }

    void to(float t, float vel, Runnable onEnd) {
        target = t;
        velocity = vel;
        end = onEnd;
        if (!running) {
            running = true;
            last = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    void stop() {
        if (running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
        end = null;
    }

    boolean running() { return running; }

    @Override
    public void doFrame(long now) {
        if (!running) return;
        float dt = last == 0 ? 1f / 120f : Math.min(0.034f, (now - last) / 1e9f);
        last = now;
        int steps = 4;
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float a = -k * (value - target) - c * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        boolean done = Math.abs(value - target) < 0.002f * minRange && Math.abs(velocity) < 0.02f * minRange;
        if (done) {
            value = target;
            velocity = 0;
            running = false;
        }
        l.onUpdate(value, velocity);
        if (running) {
            Choreographer.getInstance().postFrameCallback(this);
        } else if (end != null) {
            Runnable e = end;
            end = null;
            e.run();
        }
    }
}
