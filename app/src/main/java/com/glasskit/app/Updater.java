package com.glasskit.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Kemas kini dalam app daripada keluaran "latest" di GitHub. */
final class Updater {
    private Updater() {}

    static final String API = "https://api.github.com/repos/nabilqawiem05/GlassKit/releases/tags/latest";

    static final class Info {
        String version, url;
        long size;
    }

    interface Cb { void on(Info newer, String error); }
    interface Progress { void on(int pct); }

    static String current(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    /** a > b ? (bandingkan 11.10 vs 11.9 secara nombor) */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    /** Semak di thread latar. newer = null jika sudah terkini. */
    static void check(Context ctx, Cb cb) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setConnectTimeout(12000);
                con.setReadTimeout(12000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                }
                JSONObject o = new JSONObject(sb.toString());
                String name = o.optString("name", "");
                String ver = name.contains(" ") ? name.substring(name.lastIndexOf(' ') + 1) : name;
                JSONArray as = o.getJSONArray("assets");
                Info info = null;
                for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        info = new Info();
                        info.version = ver;
                        info.url = a.getString("browser_download_url");
                        info.size = a.optLong("size");
                    }
                }
                Prefs.get(c).edit().putLong("updChecked", System.currentTimeMillis()).apply();
                if (info != null && newer(ver, current(c))) cb.on(info, null);
                else cb.on(null, null);
            } catch (Exception e) {
                cb.on(null, String.valueOf(e.getMessage()));
            }
        }, "gk-upd").start();
    }

    static File apkFile(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getCacheDir();
        return new File(d, "GlassKit-update.apk");
    }

    /** Muat turun dan pasang (di thread latar). */
    static void install(Context ctx, Info info, Progress prog, Cb done) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            File f = apkFile(c);
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(info.url).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : info.size;
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    long got = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != last && prog != null) {
                            last = pct;
                            prog.on(pct);
                        }
                    }
                }
                if (Shell.granted()) {
                    // Shizuku: pasang terus tanpa dialog (app akan dimulakan semula)
                    Shell.run("pm install -r -t \"" + f.getAbsolutePath() + "\"", (ok, out) -> {
                        if (out != null && out.contains("Success")) done.on(null, null);
                        else sessionInstall(c, f, done);
                    });
                } else {
                    sessionInstall(c, f, done);
                }
            } catch (Exception e) {
                done.on(null, String.valueOf(e.getMessage()));
            }
        }, "gk-upd-dl").start();
    }

    /** Pemasang sistem (Android akan minta pengesahan). */
    private static void sessionInstall(Context c, File f, Cb done) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent cbI = new Intent(c, Receiver.class);
                PendingIntent sender = PendingIntent.getBroadcast(c, 91, cbI,
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(sender.getIntentSender());
            }
            done.on(null, null);
        } catch (Exception e) {
            done.on(null, String.valueOf(e.getMessage()));
        }
    }

    /** Terima status pemasang; jika perlu pengesahan pengguna, buka dialog sistem. */
    public static class Receiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) {}
                }
            }
        }
    }
}
