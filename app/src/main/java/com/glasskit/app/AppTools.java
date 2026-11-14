package com.glasskit.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Alatan app: senarai app, tetapan ikut app, sekat latar, folder rahsia, simpan APK. */
final class AppTools {
    private AppTools() {}

    static final class App {
        String pkg, label;
        Drawable icon;
        boolean system;
    }

    /** App yang boleh dibuka dari launcher (tidak termasuk GlassKit & Shizuku). */
    static List<App> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        Set<String> seen = new HashSet<>();
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (!seen.add(p) || p.equals(c.getPackageName()) || p.equals("moe.shizuku.privileged.api")) continue;
            App a = new App();
            a.pkg = p;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            a.system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            out.add(a);
        }
        // app tersembunyi (dimatikan) tidak muncul dalam launcher: tambah semula supaya boleh diurus
        for (String p : hidden(c)) {
            if (seen.contains(p)) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS);
                App a = new App();
                a.pkg = p;
                a.label = String.valueOf(pm.getApplicationLabel(ai));
                a.icon = pm.getApplicationIcon(ai);
                out.add(a);
            } catch (Exception ignored) {
            }
        }
        out.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static Set<String> set(Context c, String key) {
        String s = Prefs.get(c).getString(key, "");
        Set<String> out = new LinkedHashSet<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        out.remove("");
        return out;
    }

    private static void putSet(Context c, String key, Set<String> s) {
        Prefs.get(c).edit().putString(key, String.join(",", s)).apply();
    }

    // ======================= Sekat latar =======================

    static Set<String> restricted(Context c) { return set(c, "restrictedApps"); }

    static void setRestricted(Context c, Set<String> now) {
        Set<String> old = restricted(c);
        StringBuilder cmd = new StringBuilder();
        for (String p : now) if (!old.contains(p)) cmd.append("cmd appops set ").append(p).append(" RUN_ANY_IN_BACKGROUND ignore; am force-stop ").append(p).append("; ");
        for (String p : old) if (!now.contains(p)) cmd.append("cmd appops set ").append(p).append(" RUN_ANY_IN_BACKGROUND allow; ");
        putSet(c, "restrictedApps", now);
        if (cmd.length() > 0) Shell.run(cmd + "true");
    }

    // ======================= Folder rahsia =======================

    static Set<String> hidden(Context c) { return set(c, "hiddenApps"); }

    static void hide(Context c, String pkg) {
        Set<String> s = hidden(c);
        s.add(pkg);
        putSet(c, "hiddenApps", s);
        Shell.run("pm disable-user --user 0 " + pkg);
    }

    static void unhideForever(Context c, String pkg) {
        Set<String> s = hidden(c);
        s.remove(pkg);
        putSet(c, "hiddenApps", s);
        Shell.run("pm enable " + pkg);
    }

    /** Buka app tersembunyi: hidupkan sementara, lancarkan; disorok semula bila skrin dipadam. */
    static void openHidden(Context c, String pkg) {
        Prefs.get(c).edit().putBoolean("hiddenOpen", true).apply();
        Shell.run("pm enable " + pkg, (ok, o) -> {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(i); } catch (Exception ignored) {}
            }
        });
    }

    /** Sorok semula semua app tersembunyi (dipanggil ketika skrin dipadam). */
    static void rehideAll(Context c) {
        if (!Prefs.get(c).getBoolean("hiddenOpen", false)) return;
        Prefs.get(c).edit().putBoolean("hiddenOpen", false).apply();
        StringBuilder cmd = new StringBuilder();
        for (String p : hidden(c)) cmd.append("pm disable-user --user 0 ").append(p).append("; ");
        if (cmd.length() > 0) Shell.run(cmd + "true");
    }

    // ======================= Simpan APK =======================

    static void saveApk(Context c, String pkg, Tweaks.Done d) {
        String dir = "/sdcard/Download/GlassKit-APK";
        String name = label(c, pkg).replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_');
        if (name.isEmpty()) name = pkg;
        final String nm = name;
        Shell.run("pm path " + pkg, (ok, out) -> {
            if (out == null || !out.contains("package:")) {
                d.on(false, out);
                return;
            }
            String[] lines = out.trim().split("\n");
            StringBuilder cmd = new StringBuilder("mkdir -p " + dir + "; ");
            if (lines.length == 1) {
                cmd.append("cp '").append(lines[0].replace("package:", "").trim()).append("' '").append(dir).append("/").append(nm).append(".apk'; ");
            } else {
                cmd.append("mkdir -p '").append(dir).append("/").append(nm).append("'; ");
                for (String l : lines) {
                    String f = l.replace("package:", "").trim();
                    cmd.append("cp '").append(f).append("' '").append(dir).append("/").append(nm).append("/'; ");
                }
            }
            Shell.run(cmd + "true", (ok2, o2) -> d.on(ok2, dir + "/" + nm + (lines.length == 1 ? ".apk" : "/")));
        });
    }

    // ======================= Tetapan ikut app =======================

    static final class Profile {
        int brightness = -1;   // -1 = tidak diubah, 0..255
        int rotation = 0;      // 0 tidak diubah, 1 auto, 2 kunci
        boolean hz120, dnd, awake;
    }

    static Profile profile(Context c, String pkg) {
        String js = Prefs.get(c).getString("prof_" + pkg, null);
        if (js == null) return null;
        try {
            JSONObject o = new JSONObject(js);
            Profile p = new Profile();
            p.brightness = o.optInt("b", -1);
            p.rotation = o.optInt("r", 0);
            p.hz120 = o.optBoolean("hz");
            p.dnd = o.optBoolean("dnd");
            p.awake = o.optBoolean("aw");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    static void saveProfile(Context c, String pkg, Profile p) {
        Set<String> s = set(c, "profApps");
        if (p == null) {
            s.remove(pkg);
            Prefs.get(c).edit().remove("prof_" + pkg).apply();
        } else {
            s.add(pkg);
            try {
                Prefs.get(c).edit().putString("prof_" + pkg, new JSONObject().put("b", p.brightness).put("r", p.rotation)
                        .put("hz", p.hz120).put("dnd", p.dnd).put("aw", p.awake).toString()).apply();
            } catch (Exception ignored) {
            }
        }
        putSet(c, "profApps", s);
    }

    static Set<String> profileApps(Context c) { return set(c, "profApps"); }

    /** Pengurus aktif: guna profil bila app dibuka, pulihkan keadaan asal bila keluar. */
    static final class ProfileRunner {
        private final PillService s;
        private String active;
        private int baseBright = -1, baseBrightMode = -1, baseRot = -1, baseFilter = -1;
        private boolean baseSaved, appliedHz;

        ProfileRunner(PillService s) { this.s = s; }

        boolean keepAwake() {
            if (active == null) return false;
            Profile p = profile(s, active);
            return p != null && p.awake;
        }

        void onForeground(String pkg) {
            if (pkg == null || pkg.equals(active)) return;
            if (pkg.equals("com.android.systemui") || pkg.equals(s.getPackageName())) return;
            Profile np = profileApps(s).contains(pkg) ? profile(s, pkg) : null;
            if (active != null && np == null) restore();
            if (np != null) apply(pkg, np);
            else active = null;
            s.onProfileChanged();
        }

        private void saveBase() {
            if (baseSaved) return;
            baseSaved = true;
            android.content.ContentResolver r = s.getContentResolver();
            baseBright = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, -1);
            baseBrightMode = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            baseRot = Settings.System.getInt(r, Settings.System.ACCELEROMETER_ROTATION, -1);
            baseFilter = s.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        }

        private void apply(String pkg, Profile p) {
            saveBase();
            active = pkg;
            StringBuilder cmd = new StringBuilder();
            if (p.brightness >= 0) cmd.append("settings put system screen_brightness_mode 0; settings put system screen_brightness ")
                    .append(Math.max(1, p.brightness)).append("; ");
            if (p.rotation == 1) cmd.append("settings put system accelerometer_rotation 1; ");
            if (p.rotation == 2) cmd.append("settings put system accelerometer_rotation 0; ");
            appliedHz = p.hz120;
            if (p.hz120) cmd.append("settings put system min_refresh_rate 120.0; ");
            if (p.dnd) cmd.append("cmd notification set_dnd priority; ");
            if (cmd.length() > 0) Shell.run(cmd + "true");
        }

        void restore() {
            if (!baseSaved) {
                active = null;
                return;
            }
            StringBuilder cmd = new StringBuilder();
            if (baseBrightMode >= 0) cmd.append("settings put system screen_brightness_mode ").append(baseBrightMode).append("; ");
            if (baseBright >= 0 && baseBrightMode == 0) cmd.append("settings put system screen_brightness ").append(baseBright).append("; ");
            if (baseRot >= 0) cmd.append("settings put system accelerometer_rotation ").append(baseRot).append("; ");
            if (appliedHz && !Prefs.get(s).getBoolean(Prefs.T_120, false)) cmd.append("settings delete system min_refresh_rate; ");
            appliedHz = false;
            if (baseFilter == NotificationManager.INTERRUPTION_FILTER_ALL) cmd.append("cmd notification set_dnd off; ");
            Shell.run(cmd + "true");
            baseSaved = false;
            active = null;
        }
    }
}
