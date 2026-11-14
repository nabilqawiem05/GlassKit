package com.glasskit.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Simpanan Nota dan Peringatan (tugasan) + penggera peringatan. */
final class Notes {
    private Notes() {}

    static final class Task {
        long id;
        String text;
        boolean done, important;
        long due;          // 0 = tiada masa
        long doneAt;
    }

    static final class Note {
        long id;
        String text;
        boolean pinned;
        long updated;
    }

    // ======================= Tugasan =======================

    static synchronized List<Task> tasks(Context c) {
        List<Task> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c).getString("tasks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Task t = new Task();
                t.id = o.getLong("id");
                t.text = o.optString("text");
                t.done = o.optBoolean("done");
                t.important = o.optBoolean("imp");
                t.due = o.optLong("due");
                t.doneAt = o.optLong("doneAt");
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        // belum siap dulu (penting di atas, kemudian ikut masa), yang siap di bawah
        out.sort((x, y) -> {
            if (x.done != y.done) return x.done ? 1 : -1;
            if (x.important != y.important) return x.important ? -1 : 1;
            long dx = x.due == 0 ? Long.MAX_VALUE : x.due, dy = y.due == 0 ? Long.MAX_VALUE : y.due;
            if (dx != dy) return Long.compare(dx, dy);
            return Long.compare(x.id, y.id);
        });
        return out;
    }

    static synchronized void saveTasks(Context c, List<Task> list) {
        JSONArray a = new JSONArray();
        long cutoff = System.currentTimeMillis() - 7L * 86_400_000L; // buang yang siap > 7 hari
        try {
            for (Task t : list) {
                if (t.done && t.doneAt > 0 && t.doneAt < cutoff) continue;
                a.put(new JSONObject().put("id", t.id).put("text", t.text).put("done", t.done).put("imp", t.important)
                        .put("due", t.due).put("doneAt", t.doneAt));
            }
        } catch (Exception ignored) {
        }
        Prefs.get(c).edit().putString("tasks", a.toString()).apply();
        changed(c);
    }

    static Task addTask(Context c, String text, long due, boolean important) {
        List<Task> l = tasks(c);
        Task t = new Task();
        t.id = System.currentTimeMillis();
        t.text = text;
        t.due = due;
        t.important = important;
        l.add(t);
        saveTasks(c, l);
        schedule(c, t);
        return t;
    }

    static void setDone(Context c, long id, boolean done) {
        List<Task> l = tasks(c);
        for (Task t : l) {
            if (t.id == id) {
                t.done = done;
                t.doneAt = done ? System.currentTimeMillis() : 0;
                if (done) cancel(c, t.id);
                else schedule(c, t);
            }
        }
        saveTasks(c, l);
    }

    static void deleteTask(Context c, long id) {
        List<Task> l = tasks(c);
        l.removeIf(t -> t.id == id);
        cancel(c, id);
        saveTasks(c, l);
    }

    static void updateTask(Context c, Task nt) {
        List<Task> l = tasks(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id == nt.id) l.set(i, nt);
        saveTasks(c, l);
        cancel(c, nt.id);
        if (!nt.done) schedule(c, nt);
    }

    static int[] counts(Context c) {
        int open = 0, done = 0;
        for (Task t : tasks(c)) if (t.done) done++; else open++;
        return new int[]{open, done};
    }

    // ======================= Nota =======================

    static synchronized List<Note> notes(Context c) {
        List<Note> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c).getString("notes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Note n = new Note();
                n.id = o.getLong("id");
                n.text = o.optString("text");
                n.pinned = o.optBoolean("pin");
                n.updated = o.optLong("upd");
                out.add(n);
            }
        } catch (Exception ignored) {
        }
        out.sort((x, y) -> x.pinned != y.pinned ? (x.pinned ? -1 : 1) : Long.compare(y.updated, x.updated));
        return out;
    }

    static synchronized void saveNotes(Context c, List<Note> list) {
        JSONArray a = new JSONArray();
        try {
            for (Note n : list) {
                if (n.text == null || n.text.trim().isEmpty()) continue;
                a.put(new JSONObject().put("id", n.id).put("text", n.text).put("pin", n.pinned).put("upd", n.updated));
            }
        } catch (Exception ignored) {
        }
        Prefs.get(c).edit().putString("notes", a.toString()).apply();
        changed(c);
    }

    static Note note(Context c, long id) {
        for (Note n : notes(c)) if (n.id == id) return n;
        return null;
    }

    static void putNote(Context c, Note n) {
        List<Note> l = notes(c);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id == n.id) {
                l.set(i, n);
                found = true;
            }
        }
        if (!found) l.add(n);
        saveNotes(c, l);
    }

    static void deleteNote(Context c, long id) {
        List<Note> l = notes(c);
        l.removeIf(n -> n.id == id);
        saveNotes(c, l);
    }

    static String firstLine(String s) {
        if (s == null) return "";
        s = s.trim();
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static String rest(String s) {
        if (s == null) return "";
        s = s.trim();
        int i = s.indexOf('\n');
        return i < 0 ? "" : s.substring(i + 1).trim().replace('\n', ' ');
    }

    private static void changed(Context c) {
        TasksWidget.refresh(c);
        NotesWidget.refresh(c);
    }

    // ======================= Penggera peringatan =======================

    private static PendingIntent alarmPi(Context c, long id) {
        Intent i = new Intent(c, Alarm.class).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (int) (id % Integer.MAX_VALUE), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c, Task t) {
        if (t.due <= System.currentTimeMillis() || t.done) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.due, alarmPi(c, t.id));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.due, alarmPi(c, t.id));
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context c, long id) {
        try {
            c.getSystemService(AlarmManager.class).cancel(alarmPi(c, id));
            c.getSystemService(NotificationManager.class).cancel((int) (id % Integer.MAX_VALUE));
        } catch (Exception ignored) {
        }
    }

    static void rescheduleAll(Context c) {
        for (Task t : tasks(c)) schedule(c, t);
    }

    public static class Alarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
                rescheduleAll(c);
                return;
            }
            long id = i.getLongExtra("id", 0);
            if ("done".equals(i.getAction())) {
                setDone(c, id, true);
                c.getSystemService(NotificationManager.class).cancel((int) (id % Integer.MAX_VALUE));
                return;
            }
            Task t = null;
            for (Task x : tasks(c)) if (x.id == id) t = x;
            if (t == null || t.done) return;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("tasks", c.getString(R.string.t_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            Intent done = new Intent(c, Alarm.class).setAction("done").putExtra("id", id);
            Intent open = new Intent(c, NotesActivity.class).putExtra("tab", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification n = new Notification.Builder(c, "tasks")
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(t.text)
                    .setContentText(c.getString(R.string.t_reminder))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 5, open, PendingIntent.FLAG_IMMUTABLE))
                    .addAction(new Notification.Action.Builder(null, c.getString(R.string.t_mark_done),
                            PendingIntent.getBroadcast(c, (int) (id % 100000) + 1, done,
                                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                    .build();
            nm.notify((int) (id % Integer.MAX_VALUE), n);
        }
    }
}
