package com.glasskit.app;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Status bar tersuai: jam, ikon notifikasi, signal, Wi-Fi, bateri pil. 3 gaya. */
class StatusBar extends View {
    static final int CAPSULE = 0, MINIMAL = 1, ISLAND = 2;

    private final SharedPreferences prefs;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pct = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int style;
    private boolean showPct, dark;
    private float centerY = -1, islandCx = -1, gapHalf;
    private String time = "";
    private int battery = -1, wifi = -1, cell = -1;
    private boolean charging, dnd, airplane;
    private final List<Drawable> notifs = new ArrayList<>();
    private int moreNotifs;

    StatusBar(Context c) {
        super(c);
        prefs = Prefs.get(c);
        text.setColor(Color.WHITE);
        text.setTypeface(Ui.font(650));
        text.setTextSize(dp(14.5f));
        text.setLetterSpacing(0.01f);
        pct.setTypeface(Ui.font(800));
        pct.setTextAlign(Paint.Align.CENTER);
        pct.setTextSize(dp(8.6f));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        capEdge.setStyle(Paint.Style.STROKE);
        capEdge.setStrokeWidth(Math.max(1f, dp(0.8f)));
        capEdge.setColor(Color.argb(55, 255, 255, 255));
        refresh();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** Pusat menegak (selaras dengan kamera) dan ruang kosong untuk island. */
    void setGeometry(float centerY, float islandCx, float gapHalf) {
        this.centerY = centerY;
        this.islandCx = islandCx;
        this.gapHalf = gapHalf;
        invalidate();
    }

    void refresh() {
        Context c = getContext();
        style = prefs.getInt(Prefs.SBAR_STYLE, CAPSULE);
        showPct = prefs.getBoolean(Prefs.SBAR_PCT, true);
        dark = Ui.darkGlass(c);
        setLayerType(style == MINIMAL ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_HARDWARE, null);
        text.setShadowLayer(style == MINIMAL ? dp(3) : 0, 0, dp(0.6f), Color.argb(130, 0, 0, 0));

        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
        battery = Ui.battery(c);
        charging = Ui.charging(c);
        airplane = Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        try {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            dnd = nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) {
            dnd = false;
        }

        wifi = -1;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    int rssi = nc.getSignalStrength();
                    WifiManager wm = c.getSystemService(WifiManager.class);
                    int lvl = wm.calculateSignalLevel(rssi);
                    int max = Math.max(1, wm.getMaxSignalLevel());
                    wifi = Math.max(1, Math.round(3f * lvl / max));
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        cell = -1;
        try {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            if (tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
                SignalStrength ss = tm.getSignalStrength();
                cell = ss != null ? ss.getLevel() : 0;
            }
        } catch (Exception ignored) {
        }

        notifs.clear();
        moreNotifs = 0;
        NotifListener l = NotifListener.inst;
        if (l != null) {
            try {
                StatusBarNotification[] arr = l.getActiveNotifications();
                Set<String> seen = new HashSet<>();
                if (arr != null) {
                    java.util.Arrays.sort(arr, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
                    for (StatusBarNotification sbn : arr) {
                        Notification n = sbn.getNotification();
                        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && arr.length > 1) continue;
                        if (sbn.getPackageName().equals(c.getPackageName())) continue;
                        if (!seen.add(sbn.getPackageName())) continue;
                        if (notifs.size() >= 4) { moreNotifs++; continue; }
                        if (n.getSmallIcon() == null) continue;
                        Drawable d = n.getSmallIcon().loadDrawable(c);
                        if (d == null) continue;
                        d = d.mutate();
                        d.setTint(Color.WHITE);
                        notifs.add(d);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        invalidate();
    }

    // ===================== Lukisan =====================

    private float leftWidth() {
        float w = text.measureText(time);
        if (!notifs.isEmpty()) w += dp(8) + notifs.size() * dp(16) + (notifs.size() - 1) * dp(5);
        if (moreNotifs > 0) w += dp(12);
        return w;
    }

    private String speed;
    private final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Kelajuan internet (null = sorok). */
    void setSpeed(String s) {
        if (s == null ? speed == null : s.equals(speed)) return;
        speed = s;
        requestLayout();
        invalidate();
    }

    private float speedWidth() {
        if (speed == null) return 0;
        sp.setTextSize(dp(10.5f));
        sp.setTypeface(Ui.font(700));
        return sp.measureText(speed) + dp(7);
    }

    private float rightWidth() {
        float w = batteryWidth() + speedWidth();
        if (wifi >= 0) w += dp(17) + dp(6);
        if (cell >= 0 || airplane) w += dp(16) + dp(6);
        if (dnd) w += dp(13) + dp(6);
        return w;
    }

    private float batteryWidth() {
        return dp(27) + (charging ? dp(9) : 0);
    }

    private boolean solid;

    /** Kapsul legap: menutup cip rakaman / privasi status bar stock supaya tidak bertindih. */
    void setSolid(boolean v) {
        if (solid == v) return;
        solid = v;
        invalidate();
    }

    private void capsule(Canvas c, float l, float r, float cy) {
        float hh = dp(13.5f);
        RectF rf = new RectF(l, cy - hh, r, cy + hh);
        cap.setShader(null);
        cap.setColor(solid ? Color.rgb(20, 20, 24) : dark ? Color.argb(150, 16, 16, 20) : Color.argb(92, 22, 22, 28));
        c.drawRoundRect(rf, hh, hh, cap);
        cap.setShader(new LinearGradient(0, rf.top, 0, rf.bottom,
                new int[]{Color.argb(dark ? 34 : 60, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                new float[]{0f, 0.6f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(rf, hh, hh, cap);
        c.drawRoundRect(rf, hh, hh, capEdge);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        float cy = centerY > 0 ? centerY : getHeight() / 2f;
        float pad = dp(14), cpad = dp(11);
        float lw = leftWidth(), rw = rightWidth();
        float cx = islandCx > 0 ? islandCx : w / 2f;

        float lx, rx; // permulaan kandungan kiri & kanan
        if (style == ISLAND && cx - gapHalf - dp(8) - lw - 2 * cpad > pad
                && cx + gapHalf + dp(8) + rw + 2 * cpad < w - pad) {
            lx = cx - gapHalf - dp(8) - cpad - lw;
            rx = cx + gapHalf + dp(8) + cpad;
        } else {
            lx = pad + (style == MINIMAL ? dp(6) : cpad);
            rx = w - pad - (style == MINIMAL ? dp(6) : cpad) - rw;
        }

        if (style != MINIMAL) {
            capsule(c, lx - cpad, lx + lw + cpad, cy);
            capsule(c, rx - cpad, rx + rw + cpad, cy);
        }

        // ---- kiri: jam + ikon notifikasi ----
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2f;
        c.drawText(time, lx, base, text);
        float x = lx + text.measureText(time) + dp(8);
        int is = Math.round(dp(16));
        for (Drawable d : notifs) {
            d.setBounds(Math.round(x), Math.round(cy - is / 2f), Math.round(x) + is, Math.round(cy + is / 2f));
            d.draw(c);
            x += is + dp(5);
        }
        if (moreNotifs > 0) {
            fill.setColor(Color.WHITE);
            c.drawCircle(x + dp(3), cy, dp(2.4f), fill);
        }

        // ---- kanan: kelajuan, DND, signal, Wi-Fi, bateri ----
        x = rx;
        if (speed != null) {
            sp.setColor(Color.WHITE);
            Paint.FontMetrics sf = sp.getFontMetrics();
            c.drawText(speed, x, cy - (sf.ascent + sf.descent) / 2f, sp);
            x += speedWidth();
        }
        if (dnd) {
            drawMoon(c, x, cy);
            x += dp(13) + dp(6);
        }
        if (cell >= 0 || airplane) {
            if (airplane) drawPlane(c, x, cy); else drawCell(c, x, cy, cell);
            x += dp(16) + dp(6);
        }
        if (wifi >= 0) {
            drawWifi(c, x, cy, wifi);
            x += dp(17) + dp(6);
        }
        drawBattery(c, x, cy);
    }

    private void drawCell(Canvas c, float x, float cy, int level) {
        float bw = dp(3), gap = dp(1.4f), bottom = cy + dp(5.5f);
        for (int i = 0; i < 4; i++) {
            float bh = dp(4) + i * dp(2.4f);
            fill.setColor(i < Math.max(level, 0) ? Color.WHITE : Color.argb(80, 255, 255, 255));
            float l = x + i * (bw + gap);
            c.drawRoundRect(l, bottom - bh, l + bw, bottom, dp(1), dp(1), fill);
        }
    }

    private void drawWifi(Canvas c, float x, float cy, int level) {
        float cxw = x + dp(8.5f), base = cy + dp(5.5f);
        stroke.setStrokeWidth(dp(2.3f));
        fill.setColor(level >= 1 ? Color.WHITE : Color.argb(80, 255, 255, 255));
        c.drawCircle(cxw, base - dp(0.8f), dp(1.8f), fill);
        float[] r = {dp(6f), dp(10.5f)};
        for (int i = 0; i < 2; i++) {
            stroke.setColor(level >= i + 2 ? Color.WHITE : Color.argb(80, 255, 255, 255));
            c.drawArc(new RectF(cxw - r[i], base - r[i], cxw + r[i], base + r[i]), 225, 90, false, stroke);
        }
    }

    private void drawBattery(Canvas c, float x, float cy) {
        float bw = dp(25), bh = dp(12.5f), r = dp(4);
        RectF body = new RectF(x, cy - bh / 2, x + bw, cy + bh / 2);
        int lvl = Math.max(0, battery);
        int col = charging ? 0xFF30D158 : (lvl <= 20 ? 0xFFFF453A : Color.WHITE);

        fill.setColor(Color.argb(85, 255, 255, 255));
        c.drawRoundRect(body, r, r, fill);
        c.save();
        path.reset();
        path.addRoundRect(body, r, r, Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(col);
        c.drawRect(body.left, body.top, body.left + bw * lvl / 100f, body.bottom, fill);
        c.restore();
        fill.setColor(Color.argb(110, 255, 255, 255));
        c.drawRoundRect(body.right + dp(1.2f), cy - dp(2.3f), body.right + dp(2.6f), cy + dp(2.3f), dp(1), dp(1), fill);

        if (showPct && battery >= 0) {
            pct.setColor(lvl > 45 || charging ? Color.argb(235, 0, 0, 0) : Color.WHITE);
            Paint.FontMetrics fm = pct.getFontMetrics();
            c.drawText(String.valueOf(battery), body.centerX(), cy - (fm.ascent + fm.descent) / 2f, pct);
        }
        if (charging) {
            float bx = body.right + dp(5), s = dp(9) / 24f;
            path.reset();
            path.moveTo(bx + 13.5f * s, cy - 9.5f * s * 1.3f);
            path.lineTo(bx + 5 * s, cy + 1.5f * s * 1.3f);
            path.lineTo(bx + 11 * s, cy + 1.5f * s * 1.3f);
            path.lineTo(bx + 10.5f * s, cy + 9.5f * s * 1.3f);
            path.lineTo(bx + 19 * s, cy - 1.5f * s * 1.3f);
            path.lineTo(bx + 13 * s, cy - 1.5f * s * 1.3f);
            path.close();
            fill.setColor(Color.WHITE);
            c.drawPath(path, fill);
        }
    }

    private void drawMoon(Canvas c, float x, float cy) {
        Path a = new Path();
        a.addCircle(x + dp(6), cy, dp(5.6f), Path.Direction.CW);
        Path b = new Path();
        b.addCircle(x + dp(9.5f), cy - dp(3), dp(4.8f), Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        fill.setColor(Color.WHITE);
        c.drawPath(a, fill);
    }

    private void drawPlane(Canvas c, float x, float cy) {
        c.save();
        float s = dp(15) / 24f;
        c.translate(x, cy - dp(7.5f));
        c.scale(s, s);
        path.reset();
        path.moveTo(12, 2.5f); path.lineTo(13.4f, 9); path.lineTo(21.5f, 13.5f); path.lineTo(21.5f, 15);
        path.lineTo(13.4f, 13); path.lineTo(13, 18.5f); path.lineTo(15.5f, 20.5f); path.lineTo(15.5f, 21.5f);
        path.lineTo(12, 20.5f); path.lineTo(8.5f, 21.5f); path.lineTo(8.5f, 20.5f); path.lineTo(11, 18.5f);
        path.lineTo(10.6f, 13); path.lineTo(2.5f, 15); path.lineTo(2.5f, 13.5f); path.lineTo(10.6f, 9); path.close();
        fill.setColor(Color.WHITE);
        c.drawPath(path, fill);
        c.restore();
    }
}
