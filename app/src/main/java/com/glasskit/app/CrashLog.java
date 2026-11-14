package com.glasskit.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simpan ralat terakhir ke fail supaya boleh dilihat & disalin dalam app. */
final class CrashLog {
    private static boolean installed;

    private CrashLog() {}

    static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "crash.txt"); }

    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            write(app, e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Catat ralat yang ditangkap (tanpa crash). */
    static void write(Context c, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String head = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  v" + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName + "\n";
            try (FileWriter w = new FileWriter(file(c), false)) {
                w.write(head + sw);
            }
        } catch (Throwable ignored) {
        }
    }

    static String read(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return null;
        }
    }
}
