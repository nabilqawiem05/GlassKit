package com.glasskit.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Paparan sisi katil ketika mengecas: jam besar malap, tarikh, penggera, bateri & cuaca.
 * Skrin kekal hidup hanya selagi mengecas; kedudukan beralih sedikit setiap minit (elak kesan melekat OLED).
 * Tekan di mana-mana untuk keluar.
 */
public class NightstandActivity extends Activity {
    static boolean visible;

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private TextView time, date, info;
    private final Random rnd = new Random();

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) finish();
            else tick();
        }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        date = Ui.text(this, 18, 600, Color.WHITE);
        time = Ui.text(this, 96, 200, Color.WHITE);
        time.setIncludeFontPadding(false);
        info = Ui.text(this, 16, 500, Color.WHITE);
        info.setGravity(Gravity.CENTER);
        box.addView(date);
        box.addView(time);
        box.addView(info);
        root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.setOnClickListener(v -> finish());
        setContentView(root);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        tick();
    }

    private void tick() {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        time.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date()));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()));
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean night = hr >= 21 || hr < 6;
        // waktu malam: merah malap (tidak silau), siang: putih lembut
        int col = night ? Color.rgb(200, 60, 50) : Color.argb(230, 255, 255, 255);
        time.setTextColor(col);
        date.setTextColor(night ? Color.rgb(150, 45, 38) : Color.argb(170, 255, 255, 255));
        info.setTextColor(night ? Color.rgb(150, 45, 38) : Color.argb(170, 255, 255, 255));
        StringBuilder sb = new StringBuilder();
        int lvl = Ui.battery(this);
        sb.append("⚡ ").append(lvl).append("%");
        String alarm = Data.nextAlarm(this);
        if (alarm != null) sb.append("   ·   ⏰ ").append(alarm);
        Data.Weather wx = Data.weather(this);
        if (wx != null) sb.append("   ·   ").append(Math.round(wx.temp)).append("°");
        info.setText(sb);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = night ? 0.02f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        // anjakan kecil setiap minit
        box.setTranslationX((rnd.nextFloat() - 0.5f) * dp(24));
        box.setTranslationY((rnd.nextFloat() - 0.5f) * dp(40));
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        try {
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
            getWindow().getInsetsController().setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } catch (Exception ignored) {
        }
        if (!Ui.charging(this)) finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(rx); } catch (Exception ignored) {}
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
