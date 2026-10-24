package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * photo_panel: today's pictures from the photo screensaver add-on's shared album (its AlbumProvider, open only
 * to apps signed with the same key) as a carousel between the top buttons and the dock: three narrow cards and
 * one wide one on the right with the date it was taken. Every few seconds a new picture comes in on the right
 * and the others move one card to the left. Gone when the add-on has no pictures; faded out while the grid is
 * scrolled down over it. Reads files only: the launcher still has no network.
 */
final class PhotoPanel {
    private static final Uri PICTURES = Uri.parse("content://com.gathin.photodream.album/pictures");
    private static final long INTERVAL_MS = 9_000L;
    private static final int SMALL = 3;
    private static final Pattern DATE = Pattern.compile("-(\\d{8})\\.[A-Za-z]+$");

    /** One rounded card: two images that crossfade, the new one sliding in from the right. */
    private final class Card {
        final FrameLayout box;
        final ImageView[] images = new ImageView[2];
        int front;
        Bitmap shown;

        Card() {
            box = new FrameLayout(context);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(16 * dp);
            frame.setColor(0x40000000);
            box.setBackground(frame);
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 16 * dp);
                }
            });
            box.setClipToOutline(true);
            box.setElevation(6 * dp);
            for (int i = 0; i < 2; i++) {
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setAlpha(0f);
                box.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                images[i] = image;
            }
            panel.addView(box, new FrameLayout.LayoutParams(0, 0));
        }

        void show(Bitmap bitmap, boolean animate) {
            shown = bitmap;
            ImageView old = images[front];
            front = 1 - front;
            ImageView next = images[front];
            next.animate().cancel();
            next.setImageBitmap(bitmap);
            next.bringToFront();
            for (int i = 0; i < box.getChildCount(); i++) {
                View child = box.getChildAt(i);
                if (child != images[0] && child != images[1]) child.bringToFront();
            }
            if (!animate) {
                next.setAlpha(1f);
                next.setTranslationX(0f);
                old.setAlpha(0f);
                old.setImageDrawable(null);
                return;
            }
            next.setAlpha(0f);
            next.setTranslationX(40 * dp);
            next.animate().alpha(1f).translationX(0f).setDuration(650).withEndAction(() -> {
                old.setAlpha(0f);
                old.setImageDrawable(null);
            }).start();
        }
    }

    private final Context context;
    private final FrameLayout panel;
    private final List<Card> cards = new ArrayList<>();
    private final Card wide;
    private final TextView label;
    private final View topButtons;
    private final RecyclerView grid;
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::showNext;
    private ExecutorService decoder;
    private List<String> names = new ArrayList<>();
    private int index = -1;
    private final View above;
    private float fadeTarget = 1f;
    private boolean running;
    /** The first pictures are being decoded; cleared by stop, so a pause halfway lets the next resume try again. */
    private boolean filling;
    /** place() found room: the panel shows only then, and only with pictures. */
    private boolean fits;

    PhotoPanel(Context context, FrameLayout root, RecyclerView grid, View topButtons, View contentLayer, View above) {
        this.context = context;
        this.above = above;
        this.grid = grid;
        this.topButtons = topButtons;
        dp = context.getResources().getDisplayMetrics().density;

        panel = new FrameLayout(context);
        panel.setFocusable(false);
        panel.setClipChildren(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.setVisibility(View.GONE);
        for (int i = 0; i <= SMALL; i++) cards.add(new Card());
        wide = cards.get(SMALL);

        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        wide.box.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(64 * dp), Gravity.BOTTOM));
        label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(0xF2FFFFFF);
        label.setSingleLine(true);
        try {
            label.setTypeface(UiFont.get(context, R.string.font_medium));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.round(18 * dp);
        lp.bottomMargin = Math.round(14 * dp);
        wide.box.addView(label, lp);

        // Under the header and the grid, over the wallpaper.
        root.addView(panel, Math.max(0, root.indexOfChild(contentLayer)),
                new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));

        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> grid.post(this::place));
        if (above != null) above.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> grid.post(this::place));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // The first focus nudges the grid a few pixels; only a real move down to the rows hides the panel.
                float want = rv.computeVerticalScrollOffset() <= 40 * dp ? 1f : 0f;
                // Compared with where the fade is heading, not the alpha now: a quick down-and-back would
                // otherwise leave a fade-out running to the end.
                if (fadeTarget != want) {
                    fadeTarget = want;
                    panel.animate().cancel();
                    panel.animate().alpha(want).setDuration(200).start();
                }
            }
        });
    }

    /** From under the top buttons down to just above the dock, across the grid's width. */
    private void place() {
        View parent = (View) panel.getParent();
        int[] at = new int[2];
        int[] origin = new int[2];
        parent.getLocationInWindow(origin);
        topButtons.getLocationInWindow(at);
        int top = at[1] - origin[1] + topButtons.getHeight() + Math.round(52 * dp);
        if (above != null && above.getVisibility() == View.VISIBLE && above.getHeight() > 0) {
            above.getLocationInWindow(at);
            top = Math.max(top, at[1] - origin[1] + above.getHeight() + Math.round(18 * dp));
        }
        grid.getLocationInWindow(at);
        int dockTop = at[1] - origin[1] + grid.getPaddingTop() - Math.round(18 * dp);
        int left = at[0] - origin[0] + grid.getPaddingLeft() + Math.round(8 * dp);
        int right = at[0] - origin[0] + grid.getWidth() - grid.getPaddingRight() - Math.round(8 * dp);
        int height = dockTop - top;
        fits = height >= 100 * dp;
        if (!fits) {
            panel.setVisibility(View.GONE);
            return;
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (p.width != right - left || p.height != height || p.topMargin != top || p.leftMargin != left) {
            p.width = right - left;
            p.height = height;
            p.topMargin = top;
            p.leftMargin = left;
            panel.setLayoutParams(p);
        }
        int gap = Math.round(16 * dp);
        int wideW = Math.min(Math.round(height * 16f / 10f), Math.round(p.width * 0.48f));
        int smallW = (p.width - wideW - SMALL * gap) / SMALL;
        for (int i = 0; i < cards.size(); i++) {
            FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) cards.get(i).box.getLayoutParams();
            int w = i == SMALL ? wideW : smallW;
            int x = i == SMALL ? p.width - wideW : i * (smallW + gap);
            if (c.width == w && c.height == height && c.leftMargin == x) continue;
            c.width = w;
            c.height = height;
            c.leftMargin = x;
            cards.get(i).box.setLayoutParams(c);
        }
        show();
    }

    /** Visible once there is room and pictures; the first four come in once the cards have their size. */
    private void show() {
        panel.setVisibility(fits && !names.isEmpty() ? View.VISIBLE : View.GONE);
        if (running && fits && !names.isEmpty() && wide.shown == null && wide.box.getLayoutParams().width > 0) fill();
    }

    void start() {
        if (running) return;
        running = true;
        if (decoder == null) decoder = Executors.newSingleThreadExecutor();
        decoder.execute(() -> {
            List<String> found = list();
            // A day's order, mixed: burst shots of one moment sit next to each other by name.
            java.util.Collections.shuffle(found, new java.util.Random(LocalDate.now().toEpochDay()));
            handler.post(() -> {
                if (!running) return;
                if (!found.equals(names)) {
                    names = found;
                    index = -1;
                    for (Card card : cards) card.shown = null;
                }
                if (wide.shown != null) handler.postDelayed(advance, INTERVAL_MS);
                show();
            });
        });
    }

    void stop() {
        running = false;
        filling = false;
        handler.removeCallbacks(advance);
        if (decoder != null) decoder.shutdownNow();
        decoder = null;
    }

    private List<String> list() {
        List<String> result = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(PICTURES, null, null, null, null)) {
            while (c != null && c.moveToNext()) result.add(c.getString(0));
        } catch (RuntimeException e) {
            // No add-on, or it is not signed with our key: no panel.
        }
        return result;
    }

    /** All four cards at once, without motion: the first pictures of the day. */
    private void fill() {
        ExecutorService d = decoder;
        if (d == null || filling) return;
        filling = true;
        int w = Math.max(1, wide.box.getLayoutParams().width), h = Math.max(1, wide.box.getLayoutParams().height);
        List<String> first = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) first.add(names.get((i) % names.size()));
        index = cards.size() - 1;
        try {
            d.execute(() -> {
                List<Bitmap> bitmaps = new ArrayList<>();
                for (String name : first) bitmaps.add(decode(name, w, h));
                handler.post(() -> {
                    if (!running) return;
                    filling = false;
                    for (int i = 0; i < cards.size(); i++) {
                        if (bitmaps.get(i) != null) cards.get(i).show(bitmaps.get(i), false);
                    }
                    label.setText(date(first.get(SMALL)));
                    handler.postDelayed(advance, INTERVAL_MS);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopped meanwhile.
        }
    }

    /** A new picture on the wide card; every other picture moves one card to the left, the leftmost leaves. */
    private void showNext() {
        ExecutorService d = decoder;
        if (!running || names.isEmpty() || d == null) return;
        index = (index + 1) % names.size();
        String name = names.get(index);
        int w = Math.max(1, wide.box.getLayoutParams().width), h = Math.max(1, wide.box.getLayoutParams().height);
        try {
            d.execute(() -> {
                Bitmap bitmap = decode(name, w, h);
                handler.post(() -> {
                    if (!running) return;
                    if (bitmap != null) {
                        for (int i = 0; i < SMALL; i++) {
                            Bitmap moving = cards.get(i + 1).shown;
                            if (moving != null) cards.get(i).show(moving, true);
                        }
                        wide.show(bitmap, true);
                        label.setText(date(name));
                    }
                    handler.postDelayed(advance, INTERVAL_MS);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopped meanwhile.
        }
    }

    private Bitmap decode(String name, int w, int h) {
        Uri uri = PICTURES.buildUpon().appendPath(name).build();
        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            try (ParcelFileDescriptor again = context.getContentResolver().openFileDescriptor(uri, "r")) {
                return again == null ? null : BitmapFactory.decodeFileDescriptor(again.getFileDescriptor(), null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** "24 Okt 2024" from album-<id>-20241024.jpg, in the TV's language; "" without a date. */
    static String date(String name) {
        Matcher m = DATE.matcher(name);
        if (!m.find()) return "";
        try {
            LocalDate day = LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE);
            return day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()));
        } catch (RuntimeException e) {
            return "";
        }
    }
}
