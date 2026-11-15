package com.glasskit.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tab "Alatan": tweak sistem melalui Shizuku. Semuanya boleh dipulihkan. */
final class ToolsPage {
    private static final String[] CANTA = {"io.github.samolego.canta", "org.samo_lego.canta"};
    private static final String CANTA_URL = "https://github.com/samolego/Canta/releases/latest";
    private final MainActivity a;
    private final SharedPreferences p;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView battInfo, secretInfo, profInfo, restrictInfo;
    private LinearLayout autoList, colorRow;

    ToolsPage(MainActivity a, LinearLayout box) {
        this.a = a;
        this.p = Prefs.get(a);
        build(box);
    }

    private int dp(float v) { return Ui.dp(a, v); }

    private boolean needShell() {
        if (Shell.granted()) return true;
        a.toast(a.getString(R.string.need_shizuku));
        return false;
    }

    private Tweaks.Done done() {
        return (ok, msg) -> a.toast(a.getString(ok ? R.string.t_done : R.string.t_fail));
    }

    interface BoolCb { void on(boolean v); }

    private Switch toggleCb(LinearLayout parent, int label, boolean checked, BoolCb cb) {
        Switch sw = new Switch(a);
        sw.setText(label);
        sw.setTextSize(16);
        sw.setTextColor(Color.WHITE);
        sw.setTypeface(Ui.font(500));
        sw.setChecked(checked);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{Color.WHITE, Color.rgb(200, 200, 210)}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{0xFF30D158, Color.argb(90, 255, 255, 255)}));
        sw.setPadding(0, dp(12), 0, 0);
        sw.setOnCheckedChangeListener((v, on) -> {
            if (!v.isPressed()) return;
            if (!Shell.granted()) {
                v.setChecked(!on);
                a.toast(a.getString(R.string.need_shizuku));
                return;
            }
            cb.on(on);
        });
        parent.addView(sw, new LinearLayout.LayoutParams(-1, -2));
        return sw;
    }

    private void build(LinearLayout box) {
        // ---------- Paparan ----------
        LinearLayout d = a.card(box, R.string.tl_display, Icon.SUN, 0xFFBF5AF2);
        a.label(d, R.string.tl_syscolor);
        colorRow = new LinearLayout(a);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        d.addView(colorRow, new LinearLayout.LayoutParams(-1, dp(46)));
        paintColors();
        a.note(d, a.getString(R.string.tl_syscolor_note));

        toggleCb(d, R.string.tl_120, p.getBoolean(Prefs.T_120, false), on -> Tweaks.refresh120(a, on, done()));
        toggleCb(d, R.string.tl_forcedark, p.getBoolean(Prefs.T_FORCE_DARK, false), on -> {
            Tweaks.forceDark(a, on);
            a.toast(a.getString(R.string.tl_forcedark_note));
        });
        a.label(d, R.string.tl_anim);
        a.segmented(d, new int[]{R.string.anim_fast, R.string.anim_quick, R.string.anim_normal}, p.getInt(Prefs.T_ANIM, 2), i -> {
            if (needShell()) Tweaks.animSpeed(a, i, done());
        });
        a.label(d, R.string.tl_density);
        LinearLayout dr = new LinearLayout(a);
        d.addView(dr);
        a.addPair(dr, a.button(null, a.getString(R.string.dens_small), false, v -> density(-1)),
                a.button(null, a.getString(R.string.dens_big), false, v -> density(1)));
        a.button(d, a.getString(R.string.dens_reset), false, v -> { if (needShell()) Tweaks.density(a, 0, done()); });
        toggleCb(d, R.string.tl_graysleep, p.getBoolean(Prefs.GRAY_SLEEP, false), on -> {
            p.edit().putBoolean(Prefs.GRAY_SLEEP, on).apply();
            if (!on) Tweaks.grayscale(false);
            else if (p.getInt(Prefs.FOCUS, 0) == Focus.SLEEP) Tweaks.grayscale(true);
        });

        // ---------- Prestasi ----------
        LinearLayout pf = a.card(box, R.string.tl_perf, Icon.BOLT, 0xFF30D158);
        a.toggle(pf, R.string.tl_perf_mode, Prefs.PERF, true);
        a.note(pf, a.getString(R.string.tl_perf_note));
        a.toggle(pf, R.string.tl_haptics, Prefs.HAPTICS, true);
        a.note(pf, a.getString(R.string.tl_haptics_note));
        a.toggle(pf, R.string.tl_font, Prefs.FONT_UNI, true);
        a.toggle(pf, R.string.tl_fps, Prefs.FPS_METER, false);
        a.note(pf, a.getString(R.string.tl_fps_note));

        // ---------- Notifikasi & status ----------
        LinearLayout n = a.card(box, R.string.tl_notifs, Icon.BELL, 0xFFFF375F);
        a.toggle(n, R.string.tl_promo, Prefs.PROMO_ON, true);
        a.note(n, a.getString(R.string.tl_promo_note));
        a.toggle(n, R.string.tl_netspeed, Prefs.NET_SPEED, false);
        a.toggle(n, R.string.tl_dtlock, Prefs.DT_LOCK, false);
        a.toggle(n, R.string.tl_tempwarn, Prefs.TEMP_WARN, true);
        toggleCb(n, R.string.tl_stocklock, p.getBoolean(Prefs.T_HIDE_STOCK_NOTIF, false), on -> {
            Tweaks.stockLockNotifs(a, !on);
            a.toast(a.getString(R.string.t_done));
        });
        a.button(n, a.getString(R.string.tl_sbicons), false, v -> pickSbIcons());
        a.note(n, a.getString(R.string.tl_sbicons_note));

        // ---------- Bateri ----------
        LinearLayout b = a.card(box, R.string.tl_battery, Icon.BATTERY, 0xFF30D158);
        battInfo = a.note(b, "");
        a.button(b, a.getString(R.string.tl_restrict), false, v -> pickRestricted());
        restrictInfo = a.note(b, "");

        // ---------- App ----------
        LinearLayout ap = a.card(box, R.string.tl_apps, Icon.LOCK, 0xFF5E5CE6);
        a.label(ap, R.string.tl_secret);
        secretInfo = a.note(ap, "");
        LinearLayout sr = new LinearLayout(a);
        ap.addView(sr);
        a.addPair(sr, a.button(null, a.getString(R.string.sec_open), true, v -> auth(this::openSecret)),
                a.button(null, a.getString(R.string.sec_add), false, v -> addSecret()));
        a.button(ap, a.getString(R.string.sec_unhide), false, v -> auth(this::unhideSecret));

        a.label(ap, R.string.tl_profiles);
        profInfo = a.note(ap, "");
        a.button(ap, a.getString(R.string.prof_edit), false, v -> pickProfileApp());

        a.label(ap, R.string.tl_apk);
        a.button(ap, a.getString(R.string.apk_pick), false, v -> pickApp(a.getString(R.string.tl_apk), pkg ->
                AppTools.saveApk(a, pkg, (ok, msg) -> a.toast(ok ? a.getString(R.string.apk_saved, msg) : a.getString(R.string.t_fail)))));

        // ---------- Automasi ----------
        LinearLayout au = a.card(box, R.string.tl_auto, Icon.BOLT, 0xFFFF9F0A);
        a.note(au, a.getString(R.string.auto_note));
        autoList = new LinearLayout(a);
        autoList.setOrientation(LinearLayout.VERTICAL);
        au.addView(autoList);
        a.button(au, a.getString(R.string.auto_add), true, v -> addRule());

        // ---------- Lain-lain ----------
        LinearLayout ca = a.card(box, R.string.tl_canta, Icon.GEAR, 0xFF64D2FF);
        a.note(ca, a.getString(R.string.tl_canta_note));
        LinearLayout cr = new LinearLayout(a);
        ca.addView(cr);
        a.addPair(cr, a.button(null, a.getString(R.string.canta_get), false, v ->
                        a.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CANTA_URL)))),
                a.button(null, a.getString(R.string.canta_open), true, v -> {
                    for (String pk : CANTA) {
                        Intent i = a.getPackageManager().getLaunchIntentForPackage(pk);
                        if (i != null) {
                            a.startActivity(i);
                            return;
                        }
                    }
                    a.toast(a.getString(R.string.canta_missing));
                }));

        LinearLayout o = a.card(box, R.string.tl_more, Icon.GEAR, 0xFF8E8E93);
        a.button(o, a.getString(R.string.tl_log), false, v -> errorLog());
        a.button(o, a.getString(R.string.tl_restore), true, v -> new AlertDialog.Builder(a)
                .setTitle(R.string.tl_restore)
                .setMessage(R.string.tl_restore_msg)
                .setPositiveButton(android.R.string.ok, (x, y) -> {
                    if (!needShell()) return;
                    Tweaks.restoreAll(a, (ok, msg) -> {
                        a.toast(a.getString(R.string.t_restored));
                        a.recreate();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null).show());
        a.note(o, a.getString(R.string.tl_restore_note));
        refresh();
    }

    void refresh() {
        if (battInfo == null) return;
        Tweaks.Batt bt = Tweaks.battery(a);
        StringBuilder sb = new StringBuilder();
        sb.append(a.getString(R.string.bh_health, a.getString(Tweaks.healthText(bt.health))));
        sb.append("\n").append(a.getString(R.string.bh_temp, String.format(Locale.US, "%.1f", bt.temp)));
        if (bt.cycles >= 0) sb.append("\n").append(a.getString(R.string.bh_cycles, bt.cycles));
        if (bt.capacityMah > 0) sb.append("\n").append(a.getString(R.string.bh_cap, bt.capacityMah));
        battInfo.setText(sb);
        restrictInfo.setText(a.getString(R.string.restrict_count, AppTools.restricted(a).size()));
        secretInfo.setText(a.getString(R.string.sec_count, AppTools.hidden(a).size()));
        StringBuilder pf = new StringBuilder();
        for (String pkg : AppTools.profileApps(a)) pf.append(pf.length() > 0 ? ", " : "").append(AppTools.label(a, pkg));
        profInfo.setText(pf.length() == 0 ? a.getString(R.string.prof_none) : pf.toString());
        fillRules();
    }

    // ======================= Warna sistem =======================

    private void paintColors() {
        colorRow.removeAllViews();
        int cur = p.getInt(Prefs.SYS_COLOR, 0);
        for (int col : Tweaks.PALETTE) {
            FrameLayout f = new FrameLayout(a);
            View dot = new View(a);
            if (col == 0) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF9F0A, 0xFFBF5AF2, 0xFF0A84FF});
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setBackground(g);
            } else {
                dot.setBackground(Ui.round(col, dp(16)));
            }
            f.addView(dot, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            if (col == cur) {
                View ring = new View(a);
                android.graphics.drawable.GradientDrawable r = new android.graphics.drawable.GradientDrawable();
                r.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                r.setStroke(dp(2.5f), Color.WHITE);
                ring.setBackground(r);
                f.addView(ring, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
            }
            f.setOnClickListener(v -> {
                if (!needShell()) return;
                Tweaks.systemColor(a, col, (ok, m) -> {
                    a.toast(a.getString(ok ? R.string.t_done : R.string.t_fail));
                    paintColors();
                });
            });
            colorRow.addView(f, new LinearLayout.LayoutParams(0, dp(46), 1f));
        }
    }

    // ======================= Saiz paparan dengan auto-batal =======================

    private void density(int step) {
        if (!needShell()) return;
        Tweaks.density(a, step, (ok, m) -> {
            if (!ok) {
                a.toast(a.getString(R.string.t_fail));
                return;
            }
            final int[] left = {12};
            final AlertDialog dlg = new AlertDialog.Builder(a)
                    .setTitle(R.string.dens_keep)
                    .setMessage(a.getString(R.string.dens_revert, left[0]))
                    .setCancelable(false)
                    .setPositiveButton(R.string.dens_keep_btn, null)
                    .setNegativeButton(R.string.dens_undo, (x, y) -> Tweaks.density(a, 0, null))
                    .create();
            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    if (!dlg.isShowing()) return;
                    left[0]--;
                    if (left[0] <= 0) {
                        dlg.dismiss();
                        Tweaks.density(a, 0, null);
                        return;
                    }
                    dlg.setMessage(a.getString(R.string.dens_revert, left[0]));
                    h.postDelayed(this, 1000);
                }
            };
            dlg.setOnShowListener(x -> h.postDelayed(tick, 1000));
            dlg.show();
        });
    }

    // ======================= Ikon bar status stock =======================

    private void pickSbIcons() {
        if (!needShell()) return;
        Set<String> cur = Tweaks.sbHidden(a);
        String[] names = new String[Tweaks.SB_KEYS.length];
        boolean[] on = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = a.getString(Tweaks.SB_NAMES[i]);
            on[i] = cur.contains(Tweaks.SB_KEYS[i]);
        }
        new AlertDialog.Builder(a).setTitle(R.string.tl_sbicons)
                .setMultiChoiceItems(names, on, (x, w, c) -> on[w] = c)
                .setPositiveButton(android.R.string.ok, (x, y) -> {
                    Set<String> now = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) now.add(Tweaks.SB_KEYS[i]);
                    Tweaks.setSbHidden(a, now, done());
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ======================= Pemilih app =======================

    interface PkgCb { void on(String pkg); }

    private void pickApp(String title, PkgCb cb) {
        List<AppTools.App> apps = AppTools.launchable(a);
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).label;
        new AlertDialog.Builder(a).setTitle(title)
                .setItems(names, (x, w) -> cb.on(apps.get(w).pkg))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickRestricted() {
        if (!needShell()) return;
        List<AppTools.App> apps = new ArrayList<>();
        for (AppTools.App x : AppTools.launchable(a)) if (!x.system) apps.add(x);
        Set<String> cur = AppTools.restricted(a);
        String[] names = new String[apps.size()];
        boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = apps.get(i).label;
            on[i] = cur.contains(apps.get(i).pkg);
        }
        new AlertDialog.Builder(a).setTitle(R.string.tl_restrict)
                .setMultiChoiceItems(names, on, (x, w, c) -> on[w] = c)
                .setPositiveButton(android.R.string.ok, (x, y) -> {
                    Set<String> now = new HashSet<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) now.add(apps.get(i).pkg);
                    AppTools.setRestricted(a, now);
                    refresh();
                    a.toast(a.getString(R.string.t_done));
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ======================= Folder rahsia =======================

    private void auth(Runnable ok) {
        try {
            android.hardware.biometrics.BiometricPrompt bp = new android.hardware.biometrics.BiometricPrompt.Builder(a)
                    .setTitle(a.getString(R.string.tl_secret))
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            bp.authenticate(new android.os.CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            ok.run();
                        }
                    });
        } catch (Exception e) {
            a.toast(a.getString(R.string.t_fail));
        }
    }

    private List<String> hiddenList() { return new ArrayList<>(AppTools.hidden(a)); }

    private String[] labels(List<String> pkgs) {
        String[] n = new String[pkgs.size()];
        for (int i = 0; i < n.length; i++) n[i] = AppTools.label(a, pkgs.get(i));
        return n;
    }

    private void openSecret() {
        if (!needShell()) return;
        List<String> l = hiddenList();
        if (l.isEmpty()) {
            a.toast(a.getString(R.string.sec_empty));
            return;
        }
        new AlertDialog.Builder(a).setTitle(R.string.tl_secret)
                .setItems(labels(l), (x, w) -> AppTools.openHidden(a, l.get(w)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addSecret() {
        if (!needShell()) return;
        pickApp(a.getString(R.string.sec_add), pkg -> new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.sec_confirm, AppTools.label(a, pkg)))
                .setPositiveButton(android.R.string.ok, (x, y) -> {
                    AppTools.hide(a, pkg);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null).show());
    }

    private void unhideSecret() {
        if (!needShell()) return;
        List<String> l = hiddenList();
        if (l.isEmpty()) {
            a.toast(a.getString(R.string.sec_empty));
            return;
        }
        new AlertDialog.Builder(a).setTitle(R.string.sec_unhide)
                .setItems(labels(l), (x, w) -> {
                    AppTools.unhideForever(a, l.get(w));
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ======================= Tetapan ikut app =======================

    private void pickProfileApp() {
        if (!needShell()) return;
        pickApp(a.getString(R.string.prof_edit), this::editProfile);
    }

    private void editProfile(String pkg) {
        AppTools.Profile cur = AppTools.profile(a, pkg);
        final AppTools.Profile pr = cur != null ? cur : new AppTools.Profile();
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(22), dp(8), dp(22), 0);
        final Switch bOn = plainSwitch(v, R.string.prof_bright, pr.brightness >= 0);
        android.widget.SeekBar sb = new android.widget.SeekBar(a);
        sb.setMax(255);
        sb.setProgress(pr.brightness >= 0 ? pr.brightness : 160);
        v.addView(sb, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView rl = new TextView(a);
        rl.setText(R.string.prof_rot);
        rl.setPadding(0, dp(12), 0, dp(4));
        v.addView(rl);
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        int[] rn = {R.string.prof_rot_same, R.string.prof_rot_auto, R.string.prof_rot_lock};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(a);
            r.setId(100 + i);
            r.setText(rn[i]);
            rg.addView(r);
        }
        rg.check(100 + pr.rotation);
        v.addView(rg);
        final Switch hz = plainSwitch(v, R.string.tl_120, pr.hz120);
        final Switch dnd = plainSwitch(v, R.string.prof_dnd, pr.dnd);
        final Switch aw = plainSwitch(v, R.string.prof_awake, pr.awake);
        new AlertDialog.Builder(a).setTitle(AppTools.label(a, pkg)).setView(v)
                .setPositiveButton(R.string.save, (x, y) -> {
                    pr.brightness = bOn.isChecked() ? sb.getProgress() : -1;
                    pr.rotation = Math.max(0, rg.getCheckedRadioButtonId() - 100);
                    pr.hz120 = hz.isChecked();
                    pr.dnd = dnd.isChecked();
                    pr.awake = aw.isChecked();
                    AppTools.saveProfile(a, pkg, pr);
                    refresh();
                })
                .setNeutralButton(R.string.delete, (x, y) -> {
                    AppTools.saveProfile(a, pkg, null);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private Switch plainSwitch(LinearLayout parent, int label, boolean on) {
        Switch s = new Switch(a);
        s.setText(label);
        s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(4));
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    // ======================= Automasi =======================

    private void fillRules() {
        autoList.removeAllViews();
        List<Automation.Rule> l = Automation.all(a);
        if (l.isEmpty()) {
            a.note(autoList, a.getString(R.string.auto_empty));
            return;
        }
        for (Automation.Rule r : l) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(8), dp(10));
            row.setBackground(Ui.glass(a, 18, 20));
            TextView t = Ui.text(a, 14, 500, Color.WHITE);
            t.setText(Automation.describe(a, r));
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch sw = new Switch(a);
            sw.setChecked(r.on);
            sw.setOnCheckedChangeListener((x, on) -> {
                List<Automation.Rule> all = Automation.all(a);
                for (Automation.Rule q : all) if (q.id == r.id) q.on = on;
                Automation.save(a, all);
            });
            row.addView(sw);
            ImageView del = new ImageView(a);
            del.setImageDrawable(Ui.iconDrawable(a, Icon.CLOSE, Color.WHITE, 0, dp(28)));
            del.setPadding(dp(8), dp(8), dp(8), dp(8));
            del.setOnClickListener(x -> {
                List<Automation.Rule> all = Automation.all(a);
                all.removeIf(q -> q.id == r.id);
                Automation.save(a, all);
                fillRules();
            });
            row.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            autoList.addView(row, lp);
        }
    }

    private String[] names(int[] res) {
        String[] o = new String[res.length];
        for (int i = 0; i < o.length; i++) o[i] = a.getString(res[i]);
        return o;
    }

    interface StrCb { void on(String s); }

    private void ask(int title, int type, String hint, StrCb cb) {
        EditText e = new EditText(a);
        e.setInputType(type);
        e.setHint(hint);
        FrameLayout f = new FrameLayout(a);
        f.setPadding(dp(22), dp(8), dp(22), 0);
        f.addView(e);
        new AlertDialog.Builder(a).setTitle(title).setView(f)
                .setPositiveButton(android.R.string.ok, (x, y) -> cb.on(e.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addRule() {
        final Automation.Rule r = new Automation.Rule();
        r.id = System.currentTimeMillis();
        new AlertDialog.Builder(a).setTitle(R.string.auto_trigger)
                .setItems(names(Automation.T_NAMES), (x, w) -> {
                    r.trig = w;
                    switch (w) {
                        case Automation.T_TIME:
                            new android.app.TimePickerDialog(a, (tp, hh, mm) -> {
                                r.tArg = String.format(Locale.US, "%02d:%02d", hh, mm);
                                pickAction(r);
                            }, 22, 0, true).show();
                            break;
                        case Automation.T_BATT:
                            ask(R.string.at_batt, InputType.TYPE_CLASS_NUMBER, "20", s -> {
                                int v = Automation.safeInt(s, -1);
                                if (v < 1 || v > 100) return;
                                r.tArg = String.valueOf(v);
                                pickAction(r);
                            });
                            break;
                        case Automation.T_BT:
                        case Automation.T_WIFI:
                            ask(w == Automation.T_BT ? R.string.at_bt : R.string.at_wifi, InputType.TYPE_CLASS_TEXT,
                                    a.getString(R.string.auto_any), s -> {
                                        r.tArg = s;
                                        pickAction(r);
                                    });
                            break;
                        default:
                            pickAction(r);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickAction(Automation.Rule r) {
        new AlertDialog.Builder(a).setTitle(R.string.auto_action)
                .setItems(names(Automation.A_NAMES), (x, w) -> {
                    r.act = w;
                    switch (w) {
                        case Automation.A_FOCUS:
                            new AlertDialog.Builder(a).setTitle(R.string.aa_focus)
                                    .setItems(names(Focus.NAMES), (y, f) -> {
                                        r.aArg = String.valueOf(f);
                                        saveRule(r);
                                    }).show();
                            break;
                        case Automation.A_BRIGHT:
                        case Automation.A_VOLUME:
                            ask(w == Automation.A_BRIGHT ? R.string.aa_bright : R.string.aa_volume, InputType.TYPE_CLASS_NUMBER, "50", s -> {
                                int v = Automation.safeInt(s, -1);
                                if (v < 0 || v > 100) return;
                                r.aArg = String.valueOf(v);
                                saveRule(r);
                            });
                            break;
                        case Automation.A_OPEN:
                            pickApp(a.getString(R.string.aa_open), pkg -> {
                                r.aArg = pkg;
                                saveRule(r);
                            });
                            break;
                        case Automation.A_MSG:
                            ask(R.string.aa_msg, InputType.TYPE_CLASS_TEXT, a.getString(R.string.auto_title), s -> {
                                r.aArg = s;
                                saveRule(r);
                            });
                            break;
                        default:
                            saveRule(r);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void saveRule(Automation.Rule r) {
        List<Automation.Rule> l = Automation.all(a);
        l.add(r);
        Automation.save(a, l);
        fillRules();
        a.toast(Automation.describe(a, r));
    }

    // ======================= Log ralat =======================

    private void errorLog() {
        if (!needShell()) return;
        a.toast(a.getString(R.string.log_reading));
        Shell.run("logcat -d -t 3000 -v time 2>/dev/null | grep -iE 'glasskit|AndroidRuntime|FATAL' | tail -n 300", (ok, out) -> {
            String crash = CrashLog.read(a);
            String txt = "GlassKit " + Updater.current(a) + " · Android " + android.os.Build.VERSION.RELEASE + "\n\n"
                    + (crash == null || crash.isEmpty() ? "" : "== Crash ==\n" + crash + "\n\n")
                    + "== Logcat ==\n" + (out == null || out.trim().isEmpty() ? "(kosong)" : out);
            TextView tv = new TextView(a);
            tv.setText(txt);
            tv.setTextSize(11);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(18), dp(10), dp(18), dp(10));
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(tv);
            new AlertDialog.Builder(a).setTitle(R.string.tl_log).setView(sv)
                    .setPositiveButton(R.string.log_copy, (x, y) -> {
                        a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("GlassKit log", txt));
                        a.toast(a.getString(R.string.copied));
                    })
                    .setNeutralButton(R.string.log_share, (x, y) -> {
                        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt);
                        a.startActivity(Intent.createChooser(i, a.getString(R.string.tl_log)));
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
    }
}
