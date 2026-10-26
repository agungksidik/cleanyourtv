package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two rows under the clock, both off unless a theme asks: prayer_location ("lat,lng") gives today's five prayer
 * times as five glass cells, the next one lit; football_row adds one cell after them with the football add-on's
 * matches (its signature protected provider), turning to the next match every few seconds. One row, no clicks.
 */
final class HomeInfo {
    private static final String[] PRAYERS = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final Uri MATCHES = Uri.parse("content://io.github.agungksidik.football.matches/matches");
    private static final long TURN_MS = 8_000L;
    private static final long REFRESH_MS = 30_000L;
    private static final int PER_PAGE = 1;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    final LinearLayout column;
    private final Context context;
    private final float dp;
    private final double[] place;
    private final LinearLayout prayerRow;
    private final TextView[][] prayerCells = new TextView[PRAYERS.length][2];
    private final LinearLayout matchRow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private final Runnable turn = this::turn;
    private ExecutorService reader;
    private List<String[]> matches = new ArrayList<>();
    private int page;
    private boolean running;

    /** minimal_home: a tinted glass, as see-through as the default so faces in the photo show. */
    private final boolean tinted;

    HomeInfo(Context context, FrameLayout root, View contentLayer, String prayerLocation, boolean footballRow, boolean tinted) {
        this.context = context;
        this.tinted = tinted;
        dp = context.getResources().getDisplayMetrics().density;
        place = parse(prayerLocation);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.HORIZONTAL);
        column.setFocusable(false);
        column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        prayerRow = new LinearLayout(context);
        prayerRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PRAYERS.length; i++) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(14), dp(6), dp(14), dp(7));
            TextView name = text(11, 0xB3FFFFFF, R.string.font_medium);
            name.setAllCaps(true);
            name.setLetterSpacing(0.12f);
            name.setText(PRAYERS[i]);
            TextView time = text(19, 0xFFFFFFFF, R.string.font_regular);
            cell.addView(name);
            cell.addView(time);
            prayerCells[i][0] = name;
            prayerCells[i][1] = time;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            prayerRow.addView(cell, lp);
        }
        prayerRow.setVisibility(place == null ? View.GONE : View.VISIBLE);
        column.addView(prayerRow);

        matchRow = new LinearLayout(context);
        matchRow.setOrientation(LinearLayout.HORIZONTAL);
        matchRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = dp(8);
        column.addView(matchRow, mp);
        matchRow.setTag(footballRow);

        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        p.leftMargin = dp(60);
        p.topMargin = dp(150);
        root.addView(column, Math.max(0, root.indexOfChild(contentLayer)), p);
    }

    boolean shown() {
        return place != null || Boolean.TRUE.equals(matchRow.getTag());
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }

    private TextView text(float sp, int color, int font) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        try {
            t.setTypeface(UiFont.get(context, font));
        } catch (Exception ignored) {
            // the system font is fine
        }
        return t;
    }

    private GradientDrawable glass(boolean lit) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(14 * dp);
        g.setColor(lit ? 0xE62E9E5B : tinted ? 0x591A2230 : 0x59000000);
        g.setStroke(Math.max(1, dp(1)), lit ? 0x66FFFFFF : 0x2EFFFFFF);
        return g;
    }

    /** "-7.2279,107.9087" to {lat, lng}; null when empty or not two numbers. */
    static double[] parse(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0].trim()), lng = Double.parseDouble(parts[1].trim());
            return Math.abs(lat) <= 90 && Math.abs(lng) <= 180 ? new double[]{lat, lng} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void start() {
        if (running) return;
        running = true;
        if (reader == null) reader = Executors.newSingleThreadExecutor();
        refresh();
        handler.postDelayed(turn, TURN_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(turn);
        if (reader != null) reader.shutdownNow();
        reader = null;
    }

    private void refresh() {
        if (!running) return;
        handler.postDelayed(refresh, REFRESH_MS);
        if (place != null) showPrayers();
        ExecutorService r = reader;
        if (!Boolean.TRUE.equals(matchRow.getTag()) || r == null) return;
        try {
            r.execute(() -> {
                List<String[]> found = readMatches();
                handler.post(() -> {
                    if (!running) return;
                    boolean changed = found.size() != matches.size();
                    matches = found;
                    if (changed) page = 0;
                    showMatches();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopped meanwhile.
        }
    }

    private void showPrayers() {
        ZonedDateTime now = ZonedDateTime.now();
        double offset = now.getOffset().getTotalSeconds() / 3600.0;
        LocalTime[] times = Prayer.times(place[0], place[1], now.toLocalDate(), offset);
        int next = -1;
        for (int i = 0; i < times.length && next < 0; i++) if (times[i].isAfter(now.toLocalTime())) next = i;
        if (next < 0) next = 0;   // after Isha: tomorrow's Subuh
        for (int i = 0; i < times.length; i++) {
            prayerCells[i][1].setText(times[i].format(HM));
            ((View) prayerCells[i][1].getParent()).setBackground(glass(i == next));
        }
    }

    /** {competition, home, away, kickoff_ms, state, home_score, away_score, detail} rows from the add-on. */
    private List<String[]> readMatches() {
        List<String[]> result = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(MATCHES, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                result.add(new String[]{col(c, "competition"), col(c, "home"), col(c, "away"),
                        col(c, "kickoff_ms"), col(c, "state"), col(c, "home_score"), col(c, "away_score"),
                        col(c, "detail")});
            }
        } catch (RuntimeException e) {
            // No add-on, or not signed with our key: no row.
        }
        return result;
    }

    private static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        String v = i < 0 ? null : c.getString(i);
        return v == null ? "" : v;
    }

    private void turn() {
        if (!running) return;
        handler.postDelayed(turn, TURN_MS);
        int pages = (matches.size() + PER_PAGE - 1) / PER_PAGE;
        if (pages <= 1) return;
        page = (page + 1) % pages;
        matchRow.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            showMatches();
            matchRow.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    private void showMatches() {
        matchRow.removeAllViews();
        if (matches.isEmpty()) {
            matchRow.setVisibility(View.GONE);
            return;
        }
        matchRow.setVisibility(View.VISIBLE);
        int from = Math.min(page * PER_PAGE, Math.max(0, matches.size() - 1));
        for (int i = from; i < Math.min(matches.size(), from + PER_PAGE); i++) {
            String[] m = matches.get(i);
            boolean live = "in".equals(m[4]);
            LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setPadding(dp(14), dp(6), dp(14), dp(7));
            chip.setBackground(glass(live));
            TextView top = text(11, 0xB3FFFFFF, R.string.font_medium);
            top.setAllCaps(true);
            top.setLetterSpacing(0.08f);
            top.setText(m[0] + "  ·  " + when(m));
            TextView teams = text(15, 0xFFFFFFFF, R.string.font_regular);
            teams.setText("pre".equals(m[4]) || m[5].isEmpty()
                    ? m[1] + "  vs  " + m[2] : m[1] + "  " + m[5] + " - " + m[6] + "  " + m[2]);
            chip.addView(top);
            chip.addView(teams);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > from) lp.leftMargin = dp(8);
            matchRow.addView(chip, lp);
        }
    }

    /** "LIVE 45'", "Selesai", "Hari ini 19:30", "Besok 02:00", "Sab 25 Okt 20:00". */
    private static String when(String[] m) {
        if ("in".equals(m[4])) return "LIVE " + m[7];
        if ("post".equals(m[4])) return "Selesai";
        boolean tbd = "TBD".equalsIgnoreCase(m[7]);
        try {
            ZonedDateTime k = Instant.ofEpochMilli(Long.parseLong(m[3])).atZone(ZoneId.systemDefault());
            LocalDate today = LocalDate.now();
            String day = k.toLocalDate().equals(today) ? "Hari ini"
                    : k.toLocalDate().equals(today.plusDays(1)) ? "Besok"
                    : k.format(DateTimeFormatter.ofPattern("EEE d MMM"));
            return tbd ? day + " · jam belum ada" : day + " " + k.format(HM);
        } catch (RuntimeException e) {
            return m[7];
        }
    }
}
