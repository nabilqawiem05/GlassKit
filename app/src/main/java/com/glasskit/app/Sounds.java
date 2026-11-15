package com.glasskit.app;

import android.content.ContentValues;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bunyi UI GlassKit — set "Kristal": disintesis sendiri (tiada fail audio, tiada hak cipta orang lain).
 * Nada loceng kaca yang lembut dengan gema pendek: kunci, buka kunci, cas, tangkapan skrin, dan nada notifikasi.
 */
final class Sounds {
    static final int CHARGE = 0, UNLOCK = 1, LOCK = 2, SHOT = 3;
    static final int RATE = 44100;
    private static long lastPlay;
    private static int lastType = -1;

    private Sounds() {}

    static void play(Context c, int type) {
        try {
            String key = type == CHARGE ? Prefs.SOUND_CHARGE : type == UNLOCK ? Prefs.SOUND_UNLOCK
                    : type == LOCK ? Prefs.SOUND_LOCK : Prefs.SOUND_SHOT;
            if (!Prefs.get(c).getBoolean(key, true)) return;
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
            long now = System.currentTimeMillis();
            if (type == lastType && now - lastPlay < 400) return; // elak bunyi berganda
            lastType = type;
            lastPlay = now;
            short[] pcm = type == CHARGE ? charge() : type == UNLOCK ? unlock() : type == LOCK ? lock() : shot();
            playPcm(pcm);
        } catch (Exception ignored) {
        }
    }

    /** Main bunyi (untuk pratonton nada notifikasi). */
    static void playPcm(short[] pcm) {
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
    }

    // ======================= Bunyi =======================

    /** Buka kunci: dua titis kaca naik, ringan & cepat. */
    static short[] unlock() {
        double[] b = buf(0.55);
        bell(b, 0.00, 1567.98, 0.30, 0.22); // G6
        bell(b, 0.07, 2349.32, 0.36, 0.18); // D7
        return finish(b, 0.10);
    }

    /** Kunci: "tok" kayu lembut yang padat, rendah. */
    static short[] lock() {
        double[] b = buf(0.32);
        wood(b, 0.0, 330, 0.09, 0.42);
        wood(b, 0.012, 660, 0.05, 0.12);
        return finish(b, 0.06);
    }

    /** Cas: tiga nada naik (C6 E6 G6) dengan gema. */
    static short[] charge() {
        double[] b = buf(1.5);
        bell(b, 0.00, 1046.50, 0.9, 0.18);
        bell(b, 0.11, 1318.51, 0.9, 0.16);
        bell(b, 0.22, 1567.98, 1.1, 0.16);
        return finish(b, 0.22);
    }

    /** Tangkapan skrin: "syuk" lembut + klik kecil, seperti pengatup kamera yang halus. */
    static short[] shot() {
        double[] b = buf(0.3);
        noise(b, 0.0, 0.11, 0.16, 4500);
        wood(b, 0.004, 1800, 0.025, 0.12);
        noise(b, 0.07, 0.09, 0.09, 3000);
        return finish(b, 0.05);
    }

    // ---- nada notifikasi (disimpan sebagai fail .wav ke folder Notifications) ----
    static final String[] TONE_NAMES = {"GlassKit Kristal", "GlassKit Titis", "GlassKit Senja"};

    static short[] tone(int i) {
        double[] b;
        switch (i) {
            case 1: // Titis: dua titisan air
                b = buf(0.9);
                bell(b, 0.00, 1760.0, 0.35, 0.24);
                bell(b, 0.16, 1318.51, 0.55, 0.20);
                return finish(b, 0.16);
            case 2: // Senja: tiga nada turun yang hangat
                b = buf(1.4);
                bell(b, 0.00, 1174.66, 0.8, 0.17);
                bell(b, 0.14, 987.77, 0.8, 0.16);
                bell(b, 0.28, 783.99, 1.0, 0.18);
                return finish(b, 0.24);
            default: // Kristal: loceng kaca naik
                b = buf(1.2);
                bell(b, 0.00, 1318.51, 0.7, 0.18);
                bell(b, 0.09, 1975.53, 0.8, 0.16);
                bell(b, 0.18, 2637.02, 0.7, 0.10);
                return finish(b, 0.20);
        }
    }

    /** Simpan nada ke MediaStore (Notifications) dan jadikan nada notifikasi lalai. */
    static Uri installTone(Context c, int i, boolean setDefault) throws Exception {
        String name = TONE_NAMES[i];
        Uri col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        // padam salinan lama dengan nama sama
        c.getContentResolver().delete(col, MediaStore.Audio.Media.DISPLAY_NAME + "=?", new String[]{name + ".wav"});
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".wav");
        v.put(MediaStore.Audio.Media.TITLE, name);
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Notifications/");
        v.put(MediaStore.Audio.Media.IS_NOTIFICATION, 1);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri u = c.getContentResolver().insert(col, v);
        if (u == null) throw new Exception("insert");
        try (OutputStream os = c.getContentResolver().openOutputStream(u)) {
            os.write(wav(tone(i)));
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        c.getContentResolver().update(u, v, null, null);
        if (setDefault) {
            try {
                android.media.RingtoneManager.setActualDefaultRingtoneUri(c, android.media.RingtoneManager.TYPE_NOTIFICATION, u);
            } catch (Exception e) {
                if (Shell.granted()) Shell.run("settings put system notification_sound " + u);
            }
        }
        return u;
    }

    // ======================= Sintesis =======================

    private static double[] buf(double sec) { return new double[(int) (RATE * sec)]; }

    /** Loceng kaca: nada asas + separa tak harmonik (bunyi kaca), serangan lembut, reput eksponen. */
    private static void bell(double[] o, double at, double f, double dur, double vol) {
        int s0 = (int) (at * RATE), len = (int) (dur * RATE);
        double[] ratio = {1.0, 2.76, 5.40, 2.0};
        double[] amp = {1.0, 0.28, 0.10, 0.18};
        double[] decay = {1.0, 1.8, 3.2, 1.4};
        for (int i = 0; i < len && s0 + i < o.length; i++) {
            double t = (double) i / RATE;
            double atk = Math.min(1.0, t / 0.006);
            double v = 0;
            for (int k = 0; k < ratio.length; k++) {
                v += amp[k] * Math.sin(2 * Math.PI * f * ratio[k] * t) * Math.exp(-t * decay[k] * 4.2 / dur);
            }
            o[s0 + i] += v * atk * vol;
        }
    }

    /** Ketukan kayu: sinus dengan pic menurun sedikit dan reput sangat pantas. */
    private static void wood(double[] o, double at, double f, double dur, double vol) {
        int s0 = (int) (at * RATE), len = (int) (dur * RATE);
        double ph = 0;
        for (int i = 0; i < len && s0 + i < o.length; i++) {
            double t = (double) i / RATE;
            double ff = f * (1.0 + 0.35 * Math.exp(-t * 60));
            ph += 2 * Math.PI * ff / RATE;
            double env = Math.min(1.0, t / 0.0015) * Math.exp(-t * 6.0 / dur);
            o[s0 + i] += Math.sin(ph) * env * vol;
        }
    }

    /** Desir ditapis (lulus rendah satu kutub) untuk bunyi "syuk". */
    private static void noise(double[] o, double at, double dur, double vol, double cutoff) {
        int s0 = (int) (at * RATE), len = (int) (dur * RATE);
        java.util.Random r = new java.util.Random(7);
        double a = Math.exp(-2 * Math.PI * cutoff / RATE), y = 0;
        for (int i = 0; i < len && s0 + i < o.length; i++) {
            double t = (double) i / len;
            double env = Math.sin(Math.PI * Math.min(1.0, t * 1.2)) * (1 - t);
            y = (1 - a) * (r.nextDouble() * 2 - 1) + a * y;
            o[s0 + i] += y * env * vol * 2.2;
        }
    }

    /** Gema pendek (dua garis tunda) + had lembut supaya tidak pecah. */
    private static short[] finish(double[] b, double reverb) {
        int d1 = (int) (RATE * 0.043), d2 = (int) (RATE * 0.071);
        for (int i = 0; i < b.length; i++) {
            if (i >= d1) b[i] += b[i - d1] * reverb;
            if (i >= d2) b[i] += b[i - d2] * reverb * 0.7;
        }
        short[] out = new short[b.length];
        int fade = Math.min(b.length / 4, (int) (RATE * 0.05));
        for (int i = 0; i < b.length; i++) {
            double v = Math.tanh(b[i] * 1.1) * 0.9;
            if (i > b.length - fade) v *= (b.length - i) / (double) fade;
            out[i] = (short) Math.round(v * 32767);
        }
        return out;
    }

    private static byte[] wav(short[] pcm) {
        ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes());
        bb.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16);
        bb.put("data".getBytes()).putInt(pcm.length * 2);
        for (short s : pcm) bb.putShort(s);
        return bb.array();
    }
}
