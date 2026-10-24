package com.gathin.photodream;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Once a day, today's pictures from the shared album in album.txt: the album's list is read page by page, the
 * day's choice ({@link Album#pick}) is downloaded into pictures/ and yesterday's album pictures are removed.
 * Pictures that did not come from the album are left alone. Nothing happens without album.txt.
 */
final class AlbumSync {
    static final String FILE = "album.txt";
    private static final String RPC = "https://photos.google.com/_/PhotosUi/data/batchexecute?rpcids=snAcKc&rt=c";
    private static final int MAX_PAGES = 40;
    private static final int TIMEOUT_MS = 20_000;
    /** After a failure, Home or the screensaver try again only after this long; the job keeps its own pace. */
    private static final long RETRY_MS = 30 * 60_000L;
    private static final AtomicBoolean busy = new AtomicBoolean();

    private AlbumSync() {}

    /** Starts a sync off the main thread if today's pictures are not there yet. */
    static void startIfDue(Context context) {
        Context app = context.getApplicationContext();
        if (link(app) == null) return;
        AlbumJob.schedule(app);
        SharedPreferences state = state(app);
        long failed = state.getLong("failed_at", 0);
        long since = System.currentTimeMillis() - failed;
        if (since >= 0 && since < RETRY_MS) return;
        new Thread(() -> syncIfDue(app), "album").start();
    }

    /** True when today's pictures are there (or there is no album); false when it should be tried again. */
    static boolean syncIfDue(Context context) {
        String link = link(context);
        if (link == null) return true;
        String today = LocalDate.now().toString();
        SharedPreferences state = state(context);
        if (today.equals(state.getString("day", "")) && link.equals(state.getString("link", ""))) return true;
        if (!busy.compareAndSet(false, true)) return true;
        try {
            sync(context, link);
            state.edit().putString("day", today).putString("link", link).remove("failed_at").apply();
            return true;
        } catch (Exception e) {
            state.edit().putLong("failed_at", System.currentTimeMillis()).apply();
            return false;
        } finally {
            busy.set(false);
        }
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences("album", Context.MODE_PRIVATE);
    }

    /** The album address from album.txt, or null. */
    static String link(Context context) {
        File dir = context.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, FILE);
        try {
            if (f == null || !f.isFile() || f.length() > 1024) return null;
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim();
            return text.startsWith("https://") ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sync(Context context, String link) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) throw new Exception("no files dir");
        File pictures = new File(dir, Media.PICTURES);
        if (!pictures.isDirectory() && !pictures.mkdirs()) throw new Exception("no pictures dir");

        String[] share = Album.share(resolve(link));
        if (share == null) throw new Exception("not a shared album");
        List<Album.Photo> all = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Album.Page p = Album.parse(post(Album.request(share[0], share[1], token)));
            all.addAll(p.photos);
            token = p.next;
            if (token == null) break;
        }
        if (all.isEmpty()) throw new Exception("empty album");

        List<Album.Photo> today = Album.pick(all, LocalDate.now());
        Set<String> keep = new HashSet<>();
        boolean complete = true;
        for (Album.Photo photo : today) {
            File target = new File(pictures, photo.fileName());
            keep.add(target.getName());
            if (target.isFile() && target.length() > 0) continue;
            try {
                download(photo.downloadUrl(), target);
            } catch (Exception e) {
                complete = false;   // the rest still come; this one is tried again later
            }
        }
        File[] old = pictures.listFiles((d, name) -> name.startsWith(Album.PREFIX));
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
        if (!complete) throw new Exception("some pictures missing");
    }

    /** A photos.app.goo.gl link followed to its photos.google.com/share address. */
    private static String resolve(String link) throws Exception {
        String url = link;
        for (int hop = 0; hop < 5 && Album.share(url) == null; hop++) {
            HttpURLConnection c = open(url);
            c.setInstanceFollowRedirects(false);
            try {
                c.getResponseCode();
                String location = c.getHeaderField("Location");
                if (location == null) break;
                url = new URL(new URL(url), location).toString();
            } finally {
                c.disconnect();
            }
        }
        return url;
    }

    private static String post(String request) throws Exception {
        HttpURLConnection c = open(RPC);
        try {
            byte[] body = ("f.req=" + URLEncoder.encode(request, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                copy(in, bytes, 8L * 1024 * 1024);
            }
            return bytes.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Written to a .part file first, so the slideshow never picks up half a picture. */
    private static void download(String url, File target) throws Exception {
        File part = new File(target.getPath() + ".part");
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                copy(in, out, Media.IMAGE_MAX);
            }
            if (!part.renameTo(target)) throw new Exception("rename failed");
        } finally {
            c.disconnect();
            part.delete();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        return c;
    }

    private static void copy(InputStream in, OutputStream out, long max) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > max) throw new Exception("too big");
            out.write(buffer, 0, n);
        }
    }
}
