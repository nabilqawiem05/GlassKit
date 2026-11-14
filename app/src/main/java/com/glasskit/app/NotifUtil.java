package com.glasskit.app;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Pembantu notifikasi: teks penuh (chat), balasan terus, dan gambar dalam mesej. */
final class NotifUtil {
    private NotifUtil() {}

    static CharSequence fullText(Notification n) {
        Parcelable[] msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, msgs.length - 10);
            for (int i = from; i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                CharSequence sender = m.getCharSequence("sender");
                CharSequence text = m.getCharSequence("text");
                if (text == null) continue;
                if (sb.length() > 0) sb.append("\n");
                if (!TextUtils.isEmpty(sender)) sb.append(sender).append(": ");
                sb.append(text);
            }
            if (sb.length() > 0) return sb;
        }
        CharSequence big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (!TextUtils.isEmpty(big)) return big;
        CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) return TextUtils.join("\n", lines);
        return n.extras.getCharSequence(Notification.EXTRA_TEXT);
    }

    static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] ri = a.getRemoteInputs();
            if (ri != null && ri.length > 0 && a.actionIntent != null) return a;
        }
        return null;
    }

    static final class Pic {
        Bitmap bmp;
        Uri uri;
    }

    /** Gambar dalam notifikasi: gambar mesej (WhatsApp/Telegram) atau BigPicture. */
    static List<Pic> pictures(Context c, Notification n, int maxPx) {
        List<Pic> out = new ArrayList<>();
        Parcelable[] msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null) {
            for (int i = Math.max(0, msgs.length - 6); i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                String type = m.getString("type");
                Object u = m.get("uri");
                if (type != null && type.startsWith("image/") && u instanceof Uri) {
                    Bitmap b = load(c, (Uri) u, maxPx);
                    if (b != null) {
                        Pic p = new Pic();
                        p.bmp = b;
                        p.uri = (Uri) u;
                        out.add(p);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            try {
                Object pic = n.extras.get(Notification.EXTRA_PICTURE);
                Bitmap b = null;
                if (pic instanceof Bitmap) b = (Bitmap) pic;
                else if (Build.VERSION.SDK_INT >= 31) {
                    Object ic = n.extras.get(Notification.EXTRA_PICTURE_ICON);
                    if (ic instanceof Icon) {
                        android.graphics.drawable.Drawable d = ((Icon) ic).loadDrawable(c);
                        if (d instanceof android.graphics.drawable.BitmapDrawable) b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                    }
                }
                if (b != null) {
                    Pic p = new Pic();
                    p.bmp = b;
                    out.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Bitmap load(Context c, Uri u, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > maxPx * 2) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            try (InputStream in = c.getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, o2); }
        } catch (Exception e) {
            return null; // app tidak beri akses kepada gambar ini
        }
    }

    /** Hantar balasan melalui RemoteInput. */
    static boolean reply(Context c, Notification.Action a, String text) {
        if (a == null || TextUtils.isEmpty(text.trim())) return false;
        try {
            Intent i = new Intent();
            Bundle res = new Bundle();
            for (RemoteInput r : a.getRemoteInputs()) res.putCharSequence(r.getResultKey(), text);
            RemoteInput.addResultsToIntent(a.getRemoteInputs(), i, res);
            send(c, a.actionIntent, i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void send(Context c, PendingIntent pi, Intent fill) throws Exception {
        ActivityOptions o = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 34) {
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        pi.send(c, 0, fill, null, null, null, o.toBundle());
    }
}
