package com.gathin.photodream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shared Google Photos album, read the way its web page reads itself (the snAcKc call), and the pictures for
 * one day: taken on today's date in earlier years, widened to a few days around it or the same month when that
 * gives fewer than {@link #MIN}, and filled from the whole album when even that is short. No network here.
 */
final class Album {
    static final int MIN = 10;
    static final int MAX = 30;
    static final String PREFIX = "album-";
    /** The item field Google Photos sets on videos; they are left out. */
    private static final String VIDEO_KEY = "76647426";

    private static final Pattern SHARE = Pattern.compile(
            "https://photos\\.google\\.com/(?:u/\\d+/)?share/([A-Za-z0-9_-]+)\\?key=([A-Za-z0-9_-]+)");
    private static final Pattern NOT_NAME = Pattern.compile("[^A-Za-z0-9_-]");

    private Album() {}

    static final class Photo {
        final String id;
        final String url;
        /** The day it was taken, in the place it was taken. */
        final LocalDate day;

        Photo(String id, String url, LocalDate day) {
            this.id = id;
            this.url = url;
            this.day = day;
        }

        /** album-<id>-20251024.jpg: the date at the end is what Media.dateFromName reads for the caption. */
        String fileName() {
            String safe = NOT_NAME.matcher(id).replaceAll("");
            if (safe.length() > 12) safe = safe.substring(safe.length() - 12);
            return String.format(java.util.Locale.ROOT, "%s%s-%04d%02d%02d.jpg",
                    PREFIX, safe, day.getYear(), day.getMonthValue(), day.getDayOfMonth());
        }

        /** The picture at most 1280 x 720, which is what the TV shows. */
        String downloadUrl() {
            return url + "=w1280-h720";
        }
    }

    /** One page of the album and the token for the next, null on the last. */
    static final class Page {
        final List<Photo> photos;
        final String next;

        Page(List<Photo> photos, String next) {
            this.photos = photos;
            this.next = next;
        }
    }

    /** {album id, key} from a photos.google.com/share address, or null. */
    static String[] share(String url) {
        Matcher m = url == null ? null : SHARE.matcher(url);
        return m != null && m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    /** The form body that asks for one page; token null for the first. */
    static String request(String albumId, String key, String token) {
        JSONArray args = new JSONArray();
        args.put(albumId);
        args.put(token == null ? JSONObject.NULL : token);
        args.put(JSONObject.NULL);
        args.put(key);
        JSONArray call = new JSONArray();
        call.put("snAcKc");
        call.put(args.toString());
        call.put(JSONObject.NULL);
        call.put("generic");
        JSONArray outer = new JSONArray();
        JSONArray calls = new JSONArray();
        calls.put(call);
        outer.put(calls);
        return outer.toString();
    }

    /** Reads a batchexecute answer; an item that does not look like a photo is skipped. */
    static Page parse(String body) throws Exception {
        int start = body.indexOf("[[\"wrb.fr\"");
        if (start < 0) throw new Exception("no album data");
        int end = body.indexOf('\n', start);
        JSONArray outer = new JSONArray(end < 0 ? body.substring(start) : body.substring(start, end));
        JSONArray data = new JSONArray(outer.getJSONArray(0).getString(2));
        List<Photo> photos = new ArrayList<>();
        JSONArray items = data.optJSONArray(1);
        for (int i = 0; items != null && i < items.length(); i++) {
            Photo p = photo(items.optJSONArray(i));
            if (p != null) photos.add(p);
        }
        String next = data.isNull(2) ? null : data.optString(2, null);
        return new Page(photos, next == null || next.isEmpty() ? null : next);
    }

    private static Photo photo(JSONArray item) {
        try {
            Object last = item.get(item.length() - 1);
            if (last instanceof JSONObject && ((JSONObject) last).has(VIDEO_KEY)) return null;
            String id = item.getString(0);
            String url = item.getJSONArray(1).getString(0);
            if (!url.startsWith("https://lh3.googleusercontent.com/")) return null;
            long taken = item.getLong(2) + item.optLong(4, 0);
            LocalDate day = Instant.ofEpochMilli(taken).atZone(ZoneOffset.UTC).toLocalDate();
            return new Photo(id, url, day);
        } catch (Exception e) {
            return null;
        }
    }

    /** Days between today and the photo's date of the year, the nearest way round the turn of the year. */
    static long distance(LocalDate today, LocalDate taken) {
        MonthDay md = MonthDay.from(taken);
        long best = Long.MAX_VALUE;
        for (int y = today.getYear() - 1; y <= today.getYear() + 1; y++) {
            best = Math.min(best, Math.abs(ChronoUnit.DAYS.between(today, md.atYear(y))));
        }
        return best;
    }

    /** Today's pictures, the same choice all day. */
    static List<Photo> pick(List<Photo> all, LocalDate today) {
        Random random = new Random(today.toEpochDay());
        List<Photo> chosen = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<Predicate<Photo>> tiers = new ArrayList<>();
        tiers.add(p -> distance(today, p.day) == 0);
        tiers.add(p -> distance(today, p.day) <= 3);
        tiers.add(p -> distance(today, p.day) <= 7);
        tiers.add(p -> p.day.getMonth() == today.getMonth());
        for (Predicate<Photo> tier : tiers) {
            if (chosen.size() >= MIN) break;
            add(all, p -> p.day.getYear() < today.getYear() && tier.test(p), chosen, ids, random, MAX);
        }
        if (chosen.size() < MIN) add(all, p -> true, chosen, ids, random, MIN);
        return chosen;
    }

    private static void add(List<Photo> all, Predicate<Photo> which, List<Photo> chosen, Set<String> ids,
                            Random random, int upTo) {
        List<Photo> more = new ArrayList<>();
        for (Photo p : all) if (!ids.contains(p.id) && which.test(p)) more.add(p);
        Collections.shuffle(more, random);
        for (Photo p : more) {
            if (chosen.size() >= upTo) return;
            chosen.add(p);
            ids.add(p.id);
        }
    }
}
