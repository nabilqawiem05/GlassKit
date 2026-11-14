package com.glasskit.app;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Notifikasi promosi (diskaun, baucar, sale): dikumpul senyap dalam tindanan "Promosi". */
final class Promo {
    private Promo() {}

    static final List<StatusBarNotification> items = new ArrayList<>();

    private static final String[] SHOPS = {"com.shopee.my", "com.lazada.android", "com.zzkko", "com.einnovation.temu",
            "com.alibaba.aliexpresshd", "com.zalora.android", "my.com.mcdonalds", "com.mcdonalds.app", "com.kfc.my",
            "com.dominos.my", "com.foodpanda.android", "com.global.foodpanda.android", "com.mr.diy", "com.watsons.my",
            "com.guardian.my", "my.com.pizzahut", "com.starbucks.my", "com.grab.food"};
    private static final Pattern WORDS = Pattern.compile(
            "(diskaun|baucar|voucher|\\bsale\\b|promo|cashback|% ?off|\\d+ ?%|free shipping|penghantaran percuma|flash|\\bdeal|tawaran"
                    + "|rm ?\\d+ ?(je|sahaja|only)|claim|beli ?1|buy ?1|percuma|limited time|jimat|offer|kod|code)",
            Pattern.CASE_INSENSITIVE);

    static boolean isPromo(Context c, StatusBarNotification sbn) {
        if (!Prefs.get(c).getBoolean(Prefs.PROMO_ON, true)) return false;
        Notification n = sbn.getNotification();
        if (sbn.isOngoing() || n.extras == null) return false;
        if (Notification.CATEGORY_MESSAGE.equals(n.category) || Notification.CATEGORY_CALL.equals(n.category)
                || Notification.CATEGORY_EMAIL.equals(n.category) || Notification.CATEGORY_ALARM.equals(n.category)
                || Notification.CATEGORY_REMINDER.equals(n.category) || Notification.CATEGORY_TRANSPORT.equals(n.category)) return false;
        if (n.extras.containsKey(Notification.EXTRA_MESSAGES)) return false; // perbualan
        String pkg = sbn.getPackageName();
        if (pkg.equals("com.whatsapp") || pkg.equals("org.telegram.messenger") || pkg.startsWith("com.google.android.apps.messaging")) return false;
        String all = (n.extras.getCharSequence(Notification.EXTRA_TITLE) + " " + n.extras.getCharSequence(Notification.EXTRA_TEXT))
                .toLowerCase(Locale.ROOT);
        boolean shop = false;
        for (String s : SHOPS) if (s.equals(pkg)) shop = true;
        if (Notification.CATEGORY_PROMO.equals(n.category)) return true;
        if (shop) {
            // kemas kini pesanan / penghantaran bukan promosi
            boolean order = all.contains("pesanan") || all.contains("order") || all.contains("dihantar") || all.contains("delivered")
                    || all.contains("parcel") || all.contains("bungkusan") || all.contains("rider") || all.contains("penghantar");
            return !order;
        }
        // app lain: perlu sekurang-kurangnya dua tanda promosi
        java.util.regex.Matcher m = WORDS.matcher(all);
        int hits = 0;
        while (m.find() && hits < 2) hits++;
        return hits >= 2;
    }

    static synchronized void add(StatusBarNotification sbn) {
        items.removeIf(x -> x.getKey().equals(sbn.getKey()));
        items.add(0, sbn);
        while (items.size() > 40) items.remove(items.size() - 1);
    }

    static synchronized void remove(String key) {
        items.removeIf(x -> x.getKey().equals(key));
    }

    static synchronized List<StatusBarNotification> list() { return new ArrayList<>(items); }

    static synchronized void clear() { items.clear(); }

    static String title(StatusBarNotification sbn) {
        CharSequence t = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        return TextUtils.isEmpty(t) ? "" : t.toString();
    }
}
