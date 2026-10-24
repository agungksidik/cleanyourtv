package io.github.agungksidik.football;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches all three sources from ESPN and stores them in SharedPreferences, one entry per competition. A source
 * that fails keeps its last stored matches. Used by the page (while visible) and by {@link MatchJob}.
 */
final class Sync {
    private static final String TAG = "JadwalBola";
    static final String PREFS = "cache";
    /** Stored matches older than this are dropped. */
    private static final long KEEP_MS = 3 * 24 * 3600_000L;

    private Sync() {}

    /** Blocking; call off the main thread. True when every source answered. */
    static synchronized boolean run(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<Fixtures.Match>[] got = new List[Fixtures.COMPETITIONS];
        got[Fixtures.LIGA1] = league(Fixtures.LIGA1, now);
        got[Fixtures.UCL] = league(Fixtures.UCL, now);
        got[Fixtures.TIMNAS] = timnas(load(prefs, Fixtures.TIMNAS), now);
        long t = System.currentTimeMillis();
        boolean ok = true;
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < Fixtures.COMPETITIONS; i++) {
            if (got[i] == null) {
                ok = false;
                continue;
            }
            List<Fixtures.Match> keep = new ArrayList<>();
            for (Fixtures.Match m : got[i]) if (m.kickoff > t - KEEP_MS) keep.add(m);
            e.putString("matches_" + i, Fixtures.toJson(keep)).putLong("updated_" + i, t);
        }
        e.putBoolean("last_ok", ok);
        e.commit();
        context.getContentResolver().notifyChange(MatchProvider.MATCHES, null);
        return ok;
    }

    static List<Fixtures.Match> load(SharedPreferences prefs, int comp) {
        return Fixtures.fromJson(prefs.getString("matches_" + comp, null));
    }

    static List<Fixtures.Match> loadAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Fixtures.Match> all = new ArrayList<>();
        for (int i = 0; i < Fixtures.COMPETITIONS; i++) all.addAll(load(prefs, i));
        return all;
    }

    /** Liga 1 or the Champions League: one scoreboard page per month in the window. Null if any page failed. */
    private static List<Fixtures.Match> league(int comp, long now) {
        List<Fixtures.Match> out = new ArrayList<>();
        try {
            for (String url : Fixtures.scoreboardUrls(comp, now)) {
                out = Fixtures.merge(out, Fixtures.parse(new JSONObject(fetch(url)), comp), true);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "league " + comp + ": " + e);
            return null;
        }
    }

    /**
     * The national team: results and fixtures from its team schedule, then, for a match that may be on, the
     * live score from that day's league scoreboard. Null if the schedule failed.
     */
    private static List<Fixtures.Match> timnas(List<Fixtures.Match> cached, long now) {
        List<Fixtures.Match> out = new ArrayList<>();
        try {
            for (String url : Fixtures.timnasUrls()) {
                out = Fixtures.merge(out, Fixtures.parse(new JSONObject(fetch(url)), Fixtures.TIMNAS), true);
            }
        } catch (Exception e) {
            Log.w(TAG, "timnas: " + e);
            out = null;
        }
        List<Fixtures.Match> base = out != null ? out : cached;
        for (String url : Fixtures.liveUrls(base, now)) {
            try {
                base = Fixtures.merge(base, Fixtures.parse(new JSONObject(fetch(url)), Fixtures.TIMNAS), false);
            } catch (Exception e) {
                Log.w(TAG, "timnas live: " + e);
            }
        }
        return out != null ? base : null;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        // ESPN answers 403 to a "Java/..." agent; say who we are rather than rely on the platform default.
        c.setRequestProperty("User-Agent", "JadwalBola/1.0 (Android TV)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
