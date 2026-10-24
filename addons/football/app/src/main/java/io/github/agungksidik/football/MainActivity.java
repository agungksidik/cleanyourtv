package io.github.agungksidik.football;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Football fixtures and scores from ESPN's public JSON (no account or key): Timnas Indonesia, Liga 1 and the
 * Champions League, yesterday through the next 7 days, kickoff in WIB. Refreshes only while the page is visible:
 * every 2 minutes while a match is on, else every 30 minutes. The last answer per source is kept for offline use;
 * {@link MatchJob} refreshes it every 30 minutes for the Home ticker ({@link MatchProvider}).
 */
public class MainActivity extends Activity {
    private static final int[] COMP_NAMES = {R.string.comp_timnas, R.string.comp_liga1, R.string.comp_ucl};

    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent, live;
    private TextView window, status;
    private LinearLayout list;
    private ScrollView scroll;
    private SharedPreferences prefs;
    /** Per competition, the last matches we have; never null. */
    @SuppressWarnings("unchecked")
    private final List<Fixtures.Match>[] data = new List[Fixtures.COMPETITIONS];
    /** Per competition, when ESPN last answered (ms), 0 if never. */
    private final long[] updated = new long[Fixtures.COMPETITIONS];
    private boolean lastOk;
    /** True once a load has finished, so "no data" is not shown before the first try. */
    private boolean tried;
    private boolean loading, visible;

    private final Runnable refresh = this::load;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        accent = getColor(R.color.accent);
        live = getColor(R.color.live);
        prefs = getSharedPreferences(Sync.PREFS, MODE_PRIVATE);
        readCache();
        MatchJob.schedule(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(32), dp(64), dp(20));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(30, true, 0xFFFFFFFF);
        title.setText(R.string.app_name);
        col.addView(title);
        window = text(15, false, 0xB3FFFFFF);
        window.setPadding(0, dp(6), 0, dp(12));
        col.addView(window);

        scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setSmoothScrollingEnabled(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = text(13, false, 0x80FFFFFF);
        status.setPadding(0, dp(10), 0, 0);
        status.setText(R.string.loading);
        col.addView(status);

        setContentView(root);
        scroll.requestFocus();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        main.removeCallbacks(refresh);
    }

    private void load() {
        main.removeCallbacks(refresh);
        if (loading) return;
        loading = true;
        new Thread(() -> {
            Sync.run(getApplicationContext());
            main.post(() -> {
                loading = false;
                tried = true;
                readCache();
                render();
                if (visible) main.postDelayed(refresh, Fixtures.nextRefresh(all(), System.currentTimeMillis()));
            });
        }).start();
    }

    private void readCache() {
        for (int i = 0; i < Fixtures.COMPETITIONS; i++) {
            data[i] = Sync.load(prefs, i);
            updated[i] = prefs.getLong("updated_" + i, 0);
        }
        lastOk = prefs.getBoolean("last_ok", false);
    }

    private List<Fixtures.Match> all() {
        List<Fixtures.Match> all = new ArrayList<>();
        for (List<Fixtures.Match> l : data) all.addAll(l);
        return all;
    }

    private void render() {
        long now = System.currentTimeMillis();
        String last = dayName(Fixtures.lastDay(now));
        window.setText(getString(R.string.window, last));

        list.removeAllViews();
        List<Fixtures.Match> all = all();
        for (Fixtures.Group g : Fixtures.group(all, now)) {
            TextView head = text(17, true, accent);
            head.setText(getString(COMP_NAMES[g.comp]).toUpperCase(java.util.Locale.ROOT));
            head.setLetterSpacing(0.12f);
            head.setPadding(0, dp(list.getChildCount() == 0 ? 4 : 18), 0, dp(6));
            list.addView(head);
            if (g.matches.isEmpty()) {
                TextView none = text(16, false, 0x80FFFFFF);
                none.setText(updated[g.comp] != 0 ? getString(R.string.no_matches, last)
                        : getString(tried ? R.string.no_data : R.string.loading));
                none.setPadding(0, dp(2), 0, dp(4));
                list.addView(none);
                Fixtures.Match next = Fixtures.nextAfter(all, g.comp, now);
                if (next != null) {
                    TextView n = text(16, false, 0xB3FFFFFF);
                    n.setText(getString(R.string.next_match, dayName(next.kickoff),
                            next.timeValid ? Fixtures.kickoffTime(next.kickoff) : getString(R.string.time_tbd),
                            next.home, next.away));
                    n.setPadding(0, dp(2), 0, dp(4));
                    list.addView(n);
                }
                continue;
            }
            int prevDay = Integer.MIN_VALUE;
            for (Fixtures.Match m : g.matches) {
                int day = Fixtures.dayOffset(m.kickoff, now);
                list.addView(row(m, day != prevDay ? dayLabel(m.kickoff, day) : "", day == 0));
                prevDay = day;
            }
        }

        long oldest = Long.MAX_VALUE;
        for (long u : updated) oldest = Math.min(oldest, u);
        long newest = 0;
        for (long u : updated) newest = Math.max(newest, u);
        if (newest == 0) status.setText(tried ? R.string.no_data : R.string.loading);
        else if (lastOk) status.setText(getString(R.string.updated, Fixtures.kickoffTime(newest)));
        else if (!loading) status.setText(getString(R.string.offline,
                oldest == 0 ? Fixtures.kickoffTime(newest) : Fixtures.kickoffTime(oldest)));
    }

    private LinearLayout row(Fixtures.Match m, String day, boolean today) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(5), dp(12), dp(5));
        boolean on = m.state == Fixtures.LIVE;
        if (on || today) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(on ? (live & 0x00FFFFFF) | 0x26000000 : 0x10FFFFFF);
            r.setBackground(bg);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        r.setLayoutParams(lp);

        TextView d = text(15, today, today ? 0xFFFFFFFF : 0xB3FFFFFF);
        d.setText(day);
        r.addView(d, new LinearLayout.LayoutParams(dp(120), -2));

        TextView time = text(15, false, 0xB3FFFFFF);
        time.setText(m.timeValid ? Fixtures.kickoffTime(m.kickoff) : getString(R.string.time_tbd));
        r.addView(time, new LinearLayout.LayoutParams(dp(64), -2));

        boolean scored = m.state == Fixtures.LIVE || m.state == Fixtures.FINAL;
        TextView home = oneLine(text(17, false, 0xFFFFFFFF));
        home.setGravity(Gravity.END);
        home.setText(m.home);
        r.addView(home, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView mid = text(scored ? 18 : 15, scored, scored ? 0xFFFFFFFF : 0x80FFFFFF);
        mid.setGravity(Gravity.CENTER);
        mid.setText(scored ? m.homeScore + " - " + m.awayScore : getString(R.string.vs));
        r.addView(mid, new LinearLayout.LayoutParams(dp(84), -2));

        TextView away = oneLine(text(17, false, 0xFFFFFFFF));
        away.setText(m.away);
        r.addView(away, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView st = text(14, on, on ? 0xFFFFFFFF : 0x99FFFFFF);
        st.setGravity(Gravity.CENTER);
        st.setText(statusText(m));
        if (on) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(6));
            pill.setColor(live);
            st.setBackground(pill);
            st.setPadding(dp(6), dp(3), dp(6), dp(3));
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(112), -2);
        slp.leftMargin = dp(12);
        r.addView(st, slp);
        return r;
    }

    private String statusText(Fixtures.Match m) {
        switch (m.state) {
            case Fixtures.LIVE:
                String clock = m.halftime ? getString(R.string.halftime) : m.clock;
                return getString(R.string.live) + (TextUtils.isEmpty(clock) ? "" : " " + clock);
            case Fixtures.FINAL:
                return getString(R.string.final_time);
            case Fixtures.POSTPONED:
                return getString(R.string.postponed);
            case Fixtures.CANCELED:
                return getString(R.string.canceled);
            default:
                return "";
        }
    }

    private String dayLabel(long t, int offset) {
        if (offset == -1) return getString(R.string.yesterday);
        if (offset == 0) return getString(R.string.today);
        if (offset == 1) return getString(R.string.tomorrow);
        return dayName(t);
    }

    /** "Sab 31 Okt". */
    private String dayName(long t) {
        int[] p = Fixtures.dayParts(t);
        return getResources().getStringArray(R.array.day_names)[p[0]] + " " + p[1] + " "
                + getResources().getStringArray(R.array.month_names)[p[2]];
    }

    private static TextView oneLine(TextView t) {
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView text(float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
