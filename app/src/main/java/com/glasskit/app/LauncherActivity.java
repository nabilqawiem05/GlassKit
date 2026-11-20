package com.glasskit.app;

import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher GlassKit: home + dock + laci app + folder bertajuk.
 * Rupa dan animasi ikut tema: 0 Classic (kaca), 1 Liquid island (monokrom cecair), 2 Bento (kotak), 3 Clay (tanah liat).
 */
public class LauncherActivity extends Activity {
    static final String K_HOME = "lHome", K_DOCK = "lDock";
    private static final int COLS = 4;

    static final class App {
        String key, label;
        ComponentName cn;
        UserHandle user;
        Drawable icon;
    }

    private SharedPreferences prefs;
    private LauncherApps la;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, App> apps = new HashMap<>();
    private final List<App> sorted = new ArrayList<>();
    private int theme = -1;
    private float density;

    private FrameLayout root;
    private LinearLayout home, homeCol, dock;
    private ScrollView scroll;
    private GridLayout grid;
    private ImageView header;
    private FrameLayout drawer, folderLayer;
    private GridView drawerGrid;
    private EditText search;
    private final List<App> shown = new ArrayList<>();
    private int insetTop, insetBottom;
    private GlassWidget headerWidget;
    private boolean animatePending = true;

    private int dp(float v) { return Math.round(v * density); }

    // ======================================================= kitar hayat

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        prefs = Prefs.get(this);
        density = getResources().getDisplayMetrics().density;
        la = getSystemService(LauncherApps.class);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root = new FrameLayout(this);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars());
            if (s.top != insetTop || s.bottom != insetBottom) {
                insetTop = s.top;
                insetBottom = s.bottom;
                build();
            }
            return in;
        });
        try {
            la.registerCallback(appsCb, h);
        } catch (Throwable ignored) {
        }
        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(tick, f);
        renderHeader();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(tick); } catch (Throwable ignored) {}
        animatePending = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        int t = styleOf(this);
        if (t != theme) {
            theme = t;
            build();
        }
        if (animatePending) {
            animatePending = false;
            h.post(this::animateIn);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // butang Home semasa di home: tutup laci / folder, naik ke atas
        if (folderLayer != null && folderLayer.getVisibility() == View.VISIBLE) closeFolder();
        else if (drawer != null && drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (scroll != null) scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (folderLayer != null && folderLayer.getVisibility() == View.VISIBLE) closeFolder();
        else if (drawer != null && drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override
    protected void onDestroy() {
        try { la.unregisterCallback(appsCb); } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { renderHeader(); }
    };

    private final LauncherApps.Callback appsCb = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { loadApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { loadApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { loadApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { loadApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { loadApps(); }
    };

    /** Gaya launcher: tetapan sendiri, atau ikut gaya panel sistem (tema GlassKit). */
    static int styleOf(Context c) {
        SharedPreferences p = Prefs.get(c);
        int s = p.getInt(Prefs.L_STYLE, -1);
        if (s < 0) s = p.getInt(Prefs.PANEL_STYLE, 1);
        return Math.max(0, Math.min(3, s));
    }

    // ======================================================= data app

    private void loadApps() {
        final int px = getResources().getDisplayMetrics().densityDpi;
        io.execute(() -> {
            List<App> list = new ArrayList<>();
            try {
                for (UserHandle u : la.getProfiles()) {
                    for (LauncherActivityInfo i : la.getActivityList(null, u)) {
                        if (getPackageName().equals(i.getComponentName().getPackageName())
                                && LauncherActivity.class.getName().equals(i.getComponentName().getClassName())) continue;
                        App a = new App();
                        a.cn = i.getComponentName();
                        a.user = u;
                        a.key = a.cn.flattenToShortString() + (u.equals(Process.myUserHandle()) ? "" : "#" + u.hashCode());
                        a.label = String.valueOf(i.getLabel());
                        try {
                            a.icon = u.equals(Process.myUserHandle()) ? i.getIcon(px) : i.getBadgedIcon(px);
                        } catch (Throwable e) {
                            a.icon = getPackageManager().getDefaultActivityIcon();
                        }
                        list.add(a);
                    }
                }
            } catch (Throwable e) {
                CrashLog.write(this, e);
            }
            Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
            h.post(() -> {
                apps.clear();
                for (App a : list) apps.put(a.key, a);
                sorted.clear();
                sorted.addAll(list);
                seedDefaults();
                build();
            });
        });
    }

    /** Kali pertama: isi dock (telefon, mesej, pelayar, kamera) dan beberapa app di home. */
    private void seedDefaults() {
        if (prefs.contains(K_DOCK) || sorted.isEmpty()) return;
        JSONArray dk = new JSONArray();
        Intent[] want = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
                new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)};
        List<String> used = new ArrayList<>();
        for (Intent w : want) {
            try {
                android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(w, 0);
                if (r == null || r.activityInfo == null) continue;
                String pkg = r.activityInfo.packageName;
                for (App a : sorted)
                    if (a.cn.getPackageName().equals(pkg) && !used.contains(a.key)) {
                        dk.put(a.key);
                        used.add(a.key);
                        break;
                    }
            } catch (Throwable ignored) {
            }
        }
        JSONArray hm = new JSONArray();
        String[] pref = {"com.google.android.apps.photos", "com.whatsapp", "com.android.vending", "com.google.android.gm",
                "com.google.android.apps.maps", "com.google.android.calendar", "com.google.android.deskclock", "com.android.settings"};
        for (String p : pref)
            for (App a : sorted)
                if (a.cn.getPackageName().equals(p) && !used.contains(a.key)) {
                    hm.put(item(a.key));
                    used.add(a.key);
                    break;
                }
        prefs.edit().putString(K_DOCK, dk.toString()).putString(K_HOME, hm.toString()).apply();
    }

    private static JSONObject item(String key) {
        JSONObject o = new JSONObject();
        try { o.put("c", key); } catch (Exception ignored) {}
        return o;
    }

    private JSONArray homeItems() {
        try { return new JSONArray(prefs.getString(K_HOME, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private JSONArray dockItems() {
        try { return new JSONArray(prefs.getString(K_DOCK, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void saveHome(JSONArray a) { prefs.edit().putString(K_HOME, a.toString()).apply(); build(); }

    private void saveDock(JSONArray a) { prefs.edit().putString(K_DOCK, a.toString()).apply(); build(); }

    // ======================================================= rupa ikut tema

    private int ink() { return theme == 3 && !BentoViews.clayDark ? BentoViews.CLAY_INK : Color.WHITE; }

    private Drawable tileBg(boolean folder) {
        switch (theme) {
            case 0: {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(16));
                g.setColor(Color.argb(60, 255, 255, 255));
                g.setStroke(Math.max(1, dp(0.8f)), Color.argb(90, 255, 255, 255));
                return folder ? g : null;
            }
            case 1: {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(Color.argb(folder ? 200 : 0, 18, 18, 22));
                g.setStroke(Math.max(1, dp(1)), Color.argb(110, 255, 255, 255));
                return g;
            }
            case 2: {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(18));
                g.setColor(0xE61F1D29);
                g.setStroke(Math.max(1, dp(0.8f)), Color.argb(40, 255, 255, 255));
                return g;
            }
            default:
                return new BentoViews.Clay(this, BentoViews.CLAY_BASE, 20, false);
        }
    }

    /** Saiz ikon di dalam jubin: Classic & Liquid penuh, Bento & Clay dalam kotak. */
    private int iconInset() { return theme >= 2 ? dp(theme == 3 ? 15 : 12) : 0; }

    private void styleIcon(ImageView iv) {
        if (theme == 1) {
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(0f);
            iv.setColorFilter(new ColorMatrixColorFilter(m));
        }
        if (theme == 0) iv.setElevation(dp(4));
    }

    private TextView label(String s) {
        TextView t = Ui.line(this, 12, 500, Color.WHITE);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(dp(3), 0, dp(1), Color.argb(150, 0, 0, 0));
        return t;
    }

    private View appCell(App a, boolean withLabel, int tile) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout f = new FrameLayout(this);
        Drawable bg = tileBg(false);
        if (bg != null) f.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        styleIcon(iv);
        int in = iconInset();
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -1);
        il.setMargins(in, in, in, in);
        f.addView(iv, il);
        if (theme == 1) {
            // kilauan kaca cecair di atas ikon
            GradientDrawable gl = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)});
            gl.setShape(GradientDrawable.OVAL);
            f.setForeground(gl);
        }
        cell.addView(f, new LinearLayout.LayoutParams(tile, tile));
        if (withLabel) {
            TextView t = label(a.label);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = dp(5);
            cell.addView(t, tl);
        }
        pressFx(cell, f);
        return cell;
    }

    private View folderCell(JSONObject fo, int tile) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout f = new FrameLayout(this);
        f.setBackground(tileBg(true));
        GridLayout mini = new GridLayout(this);
        mini.setColumnCount(2);
        JSONArray a = fo.optJSONArray("a");
        int ms = (tile - dp(theme == 3 ? 26 : 20)) / 2;
        for (int i = 0; a != null && i < Math.min(4, a.length()); i++) {
            App ap = apps.get(a.optString(i));
            ImageView iv = new ImageView(this);
            if (ap != null) iv.setImageDrawable(ap.icon);
            if (theme == 1) {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(0f);
                iv.setColorFilter(new ColorMatrixColorFilter(m));
            }
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = ms;
            gl.height = ms;
            gl.setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
            mini.addView(iv, gl);
        }
        f.addView(mini, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        cell.addView(f, new LinearLayout.LayoutParams(tile, tile));
        TextView t = label(fo.optString("f", getString(R.string.l_folder)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(5);
        cell.addView(t, tl);
        pressFx(cell, f);
        return cell;
    }

    /** Kesan tekan ikut tema. */
    private void pressFx(View cell, View tile) {
        cell.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (theme == 3) tile.animate().scaleX(0.88f).scaleY(0.84f).setDuration(120).start();          // tenggelam
                    else if (theme == 1) tile.animate().scaleX(1.08f).scaleY(0.92f).setDuration(110).start();     // goyang air
                    else tile.animate().scaleX(0.92f).scaleY(0.92f).setDuration(110).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tile.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(theme == 1 ? 4f : 2.2f))
                            .setDuration(theme == 1 ? 420 : 300).start();
                    break;
            }
            return false;
        });
    }

    // ======================================================= bina skrin

    private int tileSize() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int cellW = (w - dp(24)) / COLS;
        return Math.min(cellW - dp(16), dp(theme >= 2 ? 66 : 60));
    }

    private void build() {
        if (theme < 0) theme = styleOf(this);
        if (theme == 3) BentoViews.palette(this);
        root.removeAllViews();

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        homeCol = new LinearLayout(this);
        homeCol.setOrientation(LinearLayout.VERTICAL);
        homeCol.setPadding(dp(12), insetTop + dp(10), dp(12), dp(12));
        homeCol.setOnLongClickListener(v -> { homeMenu(v); return true; });
        scroll.addView(homeCol, new FrameLayout.LayoutParams(-1, -2));
        home.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        header = new ImageView(this);
        header.setScaleType(ImageView.ScaleType.FIT_XY);
        header.setOnClickListener(v -> openIntent(theme == 1 ? GlassWidget.alarms() : GlassWidget.calendar(), v));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, dp(headerDp()));
        hl.bottomMargin = dp(14);
        homeCol.addView(header, hl);
        headerWidget = theme == 2 ? new BentoWidget() : theme == 1 ? new ClockWidget() : new UpNextWidget();
        renderHeader();

        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        homeCol.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        int tile = tileSize();
        JSONArray items = homeItems();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            final int idx = i;
            View cell;
            if (o.has("f")) {
                cell = folderCell(o, tile);
                cell.setOnClickListener(v -> openFolder(idx, v));
                cell.setOnLongClickListener(v -> { folderMenu(idx, v); return true; });
            } else {
                App a = apps.get(o.optString("c"));
                if (a == null) continue;
                cell = appCell(a, true, tile);
                cell.setOnClickListener(v -> launch(a, v));
                cell.setOnLongClickListener(v -> { homeAppMenu(idx, a, v); return true; });
            }
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            gl.width = 0;
            gl.bottomMargin = dp(16);
            grid.addView(cell, gl);
        }
        if (items.length() == 0) {
            TextView hint = Ui.text(this, 13, 500, Color.argb(200, 255, 255, 255));
            hint.setText(R.string.l_empty_hint);
            hint.setGravity(Gravity.CENTER);
            hint.setShadowLayer(dp(3), 0, dp(1), Color.argb(150, 0, 0, 0));
            hint.setPadding(dp(20), dp(30), dp(20), dp(30));
            homeCol.addView(hint);
        }

        // pemegang laci
        View handle = new View(this);
        handle.setBackground(Ui.round(Color.argb(150, 255, 255, 255), dp(3)));
        LinearLayout hw = new LinearLayout(this);
        hw.setGravity(Gravity.CENTER);
        hw.addView(handle, new LinearLayout.LayoutParams(dp(38), dp(5)));
        hw.setOnClickListener(v -> openDrawer());
        home.addView(hw, new LinearLayout.LayoutParams(-1, dp(22)));

        // dock
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(10), dp(10), dp(10), dp(10));
        dock.setBackground(dockBg());
        JSONArray dk = dockItems();
        int dt = Math.min(tile, dp(58));
        for (int i = 0; i < dk.length(); i++) {
            App a = apps.get(dk.optString(i));
            if (a == null) continue;
            final int idx = i;
            View c = appCell(a, false, dt);
            c.setOnClickListener(v -> launch(a, v));
            c.setOnLongClickListener(v -> { dockMenu(idx, a, v); return true; });
            dock.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (dock.getChildCount() == 0) {
            TextView t = Ui.text(this, 12, 500, ink());
            t.setText(R.string.l_dock_hint);
            dock.addView(t);
        }
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.setMargins(dp(12), 0, dp(12), insetBottom + dp(10));
        home.addView(dock, dl);

        buildDrawer();
        folderLayer = new FrameLayout(this);
        folderLayer.setVisibility(View.GONE);
        root.addView(folderLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private int headerDp() { return theme == 2 ? 300 : theme == 1 ? 150 : 170; }

    private Drawable dockBg() {
        switch (theme) {
            case 0: return Ui.glassStyled(this, 30, Ui.darkGlass(this));
            case 1: {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(34));
                g.setColor(Color.argb(200, 14, 14, 18));
                g.setStroke(Math.max(1, dp(1)), Color.argb(80, 255, 255, 255));
                return g;
            }
            case 2: {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(24));
                g.setColor(0xF014131C);
                g.setStroke(Math.max(1, dp(0.8f)), Color.argb(40, 255, 255, 255));
                return g;
            }
            default: return new BentoViews.Clay(this, BentoViews.CLAY_PANEL, 32, false);
        }
    }

    private void renderHeader() {
        if (header == null || headerWidget == null) return;
        int w = getResources().getDisplayMetrics().widthPixels - dp(24);
        int hh = dp(headerDp());
        float d = density;
        if (Math.max(w, hh) > 1100) d = density * 1100f / Math.max(w, hh);
        try {
            Bitmap b = headerWidget.draw(this, Math.round(w * d / density), Math.round(hh * d / density), d);
            header.setImageBitmap(b);
        } catch (Throwable e) {
            CrashLog.write(this, e);
        }
    }

    // ======================================================= animasi masuk home ikut tema

    private void animateIn() {
        if (grid == null || Power.low(this)) return;
        List<View> vs = new ArrayList<>();
        List<float[]> rc = new ArrayList<>();
        vs.add(header);
        rc.add(new float[]{-1, 1.5f});
        for (int i = 0; i < grid.getChildCount(); i++) {
            vs.add(grid.getChildAt(i));
            rc.add(new float[]{i / COLS, i % COLS});
        }
        int rows = (grid.getChildCount() + COLS - 1) / COLS;
        for (int i = 0; i < dock.getChildCount(); i++) {
            vs.add(dock.getChildAt(i));
            rc.add(new float[]{rows + 1, i});
        }
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < vs.size(); i++) {
            View v = vs.get(i);
            float r = rc.get(i)[0] + 1, c = rc.get(i)[1];
            v.animate().cancel();
            switch (theme) {
                case 0: { // zoom keluar dari app: kabur → jelas, lantun lembut
                    v.setScaleX(1.3f);
                    v.setScaleY(1.3f);
                    v.setAlpha(0f);
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(Math.round((Math.abs(c - 1.5f) + r) * 28))
                            .setDuration(440).setInterpolator(new OvershootInterpolator(1.1f)).start();
                    break;
                }
                case 1: { // titisan dari island: jatuh memanjang, leper, kembali bulat
                    v.setPivotX(v.getWidth() / 2f);
                    v.setPivotY(v.getHeight());
                    float fall = -dp(160) - r * dp(26);
                    PropertyValuesHolder ty = PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y,
                            Keyframe.ofFloat(0f, fall), kf(0.55f, 0f, new android.view.animation.AccelerateInterpolator(1.6f)), Keyframe.ofFloat(1f, 0f));
                    PropertyValuesHolder sx = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                            Keyframe.ofFloat(0f, 0.7f), Keyframe.ofFloat(0.5f, 0.82f), Keyframe.ofFloat(0.63f, 1.22f), Keyframe.ofFloat(0.8f, 0.95f), Keyframe.ofFloat(1f, 1f));
                    PropertyValuesHolder sy = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                            Keyframe.ofFloat(0f, 1.45f), Keyframe.ofFloat(0.5f, 1.25f), Keyframe.ofFloat(0.63f, 0.78f), Keyframe.ofFloat(0.8f, 1.06f), Keyframe.ofFloat(1f, 1f));
                    PropertyValuesHolder al = PropertyValuesHolder.ofKeyframe(View.ALPHA,
                            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.25f, 1f), Keyframe.ofFloat(1f, 1f));
                    ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, ty, sx, sy, al);
                    a.setDuration(760);
                    a.setStartDelay(Math.round(c * 55 + r * 80 + rnd.nextInt(40)));
                    v.setAlpha(0f);
                    a.start();
                    break;
                }
                case 2: { // kotak terbalik satu-satu macam papan
                    v.setCameraDistance(9000 * density);
                    v.setPivotY(v.getHeight());
                    v.setRotationX(-90f);
                    v.setAlpha(0f);
                    v.animate().rotationX(0f).alpha(1f).setStartDelay(Math.round((r + c) * 55)).setDuration(480)
                            .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                    break;
                }
                default: { // tanah liat: timbul, kembung, penyek
                    v.setScaleX(0f);
                    v.setScaleY(0f);
                    PropertyValuesHolder sx = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.45f, 1.18f), Keyframe.ofFloat(0.65f, 0.9f), Keyframe.ofFloat(0.85f, 1.04f), Keyframe.ofFloat(1f, 1f));
                    PropertyValuesHolder sy = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.45f, 0.82f), Keyframe.ofFloat(0.65f, 1.1f), Keyframe.ofFloat(0.85f, 0.97f), Keyframe.ofFloat(1f, 1f));
                    ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, sx, sy);
                    a.setDuration(680);
                    a.setStartDelay(i * 32L);
                    a.start();
                }
            }
        }
    }

    private static Keyframe kf(float f, float v, android.animation.TimeInterpolator in) {
        Keyframe k = Keyframe.ofFloat(f, v);
        k.setInterpolator(in);
        return k;
    }

    // ======================================================= buka app

    private void launch(App a, View v) {
        try {
            Rect r = new Rect();
            v.getGlobalVisibleRect(r);
            Bundle o = (theme == 2 ? ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.getWidth(), v.getHeight())
                    : ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight())).toBundle();
            la.startMainActivity(a.cn, a.user, r, o);
        } catch (Throwable e) {
            android.widget.Toast.makeText(this, getString(R.string.l_cant_open), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openIntent(Intent i, View v) {
        if (i == null) return;
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle());
        } catch (Throwable ignored) {
        }
    }

    // ======================================================= gerak isyarat

    private final GestureDetector gd = new GestureDetector(null, new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (Math.abs(vy) < Math.abs(vx) * 1.3f || Math.abs(vy) < 900 * density / 2.6f) return false;
            boolean dOpen = drawer != null && drawer.getVisibility() == View.VISIBLE;
            boolean fOpen = folderLayer != null && folderLayer.getVisibility() == View.VISIBLE;
            if (fOpen) return false;
            if (dOpen) {
                if (vy > 0 && !drawerGrid.canScrollVertically(-1)) { closeDrawer(); return true; }
                return false;
            }
            if (vy < 0 && (scroll == null || !scroll.canScrollVertically(1))) { openDrawer(); return true; }
            if (vy > 0 && (scroll == null || !scroll.canScrollVertically(-1))) { expandNotifications(); return true; }
            return false;
        }
    });

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        boolean used = gd.onTouchEvent(e);
        if (used) {
            MotionEvent c = MotionEvent.obtain(e);
            c.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(c);
            c.recycle();
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable ignored) {
        }
    }

    // ======================================================= laci app

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), insetTop + dp(14), dp(12), insetBottom);
        drawer.addView(col, new FrameLayout.LayoutParams(-1, -1));

        search = new EditText(this);
        search.setHint(R.string.l_search);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTypeface(Ui.font(500));
        boolean lightClay = theme == 3 && !BentoViews.clayDark;
        search.setTextColor(lightClay ? BentoViews.CLAY_INK : Color.WHITE);
        search.setHintTextColor(lightClay ? BentoViews.CLAY_SOFT : Color.argb(150, 255, 255, 255));
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.setBackground(theme == 3 ? new BentoViews.Clay(this, BentoViews.CLAY_BASE, 24, false)
                : Ui.round(theme == 2 ? 0xFF262433 : Color.argb(40, 255, 255, 255), dp(24)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (!shown.isEmpty()) launch(shown.get(0), v);
            return true;
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = dp(12);
        col.addView(search, sl);

        drawerGrid = new GridView(this);
        drawerGrid.setNumColumns(COLS);
        drawerGrid.setVerticalSpacing(dp(14));
        drawerGrid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        drawerGrid.setVerticalScrollBarEnabled(false);
        drawerGrid.setClipToPadding(false);
        drawerGrid.setPadding(0, 0, 0, dp(20));
        final int tile = tileSize();
        drawerGrid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View cv, ViewGroup p) {
                App a = shown.get(i);
                View c = appCell(a, true, tile);
                if (lightClay) ((TextView) ((LinearLayout) c).getChildAt(1)).setTextColor(BentoViews.CLAY_INK);
                if (lightClay) ((TextView) ((LinearLayout) c).getChildAt(1)).setShadowLayer(0, 0, 0, 0);
                c.setOnClickListener(v -> launch(a, v));
                c.setOnLongClickListener(v -> { drawerMenu(a, v); return true; });
                return c;
            }
        });
        col.addView(drawerGrid, new LinearLayout.LayoutParams(-1, 0, 1f));
        filter("");
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void filter(String q) {
        shown.clear();
        String s = q.trim().toLowerCase(Locale.ROOT);
        for (App a : sorted) if (s.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(s)) shown.add(a);
        if (drawerGrid != null) ((BaseAdapter) drawerGrid.getAdapter()).notifyDataSetChanged();
    }

    private int drawerColor() {
        switch (theme) {
            case 0: return Color.argb(165, 12, 14, 22);
            case 1: return Color.argb(230, 0, 0, 0);
            case 2: return 0xF514131C;
            default: return (BentoViews.CLAY_PANEL & 0x00FFFFFF) | 0xF5000000;
        }
    }

    private void openDrawer() {
        if (drawer == null || drawer.getVisibility() == View.VISIBLE) return;
        Haptics.soft(this);
        search.setText("");
        drawer.setBackgroundColor(drawerColor());
        drawer.setVisibility(View.VISIBLE);
        float hgt = root.getHeight();
        boolean low = Power.low(this);
        if (!low && android.os.Build.VERSION.SDK_INT >= 31 && theme <= 1)
            home.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(18), dp(18), android.graphics.Shader.TileMode.CLAMP));
        home.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.35f).setDuration(320).setInterpolator(Ui.EASE).start();
        drawer.animate().cancel();
        switch (theme) {
            case 2: {
                drawer.setAlpha(0f);
                drawer.setTranslationY(0);
                drawer.animate().alpha(1f).setDuration(220).start();
                if (!low) {
                    android.view.animation.AnimationSet as = new android.view.animation.AnimationSet(true);
                    as.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
                    android.view.animation.ScaleAnimation sc = new android.view.animation.ScaleAnimation(0.6f, 1f, 0.6f, 1f,
                            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
                    as.addAnimation(sc);
                    as.setDuration(300);
                    android.view.animation.GridLayoutAnimationController g = new android.view.animation.GridLayoutAnimationController(as, 0.12f, 0.12f);
                    drawerGrid.setLayoutAnimation(g);
                    drawerGrid.scheduleLayoutAnimation();
                }
                break;
            }
            case 3:
                drawer.setAlpha(1f);
                drawer.setTranslationY(hgt);
                drawer.animate().translationY(0).setDuration(low ? 200 : 520).setInterpolator(new OvershootInterpolator(0.9f)).start();
                break;
            default:
                drawer.setAlpha(0f);
                drawer.setTranslationY(hgt * 0.22f);
                drawer.animate().alpha(1f).translationY(0).setDuration(low ? 180 : 340).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        hideKeyboard();
        if (android.os.Build.VERSION.SDK_INT >= 31) home.setRenderEffect(null);
        home.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(Ui.EASE).start();
        drawer.animate().alpha(theme == 3 ? 1f : 0f).translationY(theme == 3 ? root.getHeight() : root.getHeight() * 0.15f)
                .setDuration(theme == 3 ? 300 : 220).setInterpolator(Ui.EXIT)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void hideKeyboard() {
        try {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(root.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    // ======================================================= folder

    private Drawable folderCardBg() {
        switch (theme) {
            case 0: return Ui.glassStyled(this, 32, true);
            case 1: {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(36));
                g.setColor(Color.argb(235, 12, 12, 16));
                g.setStroke(Math.max(1, dp(1)), Color.argb(90, 255, 255, 255));
                return g;
            }
            case 2: {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(28));
                g.setColor(0xFA14131C);
                g.setStroke(Math.max(1, dp(0.8f)), Color.argb(45, 255, 255, 255));
                return g;
            }
            default: return new BentoViews.Clay(this, BentoViews.CLAY_PANEL, 34, false);
        }
    }

    private void openFolder(int idx, View from) {
        JSONArray items = homeItems();
        JSONObject fo = items.optJSONObject(idx);
        if (fo == null) return;
        folderLayer.removeAllViews();
        folderLayer.setBackgroundColor(Color.argb(110, 0, 0, 0));
        folderLayer.setOnClickListener(v -> closeFolder());
        boolean lightClay = theme == 3 && !BentoViews.clayDark;
        int fg = lightClay ? BentoViews.CLAY_INK : Color.WHITE;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(folderCardBg());
        card.setClickable(true);

        EditText title = new EditText(this);
        title.setText(fo.optString("f", getString(R.string.l_folder)));
        title.setTextColor(fg);
        title.setTextSize(22);
        title.setTypeface(Ui.font(700));
        title.setSingleLine(true);
        title.setBackground(null);
        title.setGravity(Gravity.CENTER);
        title.setOnFocusChangeListener((v, has) -> {
            if (!has) renameFolder(idx, title.getText().toString());
        });
        title.setOnEditorActionListener((v, id, ev) -> {
            renameFolder(idx, title.getText().toString());
            hideKeyboard();
            title.clearFocus();
            return true;
        });
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        GridLayout g = new GridLayout(this);
        g.setColumnCount(COLS);
        int tile = Math.min(tileSize(), dp(56));
        JSONArray a = fo.optJSONArray("a");
        for (int i = 0; a != null && i < a.length(); i++) {
            App ap = apps.get(a.optString(i));
            if (ap == null) continue;
            View c = appCell(ap, true, tile);
            if (lightClay) {
                TextView t = (TextView) ((LinearLayout) c).getChildAt(1);
                t.setTextColor(BentoViews.CLAY_INK);
                t.setShadowLayer(0, 0, 0, 0);
            }
            final String key = ap.key;
            c.setOnClickListener(v -> { launch(ap, v); h.postDelayed(this::closeFolder, 400); });
            c.setOnLongClickListener(v -> {
                PopupMenu pm = new PopupMenu(this, v);
                pm.getMenu().add(R.string.l_remove_from_folder);
                pm.getMenu().add(R.string.l_app_info);
                pm.setOnMenuItemClickListener(mi -> {
                    if (mi.getTitle().equals(getString(R.string.l_remove_from_folder))) {
                        setFolderApps(idx, removeKey(fo.optJSONArray("a"), key));
                        openFolder(idx, null);
                    } else appInfo(ap);
                    return true;
                });
                pm.show();
                return true;
            });
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            gl.width = 0;
            gl.topMargin = dp(14);
            g.addView(c, gl);
        }
        card.addView(g, new LinearLayout.LayoutParams(-1, -2));

        TextView add = Ui.text(this, 15, 600, fg);
        add.setText(R.string.l_add_apps);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(14), dp(12), dp(14), dp(12));
        add.setBackground(theme == 3 ? new BentoViews.Clay(this, BentoViews.CLAY_BASE, 22, false)
                : Ui.round(Color.argb(theme == 2 ? 255 : 40, theme == 2 ? 0x26 : 255, theme == 2 ? 0x24 : 255, theme == 2 ? 0x33 : 255), dp(22)));
        add.setOnClickListener(v -> pickApps(fo.optJSONArray("a"), sel -> { setFolderApps(idx, sel); openFolder(idx, null); }));
        Ui.press(add);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(18);
        card.addView(add, al);

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        cl.setMargins(dp(22), 0, dp(22), 0);
        folderLayer.addView(card, cl);
        boolean was = folderLayer.getVisibility() == View.VISIBLE;
        folderLayer.setVisibility(View.VISIBLE);
        if (was || Power.low(this)) return;
        // animasi buka folder ikut tema
        folderLayer.setAlpha(0f);
        folderLayer.animate().alpha(1f).setDuration(200).start();
        switch (theme) {
            case 0:
                card.setScaleX(0.3f);
                card.setScaleY(0.3f);
                card.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(1.1f)).start();
                break;
            case 1:
                card.setScaleX(0.5f);
                card.setScaleY(0.2f);
                card.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(new OvershootInterpolator(2.4f)).start();
                break;
            case 2:
                card.setCameraDistance(9000 * density);
                card.setRotationY(-70f);
                card.animate().rotationY(0f).setDuration(420).setInterpolator(new DecelerateInterpolator(1.8f)).start();
                break;
            default: {
                PropertyValuesHolder sx = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                        Keyframe.ofFloat(0f, 0.2f), Keyframe.ofFloat(0.5f, 1.08f), Keyframe.ofFloat(0.75f, 0.96f), Keyframe.ofFloat(1f, 1f));
                PropertyValuesHolder sy = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                        Keyframe.ofFloat(0f, 0.2f), Keyframe.ofFloat(0.5f, 0.94f), Keyframe.ofFloat(0.75f, 1.04f), Keyframe.ofFloat(1f, 1f));
                ObjectAnimator.ofPropertyValuesHolder(card, sx, sy).setDuration(560).start();
            }
        }
    }

    private void closeFolder() {
        if (folderLayer == null || folderLayer.getVisibility() != View.VISIBLE) return;
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
        hideKeyboard();
        folderLayer.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            folderLayer.setVisibility(View.GONE);
            folderLayer.setAlpha(1f);
            build();
        }).start();
    }

    private void renameFolder(int idx, String name) {
        JSONArray items = homeItems();
        JSONObject fo = items.optJSONObject(idx);
        if (fo == null) return;
        String n = name.trim();
        if (n.isEmpty()) n = getString(R.string.l_folder);
        try { fo.put("f", n); } catch (Exception ignored) {}
        prefs.edit().putString(K_HOME, items.toString()).apply();
    }

    private void setFolderApps(int idx, JSONArray sel) {
        JSONArray items = homeItems();
        JSONObject fo = items.optJSONObject(idx);
        if (fo == null) return;
        try { fo.put("a", sel); } catch (Exception ignored) {}
        prefs.edit().putString(K_HOME, items.toString()).apply();
    }

    private static JSONArray removeKey(JSONArray a, String key) {
        JSONArray o = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) if (!key.equals(a.optString(i))) o.put(a.optString(i));
        return o;
    }

    interface Picked { void on(JSONArray sel); }

    /** Pilih banyak app sekali (bertanda ✓). */
    private void pickApps(JSONArray current, Picked cb) {
        final String[] names = new String[sorted.size()];
        final boolean[] on = new boolean[sorted.size()];
        List<String> cur = new ArrayList<>();
        for (int i = 0; current != null && i < current.length(); i++) cur.add(current.optString(i));
        for (int i = 0; i < sorted.size(); i++) {
            names[i] = sorted.get(i).label;
            on[i] = cur.contains(sorted.get(i).key);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.l_pick_apps)
                .setMultiChoiceItems(names, on, (d, w, c) -> on[w] = c)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    JSONArray out = new JSONArray();
                    for (String k : cur) {
                        int j = indexOf(k);
                        if (j >= 0 && on[j]) out.put(k);
                    }
                    for (int i = 0; i < sorted.size(); i++) if (on[i] && !cur.contains(sorted.get(i).key)) out.put(sorted.get(i).key);
                    cb.on(out);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int indexOf(String key) {
        for (int i = 0; i < sorted.size(); i++) if (sorted.get(i).key.equals(key)) return i;
        return -1;
    }

    private void newFolder(String firstKey) {
        EditText in = new EditText(this);
        in.setHint(R.string.l_folder_name_hint);
        in.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(in);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.l_new_folder)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) n = getString(R.string.l_folder);
                    final String name = n;
                    JSONArray start = new JSONArray();
                    if (firstKey != null) start.put(firstKey);
                    pickApps(start, sel -> {
                        JSONArray items = homeItems();
                        if (firstKey != null) items = removeHomeKey(items, firstKey);
                        JSONObject fo = new JSONObject();
                        try {
                            fo.put("f", name);
                            fo.put("a", sel);
                        } catch (Exception ignored) {
                        }
                        items.put(fo);
                        saveHome(items);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static JSONArray removeHomeKey(JSONArray items, String key) {
        JSONArray o = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && key.equals(it.optString("c", null))) continue;
            o.put(it);
        }
        return o;
    }

    // ======================================================= menu tekan lama

    private void homeMenu(View v) {
        PopupMenu pm = new PopupMenu(this, v, Gravity.CENTER);
        pm.getMenu().add(0, 1, 0, R.string.l_new_folder);
        pm.getMenu().add(0, 2, 0, R.string.l_all_apps);
        pm.getMenu().add(0, 3, 0, R.string.l_wallpaper);
        pm.getMenu().add(0, 4, 0, R.string.l_settings);
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: newFolder(null); break;
                case 2: openDrawer(); break;
                case 3: openIntent(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.l_wallpaper)), v); break;
                default: openIntent(new Intent(this, MainActivity.class), v);
            }
            return true;
        });
        pm.show();
    }

    private void homeAppMenu(int idx, App a, View v) {
        Haptics.thud(this);
        PopupMenu pm = new PopupMenu(this, v);
        pm.getMenu().add(0, 1, 0, R.string.l_move_left);
        pm.getMenu().add(0, 2, 0, R.string.l_move_right);
        pm.getMenu().add(0, 3, 0, R.string.l_to_folder);
        pm.getMenu().add(0, 4, 0, R.string.l_to_dock);
        pm.getMenu().add(0, 5, 0, R.string.l_remove_home);
        pm.getMenu().add(0, 6, 0, R.string.l_app_info);
        pm.getMenu().add(0, 7, 0, R.string.l_uninstall);
        pm.setOnMenuItemClickListener(mi -> {
            JSONArray items = homeItems();
            switch (mi.getItemId()) {
                case 1: saveHome(move(items, idx, -1)); break;
                case 2: saveHome(move(items, idx, 1)); break;
                case 3: toFolder(a.key, true); break;
                case 4: addDock(a.key); break;
                case 5: items.remove(idx); saveHome(items); break;
                case 6: appInfo(a); break;
                default: uninstall(a);
            }
            return true;
        });
        pm.show();
    }

    private void folderMenu(int idx, View v) {
        Haptics.thud(this);
        PopupMenu pm = new PopupMenu(this, v);
        pm.getMenu().add(0, 1, 0, R.string.l_move_left);
        pm.getMenu().add(0, 2, 0, R.string.l_move_right);
        pm.getMenu().add(0, 3, 0, R.string.l_add_apps);
        pm.getMenu().add(0, 4, 0, R.string.l_delete_folder);
        pm.setOnMenuItemClickListener(mi -> {
            JSONArray items = homeItems();
            switch (mi.getItemId()) {
                case 1: saveHome(move(items, idx, -1)); break;
                case 2: saveHome(move(items, idx, 1)); break;
                case 3: {
                    JSONObject fo = items.optJSONObject(idx);
                    if (fo != null) pickApps(fo.optJSONArray("a"), sel -> { setFolderApps(idx, sel); build(); });
                    break;
                }
                default: {
                    // padam folder: app di dalamnya dikembalikan ke home
                    JSONObject fo = items.optJSONObject(idx);
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < items.length(); i++) {
                        if (i != idx) { out.put(items.optJSONObject(i)); continue; }
                        JSONArray a = fo == null ? null : fo.optJSONArray("a");
                        for (int j = 0; a != null && j < a.length(); j++) out.put(item(a.optString(j)));
                    }
                    saveHome(out);
                }
            }
            return true;
        });
        pm.show();
    }

    private void dockMenu(int idx, App a, View v) {
        Haptics.thud(this);
        PopupMenu pm = new PopupMenu(this, v);
        pm.getMenu().add(0, 1, 0, R.string.l_remove_dock);
        pm.getMenu().add(0, 2, 0, R.string.l_app_info);
        pm.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                JSONArray d = dockItems();
                d.remove(idx);
                saveDock(d);
            } else appInfo(a);
            return true;
        });
        pm.show();
    }

    private void drawerMenu(App a, View v) {
        Haptics.thud(this);
        PopupMenu pm = new PopupMenu(this, v);
        pm.getMenu().add(0, 1, 0, R.string.l_add_home);
        pm.getMenu().add(0, 2, 0, R.string.l_to_dock);
        pm.getMenu().add(0, 3, 0, R.string.l_to_folder);
        pm.getMenu().add(0, 4, 0, R.string.l_app_info);
        pm.getMenu().add(0, 5, 0, R.string.l_uninstall);
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: {
                    JSONArray items = homeItems();
                    items.put(item(a.key));
                    saveHome(items);
                    android.widget.Toast.makeText(this, getString(R.string.l_added), android.widget.Toast.LENGTH_SHORT).show();
                    break;
                }
                case 2: addDock(a.key); break;
                case 3: toFolder(a.key, false); break;
                case 4: appInfo(a); break;
                default: uninstall(a);
            }
            return true;
        });
        pm.show();
    }

    private static JSONArray move(JSONArray a, int i, int dir) {
        int j = i + dir;
        if (j < 0 || j >= a.length()) return a;
        try {
            Object x = a.get(i);
            a.put(i, a.get(j));
            a.put(j, x);
        } catch (Exception ignored) {
        }
        return a;
    }

    private void addDock(String key) {
        JSONArray d = dockItems();
        for (int i = 0; i < d.length(); i++) if (key.equals(d.optString(i))) return;
        if (d.length() >= 5) {
            android.widget.Toast.makeText(this, getString(R.string.l_dock_full), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        d.put(key);
        saveDock(d);
    }

    /** Masukkan app ke folder sedia ada atau folder baru. */
    private void toFolder(String key, boolean fromHome) {
        JSONArray items = homeItems();
        List<Integer> idx = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && o.has("f")) {
                idx.add(i);
                names.add(o.optString("f"));
            }
        }
        names.add("+ " + getString(R.string.l_new_folder));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.l_to_folder)
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == idx.size()) {
                        newFolder(key);
                        return;
                    }
                    JSONArray all = homeItems();
                    JSONObject fo = all.optJSONObject(idx.get(w));
                    JSONArray a = fo.optJSONArray("a");
                    if (a == null) a = new JSONArray();
                    boolean has = false;
                    for (int i = 0; i < a.length(); i++) if (key.equals(a.optString(i))) has = true;
                    if (!has) a.put(key);
                    try { fo.put("a", a); } catch (Exception ignored) {}
                    if (fromHome) all = removeHomeKey(all, key);
                    saveHome(all);
                })
                .show();
    }

    private void appInfo(App a) {
        try {
            la.startAppDetailsActivity(a.cn, a.user, null, null);
        } catch (Throwable ignored) {
        }
    }

    private void uninstall(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.cn.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
