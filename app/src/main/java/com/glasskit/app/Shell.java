package com.glasskit.app;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Jalankan arahan shell melalui Shizuku (uid shell, tanpa root). */
final class Shell {
    static final int REQ = 7301;
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Cb { void done(boolean ok, String out); }

    private Shell() {}

    static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean granted() {
        try {
            return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request() {
        try {
            if (running() && !granted()) Shizuku.requestPermission(REQ);
        } catch (Throwable ignored) {
        }
    }

    static void run(String cmd) { run(cmd, null); }

    static void run(final String cmd, final Cb cb) {
        EX.execute(() -> {
            boolean ok = false;
            String out;
            try {
                if (!granted()) throw new IllegalStateException("Shizuku");
                Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
                out = read(p.getInputStream()) + read(p.getErrorStream());
                ok = p.waitFor() == 0;
            } catch (Throwable t) {
                out = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final boolean fo = ok;
            final String fs = out;
            if (cb != null) MAIN.post(() -> cb.done(fo, fs));
        });
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString();
    }

    /** Semua kebenaran GlassKit dalam satu baris (boleh tampal dalam aShell). */
    static String grantAll(String pkg) {
        String acc = pkg + "/" + PillService.class.getName();
        return "appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow; "
                + "cmd notification allow_listener " + pkg + "/" + NotifListener.class.getName() + "; "
                + "cmd notification allow_dnd " + pkg + "; "
                + "appops set " + pkg + " WRITE_SETTINGS allow; "
                + "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                + "s=$(settings get secure enabled_accessibility_services); "
                + "case \"$s\" in *" + acc + "*) ;; null|'') settings put secure enabled_accessibility_services " + acc + ";; "
                + "*) settings put secure enabled_accessibility_services \"$s:" + acc + "\";; esac; "
                + "settings put secure accessibility_enabled 1";
    }
}
