package io.github.agungksidik.football;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Fixtures are real ESPN answers from October 2026, trimmed to a few events. */
public class FixturesTest {
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    private static JSONObject res(String name) throws Exception {
        try (InputStream in = FixturesTest.class.getClassLoader().getResourceAsStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }

    private static long t(String iso) throws Exception {
        return Fixtures.parseTime(iso);
    }

    private static Fixtures.Match find(List<Fixtures.Match> l, String id) {
        for (Fixtures.Match m : l) if (m.id.equals(id)) return m;
        throw new AssertionError("no " + id);
    }

    private static Fixtures.Match match(String id, int comp, long kickoff, int state) {
        Fixtures.Match m = new Fixtures.Match();
        m.id = id;
        m.comp = comp;
        m.kickoff = kickoff;
        m.state = state;
        m.home = "A" + id;
        m.away = "B" + id;
        return m;
    }

    @Test
    public void parsesScoreboard() throws Exception {
        List<Fixtures.Match> l = Fixtures.parse(res("ucl_scoreboard.json"), Fixtures.UCL);
        assertEquals(4, l.size());
        Fixtures.Match m = l.get(0);
        assertEquals(t("2026-10-20T16:45Z"), m.kickoff);
        assertEquals("Sabah", m.home);           // shortDisplayName, shorter than "Sabah FK"
        assertEquals("1", m.homeScore);
        assertEquals("2", m.awayScore);
        assertEquals(Fixtures.FINAL, m.state);
        assertEquals("uefa.champions", m.league);
        assertEquals(Fixtures.UCL, m.comp);
        assertEquals("23:45", Fixtures.kickoffTime(m.kickoff));
    }

    @Test
    public void parsesLiga1() throws Exception {
        List<Fixtures.Match> l = Fixtures.parse(res("idn1_scoreboard.json"), Fixtures.LIGA1);
        assertEquals(3, l.size());
        assertEquals("idn.1", l.get(0).league);
        assertEquals("16:00", Fixtures.kickoffTime(l.get(0).kickoff));
        for (Fixtures.Match m : l) assertEquals(Fixtures.FINAL, m.state);
    }

    @Test
    public void parsesTeamScheduleScoresAndTbdFixtures() throws Exception {
        List<Fixtures.Match> results = Fixtures.parse(res("timnas_results.json"), Fixtures.TIMNAS);
        assertEquals(3, results.size());
        Fixtures.Match sg = results.get(0);
        assertEquals("Singapore", sg.home);
        assertEquals("Indonesia", sg.away);
        assertEquals("1", sg.homeScore);
        assertEquals("1", sg.awayScore);
        assertEquals("aff.championship", sg.league);

        List<Fixtures.Match> fixtures = Fixtures.parse(res("timnas_fixtures.json"), Fixtures.TIMNAS);
        Fixtures.Match jor = find(fixtures, "401919975");
        assertEquals(Fixtures.PRE, jor.state);
        assertFalse(jor.timeValid);
        assertEquals("", jor.homeScore);
        assertEquals("fifa.friendly", jor.league);

        List<Fixtures.Match> all = Fixtures.merge(results, fixtures, true);
        assertEquals(7, all.size());
    }

    @Test
    public void liveScoreboardUpdatesOnlyKnownMatches() throws Exception {
        List<Fixtures.Match> timnas = Fixtures.parse(res("timnas_fixtures.json"), Fixtures.TIMNAS);
        JSONObject board = res("timnas_fixtures.json");
        JSONObject ev = board.getJSONArray("events").getJSONObject(0);
        JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
        comp.getJSONArray("competitors").getJSONObject(0).put("score", "1");
        comp.getJSONArray("competitors").getJSONObject(1).put("score", "2");
        JSONObject status = comp.getJSONObject("status");
        status.put("displayClock", "67'");
        status.getJSONObject("type").put("state", "in").put("name", "STATUS_SECOND_HALF");
        ev.put("id", "401919975");
        // Another team's match on the same scoreboard.
        JSONObject other = new JSONObject(ev.toString()).put("id", "999");
        board.getJSONArray("events").put(other);

        List<Fixtures.Match> merged = Fixtures.merge(timnas, Fixtures.parse(board, Fixtures.UCL), false);
        assertEquals(4, merged.size());
        Fixtures.Match m = find(merged, "401919975");
        assertEquals(Fixtures.LIVE, m.state);
        assertEquals(Fixtures.TIMNAS, m.comp);
        assertEquals("1", m.homeScore);
        assertEquals("67'", m.clock);
    }

    @Test
    public void statusMapping() throws Exception {
        JSONObject root = res("ucl_scoreboard.json");
        String[][] cases = {{"STATUS_HALFTIME", "in"}, {"STATUS_POSTPONED", "post"}, {"STATUS_CANCELED", "post"},
                {"STATUS_SCHEDULED", "pre"}};
        int[] want = {Fixtures.LIVE, Fixtures.POSTPONED, Fixtures.CANCELED, Fixtures.PRE};
        for (int i = 0; i < cases.length; i++) {
            JSONObject ev = root.getJSONArray("events").getJSONObject(i);
            ev.getJSONObject("status").getJSONObject("type").put("name", cases[i][0]).put("state", cases[i][1]);
        }
        List<Fixtures.Match> l = Fixtures.parse(root, Fixtures.UCL);
        for (int i = 0; i < want.length; i++) assertEquals(cases[i][0], want[i], l.get(i).state);
        assertTrue(l.get(0).halftime);
    }

    @Test
    public void skipsBrokenEvents() throws Exception {
        JSONObject root = res("ucl_scoreboard.json");
        root.getJSONArray("events").getJSONObject(1).remove("competitions");
        assertEquals(3, Fixtures.parse(root, Fixtures.UCL).size());
        assertEquals(0, Fixtures.parse(new JSONObject("{\"code\":400}"), Fixtures.UCL).size());
    }

    @Test
    public void windowIsYesterdayThroughSevenDaysInWib() throws Exception {
        long now = t("2026-10-24T03:00Z");               // 10:00 WIB Saturday 24 Oct
        long[] w = Fixtures.window(now);
        assertEquals(t("2026-10-22T17:00Z"), w[0]);      // 23 Oct 00:00 WIB
        assertEquals(t("2026-10-31T17:00Z"), w[1]);      // 1 Nov 00:00 WIB, so 31 Oct is the last day
        assertEquals(-1, Fixtures.dayOffset(t("2026-10-22T17:30Z"), now));
        assertEquals(0, Fixtures.dayOffset(t("2026-10-23T17:00Z"), now));
        assertEquals(0, Fixtures.dayOffset(t("2026-10-24T16:59Z"), now));
        assertEquals(1, Fixtures.dayOffset(t("2026-10-24T17:00Z"), now));
        assertEquals(7, Fixtures.dayOffset(t("2026-10-31T16:00Z"), now));
        int[] p = Fixtures.dayParts(Fixtures.lastDay(now));
        assertEquals(6, p[0]);                            // Saturday
        assertEquals(31, p[1]);
        assertEquals(9, p[2]);                            // October
    }

    @Test
    public void monthsCoverTheWindow() throws Exception {
        assertEquals(List.of("202610", "202611"), Fixtures.months(t("2026-10-24T03:00Z")));
        assertEquals(List.of("202610"), Fixtures.months(t("2026-10-10T03:00Z")));
        assertEquals(List.of("202611", "202612"), Fixtures.months(t("2026-11-28T03:00Z")));
        List<String> urls = Fixtures.scoreboardUrls(Fixtures.UCL, t("2026-10-10T03:00Z"));
        assertEquals(List.of("https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard?dates=202610&limit=200"), urls);
    }

    @Test
    public void groupsInOrderFiltersAndSorts() throws Exception {
        long now = t("2026-10-24T03:00Z");
        List<Fixtures.Match> all = new ArrayList<>();
        all.add(match("u2", Fixtures.UCL, now + 2 * DAY, Fixtures.PRE));
        all.add(match("u1", Fixtures.UCL, now - 20 * HOUR, Fixtures.FINAL));
        all.add(match("old", Fixtures.UCL, now - 3 * DAY, Fixtures.FINAL));
        all.add(match("far", Fixtures.TIMNAS, now + 20 * DAY, Fixtures.PRE));
        all.add(match("t1", Fixtures.TIMNAS, now + HOUR, Fixtures.PRE));
        all.add(match("t1", Fixtures.TIMNAS, now + HOUR, Fixtures.PRE));
        List<Fixtures.Group> g = Fixtures.group(all, now);
        assertEquals(3, g.size());
        assertEquals(Fixtures.TIMNAS, g.get(0).comp);
        assertEquals(1, g.get(0).matches.size());
        assertEquals(0, g.get(1).matches.size());
        assertEquals("u1", g.get(2).matches.get(0).id);
        assertEquals("u2", g.get(2).matches.get(1).id);
    }

    @Test
    public void nextAfterTheWindowForAnEmptyGroup() throws Exception {
        long now = t("2026-10-24T03:00Z");
        List<Fixtures.Match> all = new ArrayList<>();
        all.add(match("past", Fixtures.UCL, now - 3 * DAY, Fixtures.FINAL));
        all.add(match("late", Fixtures.UCL, t("2026-11-25T20:00Z"), Fixtures.PRE));
        all.add(match("next", Fixtures.UCL, t("2026-11-03T17:45Z"), Fixtures.PRE));
        all.add(match("other", Fixtures.TIMNAS, t("2026-11-01T17:45Z"), Fixtures.PRE));
        assertEquals("next", Fixtures.nextAfter(all, Fixtures.UCL, now).id);
        assertEquals(null, Fixtures.nextAfter(all, Fixtures.LIGA1, now));
    }

    @Test
    public void refreshFastOnlyWhileAMatchMayBeOn() throws Exception {
        long now = t("2026-10-24T12:00Z");
        List<Fixtures.Match> l = new ArrayList<>();
        l.add(match("done", Fixtures.UCL, now - 3 * HOUR, Fixtures.FINAL));
        assertEquals(Fixtures.SLOW_REFRESH, Fixtures.nextRefresh(l, now));
        assertFalse(Fixtures.anyLive(l, now));

        Fixtures.Match soon = match("soon", Fixtures.LIGA1, now + 10 * MIN, Fixtures.PRE);
        l.add(soon);
        assertEquals(11 * MIN, Fixtures.nextRefresh(l, now));
        soon.timeValid = false;
        assertEquals(Fixtures.SLOW_REFRESH, Fixtures.nextRefresh(l, now));

        // Past kickoff but ESPN has not flipped it to live yet.
        l.add(match("late", Fixtures.LIGA1, now - 30 * MIN, Fixtures.PRE));
        assertEquals(Fixtures.FAST_REFRESH, Fixtures.nextRefresh(l, now));

        List<Fixtures.Match> live = new ArrayList<>();
        live.add(match("on", Fixtures.TIMNAS, now - 4 * HOUR, Fixtures.LIVE));
        assertEquals(Fixtures.FAST_REFRESH, Fixtures.nextRefresh(live, now));
        assertTrue(Fixtures.anyLive(live, now));
    }

    @Test
    public void liveUrlsAskTheLeagueOfTheDay() throws Exception {
        Fixtures.Match m = match("1", Fixtures.TIMNAS, t("2026-11-13T20:00Z"), Fixtures.PRE);
        m.league = "fifa.friendly";
        List<Fixtures.Match> l = List.of(m);
        assertTrue(Fixtures.liveUrls(l, t("2026-11-13T12:00Z")).isEmpty());
        assertEquals(List.of(Fixtures.BASE + "fifa.friendly/scoreboard?dates=20261113"),
                Fixtures.liveUrls(l, t("2026-11-13T20:30Z")));
        // 01:00Z is the previous evening in New York: both days are asked.
        m.kickoff = t("2026-11-14T01:00Z");
        assertEquals(2, Fixtures.liveUrls(l, t("2026-11-14T01:30Z")).size());
    }

    @Test
    public void tickerRowsTimnasFirstThenByKickoff() throws Exception {
        long now = t("2026-10-24T03:00Z");
        List<Fixtures.Match> all = new ArrayList<>();
        Fixtures.Match ucl = match("u", Fixtures.UCL, now + HOUR, Fixtures.LIVE);
        ucl.homeScore = "1";
        ucl.awayScore = "0";
        ucl.detail = "45'";
        all.add(ucl);
        Fixtures.Match liga = match("l", Fixtures.LIGA1, now + 2 * HOUR, Fixtures.PRE);
        liga.homeScore = "0";
        liga.awayScore = "0";
        all.add(liga);
        all.add(match("t", Fixtures.TIMNAS, now + 3 * DAY, Fixtures.FINAL));
        all.add(match("p", Fixtures.UCL, now - HOUR, Fixtures.POSTPONED));
        all.add(match("out", Fixtures.UCL, now + 20 * DAY, Fixtures.PRE));
        List<Object[]> rows = Fixtures.tickerRows(all, now);
        assertEquals(4, rows.size());
        assertEquals(8, Fixtures.TICKER_COLUMNS.length);
        assertEquals("Timnas", rows.get(0)[0]);
        assertEquals("post", rows.get(0)[4]);
        assertEquals("UCL", rows.get(1)[0]);
        assertEquals("post", rows.get(1)[4]);       // postponed
        assertEquals("", rows.get(1)[5]);
        Object[] u = rows.get(2);
        assertEquals("Au", u[1]);
        assertEquals(now + HOUR, u[3]);
        assertEquals("in", u[4]);
        assertEquals("1", u[5]);
        assertEquals("0", u[6]);
        assertEquals("45'", u[7]);
        assertEquals("Liga 1", rows.get(3)[0]);
        assertEquals("pre", rows.get(3)[4]);
        assertEquals("", rows.get(3)[5]);           // no "0 - 0" before kickoff
    }

    @Test
    public void tickerAddsNextMatchForEmptyCompetitions() throws Exception {
        long now = t("2026-10-24T03:00Z");
        List<Fixtures.Match> all = new ArrayList<>();
        all.add(match("uOld", Fixtures.UCL, t("2026-10-21T19:00Z"), Fixtures.FINAL));
        all.add(match("uNext", Fixtures.UCL, t("2026-11-03T17:45Z"), Fixtures.PRE));
        all.add(match("uLater", Fixtures.UCL, t("2026-11-04T20:00Z"), Fixtures.PRE));
        Fixtures.Match jor = match("tNext", Fixtures.TIMNAS, t("2026-11-13T20:00Z"), Fixtures.PRE);
        jor.timeValid = false;
        jor.detail = "TBD";
        all.add(jor);
        all.add(match("tLater", Fixtures.TIMNAS, t("2027-01-11T20:00Z"), Fixtures.PRE));
        // Liga 1 has nothing at all: no row.
        List<Object[]> rows = Fixtures.tickerRows(all, now);
        assertEquals(2, rows.size());
        assertEquals("Timnas", rows.get(0)[0]);
        assertEquals("AtNext", rows.get(0)[1]);
        assertEquals("pre", rows.get(0)[4]);
        assertEquals("TBD", rows.get(0)[7]);
        assertEquals("UCL", rows.get(1)[0]);
        assertEquals("AuNext", rows.get(1)[1]);
        assertEquals(t("2026-11-03T17:45Z"), rows.get(1)[3]);

        // A competition with a match in the window gets no extra "next" row.
        all.add(match("uIn", Fixtures.UCL, now + DAY, Fixtures.PRE));
        rows = Fixtures.tickerRows(all, now);
        assertEquals(2, rows.size());
        assertEquals("AuIn", rows.get(1)[1]);
    }

    @Test
    public void detailIsEspnShortDetail() throws Exception {
        assertEquals("FT", Fixtures.parse(res("ucl_scoreboard.json"), Fixtures.UCL).get(0).detail);
        assertEquals("TBD", Fixtures.parse(res("timnas_fixtures.json"), Fixtures.TIMNAS).get(0).detail);
    }

    @Test
    public void cacheRoundTrip() throws Exception {
        List<Fixtures.Match> l = Fixtures.parse(res("timnas_fixtures.json"), Fixtures.TIMNAS);
        l.get(0).state = Fixtures.LIVE;
        l.get(0).clock = "12'";
        l.get(0).detail = "12'";
        List<Fixtures.Match> back = Fixtures.fromJson(Fixtures.toJson(l));
        assertEquals(l.size(), back.size());
        Fixtures.Match a = l.get(0), b = back.get(0);
        assertEquals(a.id, b.id);
        assertEquals(a.kickoff, b.kickoff);
        assertEquals(a.home, b.home);
        assertEquals(a.timeValid, b.timeValid);
        assertEquals(Fixtures.LIVE, b.state);
        assertEquals("12'", b.clock);
        assertEquals("12'", b.detail);
        assertEquals("fifa.friendly", b.league);
        assertEquals(0, Fixtures.fromJson("not json").size());
        assertEquals(0, Fixtures.fromJson(null).size());
    }
}
