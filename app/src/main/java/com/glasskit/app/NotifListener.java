package com.glasskit.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotifListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        PillService p = PillService.inst;
        if (p != null) p.initMedia();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        PillService p = PillService.inst;
        if (p == null || sbn == null) return;

        Notification n = sbn.getNotification();
        Bundle ex = n.extras;

        // Notifikasi muzik: cuma beritahu supaya pil cari sesi media baru
        if (ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            p.initMedia();
            p.repickMedia();
            return;
        }

        if (getCurrentInterruptionFilter() > INTERRUPTION_FILTER_ALL) return; // DND
        if (sbn.isOngoing()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (getPackageName().equals(sbn.getPackageName())) return;
        if ("android".equals(sbn.getPackageName())) return;

        p.onNotification(sbn);
    }
}
