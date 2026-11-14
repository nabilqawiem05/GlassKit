package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Sandaran & pulih: semua tetapan, nota, peringatan dan gambar (wallpaper, muka, poster, album) dalam satu fail zip. */
final class Backup {
    private Backup() {}

    static void export(Context c, Uri dest) throws Exception {
        try (OutputStream os = c.getContentResolver().openOutputStream(dest); ZipOutputStream z = new ZipOutputStream(os)) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : Prefs.get(c).getAll().entrySet()) {
                Object v = e.getValue();
                String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                        : v instanceof Float ? "f" : v instanceof String ? "s" : null;
                if (t == null) continue;
                o.put(e.getKey(), new JSONObject().put("t", t).put("v", v));
            }
            o.put("_version", new JSONObject().put("t", "s").put("v", Updater.current(c)));
            z.putNextEntry(new ZipEntry("prefs.json"));
            z.write(o.toString().getBytes("UTF-8"));
            z.closeEntry();
            addDir(z, c.getFilesDir(), "files/");
        }
    }

    private static void addDir(ZipOutputStream z, File dir, String prefix) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        byte[] buf = new byte[1 << 16];
        for (File f : fs) {
            if (f.isDirectory()) {
                addDir(z, f, prefix + f.getName() + "/");
                continue;
            }
            if (f.getName().endsWith(".tmp")) continue;
            z.putNextEntry(new ZipEntry(prefix + f.getName()));
            try (InputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
            }
            z.closeEntry();
        }
    }

    /** Pulihkan. Pulangkan bilangan fail dipulihkan. */
    static int restore(Context c, Uri src) throws Exception {
        int files = 0;
        File base = c.getFilesDir().getCanonicalFile();
        try (InputStream is = c.getContentResolver().openInputStream(src); ZipInputStream z = new ZipInputStream(is)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("prefs.json")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    int r;
                    while ((r = z.read(buf)) > 0) bo.write(buf, 0, r);
                    applyPrefs(c, new JSONObject(bo.toString("UTF-8")));
                } else if (n.startsWith("files/") && !e.isDirectory()) {
                    File out = new File(base, n.substring("files/".length())).getCanonicalFile();
                    if (!out.getPath().startsWith(base.getPath() + File.separator)) continue; // elak laluan berbahaya
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        int r;
                        while ((r = z.read(buf)) > 0) os.write(buf, 0, r);
                    }
                    files++;
                }
                z.closeEntry();
            }
        }
        Prefs.get(c).edit().putLong(Prefs.IMG_VER, System.currentTimeMillis()).apply();
        GlassWidget.refreshAll(c);
        TasksWidget.refresh(c);
        NotesWidget.refresh(c);
        Notes.rescheduleAll(c);
        Prayer.schedule(c);
        return files;
    }

    private static void applyPrefs(Context c, JSONObject o) throws Exception {
        SharedPreferences.Editor ed = Prefs.get(c).edit();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("_")) continue;
            JSONObject x = o.getJSONObject(k);
            switch (x.getString("t")) {
                case "b": ed.putBoolean(k, x.getBoolean("v")); break;
                case "i": ed.putInt(k, x.getInt("v")); break;
                case "l": ed.putLong(k, x.getLong("v")); break;
                case "f": ed.putFloat(k, (float) x.getDouble("v")); break;
                case "s":
                    String v = x.getString("v");
                    // fail audio azan dari telefon lain tidak boleh dibuka di sini
                    if (k.equals(Prefs.PRAYER_SOUND) && v.startsWith("content:")) v = "default";
                    ed.putString(k, v);
                    break;
            }
        }
        ed.apply();
    }
}
