package com.gathin.photodream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AlbumTest {

    private static Album.Photo photo(String id, String day) {
        return new Album.Photo(id, "https://lh3.googleusercontent.com/pw/" + id, LocalDate.parse(day));
    }

    @Test public void shareAddressGivesAlbumAndKey() {
        assertArrayEquals(new String[]{"AF1Qip-x_1", "Tk_2-y"},
                Album.share("https://photos.google.com/share/AF1Qip-x_1?key=Tk_2-y"));
        assertArrayEquals(new String[]{"AF1Qip", "k"}, Album.share("https://photos.google.com/u/0/share/AF1Qip?key=k"));
        assertNull(Album.share("https://photos.app.goo.gl/YbTR9"));
        assertNull(Album.share(null));
    }

    @Test public void fileNameEndsInTheDateTaken() {
        Album.Photo p = photo("AF1QipOAtmsf63DETN7ggwGcs7Pm9wJelTjnlpt2Vmlp", "2025-10-24");
        assertEquals("album-lTjnlpt2Vmlp-20251024.jpg", p.fileName());
        assertTrue(Media.image(p.fileName()));
        assertArrayEquals(new int[]{2025, 10}, Media.dateFromName(p.fileName()));
        assertEquals("https://lh3.googleusercontent.com/pw/AF1Qip=w1280-h720", photo("AF1Qip", "2025-01-01").downloadUrl());
    }

    @Test public void parseSkipsVideosAndReadsTheLocalDay() throws Exception {
        // Taken 2025-10-23 20:00 UTC, seven hours ahead (Jakarta): 24 October where it was taken.
        long taken = LocalDate.parse("2025-10-23").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                + 20 * 3_600_000L;
        String data = "[null,[" +
                "[\"AF1QipA\",[\"https://lh3.googleusercontent.com/pw/A\",3072,4534],"
                + taken + ",\"x\",25200000,1,[],[],2,{\"15\":1}]," +
                "[\"AF1QipB\",[\"https://lh3.googleusercontent.com/pw/B\",1080,1920],"
                + taken + ",\"y\",25200000,1,[],[],2,{\"15\":1,\"76647426\":[1]}]" +
                "],\"NEXT\"]";
        JSONArray line = new JSONArray().put(new JSONArray().put("wrb.fr").put("snAcKc").put(data));
        Album.Page page = Album.parse(")]}'\n\n123\n" + line + "\n45\n[[\"di\",1]]\n");
        assertEquals(1, page.photos.size());
        assertEquals("AF1QipA", page.photos.get(0).id);
        assertEquals(LocalDate.parse("2025-10-24"), page.photos.get(0).day);
        assertEquals("NEXT", page.next);

        JSONArray last = new JSONArray().put(new JSONArray().put("wrb.fr").put("snAcKc").put("[null,[],null]"));
        assertNull(Album.parse(last.toString()).next);
    }

    @Test public void requestCarriesAlbumTokenAndKey() throws Exception {
        JSONArray r = new JSONArray(Album.request("ALB", "KEY", null));
        JSONArray args = new JSONArray(r.getJSONArray(0).getJSONArray(0).getString(1));
        assertEquals("snAcKc", r.getJSONArray(0).getJSONArray(0).getString(0));
        assertEquals("ALB", args.getString(0));
        assertTrue(args.isNull(1));
        assertEquals("KEY", args.getString(3));
    }

    @Test public void distanceWrapsRoundTheYear() {
        LocalDate today = LocalDate.parse("2026-01-02");
        assertEquals(0, Album.distance(today, LocalDate.parse("2020-01-02")));
        assertEquals(4, Album.distance(today, LocalDate.parse("2024-12-29")));
        assertEquals(1, Album.distance(LocalDate.parse("2026-12-31"), LocalDate.parse("2025-01-01")));
    }

    @Test public void todaysDateInEarlierYearsComesFirst() {
        LocalDate today = LocalDate.parse("2026-10-24");
        List<Album.Photo> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) all.add(photo("same" + i, (2014 + i) + "-10-24"));
        all.add(photo("thisYear", "2026-10-24"));
        all.add(photo("near", "2025-10-22"));
        List<Album.Photo> picked = Album.pick(all, today);
        assertEquals(12, picked.size());
        for (Album.Photo p : picked) assertTrue(p.id.startsWith("same"));
    }

    @Test public void widensStepByStepWhenTheDayHasFew() {
        LocalDate today = LocalDate.parse("2026-10-24");
        List<Album.Photo> all = new ArrayList<>(Arrays.asList(
                photo("exact", "2025-10-24"),
                photo("three", "2024-10-27"),
                photo("week", "2020-10-17"),
                photo("month", "2020-10-02"),
                photo("thisYear", "2026-10-23")));
        for (int i = 0; i < 20; i++) all.add(photo("other" + i, "2025-03-" + (10 + i)));
        List<Album.Photo> picked = Album.pick(all, today);
        assertEquals(Album.MIN, picked.size());
        assertEquals(Arrays.asList("exact", "three", "week", "month"),
                Arrays.asList(picked.get(0).id, picked.get(1).id, picked.get(2).id, picked.get(3).id));
    }

    @Test public void atMostThirtyAndTheSameAllDay() {
        LocalDate today = LocalDate.parse("2026-10-24");
        List<Album.Photo> all = new ArrayList<>();
        for (int i = 0; i < 80; i++) all.add(photo("p" + i, (2000 + i % 20) + "-10-24"));
        List<Album.Photo> a = Album.pick(all, today);
        assertEquals(Album.MAX, a.size());
        List<Album.Photo> b = Album.pick(all, today);
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).id, b.get(i).id);
    }
}
