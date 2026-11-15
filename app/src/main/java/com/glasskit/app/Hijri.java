package com.glasskit.app;

import android.content.Context;
import android.icu.util.IslamicCalendar;

import java.util.Calendar;

/** Tarikh Hijri (Umm al-Qura) dengan pelarasan ±1 hari supaya sepadan dengan takwim JAKIM. */
final class Hijri {
    private Hijri() {}

    private static final String[] MONTHS = {"Muharam", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal", "Jamadilakhir",
            "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijah"};

    static String today(Context c) {
        try {
            Calendar g = Calendar.getInstance();
            g.add(Calendar.DAY_OF_MONTH, Prefs.get(c).getInt(Prefs.HIJRI_ADJ, 0));
            IslamicCalendar ic = new IslamicCalendar();
            ic.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            ic.setTimeInMillis(g.getTimeInMillis());
            return ic.get(IslamicCalendar.DAY_OF_MONTH) + " " + MONTHS[ic.get(IslamicCalendar.MONTH)] + " "
                    + ic.get(IslamicCalendar.YEAR) + "H";
        } catch (Throwable t) {
            return "";
        }
    }
}
