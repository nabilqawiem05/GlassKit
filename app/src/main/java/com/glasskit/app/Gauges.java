package com.glasskit.app;

import android.app.AlarmManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tolok bulat kaca gaya widget lock screen iOS: bateri, penggera, tarikh, notifikasi. */
final class Gauges {
    static final int BATTERY = 0, ALARM = 1, DATE = 2, NOTIFS = 3;

    private Gauges() {}

    static void draw(Context c, Canvas cv, int type, float cx, float cy, float r, boolean dark) {
        Path disc = new Path();
        disc.addCircle(cx, cy, r, Path.Direction.CW);
        GlassArt.draw(cv, disc, r * 1.1f, GlassArt.tint(c), dark, null, null);

        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(Color.WHITE);
        t.setShadowLayer(r * 0.08f, 0, r * 0.03f, Color.argb(110, 0, 0, 0));

        switch (type) {
            case BATTERY: {
                int lvl = Math.max(0, Ui.battery(c));
                boolean ch = Ui.charging(c);
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeCap(Paint.Cap.ROUND);
                ring.setStrokeWidth(r * 0.13f);
                ring.setColor(Color.argb(70, 255, 255, 255));
                float m = r * 0.78f;
                RectF rf = new RectF(cx - m, cy - m, cx + m, cy + m);
                cv.drawArc(rf, 135, 270, false, ring);
                ring.setColor(ch ? 0xFF30D158 : (lvl <= 15 ? 0xFFFF453A : Color.WHITE));
                cv.drawArc(rf, 135, 270f * lvl / 100f, false, ring);
                t.setTypeface(Ui.font(800));
                t.setTextSize(r * 0.5f);
                cv.drawText(String.valueOf(lvl), cx, cy + r * 0.17f, t);
                icon(c, cv, ch ? Icon.BOLT : Icon.BATTERY, cx, cy + r * 0.62f, r * 0.34f);
                break;
            }
            case ALARM: {
                AlarmManager am = c.getSystemService(AlarmManager.class);
                AlarmManager.AlarmClockInfo next = am != null ? am.getNextAlarmClock() : null;
                icon(c, cv, Icon.BELL, cx, cy - r * 0.38f, r * 0.42f);
                t.setTypeface(Ui.font(800));
                if (next != null) {
                    boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
                    String s = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date(next.getTriggerTime()));
                    t.setTextSize(r * (s.length() > 4 ? 0.36f : 0.42f));
                    cv.drawText(s, cx, cy + r * 0.38f, t);
                } else {
                    t.setTextSize(r * 0.42f);
                    cv.drawText("—", cx, cy + r * 0.38f, t);
                }
                break;
            }
            case DATE: {
                Date now = new Date();
                t.setTypeface(Ui.font(700));
                t.setTextSize(r * 0.3f);
                t.setColor(Ui.accent(c, false));
                cv.drawText(new SimpleDateFormat("EEE", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()),
                        cx, cy - r * 0.18f, t);
                t.setColor(Color.WHITE);
                t.setTypeface(Ui.font(800));
                t.setTextSize(r * 0.62f);
                cv.drawText(new SimpleDateFormat("d", Locale.getDefault()).format(now), cx, cy + r * 0.5f, t);
                break;
            }
            case NOTIFS: {
                int n = 0;
                NotifListener l = NotifListener.inst;
                if (l != null) {
                    try {
                        for (android.service.notification.StatusBarNotification s : l.getActiveNotifications()) {
                            if (!s.isOngoing()) n++;
                        }
                    } catch (Exception ignored) {
                    }
                }
                icon(c, cv, Icon.BELL, cx, cy - r * 0.36f, r * 0.4f);
                t.setTypeface(Ui.font(800));
                t.setTextSize(r * 0.5f);
                cv.drawText(String.valueOf(n), cx, cy + r * 0.45f, t);
                break;
            }
        }
    }

    private static void icon(Context c, Canvas cv, int kind, float cx, float cy, float size) {
        Icon ic = new Icon(c, kind, Color.WHITE);
        int s = Math.max(1, Math.round(size));
        ic.layout(0, 0, s, s);
        cv.save();
        cv.translate(cx - s / 2f, cy - s / 2f);
        ic.draw(cv);
        cv.restore();
    }
}
