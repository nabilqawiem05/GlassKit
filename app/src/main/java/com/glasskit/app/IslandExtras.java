package com.glasskit.app;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tambahan Dynamic Island: earbud / jam bersambung (dengan bateri), mod bunyi, petunjuk privasi
 * kamera & mikrofon, dan pratonton screenshot.
 */
final class IslandExtras {
    private final PillService s;

    IslandExtras(PillService s) { this.s = s; }

    private int dp(float v) { return Ui.dp(s, v); }

    void start() {
        startPrivacy();
        startScreenshots();
        try {
            lastRinger = s.getSystemService(AudioManager.class).getRingerMode();
        } catch (Exception ignored) {
        }
    }

    void stop() {
        try {
            if (camCb != null) s.getSystemService(CameraManager.class).unregisterAvailabilityCallback(camCb);
            if (micCb != null) s.getSystemService(AudioManager.class).unregisterAudioRecordingCallback(micCb);
            if (shotObs != null) s.getContentResolver().unregisterContentObserver(shotObs);
        } catch (Exception ignored) {
        }
        if (thumb != null) thumb.dismiss(false);
    }

    // ======================= Bluetooth =======================

    private String pendingAddr;
    private CharSequence pendingName;
    private boolean pendingWatch;

    void onBluetooth(Intent i) {
        if (!s.prefs.getBoolean(Prefs.ISL_BT, true)) return;
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (d == null) return;
        String a = i.getAction();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
            BluetoothClass bc = null;
            try { bc = d.getBluetoothClass(); } catch (SecurityException ignored) {}
            int major = bc == null ? 0 : bc.getMajorDeviceClass();
            if (major != BluetoothClass.Device.Major.AUDIO_VIDEO && major != BluetoothClass.Device.Major.WEARABLE
                    && major != BluetoothClass.Device.Major.UNCATEGORIZED) return; // abaikan kereta, PC, dll.
            pendingAddr = d.getAddress();
            String n = null;
            try { n = d.getAlias() != null ? d.getAlias() : d.getName(); } catch (SecurityException ignored) {}
            pendingName = n == null ? s.getString(R.string.isl_device) : n;
            pendingWatch = major == BluetoothClass.Device.Major.WEARABLE;
            int lvl = level(d);
            if (lvl >= 0) showDevice(lvl);
            else {
                // bateri biasanya dilaporkan beberapa saat selepas sambungan
                s.h.removeCallbacks(showNoBattery);
                s.h.postDelayed(showNoBattery, 3500);
            }
        } else if ("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(a)) {
            if (d.getAddress().equals(pendingAddr)) {
                int lvl = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
                if (lvl >= 0) {
                    s.h.removeCallbacks(showNoBattery);
                    showDevice(lvl);
                }
            }
        }
    }

    private final Runnable showNoBattery = () -> showDevice(-1);

    private int level(BluetoothDevice d) {
        try {
            return (int) d.getClass().getMethod("getBatteryLevel").invoke(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    private void showDevice(int lvl) {
        if (pendingAddr == null) return;
        int sz = dp(64);
        Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        float th = sz * 0.09f;
        p.setStrokeWidth(th);
        p.setStrokeCap(Paint.Cap.ROUND);
        RectF o = new RectF(th, th, sz - th, sz - th);
        p.setColor(Color.argb(70, 255, 255, 255));
        c.drawOval(o, p);
        if (lvl >= 0) {
            p.setColor(lvl <= 20 ? 0xFFFF453A : 0xFF30D158);
            c.drawArc(o, -90, 360 * lvl / 100f, false, p);
        }
        Icon ic = new Icon(s, Icon.BT, Color.WHITE);
        int is = Math.round(sz * 0.46f);
        ic.layout(0, 0, is, is);
        c.save();
        c.translate((sz - is) / 2f, (sz - is) / 2f);
        ic.draw(c);
        c.restore();
        Drawable d = new BitmapDrawable(s.getResources(), b);
        String body = s.getString(R.string.isl_connected) + (lvl >= 0 ? " · " + lvl + "%" : "");
        s.showIsland(d, d, pendingName, body, null);
        pendingAddr = null;
    }

    // ======================= Mod bunyi =======================

    private int lastRinger = -1;

    void onRinger(Intent i) {
        if (!s.prefs.getBoolean(Prefs.ISL_RINGER, true)) return;
        int m = i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
        if (m < 0 || m == lastRinger) return;
        boolean first = lastRinger < 0;
        lastRinger = m;
        if (first) return; // siaran melekit semasa daftar
        int col, txt;
        switch (m) {
            case AudioManager.RINGER_MODE_SILENT: col = 0xFFFF453A; txt = R.string.isl_silent; break;
            case AudioManager.RINGER_MODE_VIBRATE: col = 0xFFFF9F0A; txt = R.string.isl_vibrate; break;
            default: col = 0xFF30D158; txt = R.string.isl_ring;
        }
        Drawable ic = Ui.iconDrawable(s, Icon.BELL, Color.WHITE, col, dp(42));
        s.showIsland(ic, ic, s.getString(txt), "", null);
        s.shakeIslandIcon(m != AudioManager.RINGER_MODE_SILENT);
    }

    // ======================= Privasi =======================

    private final Set<String> camBusy = new HashSet<>();
    private boolean micBusy;
    private CameraManager.AvailabilityCallback camCb;
    private AudioManager.AudioRecordingCallback micCb;
    String fgPkg;

    private void startPrivacy() {
        try {
            camCb = new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraUnavailable(String id) {
                    camBusy.add(id);
                    s.onPrivacy();
                }

                @Override
                public void onCameraAvailable(String id) {
                    camBusy.remove(id);
                    s.onPrivacy();
                }
            };
            s.getSystemService(CameraManager.class).registerAvailabilityCallback(camCb, s.h);
        } catch (Exception ignored) {
        }
        try {
            micCb = new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    micBusy = configs != null && !configs.isEmpty();
                    s.onPrivacy();
                }
            };
            AudioManager am = s.getSystemService(AudioManager.class);
            am.registerAudioRecordingCallback(micCb, s.h);
            List<AudioRecordingConfiguration> now = am.getActiveRecordingConfigurations();
            micBusy = now != null && !now.isEmpty();
        } catch (Exception ignored) {
        }
    }

    /** 0 tiada, 1 kamera (hijau), 2 mikrofon (oren). */
    int privacy() {
        if (!s.prefs.getBoolean(Prefs.ISL_PRIVACY, true)) return 0;
        if (!camBusy.isEmpty()) return 1;
        if (micBusy && !ScreenRecService.running) return 2;
        return 0;
    }

    void showPrivacyInfo() {
        int k = privacy();
        if (k == 0) return;
        String who = s.getString(R.string.isl_unknown_app);
        if (fgPkg != null) {
            try {
                who = s.getPackageManager().getApplicationLabel(s.getPackageManager().getApplicationInfo(fgPkg, 0)).toString();
            } catch (Exception ignored) {
            }
        }
        Drawable ic = Ui.iconDrawable(s, k == 1 ? Icon.CAMERA : Icon.NOTE, Color.WHITE, k == 1 ? 0xFF30D158 : 0xFFFF9F0A, dp(42));
        s.showIsland(ic, ic, s.getString(k == 1 ? R.string.isl_cam_use : R.string.isl_mic_use), who, null);
    }

    // ======================= Screenshot =======================

    private ContentObserver shotObs;
    private long lastShotId;
    ScreenshotThumb thumb;

    private void startScreenshots() {
        if (!Data.has(s, "android.permission.READ_MEDIA_IMAGES")) return;
        lastShotId = latestId();
        shotObs = new ContentObserver(s.h) {
            @Override
            public void onChange(boolean self, Uri uri) {
                s.h.removeCallbacks(checkShot);
                s.h.postDelayed(checkShot, 700);
            }
        };
        try {
            s.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, shotObs);
        } catch (Exception ignored) {
        }
    }

    void restartScreenshotsIfNeeded() {
        if (shotObs == null) startScreenshots();
    }

    private long latestId() {
        try (Cursor c = s.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private final Runnable checkShot = new Runnable() {
        @Override
        public void run() {
            if (!s.prefs.getBoolean(Prefs.ISL_SHOT, true)) return;
            long since = System.currentTimeMillis() / 1000 - 20;
            try (Cursor c = s.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH},
                    MediaStore.Images.Media._ID + ">? AND " + MediaStore.Images.Media.DATE_ADDED + ">? AND "
                            + MediaStore.Images.Media.IS_PENDING + "=0",
                    new String[]{String.valueOf(lastShotId), String.valueOf(since)},
                    MediaStore.Images.Media._ID + " DESC")) {
                if (c == null || !c.moveToFirst()) return;
                long id = c.getLong(0);
                String path = c.getString(1);
                lastShotId = Math.max(lastShotId, id);
                if (path == null || !path.toLowerCase(java.util.Locale.ROOT).contains("screenshot")) return;
                Uri u = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Bitmap b = s.getContentResolver().loadThumbnail(u, new android.util.Size(720, 1440), null);
                if (thumb != null) thumb.dismiss(false);
                thumb = new ScreenshotThumb(s, u, b);
                thumb.show();
            } catch (Exception e) {
                CrashLog.write(s, e);
            }
        }
    };
}
