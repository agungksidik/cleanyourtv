package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The five daily prayer times for one place, worked out on the TV (the praytimes.org method, no network): Fajr
 * and Isha at the sun's depression angles, Dhuhr at solar noon, Asr when a shadow equals its object's length
 * plus the noon shadow, Maghrib at sunset. Indonesia's Kemenag uses 20 and 18 degrees. Within a minute of Aladhan's method 20.
 */
final class Prayer {
    static final double FAJR_ANGLE = 20;
    static final double ISHA_ANGLE = 18;

    private final double lat;
    private final double lng;
    private final double jd;

    private Prayer(double lat, double lng, LocalDate day) {
        this.lat = lat;
        this.lng = lng;
        jd = julian(day.getYear(), day.getMonthValue(), day.getDayOfMonth()) - lng / (15 * 24);
    }

    /** {Fajr, Dhuhr, Asr, Maghrib, Isha} for the day, at the given UTC offset in hours. */
    static LocalTime[] times(double lat, double lng, LocalDate day, double utcOffsetHours) {
        Prayer p = new Prayer(lat, lng, day);
        double[] t = {
                p.angleTime(FAJR_ANGLE, 5 / 24.0, true),
                p.midDay(12 / 24.0),
                p.asr(13 / 24.0),
                p.angleTime(0.833, 18 / 24.0, false),
                p.angleTime(ISHA_ANGLE, 18 / 24.0, false),
        };
        LocalTime[] result = new LocalTime[t.length];
        for (int i = 0; i < t.length; i++) {
            double hours = fixHour(t[i] + utcOffsetHours - lng / 15);
            int minutes = (int) Math.round(hours * 60) % (24 * 60);
            result[i] = LocalTime.of(minutes / 60, minutes % 60);
        }
        return result;
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    private static double fixAngle(double a) {
        return a - 360 * Math.floor(a / 360);
    }

    private static double fixHour(double a) {
        return a - 24 * Math.floor(a / 24);
    }

    /** {declination in radians, equation of time in hours}. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = Math.toRadians(fixAngle(357.529 + 0.98560028 * d));
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = Math.toRadians(fixAngle(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.toDegrees(Math.atan2(Math.cos(e) * Math.sin(l), Math.cos(l))) / 15;
        return new double[]{Math.asin(Math.sin(e) * Math.sin(l)), q / 15 - fixHour(ra)};
    }

    private double midDay(double t) {
        return fixHour(12 - sun(jd + t)[1]);
    }

    private double angleTime(double angle, double t, boolean beforeNoon) {
        double decl = sun(jd + t)[0];
        double phi = Math.toRadians(lat);
        double cos = (-Math.sin(Math.toRadians(angle)) - Math.sin(decl) * Math.sin(phi))
                / (Math.cos(decl) * Math.cos(phi));
        double hours = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) / 15;
        double noon = midDay(t);
        return beforeNoon ? noon - hours : noon + hours;
    }

    private double asr(double t) {
        double decl = sun(jd + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.abs(Math.toRadians(lat) - decl)))));
        return angleTime(angle, t, false);
    }
}
