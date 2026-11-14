package com.glasskit.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Bunyi ringkas yang disintesis (tiada fail audio): mengecas & buka kunci. */
final class Sounds {
    static final int CHARGE = 0, UNLOCK = 1;
    private static final int RATE = 44100;

    private Sounds() {}

    static void play(Context c, int type) {
        try {
            String key = type == CHARGE ? Prefs.SOUND_CHARGE : Prefs.SOUND_UNLOCK;
            if (!Prefs.get(c).getBoolean(key, true)) return;
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
            short[] pcm = type == CHARGE ? chime() : click();
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            t.write(pcm, 0, pcm.length);
            t.play();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { t.release(); } catch (Exception ignored) {}
            }, pcm.length * 1000L / RATE + 300);
        } catch (Exception ignored) {
        }
    }

    /** Dua nada naik yang lembut, seperti "ding" pengecas. */
    private static short[] chime() {
        int n = (int) (RATE * 0.9);
        short[] out = new short[n];
        add(out, 0, 0.55, 1318.5, 0.32);   // E6
        add(out, (int) (RATE * 0.12), 0.75, 1975.5, 0.28); // B6
        return out;
    }

    /** Klik lembut dua lapis untuk buka kunci. */
    private static short[] click() {
        int n = (int) (RATE * 0.16);
        short[] out = new short[n];
        add(out, 0, 0.05, 1900, 0.35);
        add(out, (int) (RATE * 0.045), 0.09, 2600, 0.3);
        return out;
    }

    private static void add(short[] out, int start, double dur, double freq, double vol) {
        int len = (int) (RATE * dur);
        for (int i = 0; i < len && start + i < out.length; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1.0, i / (RATE * 0.004)) * Math.exp(-t * (5.0 / dur));
            double v = Math.sin(2 * Math.PI * freq * t) * 0.8 + Math.sin(2 * Math.PI * freq * 2 * t) * 0.2;
            int s = out[start + i] + (int) (v * env * vol * 32767);
            out[start + i] = (short) Math.max(-32768, Math.min(32767, s));
        }
    }
}
