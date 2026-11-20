package com.glasskit.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotifListener extends NotificationListenerService {

    static NotifListener inst;

    @Override
    public void onListenerConnected() {
        inst = this;
        BankGuard.onStart(this); // proses bermula semula semasa mod bank: sambung pantau
        PillService p = PillService.inst;
        if (p != null) {
            p.initMedia();
            try {
                StatusBarNotification[] all = getActiveNotifications();
                if (all != null) for (StatusBarNotification sbn : all) p.onLive(sbn);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        inst = null;
    }

    /** Perakam skrin stock / kongsi skrin aktif? (notifikasi berterusan dari System UI) */
    private void checkStockRec() {
        boolean on = false;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) {
                if (!"com.android.systemui".equals(s.getPackageName())) continue;
                String ch = s.getNotification().getChannelId();
                ch = ch == null ? "" : ch.toLowerCase(java.util.Locale.ROOT);
                if (s.isOngoing() && (ch.contains("screen_record") || ch.contains("screenrecord") || ch.contains("media_projection")
                        || ch.contains("screen_share") || ch.contains("cast"))) { on = true; break; }
            }
        } catch (Throwable ignored) {
        }
        if (on != Recorder.stockRec) {
            Recorder.stockRec = on;
            PillService p = PillService.inst;
            if (p != null) p.h.post(p::onRecChanged);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        checkStockRec();
        PillService p = PillService.inst;
        if (p != null && p.panels != null) p.panels.onNotificationsChanged();
        if (p != null) p.onNotifsChanged();
        if (p != null && p.call != null && sbn != null) p.call.onRemoved(sbn.getKey());
        if (p != null && sbn != null) p.onLiveRemoved(sbn.getKey());
        if (p != null && sbn != null) p.onAlarmRemoved(sbn.getKey());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        checkStockRec();
        PillService p = PillService.inst;
        if (p == null || sbn == null) return;
        if (p.panels != null) p.panels.onNotificationsChanged();
        p.onNotifsChanged();

        Notification n = sbn.getNotification();
        Bundle ex = n.extras;

        // penggera / pemasa berbunyi: beri laluan kepada skrin penggera (jangan tutup dengan lock screen)
        if (PillService.isAlarm(sbn)) {
            p.onAlarm(sbn);
            return;
        }

        if (CallOverlay.isCall(n)) {
            if (p.call != null) p.call.onCallNotification(sbn);
            return;
        }
        // promosi: kumpul senyap, keluarkan dari bar status (kembali selepas 24 jam jika tidak dibuka)
        if (Promo.isPromo(p, sbn)) {
            Promo.add(sbn);
            try { snoozeNotification(sbn.getKey(), 24 * 3600_000L); } catch (Exception ignored) {}
            if (p.panels != null) p.panels.onNotificationsChanged();
            return;
        }

        if (ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            p.initMedia();
            p.repickMedia();
            return;
        }

        if (p.onLive(sbn)) return; // aktiviti langsung (Maps, Grab, muat turun, pemasa)
        if (getCurrentInterruptionFilter() > INTERRUPTION_FILTER_ALL) return; // Jangan Ganggu
        if (sbn.isOngoing()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (getPackageName().equals(sbn.getPackageName())) return;
        if ("android".equals(sbn.getPackageName())) return;

        p.onNotification(sbn);
    }
}
