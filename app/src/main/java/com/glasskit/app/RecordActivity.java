package com.glasskit.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Aktiviti telus: minta kebenaran rakam skrin, kemudian mulakan ScreenRecService. */
public class RecordActivity extends Activity {
    private static final int REQ = 51, REQ_MIC = 52;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int mode = Prefs.get(this).getInt(Prefs.REC_AUDIO, ScreenRecService.AUDIO_INTERNAL);
        if (mode != ScreenRecService.AUDIO_NONE && !Data.has(this, android.Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.POST_NOTIFICATIONS}, REQ_MIC);
        } else {
            ask();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        ask();
    }

    private void ask() {
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(m.createScreenCaptureIntent(), REQ);
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenRecService.class)
                    .putExtra(ScreenRecService.EXTRA_CODE, res)
                    .putExtra(ScreenRecService.EXTRA_DATA, data);
            startForegroundService(i);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
