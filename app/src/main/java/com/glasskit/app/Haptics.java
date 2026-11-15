package com.glasskit.app;

import android.content.Context;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Bahasa getaran premium yang seragam (motor Pixel 8 Pro): ringan untuk sentuhan, "tik" untuk peluncur,
 * dentum lembut untuk kunci / buka kunci. Mengikut tetapan "Touch feedback" sistem (USAGE_TOUCH).
 */
final class Haptics {
    private Haptics() {}

    private static Vibrator vb;
    private static long lastTick;

    private static Vibrator v(Context c) {
        if (vb == null) vb = c.getApplicationContext().getSystemService(Vibrator.class);
        return vb;
    }

    private static boolean on(Context c) { return Prefs.get(c).getBoolean(Prefs.HAPTICS, true); }

    private static void play(Context c, int primitive, float scale, int fallback) {
        if (!on(c)) return;
        try {
            Vibrator x = v(c);
            if (x == null || !x.hasVibrator()) return;
            VibrationEffect e;
            if (x.areAllPrimitivesSupported(primitive)) {
                e = VibrationEffect.startComposition().addPrimitive(primitive, scale).compose();
            } else {
                e = VibrationEffect.createPredefined(fallback);
            }
            x.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        } catch (Throwable ignored) {
        }
    }

    /** Sentuhan butang / tile / kad (ringan & pendek). */
    static void tap(Context c) {
        play(c, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f, VibrationEffect.EFFECT_TICK);
    }

    /** Tik halus (peluncur setiap 10%, senarai). Dihadkan supaya tidak berdengung. */
    static void tick(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastTick < 35) return;
        lastTick = now;
        play(c, VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f, VibrationEffect.EFFECT_TICK);
    }

    /** Hujung peluncur (0% / 100%): lebih jelas. */
    static void edge(Context c) {
        play(c, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, VibrationEffect.EFFECT_CLICK);
    }

    /** Panel / helaian terbuka dan mendap. */
    static void soft(Context c) {
        play(c, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.35f, VibrationEffect.EFFECT_TICK);
    }

    /** Kunci / buka kunci: dentum lembut yang padat. */
    static void thud(Context c) {
        play(c, VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, VibrationEffect.EFFECT_HEAVY_CLICK);
    }

    /** Hidup / mati suis. */
    static void toggle(Context c, boolean on) {
        play(c, on ? VibrationEffect.Composition.PRIMITIVE_CLICK : VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                on ? 0.7f : 0.6f, VibrationEffect.EFFECT_CLICK);
    }
}
