package com.glasskit.app;

import android.content.Intent;
import android.widget.Toast;

/**
 * Rakam skrin melalui Shizuku (alat screenrecord sistem, tanpa dialog kebenaran).
 * Sentuhan jari ditunjukkan (show_touches) semasa merakam. Fail: Movies/GlassKit.
 * Nota: screenrecord tidak merakam audio; setiap segmen maksimum 3 minit (disambung automatik).
 */
final class Recorder {
    private static boolean rec;
    private static long start;
    private static String prevTouches = "0";
    private static final String FLAG = "/data/local/tmp/gk_rec";
    private static final String DIR = "/sdcard/Movies/GlassKit";

    private Recorder() {}

    static boolean recording() { return rec || ScreenRecService.running; }

    static long elapsed() {
        if (ScreenRecService.running) return System.currentTimeMillis() - ScreenRecService.startedAt;
        return rec ? System.currentTimeMillis() - start : 0;
    }

    static void toggle(PillService s) {
        if (recording()) stop(s); else start(s);
    }

    static void start(PillService s) {
        if (Prefs.get(s).getInt(Prefs.REC_AUDIO, ScreenRecService.AUDIO_INTERNAL) != ScreenRecService.AUDIO_NONE) {
            Intent i = new Intent(s, RecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            s.startActivity(i);
            return;
        }
        if (!Shell.granted()) {
            Toast.makeText(s, R.string.need_shizuku, Toast.LENGTH_SHORT).show();
            return;
        }
        Shell.run("settings get system show_touches", (ok, out) -> {
            String v = out == null ? "0" : out.trim();
            prevTouches = v.equals("1") ? "1" : "0";
            Shell.run("settings put system show_touches 1; mkdir -p " + DIR + "; touch " + FLAG, (ok2, o2) -> {
                Shell.spawn("while [ -f " + FLAG + " ]; do screenrecord --bit-rate 16000000 "
                        + DIR + "/GlassKit_$(date +%Y%m%d_%H%M%S).mp4; done");
                rec = true;
                start = System.currentTimeMillis();
                s.onRecChanged();
            });
        });
    }

    static void stop(PillService s) {
        if (ScreenRecService.running) {
            s.startService(new Intent(s, ScreenRecService.class).setAction(ScreenRecService.ACTION_STOP));
            return;
        }
        if (!rec) return;
        rec = false;
        s.onRecChanged();
        Shell.run("rm -f " + FLAG + "; pkill -INT screenrecord; sleep 1; settings put system show_touches " + prevTouches
                + "; for f in $(ls -t " + DIR + " | head -3); do "
                + "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + DIR + "/$f >/dev/null 2>&1; done",
                (ok, out) -> Toast.makeText(s, R.string.rec_saved, Toast.LENGTH_LONG).show());
    }
}
