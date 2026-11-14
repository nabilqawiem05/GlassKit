package com.glasskit.app;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Meter FPS kecil di penjuru skrin. Baris 1: kelancaran benang UI GlassKit.
 * Baris 2-4: peratus bingkai "jank" (lambat) dalam 2 saat terakhir untuk app di depan,
 * SystemUI dan GlassKit sendiri, dari dumpsys gfxinfo (melalui Shizuku).
 */
final class FpsMeter implements Choreographer.FrameCallback {
    private final PillService s;
    private TextView tv;
    private boolean on;
    private long last, winStart;
    private int frames, slow;
    private float period = 1e9f / 120f;
    private String ui = "", gfx = "";
    private final Map<String, long[]> prev = new HashMap<>();
    private static final Pattern TOTAL = Pattern.compile("Total frames rendered: (\\d+)");
    private static final Pattern JANK = Pattern.compile("Janky frames: (\\d+)");

    FpsMeter(PillService s) { this.s = s; }

    boolean running() { return on; }

    void start() {
        if (on) return;
        on = true;
        if (tv == null) {
            tv = Ui.text(s, 11, 600, Color.WHITE);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setBackground(Ui.round(Color.argb(170, 0, 0, 0), Ui.dp(s, 10)));
            tv.setPadding(Ui.dp(s, 8), Ui.dp(s, 5), Ui.dp(s, 8), Ui.dp(s, 5));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Ui.dp(s, 8);
            lp.y = s.statusH + Ui.dp(s, 8);
            lp.setTitle("GlassKitFps");
            try {
                s.wm.addView(tv, lp);
            } catch (Exception e) {
                tv = null;
                on = false;
                return;
            }
        }
        try {
            android.view.Display d = s.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
            if (d != null && d.getRefreshRate() > 1) period = 1e9f / d.getRefreshRate();
        } catch (Exception ignored) {
        }
        last = 0;
        winStart = 0;
        frames = slow = 0;
        Choreographer.getInstance().postFrameCallback(this);
        s.h.post(poll);
    }

    void stop() {
        if (!on) return;
        on = false;
        Choreographer.getInstance().removeFrameCallback(this);
        s.h.removeCallbacks(poll);
        if (tv != null) {
            try { s.wm.removeViewImmediate(tv); } catch (Exception ignored) {}
            tv = null;
        }
        prev.clear();
    }

    @Override
    public void doFrame(long ns) {
        if (!on) return;
        if (last != 0) {
            frames++;
            if (ns - last > period * 1.5f) slow++;
        } else {
            winStart = ns;
        }
        last = ns;
        if (ns - winStart >= 1_000_000_000L) {
            float fps = frames * 1e9f / (ns - winStart);
            ui = String.format(Locale.US, "UI %3.0f fps · %d lambat", fps, slow);
            frames = slow = 0;
            winStart = ns;
            paint();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void paint() {
        if (tv != null) tv.setText(gfx.isEmpty() ? ui : ui + "\n" + gfx);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!on) return;
            s.h.postDelayed(this, 2000);
            if (!Shell.granted()) return;
            final String fg = s.extras != null && s.extras.fgPkg != null ? s.extras.fgPkg : "";
            final String[] pk = fg.isEmpty() ? new String[]{"com.android.systemui", s.getPackageName()}
                    : new String[]{fg, "com.android.systemui", s.getPackageName()};
            StringBuilder cmd = new StringBuilder();
            for (String p : pk) cmd.append("echo '@@").append(p).append("'; dumpsys gfxinfo ").append(p)
                    .append(" | grep -E 'Total frames rendered|Janky frames' | head -2; ");
            Shell.run(cmd.toString(), (ok, out) -> {
                if (!on || out == null) return;
                StringBuilder sb = new StringBuilder();
                for (String part : out.split("@@")) {
                    int nl = part.indexOf('\n');
                    if (nl < 0) continue;
                    String pkg = part.substring(0, nl).replace("'", "").trim();
                    Matcher t = TOTAL.matcher(part), j = JANK.matcher(part);
                    if (!t.find() || !j.find()) continue;
                    long tot = Long.parseLong(t.group(1)), jk = Long.parseLong(j.group(1));
                    long[] pv = prev.put(pkg, new long[]{tot, jk});
                    String name = pkg.equals("com.android.systemui") ? "SystemUI"
                            : pkg.equals(s.getPackageName()) ? "GlassKit" : AppTools.label(s, pkg);
                    if (name.length() > 12) name = name.substring(0, 12);
                    if (pv == null || tot < pv[0]) {
                        sb.append(String.format(Locale.US, "%-12s  …", name)).append('\n');
                        continue;
                    }
                    long dt = tot - pv[0], dj = Math.max(0, jk - pv[1]);
                    if (dt == 0) sb.append(String.format(Locale.US, "%-12s  diam", name));
                    else sb.append(String.format(Locale.US, "%-12s %3d bgkn %2d%% lambat", name, dt, Math.round(dj * 100f / dt)));
                    sb.append('\n');
                }
                gfx = sb.toString().trim();
                paint();
            });
        }
    };
}
