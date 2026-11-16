package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muka lock screen: 4 slot (Utama, Kerja, Keluarga, Malam). Setiap muka menyimpan wallpaper depth sendiri
 * dan gaya jam (warna, fon, saiz, kedudukan). Bertukar muka = simpan muka semasa, muat muka baru.
 * Muka yang belum pernah diisi bermula sebagai salinan muka semasa.
 */
final class Faces {
    private Faces() {}

    static final int COUNT = 4;
    static final int[] NAMES = {R.string.face_main, R.string.face_work, R.string.face_family, R.string.face_night};
    private static final String[] KEYS = {Prefs.CLOCK_SIZE, Prefs.CLOCK_Y, Prefs.CLOCK_COLOR, Prefs.CLOCK_FONT,
            Prefs.CLOCK_W, Prefs.CLOCK_H, Prefs.CLOCK_GLASS, Prefs.WALL_MOOD, Prefs.CLOCK_LAYOUT, Prefs.CLOCK_SHAPE, Prefs.POP_STYLE, Prefs.CLOCK_AUTO};
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "gk-faces"));

    static int active(Context c) { return Prefs.get(c).getInt(Prefs.FACE, 0); }

    private static File dir(Context c, int i) {
        File d = new File(c.getFilesDir(), "faces/" + i);
        d.mkdirs();
        return d;
    }

    private static void copy(File a, File b) throws Exception {
        if (!a.exists()) {
            b.delete();
            return;
        }
        File tmp = new File(b.getPath() + ".tmp");
        try (FileInputStream in = new FileInputStream(a); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(b)) throw new Exception("rename");
    }

    private static void save(Context c, int i) throws Exception {
        SharedPreferences p = Prefs.get(c);
        JSONObject o = new JSONObject();
        for (String k : KEYS) {
            Object v = p.getAll().get(k);
            if (v != null) o.put(k, v);
        }
        p.edit().putString("face_" + i, o.toString()).apply();
        File d = dir(c, i);
        copy(DepthRenderer.bgFile(c), new File(d, "bg.jpg"));
        copy(DepthRenderer.fgFile(c), new File(d, "fg.png"));
    }

    private static void load(Context c, int i) throws Exception {
        SharedPreferences p = Prefs.get(c);
        String js = p.getString("face_" + i, null);
        if (js == null) return; // muka baru: kekalkan rupa semasa sebagai permulaan
        JSONObject o = new JSONObject(js);
        SharedPreferences.Editor e = p.edit();
        for (String k : KEYS) {
            if (!o.has(k)) continue;
            Object v = o.get(k);
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Double) e.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof String) e.putString(k, (String) v);
        }
        e.apply();
        File d = dir(c, i);
        File bg = new File(d, "bg.jpg"), fg = new File(d, "fg.png");
        if (bg.exists()) copy(bg, DepthRenderer.bgFile(c));
        copy(fg, DepthRenderer.fgFile(c));
    }

    interface Done { void run(); }

    /** Tukar ke muka i (di latar). done dipanggil di thread latar selepas siap. */
    static void switchTo(Context ctx, int i, Done done) {
        final Context c = ctx.getApplicationContext();
        int cur = active(c);
        if (i == cur || i < 0 || i >= COUNT) {
            if (done != null) done.run();
            return;
        }
        IO.execute(() -> {
            try {
                save(c, cur);
                load(c, i);
                Prefs.get(c).edit().putInt(Prefs.FACE, i).putLong(Prefs.IMG_VER, System.currentTimeMillis()).apply();
            } catch (Throwable t) {
                CrashLog.write(c, t);
            }
            if (done != null) done.run();
        });
    }

    /** Simpan perubahan pada muka semasa (contoh selepas tukar wallpaper). */
    static void saveCurrent(Context ctx) {
        final Context c = ctx.getApplicationContext();
        IO.execute(() -> {
            try { save(c, active(c)); } catch (Throwable t) { CrashLog.write(c, t); }
        });
    }

    /** Muka untuk Mod Fokus: Kerja → Kerja, Rumah → Keluarga, Tidur → Malam, Mati → Utama. */
    static int forFocus(int focus) {
        switch (focus) {
            case Focus.WORK: return 1;
            case Focus.HOME: return 2;
            case Focus.SLEEP: return 3;
            case Focus.OFF: return 0;
            default: return -1;
        }
    }
}
