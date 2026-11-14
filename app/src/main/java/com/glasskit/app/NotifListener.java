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
        PillService p = PillService.inst;
        if (p != null) p.initMedia();
    }

    @Override
    public void onListenerDisconnected() {
        inst = null;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        PillService p = PillService.inst;
        if (p != null && p.panels != null) p.panels.onNotificationsChanged();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        PillService p = PillService.inst;
        if (p == null || sbn == null) return;
        if (p.panels != null) p.panels.onNotificationsChanged();

        Notification n = sbn.getNotification();
        Bundle ex = n.extras;

        if (ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            p.initMedia();
            p.repickMedia();
            return;
        }

        if (getCurrentInterruptionFilter() > INTERRUPTION_FILTER_ALL) return; // Jangan Ganggu
        if (sbn.isOngoing()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (getPackageName().equals(sbn.getPackageName())) return;
        if ("android".equals(sbn.getPackageName())) return;

        p.onNotification(sbn);
    }
}
