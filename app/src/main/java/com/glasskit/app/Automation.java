package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Automasi gaya Shortcuts: bila [pencetus] berlaku, buat [tindakan]. */
final class Automation {
    private Automation() {}

    // pencetus
    static final int T_TIME = 0, T_CHARGE = 1, T_BATT = 2, T_BT = 3, T_WIFI = 4, T_UNPLUG = 5;
    // tindakan
    static final int A_FOCUS = 0, A_DND_ON = 1, A_DND_OFF = 2, A_WIFI_ON = 3, A_WIFI_OFF = 4, A_BT_ON = 5,
            A_BT_OFF = 6, A_BRIGHT = 7, A_OPEN = 8, A_VOLUME = 9, A_MSG = 10, A_GRAY_ON = 11, A_GRAY_OFF = 12;

    static final int[] T_NAMES = {R.string.at_time, R.string.at_charge, R.string.at_batt, R.string.at_bt, R.string.at_wifi, R.string.at_unplug};
    static final int[] A_NAMES = {R.string.aa_focus, R.string.aa_dnd_on, R.string.aa_dnd_off, R.string.aa_wifi_on, R.string.aa_wifi_off,
            R.string.aa_bt_on, R.string.aa_bt_off, R.string.aa_bright, R.string.aa_open, R.string.aa_volume, R.string.aa_msg,
            R.string.aa_gray_on, R.string.aa_gray_off};

    static final class Rule {
        long id;
        boolean on = true;
        int trig, act;
        String tArg = "", aArg = "";
        String lastFired = "";
    }

    static List<Rule> all(Context c) {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c).getString("autoRules", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Rule r = new Rule();
                r.id = o.getLong("id");
                r.on = o.optBoolean("on", true);
                r.trig = o.optInt("t");
                r.act = o.optInt("a");
                r.tArg = o.optString("ta");
                r.aArg = o.optString("aa");
                r.lastFired = o.optString("lf");
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, List<Rule> l) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : l) a.put(new JSONObject().put("id", r.id).put("on", r.on).put("t", r.trig).put("a", r.act)
                    .put("ta", r.tArg).put("aa", r.aArg).put("lf", r.lastFired));
        } catch (Exception ignored) {
        }
        Prefs.get(c).edit().putString("autoRules", a.toString()).apply();
    }

    static String describe(Context c, Rule r) {
        String t = c.getString(T_NAMES[r.trig]);
        if (!r.tArg.isEmpty()) t += " " + (r.trig == T_BATT ? r.tArg + "%" : r.tArg);
        String a = c.getString(A_NAMES[r.act]);
        if (r.act == A_FOCUS) a += " " + c.getString(Focus.NAMES[safeInt(r.aArg, 0)]);
        else if (r.act == A_OPEN) a += " " + AppTools.label(c, r.aArg);
        else if (!r.aArg.isEmpty()) a += " " + r.aArg + (r.act == A_BRIGHT || r.act == A_VOLUME ? "%" : "");
        return c.getString(R.string.auto_when, t, a);
    }

    static int safeInt(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    /** Semak pencetus. key = nilai unik untuk elak berulang (cth tarikh+masa). */
    static void fire(PillService s, int trig, String value) {
        List<Rule> l = all(s);
        boolean changed = false;
        String today = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
        for (Rule r : l) {
            if (!r.on || r.trig != trig) continue;
            boolean match;
            String stamp;
            switch (trig) {
                case T_TIME:
                    match = r.tArg.equals(value);
                    stamp = today + r.tArg;
                    break;
                case T_BATT:
                    match = safeInt(value, -1) == safeInt(r.tArg, -2);
                    stamp = today + "b" + value + "_" + System.currentTimeMillis() / 3_600_000L;
                    break;
                case T_BT:
                case T_WIFI:
                    match = r.tArg.isEmpty() || (value != null && value.toLowerCase(Locale.ROOT).contains(r.tArg.toLowerCase(Locale.ROOT)));
                    stamp = String.valueOf(System.currentTimeMillis() / 60_000L);
                    break;
                default:
                    match = true;
                    stamp = String.valueOf(System.currentTimeMillis() / 10_000L);
            }
            if (!match || stamp.equals(r.lastFired)) continue;
            r.lastFired = stamp;
            changed = true;
            run(s, r);
        }
        if (changed) save(s, l);
    }

    private static void run(PillService s, Rule r) {
        try {
            switch (r.act) {
                case A_FOCUS: Focus.set(s, safeInt(r.aArg, 0)); break;
                case A_DND_ON: Shell.run("cmd notification set_dnd priority"); break;
                case A_DND_OFF: Shell.run("cmd notification set_dnd off"); break;
                case A_WIFI_ON: Shell.run("svc wifi enable"); break;
                case A_WIFI_OFF: Shell.run("svc wifi disable"); break;
                case A_BT_ON: Shell.run("svc bluetooth enable"); break;
                case A_BT_OFF: Shell.run("svc bluetooth disable"); break;
                case A_BRIGHT:
                    Shell.run("settings put system screen_brightness_mode 0; settings put system screen_brightness "
                            + Math.max(1, Math.round(safeInt(r.aArg, 50) * 2.55f)));
                    break;
                case A_OPEN: {
                    Intent i = s.getPackageManager().getLaunchIntentForPackage(r.aArg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                        if (android.os.Build.VERSION.SDK_INT >= 34) {
                            o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                        }
                        s.startActivity(i, o.toBundle());
                    }
                    break;
                }
                case A_VOLUME: {
                    AudioManager am = s.getSystemService(AudioManager.class);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * safeInt(r.aArg, 50) / 100f), 0);
                    break;
                }
                case A_MSG: {
                    android.graphics.drawable.Drawable ic = Ui.iconDrawable(s, Icon.BOLT, android.graphics.Color.WHITE, 0xFF5E5CE6, Ui.dp(s, 42));
                    s.showIsland(ic, ic, r.aArg.isEmpty() ? s.getString(R.string.auto_title) : r.aArg, "", null);
                    break;
                }
                case A_GRAY_ON: Tweaks.grayscale(true); break;
                case A_GRAY_OFF: Tweaks.grayscale(false); break;
            }
        } catch (Exception e) {
            CrashLog.write(s, e);
        }
    }

    /** Dipanggil setiap minit. */
    static void tick(PillService s) {
        Calendar k = Calendar.getInstance();
        fire(s, T_TIME, String.format(Locale.US, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE)));
    }
}
