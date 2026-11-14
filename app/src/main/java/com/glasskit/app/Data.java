package com.glasskit.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Sumber data widget: kalendar, cuaca (Open-Meteo), langkah, gambar. */
final class Data {
    private Data() {}

    static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ===================== Kalendar =====================

    static final class Event {
        String title, location;
        long begin, end;
        boolean allDay;
        int color;
    }

    static List<Event> events(Context c, int days, int max) {
        List<Event> out = new ArrayList<>();
        if (!has(c, Manifest.permission.READ_CALENDAR)) return out;
        long now = System.currentTimeMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now - 24L * 3600_000);
        ContentUris.appendId(b, now + days * 24L * 3600_000);
        String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION};
        try (Cursor cur = c.getContentResolver().query(b.build(), proj,
                CalendarContract.Instances.VISIBLE + "=1 AND " + CalendarContract.Instances.END + ">=?",
                new String[]{String.valueOf(now)}, CalendarContract.Instances.BEGIN + " ASC")) {
            while (cur != null && cur.moveToNext() && out.size() < max) {
                Event e = new Event();
                e.title = cur.getString(0);
                e.begin = cur.getLong(1);
                e.end = cur.getLong(2);
                e.allDay = cur.getInt(3) != 0;
                e.color = cur.getInt(4) | 0xFF000000;
                e.location = cur.getString(5);
                if (e.title == null || e.title.isEmpty()) e.title = "—";
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Hari dalam bulan semasa yang ada acara. */
    static java.util.Set<Integer> eventDaysThisMonth(Context c) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (!has(c, Manifest.permission.READ_CALENDAR)) return out;
        java.util.Calendar a = java.util.Calendar.getInstance();
        a.set(java.util.Calendar.DAY_OF_MONTH, 1);
        a.set(java.util.Calendar.HOUR_OF_DAY, 0);
        a.set(java.util.Calendar.MINUTE, 0);
        long from = a.getTimeInMillis();
        a.add(java.util.Calendar.MONTH, 1);
        long to = a.getTimeInMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        try (Cursor cur = c.getContentResolver().query(b.build(), new String[]{CalendarContract.Instances.START_DAY,
                        CalendarContract.Instances.END_DAY}, CalendarContract.Instances.VISIBLE + "=1", null, null)) {
            java.util.Calendar z = java.util.Calendar.getInstance();
            int month = z.get(java.util.Calendar.MONTH);
            while (cur != null && cur.moveToNext()) {
                for (int jd = cur.getInt(0); jd <= cur.getInt(1); jd++) {
                    android.text.format.Time t = new android.text.format.Time();
                    t.setJulianDay(jd);
                    if (t.month == month) out.add(t.monthDay);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Penggera seterusnya (format jam), atau null. */
    static String nextAlarm(Context c) {
        try {
            android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
            android.app.AlarmManager.AlarmClockInfo i = am.getNextAlarmClock();
            if (i == null) return null;
            long t = i.getTriggerTime();
            if (t - System.currentTimeMillis() > 7 * 86_400_000L) return null;
            boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
            String day = new SimpleDateFormat("EEE", Locale.getDefault()).format(new Date(t));
            String tm = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(t));
            return t - System.currentTimeMillis() < 86_400_000L ? tm : day + " " + tm;
        } catch (Exception e) {
            return null;
        }
    }

    static final class Device {
        String name;
        int level;
        boolean headset;
    }

    /** Bateri peranti Bluetooth yang bersambung (jika sistem melaporkannya). */
    static List<Device> btBatteries(Context c) {
        List<Device> out = new ArrayList<>();
        if (!has(c, "android.permission.BLUETOOTH_CONNECT")) return out;
        try {
            android.bluetooth.BluetoothManager bm = c.getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) return out;
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                try {
                    boolean conn = (boolean) d.getClass().getMethod("isConnected").invoke(d);
                    if (!conn) continue;
                    int lvl = (int) d.getClass().getMethod("getBatteryLevel").invoke(d);
                    if (lvl < 0 || lvl > 100) continue;
                    Device x = new Device();
                    x.name = d.getAlias() != null ? d.getAlias() : d.getName();
                    x.level = lvl;
                    android.bluetooth.BluetoothClass bc = d.getBluetoothClass();
                    x.headset = bc != null && bc.getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO;
                    out.add(x);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String eventTime(Context c, Event e) {
        if (e.allDay) return c.getString(R.string.all_day);
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        SimpleDateFormat f = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault());
        return f.format(new Date(e.begin)) + " – " + f.format(new Date(e.end));
    }

    static String dayLabel(Context c, long t) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(t);
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)) {
            int d = b.get(java.util.Calendar.DAY_OF_YEAR) - a.get(java.util.Calendar.DAY_OF_YEAR);
            if (d <= 0) return c.getString(R.string.today);
            if (d == 1) return c.getString(R.string.tomorrow);
        }
        return new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(new Date(t));
    }

    // ===================== Cuaca =====================

    static final class Weather {
        String city;
        float temp, hi, lo;
        int code;
        boolean day = true;
        int[] dCode = new int[0];
        float[] dHi = new float[0], dLo = new float[0];
        String[] dName = new String[0];
        int[] dRain = new int[0];
        long time;
        // butiran tambahan (mungkin tiada jika data lama)
        int humidity = -1, uv = -1, rainNow = -1;
        float wind = -1;
        String sunrise, sunset;
        int[] hCode = new int[0], hRain = new int[0];
        float[] hTemp = new float[0];
        String[] hName = new String[0];
        boolean[] hDay = new boolean[0];
    }

    static Weather weather(Context c) {
        SharedPreferences p = Prefs.get(c);
        String js = p.getString("wxJson", null);
        if (js == null) return null;
        try {
            JSONObject o = new JSONObject(js);
            Weather w = new Weather();
            w.city = p.getString("wxCity", "");
            w.time = p.getLong("wxTime", 0);
            JSONObject cur = o.getJSONObject("current");
            w.temp = (float) cur.getDouble("temperature_2m");
            w.code = cur.getInt("weather_code");
            w.day = cur.optInt("is_day", 1) == 1;
            JSONObject d = o.getJSONObject("daily");
            JSONArray codes = d.getJSONArray("weather_code"), hi = d.getJSONArray("temperature_2m_max"),
                    lo = d.getJSONArray("temperature_2m_min"), time = d.getJSONArray("time");
            int n = codes.length();
            w.dCode = new int[n];
            w.dHi = new float[n];
            w.dLo = new float[n];
            w.dName = new String[n];
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat outF = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < n; i++) {
                w.dCode[i] = codes.getInt(i);
                w.dHi[i] = (float) hi.getDouble(i);
                w.dLo[i] = (float) lo.getDouble(i);
                try { w.dName[i] = outF.format(in.parse(time.getString(i))); } catch (Exception e) { w.dName[i] = ""; }
            }
            // tambahan: kelembapan, angin, UV, matahari, ramalan setiap jam
            w.humidity = cur.optInt("relative_humidity_2m", -1);
            w.wind = (float) cur.optDouble("wind_speed_10m", -1);
            JSONArray uvA = d.optJSONArray("uv_index_max"), pA = d.optJSONArray("precipitation_probability_max");
            JSONArray srA = d.optJSONArray("sunrise"), ssA = d.optJSONArray("sunset");
            if (uvA != null && uvA.length() > 0) w.uv = (int) Math.round(uvA.optDouble(0, -1));
            w.dRain = new int[n];
            for (int i = 0; i < n; i++) w.dRain[i] = pA != null ? pA.optInt(i, -1) : -1;
            boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            SimpleDateFormat hm = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault());
            try {
                if (srA != null) w.sunrise = hm.format(iso.parse(srA.getString(0)));
                if (ssA != null) w.sunset = hm.format(iso.parse(ssA.getString(0)));
            } catch (Exception ignored) {
            }
            JSONObject hr = o.optJSONObject("hourly");
            if (hr != null) {
                JSONArray ht = hr.getJSONArray("time"), hc = hr.getJSONArray("weather_code"),
                        htp = hr.getJSONArray("temperature_2m");
                JSONArray hp = hr.optJSONArray("precipitation_probability"), hd = hr.optJSONArray("is_day");
                String nowKey = iso.format(new Date()).substring(0, 13);
                int start = 0;
                for (int i = 0; i < ht.length(); i++) {
                    if (ht.getString(i).substring(0, 13).compareTo(nowKey) >= 0) { start = i; break; }
                }
                int m = Math.max(0, Math.min(12, ht.length() - start));
                w.hCode = new int[m];
                w.hRain = new int[m];
                w.hTemp = new float[m];
                w.hName = new String[m];
                w.hDay = new boolean[m];
                SimpleDateFormat hf = new SimpleDateFormat(is24 ? "HH" : "h a", Locale.getDefault());
                for (int i = 0; i < m; i++) {
                    int k = start + i;
                    w.hCode[i] = hc.getInt(k);
                    w.hTemp[i] = (float) htp.getDouble(k);
                    w.hRain[i] = hp != null ? hp.optInt(k, -1) : -1;
                    w.hDay[i] = hd == null || hd.optInt(k, 1) == 1;
                    try { w.hName[i] = i == 0 ? c.getString(R.string.now) : hf.format(iso.parse(ht.getString(k))); }
                    catch (Exception e) { w.hName[i] = ""; }
                }
                if (m > 0) w.rainNow = w.hRain[0];
            }
            if (n > 0) {
                w.hi = w.dHi[0];
                w.lo = w.dLo[0];
            }
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    static String condition(Context c, int code) {
        int r;
        if (code == 0) r = R.string.wx_clear;
        else if (code <= 2) r = R.string.wx_partly;
        else if (code == 3) r = R.string.wx_cloudy;
        else if (code == 45 || code == 48) r = R.string.wx_fog;
        else if (code >= 51 && code <= 57) r = R.string.wx_drizzle;
        else if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) r = R.string.wx_rain;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) r = R.string.wx_snow;
        else if (code >= 95) r = R.string.wx_storm;
        else r = R.string.wx_cloudy;
        return c.getString(r);
    }

    /** Ambil cuaca baru (panggil di thread latar). */
    static boolean fetchWeather(Context c) {
        if (!has(c, Manifest.permission.ACCESS_COARSE_LOCATION)) return false;
        try {
            LocationManager lm = c.getSystemService(LocationManager.class);
            Location loc = null;
            for (String prov : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (loc == null || l.getTime() > loc.getTime())) loc = l;
                } catch (Exception ignored) {
                }
            }
            if (loc == null) {
                final Location[] got = new Location[1];
                final Object lock = new Object();
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, c.getMainExecutor(), l -> {
                    synchronized (lock) {
                        got[0] = l;
                        lock.notifyAll();
                    }
                });
                synchronized (lock) {
                    if (got[0] == null) lock.wait(15000);
                }
                loc = got[0];
            }
            if (loc == null) return false;
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                            + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=14"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,"
                            + "precipitation_probability_max&forecast_days=6&timezone=auto",
                    loc.getLatitude(), loc.getLongitude());
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(12000);
            con.setReadTimeout(12000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            new JSONObject(sb.toString()); // sahkan
            String city = "";
            try {
                List<Address> a = new Geocoder(c, Locale.getDefault()).getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                if (a != null && !a.isEmpty()) {
                    city = a.get(0).getLocality();
                    if (city == null) city = a.get(0).getSubAdminArea();
                    if (city == null) city = a.get(0).getAdminArea();
                }
            } catch (Exception ignored) {
            }
            Prefs.get(c).edit().putString("wxJson", sb.toString()).putString("wxCity", city == null ? "" : city)
                    .putLong("wxTime", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== Langkah =====================

    static int steps(Context c) {
        SharedPreferences p = Prefs.get(c);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return today.equals(p.getString("stepDay", "")) ? p.getInt("stepsToday", 0) : 0;
    }

    /** Dipanggil oleh sensor pembilang langkah (jumlah sejak but). */
    static void onStepCounter(Context c, float total) {
        SharedPreferences p = Prefs.get(c);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        float last = p.getFloat("stepLast", -1);
        int steps = today.equals(p.getString("stepDay", "")) ? p.getInt("stepsToday", 0) : 0;
        if (last >= 0) {
            float d = total >= last ? total - last : total; // total < last bermaksud telefon dibut semula
            if (d < 50000) steps += Math.round(d);
        }
        p.edit().putFloat("stepLast", total).putString("stepDay", today).putInt("stepsToday", steps).apply();
    }

    // ===================== Gambar =====================

    static File photoDir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File widgetPhotoDir(Context c, int id) {
        File d = new File(photoDir(c), "w" + id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Gambar untuk widget tertentu (jika dipilih), jika tiada guna set umum. */
    static Bitmap currentPhoto(Context c, int id) {
        File[] own = widgetPhotoDir(c, id).listFiles();
        File[] fs = own != null && own.length > 0 ? own : photoDir(c).listFiles(File::isFile);
        if (fs == null || fs.length == 0) return null;
        Arrays.sort(fs);
        long hour = System.currentTimeMillis() / 3600_000L;
        File f = fs[(int) (hour % fs.length)];
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }
}
