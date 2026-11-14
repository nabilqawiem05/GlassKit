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

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        PillService p = PillService.inst;
        if (p != null && p.panels != null) p.panels.onNotificationsChanged();
        if (p != null) p.onNotifsChanged();
        if (p != null && p.call != null && sbn != null) p.call.onRemoved(sbn.getKey());
        if (p != null && sbn != null) p.onLiveRemoved(sbn.getKey());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        PillService p = PillService.inst;
        if (p == null || sbn == null) return;
        if (p.panels != null) p.panels.onNotificationsChanged();
        p.onNotifsChanged();

        Notification n = sbn.getNotification();
        Bundle ex = n.extras;

        if (CallOverlay.isCall(n)) {
            if (p.call != null) p.call.onCallNotification(sbn);
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
