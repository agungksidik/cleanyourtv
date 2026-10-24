package io.github.agungksidik.football;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * ESPN's public scoreboard and team-schedule JSON turned into small {@link Match} rows, and the rows grouped and
 * filtered for the page. No Android classes here, so it runs in plain JUnit.
 */
public final class Fixtures {
    public static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    public static final String TIMNAS_TEAM_ID = "4895";

    /** Competitions in the order they are shown. */
    public static final int TIMNAS = 0, LIGA1 = 1, UCL = 2;
    public static final int COMPETITIONS = 3;
    /** League slugs for the scoreboard sources; the national team has no single league. */
    static final String[] SLUGS = {null, "idn.1", "uefa.champions"};

    public static final int PRE = 0, LIVE = 1, FINAL = 2, POSTPONED = 3, CANCELED = 4;

    public static final TimeZone WIB = TimeZone.getTimeZone("Asia/Jakarta");
    static final TimeZone ESPN_DAY = TimeZone.getTimeZone("America/New_York");
    static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    public static final long FAST_REFRESH = 2 * MINUTE, SLOW_REFRESH = 30 * MINUTE;
    /** Days shown after today. */
    static final int DAYS_AHEAD = 7;
    /** A match whose kickoff passed less than this long ago may still be on, even if ESPN still says "pre". */
    static final long MAX_MATCH_LENGTH = 150 * MINUTE;

    private Fixtures() {}

    public static final class Match {
        public String id;
        public int comp;
        /** ESPN league slug of this match, e.g. "fifa.friendly"; used to ask for a live score. */
        public String league;
        public long kickoff;
        /** False when ESPN has the date but not the kickoff time yet ("TBD"). */
        public boolean timeValid = true;
        public String home, away;
        public String homeScore = "", awayScore = "";
        public int state;
        /** Match clock while live, e.g. "67'". */
        public String clock = "";
        public boolean halftime;
        /** ESPN's short status text, e.g. "45'", "HT", "FT", "TBD". */
        public String detail = "";
    }

    public static final class Group {
        public final int comp;
        public final List<Match> matches = new ArrayList<>();

        Group(int comp) {
            this.comp = comp;
        }
    }

    // ---- URLs ------------------------------------------------------------------------------------------------

    /**
     * Scoreboard URLs for one league covering the window. ESPN's site API refuses a date range
     * ({@code dates=YYYYMMDD-YYYYMMDD} answers 400) but takes a whole month ({@code dates=YYYYMM}); its default
     * page is 100 events, so ask for more.
     */
    public static List<String> scoreboardUrls(int comp, long now) {
        List<String> urls = new ArrayList<>();
        for (String month : months(now)) urls.add(BASE + SLUGS[comp] + "/scoreboard?dates=" + month + "&limit=200");
        return urls;
    }

    /** The national team's past results (newest first) and, with fixture=true, its coming matches. */
    public static List<String> timnasUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(BASE + "all/teams/" + TIMNAS_TEAM_ID + "/schedule");
        urls.add(BASE + "all/teams/" + TIMNAS_TEAM_ID + "/schedule?fixture=true");
        return urls;
    }

    /**
     * The months (yyyyMM, UTC) that hold the window, padded by a day either side so a match near midnight in
     * any time zone is not missed.
     */
    static List<String> months(long now) {
        long[] w = window(now);
        SimpleDateFormat f = new SimpleDateFormat("yyyyMM", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Set<String> out = new LinkedHashSet<>();
        for (long t = w[0] - DAY; t <= w[1] + DAY; t += DAY) out.add(f.format(t));
        out.add(f.format(w[1] + DAY));
        return new ArrayList<>(out);
    }

    /**
     * For national-team matches that may be on now, the league scoreboard of that day, which carries the live
     * score (the team schedule is not meant for live updates).
     */
    public static List<String> liveUrls(List<Match> timnas, long now) {
        Set<String> out = new LinkedHashSet<>();
        SimpleDateFormat et = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        et.setTimeZone(ESPN_DAY);
        SimpleDateFormat utc = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (Match m : timnas) {
            if (m.league == null || m.league.isEmpty() || !mayBeOn(m, now)) continue;
            out.add(BASE + m.league + "/scoreboard?dates=" + et.format(m.kickoff));
            out.add(BASE + m.league + "/scoreboard?dates=" + utc.format(m.kickoff));
        }
        return new ArrayList<>(out);
    }

    // ---- Parsing ---------------------------------------------------------------------------------------------

    /** Every event in a scoreboard or team-schedule answer. Events that cannot be read are skipped. */
    public static List<Match> parse(JSONObject root, int comp) {
        List<Match> out = new ArrayList<>();
        JSONArray events = root.optJSONArray("events");
        if (events == null) return out;
        String rootSlug = "";
        JSONArray leagues = root.optJSONArray("leagues");
        if (leagues != null && leagues.length() > 0) {
            JSONObject l = leagues.optJSONObject(0);
            if (l != null) rootSlug = l.optString("slug", "");
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            try {
                Match m = parseEvent(e, comp);
                if (m.league.isEmpty()) m.league = rootSlug;
                out.add(m);
            } catch (JSONException | ParseException ex) {
                // One odd event should not hide the rest.
            }
        }
        return out;
    }

    static Match parseEvent(JSONObject e, int comp) throws JSONException, ParseException {
        Match m = new Match();
        m.id = e.getString("id");
        m.comp = comp;
        JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
        m.kickoff = parseTime(c.optString("date", e.optString("date")));
        m.timeValid = e.optBoolean("timeValid", c.optBoolean("timeValid", true));
        JSONObject league = e.optJSONObject("league");
        m.league = league != null ? league.optString("slug", "") : "";

        JSONArray teams = c.getJSONArray("competitors");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i);
            JSONObject team = t.getJSONObject("team");
            String name = team.optString("shortDisplayName", "");
            if (name.isEmpty()) name = team.optString("displayName", "?");
            String score = score(t.opt("score"));
            if ("away".equals(t.optString("homeAway"))) {
                m.away = name;
                m.awayScore = score;
            } else {
                m.home = name;
                m.homeScore = score;
            }
        }
        if (m.home == null || m.away == null) throw new JSONException("not two teams");

        // Scoreboards put the status on the event (and the competition), team schedules on the competition only.
        JSONObject status = e.optJSONObject("status");
        if (status == null) status = c.optJSONObject("status");
        JSONObject type = status != null ? status.optJSONObject("type") : null;
        String name = type != null ? type.optString("name", "") : "";
        String state = type != null ? type.optString("state", "") : "";
        if (name.contains("POSTPONED")) m.state = POSTPONED;
        else if (name.contains("CANCELED") || name.contains("CANCELLED") || name.contains("ABANDONED")) m.state = CANCELED;
        else if ("in".equals(state)) m.state = LIVE;
        else if ("post".equals(state)) m.state = FINAL;
        else m.state = PRE;
        m.halftime = name.contains("HALFTIME");
        m.clock = status != null ? status.optString("displayClock", "") : "";
        m.detail = type != null ? type.optString("shortDetail", "") : "";
        return m;
    }

    /** "2" from a scoreboard, {"displayValue":"2"} from a team schedule, nothing before kickoff. */
    static String score(Object s) {
        if (s instanceof JSONObject) return ((JSONObject) s).optString("displayValue", "");
        if (s instanceof String) return (String) s;
        if (s instanceof Number) return String.valueOf(((Number) s).intValue());
        return "";
    }

    /** ESPN writes "2026-10-21T19:00Z", sometimes with seconds. */
    static long parseTime(String s) throws ParseException {
        String p = s.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'";
        SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(s).getTime();
    }

    /**
     * The union of two lists by event id, the newer copy winning. With {@code addNew} false, only events already in
     * {@code base} are updated: a league scoreboard asked for a live score holds other teams' matches too.
     */
    public static List<Match> merge(List<Match> base, List<Match> newer, boolean addNew) {
        Map<String, Match> byId = new LinkedHashMap<>();
        for (Match m : base) byId.put(m.id, m);
        for (Match m : newer) {
            Match old = byId.get(m.id);
            if (old == null && !addNew) continue;
            if (old != null) {
                m.comp = old.comp;
                if (m.league == null || m.league.isEmpty()) m.league = old.league;
            }
            byId.put(m.id, m);
        }
        return new ArrayList<>(byId.values());
    }

    // ---- Window, grouping, refresh ---------------------------------------------------------------------------

    /** [start of yesterday, end of the 7th day after today) in WIB. */
    static long[] window(long now) {
        Calendar c = Calendar.getInstance(WIB, Locale.ROOT);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, -1);
        long start = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, DAYS_AHEAD + 2);
        return new long[] {start, c.getTimeInMillis()};
    }

    /** The last day shown, for "until ..." text. */
    public static long lastDay(long now) {
        return window(now)[1] - 1;
    }

    /** Each competition in display order, its matches in the window sorted by kickoff. Empty groups are kept. */
    public static List<Group> group(List<Match> all, long now) {
        long[] w = window(now);
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < COMPETITIONS; i++) groups.add(new Group(i));
        Set<String> seen = new LinkedHashSet<>();
        for (Match m : all) {
            if (m.kickoff < w[0] || m.kickoff >= w[1] || m.comp < 0 || m.comp >= COMPETITIONS) continue;
            if (!seen.add(m.id)) continue;
            groups.get(m.comp).matches.add(m);
        }
        for (Group g : groups) {
            Collections.sort(g.matches, (a, b) -> {
                int k = Long.compare(a.kickoff, b.kickoff);
                return k != 0 ? k : a.home.compareTo(b.home);
            });
        }
        return groups;
    }

    /** The first match of a competition after the window, for a group with nothing in it; null if none is known. */
    public static Match nextAfter(List<Match> all, int comp, long now) {
        long end = window(now)[1];
        Match best = null;
        for (Match m : all) {
            if (m.comp != comp || m.kickoff < end || m.state != PRE) continue;
            if (best == null || m.kickoff < best.kickoff) best = m;
        }
        return best;
    }

    /** Days from today (WIB) to the kickoff's day: -1 yesterday, 0 today, 1 tomorrow. */
    public static int dayOffset(long kickoff, long now) {
        return Math.round((startOfDay(kickoff) - startOfDay(now)) / (float) DAY);
    }

    static long startOfDay(long t) {
        Calendar c = Calendar.getInstance(WIB, Locale.ROOT);
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** "HH:mm" in WIB. */
    public static String kickoffTime(long kickoff) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        f.setTimeZone(WIB);
        return f.format(kickoff);
    }

    /** Calendar fields of a time in WIB: {day of week 0 = Sunday, day of month, month 0 = January}. */
    public static int[] dayParts(long t) {
        Calendar c = Calendar.getInstance(WIB, Locale.ROOT);
        c.setTimeInMillis(t);
        return new int[] {c.get(Calendar.DAY_OF_WEEK) - 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH)};
    }

    /** Live by ESPN, or past its kickoff and not yet reported finished. */
    static boolean mayBeOn(Match m, long now) {
        if (m.state == LIVE) return true;
        return m.state == PRE && m.timeValid && m.kickoff <= now + 5 * MINUTE && now - m.kickoff < MAX_MATCH_LENGTH;
    }

    /**
     * Milliseconds until the next refresh: 2 minutes while a match may be on, else 30 minutes, or sooner when a
     * kickoff falls before then.
     */
    public static long nextRefresh(List<Match> all, long now) {
        long delay = SLOW_REFRESH;
        for (Match m : all) {
            if (mayBeOn(m, now)) return FAST_REFRESH;
            if (m.state == PRE && m.timeValid && m.kickoff > now) {
                delay = Math.min(delay, Math.max(FAST_REFRESH, m.kickoff - now + MINUTE));
            }
        }
        return delay;
    }

    public static boolean anyLive(List<Match> all, long now) {
        for (Match m : all) if (mayBeOn(m, now)) return true;
        return false;
    }

    // ---- Home ticker (MatchProvider) -------------------------------------------------------------------------

    /** Columns of content://<package>.matches/matches. */
    public static final String[] TICKER_COLUMNS = {"competition", "home", "away", "kickoff_ms", "state",
            "home_score", "away_score", "detail"};
    /** Short competition names for the ticker, in competition order. Data for another app, not page text. */
    static final String[] TICKER_NAMES = {"Timnas", "Liga 1", "UCL"};

    /** "pre", "in" or "post", as ESPN's status state; postponed and canceled are "post". */
    static String stateName(int state) {
        return state == PRE ? "pre" : state == LIVE ? "in" : "post";
    }

    /**
     * The window's matches as ticker rows: Timnas first, then Liga 1 and UCL together by kickoff. A competition with
     * nothing in the window adds its next known match instead (the page's "Berikutnya"), so a quiet week still
     * has rows.
     */
    public static List<Object[]> tickerRows(List<Match> all, long now) {
        List<Group> groups = group(all, now);
        List<List<Match>> picked = new ArrayList<>();
        for (Group g : groups) {
            List<Match> l = new ArrayList<>(g.matches);
            if (l.isEmpty()) {
                Match next = nextAfter(all, g.comp, now);
                if (next != null) l.add(next);
            }
            picked.add(l);
        }
        List<Match> rest = new ArrayList<>();
        for (int i = 1; i < picked.size(); i++) rest.addAll(picked.get(i));
        Collections.sort(rest, (a, b) -> Long.compare(a.kickoff, b.kickoff));
        List<Match> ordered = new ArrayList<>(picked.get(TIMNAS));
        ordered.addAll(rest);
        List<Object[]> rows = new ArrayList<>();
        for (Match m : ordered) {
            boolean scored = m.state == LIVE || m.state == FINAL;
            rows.add(new Object[] {TICKER_NAMES[m.comp], m.home, m.away, m.kickoff, stateName(m.state),
                    scored ? m.homeScore : "", scored ? m.awayScore : "", m.detail == null ? "" : m.detail});
        }
        return rows;
    }

    // ---- Cache -----------------------------------------------------------------------------------------------

    /** Our own small format for SharedPreferences, not ESPN's. */
    public static String toJson(List<Match> list) {
        JSONArray a = new JSONArray();
        try {
            for (Match m : list) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("c", m.comp);
                o.put("l", m.league == null ? "" : m.league);
                o.put("k", m.kickoff);
                o.put("v", m.timeValid);
                o.put("h", m.home);
                o.put("a", m.away);
                o.put("hs", m.homeScore);
                o.put("as", m.awayScore);
                o.put("s", m.state);
                o.put("t", m.clock);
                o.put("ht", m.halftime);
                o.put("d", m.detail == null ? "" : m.detail);
                a.put(o);
            }
        } catch (JSONException e) {
            return "[]";
        }
        return a.toString();
    }

    public static List<Match> fromJson(String s) {
        List<Match> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Match m = new Match();
                m.id = o.getString("id");
                m.comp = o.getInt("c");
                m.league = o.optString("l", "");
                m.kickoff = o.getLong("k");
                m.timeValid = o.optBoolean("v", true);
                m.home = o.getString("h");
                m.away = o.getString("a");
                m.homeScore = o.optString("hs", "");
                m.awayScore = o.optString("as", "");
                m.state = o.optInt("s", PRE);
                m.clock = o.optString("t", "");
                m.halftime = o.optBoolean("ht", false);
                m.detail = o.optString("d", "");
                out.add(m);
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }
}
