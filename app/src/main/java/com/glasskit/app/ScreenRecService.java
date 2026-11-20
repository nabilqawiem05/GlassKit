package com.glasskit.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Rakaman skrin dengan audio (dalaman / mikrofon / kedua-dua) guna MediaProjection.
 * Video H.264 + audio AAC, dicantum ke MP4 dalam Movies/GlassKit melalui MediaStore.
 */
public class ScreenRecService extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "com.glasskit.app.REC_STOP";
    static final int AUDIO_NONE = 0, AUDIO_INTERNAL = 1, AUDIO_MIC = 2, AUDIO_BOTH = 3;
    static volatile boolean running;
    static long startedAt;

    private static final int RATE = 44100;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaProjection mp;
    private VirtualDisplay vd;
    private MediaCodec venc, aenc;
    private Surface inSurface;
    private AudioRecord arInt, arMic;
    private MediaMuxer mux;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted, wantAudio;
    private final Object muxLock = new Object();
    private volatile boolean stopping;
    private Thread vThread, aThread;
    private long t0;
    private String prevTouches;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) {
            stopRec();
            return START_NOT_STICKY;
        }
        if (running || i == null) return START_NOT_STICKY;
        int mode = Prefs.get(this).getInt(Prefs.REC_AUDIO, AUDIO_INTERNAL);
        boolean mic = (mode == AUDIO_MIC || mode == AUDIO_BOTH) && Data.has(this, android.Manifest.permission.RECORD_AUDIO);
        try {
            startForeground(77, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    | (mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0));
        } catch (Exception e) {
            CrashLog.write(this, e);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            mp = m.getMediaProjection(i.getIntExtra(EXTRA_CODE, 0), i.getParcelableExtra(EXTRA_DATA));
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { h.post(ScreenRecService.this::stopRec); }
            }, h);
            begin(mode);
        } catch (Throwable t) {
            CrashLog.write(this, t);
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show();
            cleanup();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rec", getString(R.string.tg_rec), NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, ScreenRecService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "rec")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(getString(R.string.rec_running))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.rec_stop), stop).build())
                .setContentIntent(stop)
                .build();
    }

    private void begin(int mode) throws Exception {
        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        float sc = Math.min(1f, 1080f / Math.min(dm.widthPixels, dm.heightPixels));
        int w = (Math.round(dm.widthPixels * sc) / 16) * 16, hgt = (Math.round(dm.heightPixels * sc) / 16) * 16;

        // fail dalam Movies/GlassKit
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "GlassKit_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4");
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GlassKit");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        pfd = getContentResolver().openFileDescriptor(uri, "rw");
        mux = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // video
        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, hgt);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inSurface = venc.createInputSurface();
        venc.start();

        // audio
        int bufSz = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
        AudioFormat af = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        boolean micOk = Data.has(this, android.Manifest.permission.RECORD_AUDIO);
        if ((mode == AUDIO_INTERNAL || mode == AUDIO_BOTH) && micOk) {
            AudioPlaybackCaptureConfiguration pc = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            arInt = new AudioRecord.Builder().setAudioFormat(af).setBufferSizeInBytes(bufSz * 2)
                    .setAudioPlaybackCaptureConfig(pc).build();
        }
        if ((mode == AUDIO_MIC || mode == AUDIO_BOTH) && micOk) {
            arMic = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(af).setBufferSizeInBytes(bufSz * 2).build();
        }
        wantAudio = arInt != null || arMic != null;
        if (wantAudio) {
            MediaFormat aformat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            aformat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            aformat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            aformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aenc.configure(aformat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aenc.start();
        }

        // sentuhan jari kelihatan
        if (Shell.granted()) {
            Shell.run("settings get system show_touches", (ok, out) -> {
                prevTouches = out == null || !out.trim().equals("1") ? "0" : "1";
                Shell.run("settings put system show_touches 1");
            });
        }

        t0 = System.nanoTime() / 1000;
        vd = mp.createVirtualDisplay("GlassKitRec", w, hgt, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inSurface, null, null);
        running = true;
        startedAt = System.currentTimeMillis();
        if (PillService.inst != null) PillService.inst.onRecChanged();

        vThread = new Thread(this::videoLoop, "gk-rec-v");
        vThread.start();
        if (wantAudio) {
            aThread = new Thread(this::audioLoop, "gk-rec-a");
            aThread.start();
        }
    }

    private void tryStartMux() {
        synchronized (muxLock) {
            if (muxStarted) return;
            if (vTrack < 0 || (wantAudio && aTrack < 0)) return;
            mux.start();
            muxStarted = true;
            muxLock.notifyAll();
        }
    }

    private void write(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            if (!muxStarted || info.size <= 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
            long pts = info.presentationTimeUs - t0;
            if (pts < 0) return;
            MediaCodec.BufferInfo b = new MediaCodec.BufferInfo();
            b.set(info.offset, info.size, pts, info.flags);
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            try { mux.writeSampleData(track, buf, b); } catch (Exception ignored) {}
        }
    }

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx;
            try {
                idx = venc.dequeueOutputBuffer(info, 10_000);
            } catch (Exception e) {
                break;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxLock) { vTrack = mux.addTrack(venc.getOutputFormat()); }
                tryStartMux();
            } else if (idx >= 0) {
                ByteBuffer out = venc.getOutputBuffer(idx);
                if (out != null && vTrack >= 0) write(vTrack, out, info);
                venc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } else if (stopping && idx == MediaCodec.INFO_TRY_AGAIN_LATER && !running) {
                // tunggu EOS
            }
        }
    }

    private void audioLoop() {
        int n = 2048;
        short[] a = new short[n], b = new short[n];
        byte[] pcm = new byte[n * 2];
        if (arInt != null) arInt.startRecording();
        if (arMic != null) arMic.startRecording();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eosSent = false;
        while (true) {
            if (!eosSent) {
                int got = 0;
                if (arInt != null) got = Math.max(got, arInt.read(a, 0, n));
                else java.util.Arrays.fill(a, (short) 0);
                if (arMic != null) {
                    int g2 = arMic.read(b, 0, n);
                    got = Math.max(got, g2);
                    for (int i = 0; i < n; i++) {
                        int v = (arInt != null ? a[i] : 0) + (i < g2 ? b[i] : 0);
                        a[i] = (short) Math.max(-32768, Math.min(32767, v));
                    }
                }
                if (got > 0) {
                    for (int i = 0; i < got; i++) {
                        pcm[i * 2] = (byte) (a[i] & 0xff);
                        pcm[i * 2 + 1] = (byte) ((a[i] >> 8) & 0xff);
                    }
                    int in = aenc.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer ib = aenc.getInputBuffer(in);
                        ib.clear();
                        int len = Math.min(got * 2, ib.capacity());
                        ib.put(pcm, 0, len);
                        long pts = System.nanoTime() / 1000;
                        aenc.queueInputBuffer(in, 0, len, pts, stopping ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (stopping) eosSent = true;
                    }
                } else if (stopping) {
                    int in = aenc.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        aenc.queueInputBuffer(in, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosSent = true;
                    }
                }
            }
            int idx = aenc.dequeueOutputBuffer(info, eosSent ? 10_000 : 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxLock) { aTrack = mux.addTrack(aenc.getOutputFormat()); }
                tryStartMux();
            } else if (idx >= 0) {
                ByteBuffer out = aenc.getOutputBuffer(idx);
                if (out != null && aTrack >= 0) write(aTrack, out, info);
                aenc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    void stopRec() {
        if (!running || stopping) return;
        stopping = true;
        running = false;
        if (PillService.inst != null) PillService.inst.onRecChanged();
        // hentikan tayangan serta-merta: cip merah stock hilang & audio tidak lagi menyekat benang
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        vd = null;
        try { if (mp != null) mp.stop(); } catch (Exception ignored) {}
        mp = null;
        try { if (arInt != null) arInt.stop(); } catch (Exception ignored) {}
        try { if (arMic != null) arMic.stop(); } catch (Exception ignored) {}
        new Thread(() -> {
            try { venc.signalEndOfInputStream(); } catch (Exception ignored) {}
            try { if (vThread != null) vThread.join(2500); } catch (Exception ignored) {}
            try { if (aThread != null) aThread.join(1500); } catch (Exception ignored) {}
            cleanup();
            if (prevTouches != null) Shell.run("settings put system show_touches " + prevTouches);
            h.post(() -> {
                Toast.makeText(this, R.string.rec_saved, Toast.LENGTH_LONG).show();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }, "gk-rec-stop").start();
    }

    private void cleanup() {
        try { if (arInt != null) arInt.release(); } catch (Exception ignored) {}
        try { if (arMic != null) arMic.release(); } catch (Exception ignored) {}
        try { if (venc != null) { venc.stop(); venc.release(); } } catch (Exception ignored) {}
        try { if (aenc != null) { aenc.stop(); aenc.release(); } } catch (Exception ignored) {}
        synchronized (muxLock) {
            try { if (mux != null && muxStarted) mux.stop(); } catch (Exception ignored) {}
            try { if (mux != null) mux.release(); } catch (Exception ignored) {}
        }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        try {
            if (uri != null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            }
        } catch (Exception ignored) {
        }
        try { if (mp != null) mp.stop(); } catch (Exception ignored) {}
        running = false;
        if (PillService.inst != null) h.post(() -> { if (PillService.inst != null) PillService.inst.onRecChanged(); });
    }
}
