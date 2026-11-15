package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;

/**
 * Wallpaper 3D: gambar dibengkokkan ikut peta kedalaman bila telefon disengetkan (AGSL di GPU).
 * Jam berada di satah tengah; subjek di hadapan jam.
 */
final class Depth3DView extends View implements SensorEventListener {
    private static final String AGSL =
            "uniform shader photo;\n"
                    + "uniform shader depth;\n"
                    + "uniform shader clock;\n"
                    + "uniform shader subj;\n"
                    + "uniform float2 tilt;\n"
                    + "uniform float focus;\n"
                    + "half4 main(float2 xy) {\n"
                    + "  float2 p = xy;\n"
                    + "  for (int i = 0; i < 5; i++) {\n"
                    + "    float d = depth.eval(p).r;\n"
                    + "    p = xy - tilt * (d - focus);\n"
                    + "  }\n"
                    + "  half4 base = photo.eval(p);\n"
                    + "  half4 ck = clock.eval(xy);\n"
                    + "  half sa = subj.eval(p).a;\n"
                    + "  half3 c = ck.rgb + base.rgb * (1.0 - ck.a);\n"
                    + "  c = mix(c, base.rgb, sa);\n"
                    + "  return half4(c, 1.0);\n"
                    + "}\n";

    private RuntimeShader shader;
    private final Paint paint = new Paint();
    private boolean ready, running;
    private float maxPx;
    private float tx, ty, gx, gy; // semasa & sasaran (-1..1)
    private float baseRoll = Float.NaN, basePitch = Float.NaN;
    private final float[] rot = new float[9], ori = new float[3];

    Depth3DView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    /** Sediakan lapisan. clockTop = kedudukan Y bitmap jam pada skrin. */
    void setLayers(Bitmap bg, Bitmap fg, Bitmap depth, Bitmap clock, float clockTop, int w, int h, float strength) {
        try {
            shader = new RuntimeShader(AGSL);
            RectF r = DepthRenderer.centerCrop(bg, w, h);
            Matrix m = new Matrix();
            m.setScale(r.width() / bg.getWidth(), r.height() / bg.getHeight());
            m.postTranslate(r.left, r.top);
            BitmapShader ps = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            ps.setLocalMatrix(m);
            ps.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("photo", ps);

            Matrix dm = new Matrix();
            dm.setScale(r.width() / depth.getWidth(), r.height() / depth.getHeight());
            dm.postTranslate(r.left, r.top);
            BitmapShader ds = new BitmapShader(depth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            ds.setLocalMatrix(dm);
            ds.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("depth", ds);

            BitmapShader cs = new BitmapShader(clock, Shader.TileMode.DECAL, Shader.TileMode.DECAL);
            Matrix cm = new Matrix();
            cm.setTranslate(0, clockTop);
            cs.setLocalMatrix(cm);
            shader.setInputShader("clock", cs);

            if (fg != null) {
                RectF fr = DepthRenderer.centerCrop(fg, w, h);
                Matrix fm = new Matrix();
                fm.setScale(fr.width() / fg.getWidth(), fr.height() / fg.getHeight());
                fm.postTranslate(fr.left, fr.top);
                BitmapShader fs = new BitmapShader(fg, Shader.TileMode.DECAL, Shader.TileMode.DECAL);
                fs.setLocalMatrix(fm);
                fs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                shader.setInputShader("subj", fs);
            } else {
                Bitmap empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
                shader.setInputShader("subj", new BitmapShader(empty, Shader.TileMode.DECAL, Shader.TileMode.DECAL));
            }
            // satah jam: kedalaman purata di bawah jam supaya jam kelihatan "di tengah"
            shader.setFloatUniform("focus", 0.45f);
            maxPx = getResources().getDisplayMetrics().density * 22f * strength;
            paint.setShader(shader);
            ready = true;
            invalidate();
        } catch (Throwable t) {
            ready = false;
            CrashLog.write(getContext(), t);
        }
    }

    boolean ready() { return ready; }

    void clear() {
        ready = false;
        shader = null;
        paint.setShader(null);
        invalidate();
    }

    void start() {
        if (running) return;
        SensorManager sm = getContext().getSystemService(SensorManager.class);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) return;
        running = true;
        baseRoll = basePitch = Float.NaN;
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (!running) return;
        running = false;
        SensorManager sm = getContext().getSystemService(SensorManager.class);
        if (sm != null) sm.unregisterListener(this);
        gx = gy = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        float pitch = ori[1], roll = ori[2];
        if (Float.isNaN(baseRoll)) {
            baseRoll = roll;
            basePitch = pitch;
        }
        // kedudukan "rehat" mengikut perlahan-lahan cara telefon dipegang
        baseRoll += (roll - baseRoll) * 0.015f;
        basePitch += (pitch - basePitch) * 0.015f;
        float range = 0.32f; // ~18 darjah untuk kesan penuh
        gx = clamp((roll - baseRoll) / range);
        gy = clamp((pitch - basePitch) / range);
        if (Math.abs(gx - tx) > 0.002f || Math.abs(gy - ty) > 0.002f) postInvalidateOnAnimation();
    }

    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onDraw(Canvas c) {
        if (!ready || shader == null) return;
        // pelembutan supaya gerakan lancar, bukan goyang
        tx += (gx - tx) * 0.18f;
        ty += (gy - ty) * 0.18f;
        shader.setFloatUniform("tilt", -tx * maxPx, -ty * maxPx * 0.7f);
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (running && (Math.abs(gx - tx) > 0.002f || Math.abs(gy - ty) > 0.002f)) postInvalidateOnAnimation();
    }
}
