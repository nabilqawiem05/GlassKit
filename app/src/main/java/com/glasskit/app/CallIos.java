package com.glasskit.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Person;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/** Skrin panggilan masuk gaya iOS 27: poster penuh, nama besar, butang kaca Jawab/Tolak, bunga api hari jadi. */
final class CallIos {
    private CallIos() {}

    interface Act { void run(int what); } // 0 tolak, 1 jawab, 2 senyap, 3 kecilkan

    static FrameLayout build(PillService s, LiquidGlass.Backdrop bd, CharSequence name, String subtitle,
                             Bitmap photo, boolean birthday, Act act, TextView[] outName) {
        FrameLayout root = new FrameLayout(s);
        root.setBackgroundColor(Color.BLACK);
        int W = s.screenW, H = s.screenH;

        // ---- poster / latar ----
        // Gambar besar (≥ 480px) = poster penuh tajam. Gambar kecil (cth. profil WhatsApp) TIDAK dibesarkan
        // (jadi kabur) — sebaliknya latar warna terang + gambar bulat tajam di tengah, seperti iPhone.
        boolean posterMode = photo != null && Math.min(photo.getWidth(), photo.getHeight()) >= 480;
        ImageView poster = new ImageView(s);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap wall = DepthRenderer.load(DepthRenderer.bgFile(s));
        if (posterMode) {
            poster.setImageBitmap(photo);
            bd.set(photo, W, H);
        } else {
            int base = wall != null ? DepthRenderer.autoColor(wall) : 0xFF7F77DD;
            float[] hsv = new float[3];
            Color.colorToHSV(base, hsv);
            hsv[1] = Math.max(0.55f, Math.min(1f, hsv[1] + 0.35f)); // warna terang, bukan pudar
            hsv[2] = 0.78f;
            int c1 = Color.HSVToColor(hsv);
            hsv[0] = (hsv[0] + 28) % 360;
            hsv[2] = 0.42f;
            int c2 = Color.HSVToColor(hsv);
            hsv[0] = (hsv[0] + 300) % 360;
            hsv[2] = 0.18f;
            int c3 = Color.HSVToColor(hsv);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2, c3});
            poster.setBackground(g);
            if (wall != null) bd.set(wall, W, H);
        }
        root.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        if (!posterMode) {
            // cahaya lembut di belakang gambar bulat + gelang deringan
            FrameLayout ava = new FrameLayout(s);
            ava.setClipChildren(false);
            int d = Ui.dp(s, 168);
            for (int i = 0; i < 3; i++) {
                View ring = new View(s);
                GradientDrawable rg = new GradientDrawable();
                rg.setShape(GradientDrawable.OVAL);
                rg.setStroke(Ui.dp(s, 2), Color.argb(150, 255, 255, 255));
                ring.setBackground(rg);
                ava.addView(ring, new FrameLayout.LayoutParams(d, d, Gravity.CENTER));
                ObjectAnimator ra = ObjectAnimator.ofPropertyValuesHolder(ring,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.9f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.9f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
                ra.setDuration(2400);
                ra.setStartDelay(i * 800L);
                ra.setRepeatCount(ValueAnimator.INFINITE);
                ra.setInterpolator(new android.view.animation.DecelerateInterpolator());
                ra.start();
            }
            View pic;
            if (photo != null) {
                ImageView iv = new ImageView(s);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(photo);
                pic = iv;
            } else {
                TextView mono = Ui.text(s, 64, 600, Color.WHITE);
                mono.setText(initials(name));
                mono.setGravity(Gravity.CENTER);
                pic = mono;
            }
            GradientDrawable pb = new GradientDrawable();
            pb.setShape(GradientDrawable.OVAL);
            pb.setColor(Color.argb(70, 255, 255, 255));
            pb.setStroke(Ui.dp(s, 3), Color.argb(200, 255, 255, 255));
            pic.setBackground(pb);
            circle(s, pic);
            pic.setElevation(Ui.dp(s, 12));
            ava.addView(pic, new FrameLayout.LayoutParams(d, d, Gravity.CENTER));
            FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-1, d * 2, Gravity.CENTER);
            al.topMargin = -Ui.dp(s, 20);
            root.addView(ava, al);
        }
        // zoom perlahan (Ken Burns) — poster sahaja
        if (posterMode) {
            ObjectAnimator kb = ObjectAnimator.ofPropertyValuesHolder(poster,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
            kb.setDuration(9000);
            kb.setRepeatCount(ValueAnimator.INFINITE);
            kb.setRepeatMode(ValueAnimator.REVERSE);
            kb.start();
        }

        View shade = new View(s);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(110, 0, 0, 0)}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        if (birthday) root.addView(new Fireworks(s), new FrameLayout.LayoutParams(-1, -1));

        // ---- nama di atas ----
        LinearLayout top = new LinearLayout(s);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(Ui.dp(s, 24), s.statusH + Ui.dp(s, 44), Ui.dp(s, 24), 0);
        TextView sub = Ui.text(s, 15, 500, Color.argb(215, 255, 255, 255));
        sub.setText(subtitle);
        sub.setGravity(Gravity.CENTER);
        top.addView(sub);
        TextView nm = Ui.text(s, 44, 600, Color.WHITE);
        nm.setText(name);
        nm.setGravity(Gravity.CENTER);
        nm.setMaxLines(2);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setLetterSpacing(-0.02f);
        nm.setShadowLayer(Ui.dp(s, 10), 0, Ui.dp(s, 2), Color.argb(110, 0, 0, 0));
        nm.setPadding(0, Ui.dp(s, 2), 0, 0);
        top.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        outName[0] = nm;
        if (birthday) {
            TextView ban = Ui.text(s, 14, 600, Color.WHITE);
            ban.setText(s.getString(R.string.call_birthday, name));
            ban.setGravity(Gravity.CENTER);
            ban.setPadding(Ui.dp(s, 16), Ui.dp(s, 8), Ui.dp(s, 16), Ui.dp(s, 8));
            ban.setBackground(new LiquidGlass.Surface(s, Ui.dp(s, 20), false, bd, 0));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = Ui.dp(s, 18);
            top.addView(ban, bl);
            ban.setScaleX(0.6f);
            ban.setScaleY(0.6f);
            ban.setAlpha(0f);
            ban.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(500).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
        }
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- butang ----
        LinearLayout bottom = new LinearLayout(s);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        root.setClipChildren(false);
        bottom.setPadding(Ui.dp(s, 30), 0, Ui.dp(s, 30), Ui.dp(s, 60));
        LinearLayout row1 = new LinearLayout(s);
        row1.addView(small(s, bd, Icon.BELL, R.string.call_silence, v -> act.run(2)));
        row1.addView(new View(s), new LinearLayout.LayoutParams(0, 1, 1f));
        row1.addView(small(s, bd, -1, R.string.call_minimize, v -> act.run(3)));
        bottom.addView(row1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row2 = new LinearLayout(s);
        row2.setClipChildren(false);
        LinearLayout dec = big(s, bd, 0xFFFF3B30, true, R.string.call_decline, v -> act.run(0));
        LinearLayout acc = big(s, bd, 0xFF34C759, false, R.string.call_accept, v -> act.run(1));
        row2.addView(dec);
        row2.addView(new View(s), new LinearLayout.LayoutParams(0, 1, 1f));
        row2.addView(acc);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1, -2);
        r2.topMargin = Ui.dp(s, 30);
        bottom.addView(row2, r2);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        breathe(acc.getChildAt(0));

        // ---- masuk ----
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(240).start();
        if (posterMode) {
            poster.setScaleX(1.15f);
            poster.setScaleY(1.15f);
        }
        top.setTranslationY(-Ui.dp(s, 30));
        top.setAlpha(0f);
        top.animate().translationY(0).alpha(1f).setStartDelay(80).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
        row1.setTranslationY(Ui.dp(s, 90));
        row2.setTranslationY(Ui.dp(s, 140));
        row1.animate().translationY(0).setStartDelay(140).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
        row2.animate().translationY(0).setStartDelay(60).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
        return root;
    }

    private static String initials(CharSequence name) {
        if (TextUtils.isEmpty(name)) return "?";
        String[] p = name.toString().trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String w : p) {
            if (w.isEmpty() || !Character.isLetterOrDigit(w.charAt(0))) continue;
            b.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            if (b.length() >= 2) break;
        }
        return b.length() == 0 ? name.subSequence(0, 1).toString() : b.toString();
    }

    private static View circle(Context s, View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        v.setClipToOutline(true);
        return v;
    }

    private static LinearLayout small(PillService s, LiquidGlass.Backdrop bd, int icon, int label, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout b = new FrameLayout(s);
        b.setBackground(new Raised(0x66FFFFFF, Ui.dp(s, 1.2f))); // butang kecil kaca timbul
        b.setElevation(Ui.dp(s, 8));
        if (icon > 0) {
            b.addView(new Icon(s, icon, Color.WHITE), new FrameLayout.LayoutParams(Ui.dp(s, 22), Ui.dp(s, 22), Gravity.CENTER));
        } else {
            // anak panah ke atas (kecilkan ke island)
            View arrow = new View(s) {
                final Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                protected void onDraw(Canvas c) {
                    ap.setStyle(Paint.Style.STROKE);
                    ap.setStrokeCap(Paint.Cap.ROUND);
                    ap.setStrokeJoin(Paint.Join.ROUND);
                    ap.setStrokeWidth(getWidth() * 0.11f);
                    ap.setColor(Color.WHITE);
                    float w = getWidth(), h = getHeight();
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(w * 0.22f, h * 0.64f);
                    p.lineTo(w * 0.5f, h * 0.36f);
                    p.lineTo(w * 0.78f, h * 0.64f);
                    c.drawPath(p, ap);
                }
            };
            b.addView(arrow, new FrameLayout.LayoutParams(Ui.dp(s, 22), Ui.dp(s, 22), Gravity.CENTER));
        }
        circle(s, b);
        b.setOnClickListener(l);
        sink(b, Ui.dp(s, 8));
        col.addView(b, new LinearLayout.LayoutParams(Ui.dp(s, 52), Ui.dp(s, 52)));
        col.setClipChildren(false);
        col.setPadding(Ui.dp(s, 6), Ui.dp(s, 6), Ui.dp(s, 6), 0);
        TextView t = Ui.text(s, 12, 600, Color.WHITE);
        t.setText(label);
        t.setPadding(0, Ui.dp(s, 6), 0, 0);
        col.addView(t);
        return col;
    }

    private static LinearLayout big(PillService s, LiquidGlass.Backdrop bd, int color, boolean hang, int label, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(Ui.dp(s, 8), Ui.dp(s, 8), Ui.dp(s, 8), 0); // ruang untuk denyutan & bayang
        FrameLayout b = new FrameLayout(s);
        b.setBackground(new Raised(color, Ui.dp(s, 1.5f))); // butang timbul 3D
        Icon ic = new Icon(s, Icon.PHONE, Color.WHITE);
        if (hang) ic.setRotation(135);
        b.addView(ic, new FrameLayout.LayoutParams(Ui.dp(s, 34), Ui.dp(s, 34), Gravity.CENTER));
        b.setElevation(Ui.dp(s, 16));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            b.setOutlineSpotShadowColor(color);
            b.setOutlineAmbientShadowColor(color);
        }
        circle(s, b);
        b.setOnClickListener(l);
        sink(b, Ui.dp(s, 16));
        col.addView(b, new LinearLayout.LayoutParams(Ui.dp(s, 78), Ui.dp(s, 78)));
        TextView t = Ui.text(s, 14, 600, Color.WHITE);
        t.setText(label);
        t.setShadowLayer(Ui.dp(s, 4), 0, Ui.dp(s, 1), Color.argb(120, 0, 0, 0));
        t.setPadding(0, Ui.dp(s, 12), 0, 0);
        col.addView(t);
        return col;
    }

    /** Tekan: butang tenggelam (mengecil, bayang hilang); lepas: timbul semula dengan lantunan. */
    private static void sink(View v, float elev) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.9f).scaleY(0.9f).translationZ(-elev * 0.8f).setInterpolator(Ui.EASE).setDuration(110).start();
                    Haptics.tap(view.getContext());
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).translationZ(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                    break;
            }
            return false;
        });
    }

    /**
     * Butang timbul: badan bulat dengan cahaya dari atas-kiri (radial), bibir tepi (atas terang, bawah gelap),
     * kilauan kaca di atas dan bayang dalam di bawah.
     */
    static final class Raised extends android.graphics.drawable.Drawable {
        private final int color;
        private final float rim;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Raised(int color, float rim) {
            this.color = color;
            this.rim = rim;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(w, h) / 2f;
            boolean glass = Color.alpha(color) < 255;
            int light = blend(color | 0xFF000000, Color.WHITE, 0.45f), dark = blend(color | 0xFF000000, Color.BLACK, 0.35f);
            int a = Color.alpha(color);
            // badan: cahaya dari atas-kiri
            p.setShader(new android.graphics.RadialGradient(cx - r * 0.3f, cy - r * 0.45f, r * 1.55f,
                    new int[]{withA(light, a), withA(color | 0xFF000000, a), withA(dark, a)},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            // bayang dalam di bawah (memberi isipadu)
            p.setShader(new android.graphics.LinearGradient(0, cy, 0, cy + r,
                    Color.argb(0, 0, 0, 0), Color.argb(glass ? 40 : 70, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            // kilauan kaca di atas
            p.setShader(new android.graphics.LinearGradient(0, cy - r * 0.92f, 0, cy - r * 0.05f,
                    Color.argb(glass ? 130 : 165, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP));
            c.drawOval(cx - r * 0.62f, cy - r * 0.9f, cx + r * 0.62f, cy - r * 0.02f, p);
            // bibir: tepi atas terang, tepi bawah gelap
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(rim);
            p.setShader(new android.graphics.LinearGradient(0, cy - r, 0, cy + r,
                    Color.argb(200, 255, 255, 255), Color.argb(glass ? 30 : 110, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r - rim / 2f, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setShader(null);
        }

        private static int withA(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Butang jawab "bernafas": membesar sedikit dan kembali, berulang. */
    private static void breathe(View v) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        a.setDuration(800);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(Ui.EASE);
        a.start();
    }

    // ======================= Hari jadi =======================

    /** Benar jika hari ini hari jadi pemanggil (perlukan kebenaran kenalan; jika tiada, false). */
    static boolean birthdayToday(Context c, Person person, CharSequence name) {
        try {
            if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return false;
            long id = -1;
            if (person != null && person.getUri() != null) {
                Uri u = Uri.parse(person.getUri());
                if ("tel".equals(u.getScheme())) {
                    Uri q = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(u.getSchemeSpecificPart()));
                    try (Cursor cu = c.getContentResolver().query(q, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
                        if (cu != null && cu.moveToFirst()) id = cu.getLong(0);
                    }
                } else if ("content".equals(u.getScheme())) {
                    try (Cursor cu = c.getContentResolver().query(u, new String[]{ContactsContract.Contacts._ID}, null, null, null)) {
                        if (cu != null && cu.moveToFirst()) id = cu.getLong(0);
                    }
                }
            }
            String sel;
            String[] args;
            if (id >= 0) {
                sel = ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=? AND "
                        + ContactsContract.CommonDataKinds.Event.TYPE + "=?";
                args = new String[]{String.valueOf(id), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                        String.valueOf(ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)};
            } else if (!TextUtils.isEmpty(name)) {
                sel = ContactsContract.Data.DISPLAY_NAME + "=? AND " + ContactsContract.Data.MIMETYPE + "=? AND "
                        + ContactsContract.CommonDataKinds.Event.TYPE + "=?";
                args = new String[]{name.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                        String.valueOf(ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)};
            } else return false;
            Calendar now = Calendar.getInstance();
            String md = String.format(Locale.ROOT, "%02d-%02d", now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
            try (Cursor cu = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Event.START_DATE}, sel, args, null)) {
                while (cu != null && cu.moveToNext()) {
                    String d = cu.getString(0);
                    if (d != null && d.trim().endsWith(md)) return true; // "YYYY-MM-DD" atau "--MM-DD"
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Bunga api ringan: letupan zarah berwarna di bahagian atas skrin. */
    static final class Fireworks extends View {
        private final ArrayList<float[]> ps = new ArrayList<>(); // x, y, vx, vy, hayat, warna
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private long last, nextBurst;
        private static final int[] COLS = {0xFFFAC775, 0xFFED93B1, 0xFF9FE1CB, 0xFFAFA9EC, 0xFFF0997B, 0xFFFFFFFF};

        Fireworks(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        private void burst() {
            float cx = getWidth() * (0.18f + rnd.nextFloat() * 0.64f), cy = getHeight() * (0.12f + rnd.nextFloat() * 0.38f);
            int col = COLS[rnd.nextInt(COLS.length)];
            int n = 34;
            float sp = getWidth() * (0.35f + rnd.nextFloat() * 0.2f);
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + rnd.nextFloat() * 0.15;
                float v = sp * (0.6f + rnd.nextFloat() * 0.4f);
                ps.add(new float[]{cx, cy, (float) Math.cos(a) * v, (float) Math.sin(a) * v, 1f, col});
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = System.currentTimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            if (now >= nextBurst && getWidth() > 0) {
                burst();
                nextBurst = now + 650 + rnd.nextInt(700);
            }
            float g = getHeight() * 0.18f, r = Math.max(2f, getWidth() * 0.006f);
            for (int i = ps.size() - 1; i >= 0; i--) {
                float[] q = ps.get(i);
                q[2] *= (1f - 1.6f * dt);
                q[3] = q[3] * (1f - 1.6f * dt) + g * dt;
                q[0] += q[2] * dt;
                q[1] += q[3] * dt;
                q[4] -= dt * 0.75f;
                if (q[4] <= 0) { ps.remove(i); continue; }
                p.setColor((int) q[5]);
                p.setAlpha((int) (255 * Math.min(1f, q[4] * 1.4f)));
                c.drawCircle(q[0], q[1], r * (0.6f + q[4] * 0.6f), p);
            }
            if (isAttachedToWindow()) postInvalidateOnAnimation();
        }
    }
}
