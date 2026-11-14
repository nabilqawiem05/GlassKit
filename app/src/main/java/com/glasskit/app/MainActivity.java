package com.glasskit.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private static final int REQ_BG = 1, REQ_FG = 2;
    private static final int[] COLORS = {
            Color.WHITE, 0xFF8AB4F8, 0xFFC8B6FF, 0xFFF5D06F, 0xFFFF8A80, 0xFF101418
    };
    private static final String[] FONTS = {"Tebal", "Nipis", "Serif", "Mono"};

    private SharedPreferences prefs;
    private ImageView preview;
    private TextView status, pillStatus;
    private LinearLayout box;
    private Bitmap cacheBg, cacheFg;
    private long cacheVer = Long.MIN_VALUE;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------- UI ----------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(24), dp(20), dp(40));
        sv.addView(box);
        setContentView(sv);

        heading("GlassKit", 28);
        note("Jam depth effect + pil gaya Dynamic Island. Tanpa root.");

        // ===== Jam depth effect =====
        heading("Jam depth effect", 20);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(420));
        pl.topMargin = dp(8);
        box.addView(preview, pl);

        status = note("");
        button("1. Pilih wallpaper", v -> pick(REQ_BG));
        button("2. Potong subjek automatik", v -> autoCut());
        button("2b. Guna PNG subjek sendiri", v -> pick(REQ_FG));
        button("Buang subjek", v -> {
            DepthRenderer.fgFile(this).delete();
            bump();
        });

        label("Saiz jam");
        slider(10, 45, prefs.getInt(Prefs.CLOCK_SIZE, 30), val -> prefs.edit().putInt(Prefs.CLOCK_SIZE, val).apply());
        label("Kedudukan menegak");
        slider(12, 70, prefs.getInt(Prefs.CLOCK_Y, 28), val -> prefs.edit().putInt(Prefs.CLOCK_Y, val).apply());

        label("Warna jam");
        LinearLayout cr = row();
        for (int c : COLORS) {
            View chip = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(dp(1), Color.GRAY);
            chip.setBackground(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(40), dp(40));
            l.rightMargin = dp(10);
            chip.setOnClickListener(v -> prefs.edit().putInt(Prefs.CLOCK_COLOR, c).apply());
            cr.addView(chip, l);
        }

        label("Font jam");
        LinearLayout fr = row();
        for (int i = 0; i < FONTS.length; i++) {
            final int idx = i;
            Button fb = new Button(this);
            fb.setText(FONTS[i]);
            fb.setAllCaps(false);
            fb.setOnClickListener(v -> prefs.edit().putInt(Prefs.CLOCK_FONT, idx).apply());
            fr.addView(fb, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        toggle("Jam hanya di lock screen", Prefs.LOCK_ONLY, true);
        button("3. Set sebagai wallpaper", v -> setWallpaper());
        note("Tip: dalam Wallpaper & style, pilih jam stock yang paling kecil supaya tak bertindih dengan jam GlassKit.");

        // ===== Pil =====
        heading("Pil (Dynamic Island)", 20);
        pillStatus = note("");
        toggle("Hidupkan pil", Prefs.PILL_ON, true);
        toggle("Tunjuk muzik", Prefs.PILL_MUSIC, true);
        toggle("Tunjuk notifikasi", Prefs.PILL_NOTI, true);
        button("Buka tetapan Accessibility", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("Buka Notification access", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button("Uji pil", v -> {
            if (PillService.inst != null) PillService.inst.test();
            else toast("Hidupkan Accessibility GlassKit Pill dulu");
        });

        label("Kalau Android sekat (\"Restricted setting\"), jalan ni dalam aShell:");
        final String cmds = shellCmds();
        TextView code = note(cmds);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        button("Salin arahan aShell", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("aShell", cmds));
            toast("Disalin");
        });

        prefs.registerOnSharedPreferenceChangeListener(prefL);
        renderPreview();
    }

    private String shellCmds() {
        String pkg = getPackageName();
        return "appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow\n"
                + "cmd notification allow_listener " + pkg + "/" + NotifListener.class.getName() + "\n"
                + "s=$(settings get secure enabled_accessibility_services); "
                + "[ \"$s\" = null ] && s=''; "
                + "settings put secure enabled_accessibility_services \"${s:+$s:}" + pkg + "/" + PillService.class.getName() + "\"; "
                + "settings put secure accessibility_enabled 1";
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefL = (sp, k) -> renderPreview();

    @Override
    protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefL);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String acc = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean a = acc != null && acc.contains(getPackageName() + "/");
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean n = nm.isNotificationListenerAccessGranted(new ComponentName(this, NotifListener.class));
        pillStatus.setText("Accessibility: " + (a ? "ON" : "OFF") + "    Notification access: " + (n ? "ON" : "OFF")
                + (n ? "" : "\n(Muzik & notifikasi perlukan Notification access)"));
    }

    private TextView heading(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 700, false));
        t.setPadding(0, dp(18), 0, dp(4));
        box.addView(t);
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setAlpha(0.8f);
        t.setPadding(0, dp(4), 0, dp(4));
        box.addView(t);
        return t;
    }

    private void label(String s) {
        TextView t = note(s);
        t.setAlpha(1f);
        t.setPadding(0, dp(12), 0, dp(2));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void button(String s, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        box.addView(bt, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggle(String s, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(s);
        sw.setTextSize(15);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        box.addView(sw, new LinearLayout.LayoutParams(-1, -2));
    }

    interface IntCb { void on(int v); }

    private void slider(int min, int max, int cur, IntCb cb) {
        SeekBar s = new SeekBar(this);
        s.setMin(min);
        s.setMax(max);
        s.setProgress(cur);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { if (fromUser) cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(s, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- Gambar ----------------

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            Bitmap bmp = decode(uri);
            if (req == REQ_BG) {
                save(bmp, DepthRenderer.bgFile(this), false);
                DepthRenderer.fgFile(this).delete();
                status.setText("Wallpaper dipilih. Sekarang tekan \"Potong subjek automatik\".");
            } else {
                save(bmp, DepthRenderer.fgFile(this), true);
                status.setText("PNG subjek dipasang.");
            }
            bump();
        } catch (Exception e) {
            status.setText("Gagal buka gambar: " + e.getMessage());
        }
    }

    private Bitmap decode(Uri uri) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int max = 2400;
            if (Math.max(w, h) > max) {
                float sc = (float) max / Math.max(w, h);
                decoder.setTargetSize(Math.round(w * sc), Math.round(h * sc));
            }
        });
    }

    private void save(Bitmap bmp, File f, boolean png) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, png ? 100 : 95, out);
        }
    }

    private void bump() {
        prefs.edit().putLong(Prefs.IMG_VER, System.currentTimeMillis()).apply();
    }

    private void autoCut() {
        Bitmap bg = DepthRenderer.load(DepthRenderer.bgFile(this));
        if (bg == null) {
            toast("Pilih wallpaper dulu");
            return;
        }
        status.setText("Memotong subjek... (kali pertama mungkin lambat sebab model dimuat turun)");
        SubjectSegmenterOptions opts = new SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build();
        SubjectSegmenter seg = SubjectSegmentation.getClient(opts);
        Bitmap argb = bg.copy(Bitmap.Config.ARGB_8888, false);
        seg.process(InputImage.fromBitmap(argb, 0))
                .addOnSuccessListener(result -> {
                    Bitmap fg = result.getForegroundBitmap();
                    if (fg == null) {
                        status.setText("Tiada subjek dijumpai. Cuba gambar orang/haiwan/objek yang jelas.");
                        return;
                    }
                    try {
                        save(fg, DepthRenderer.fgFile(this), true);
                        status.setText("Siap! Subjek dipotong. Laraskan jam, kemudian tekan \"Set sebagai wallpaper\".");
                        bump();
                    } catch (Exception e) {
                        status.setText("Gagal simpan: " + e.getMessage());
                    }
                })
                .addOnFailureListener(e -> status.setText(
                        "Gagal potong: " + e.getMessage()
                                + "\nKalau model belum siap, tunggu sekejap & cuba lagi, atau guna PNG subjek sendiri."));
    }

    private void renderPreview() {
        if (preview == null) return;
        int w = 540, h = Math.round(540f * getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().widthPixels);
        long ver = prefs.getLong(Prefs.IMG_VER, 0);
        if (ver != cacheVer) {
            cacheVer = ver;
            cacheBg = DepthRenderer.load(DepthRenderer.bgFile(this));
            cacheFg = DepthRenderer.load(DepthRenderer.fgFile(this));
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        DepthRenderer.draw(this, c, w, h, cacheBg, cacheFg, prefs, true);
        preview.setImageBitmap(out);
        if (TextUtils.isEmpty(status.getText()) && !DepthRenderer.bgFile(this).exists()) {
            status.setText("Mula dengan pilih wallpaper (gambar orang, haiwan atau objek jelas paling cantik).");
        }
    }

    private void setWallpaper() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, DepthWallpaperService.class));
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Tak boleh buka pemilih wallpaper");
        }
    }
}
