package io.github.agungksidik.football;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

/**
 * The stored matches for a Home ticker, read only, and only for apps signed with this app's key (the
 * READ_MATCHES signature permission):
 *
 *   content://io.github.agungksidik.football.matches/matches
 *
 * One row per match from yesterday through the next 7 days (WIB): Timnas first, then the rest by kickoff.
 * Columns are {@link Fixtures#TICKER_COLUMNS}. Nothing is fetched here; {@link MatchJob} and the page do that.
 */
public class MatchProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.agungksidik.football.matches";
    static final Uri MATCHES = Uri.parse("content://" + AUTHORITY + "/matches");

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor rows = new MatrixCursor(Fixtures.TICKER_COLUMNS);
        List<String> segments = uri.getPathSegments();
        if (getContext() == null || segments.size() != 1 || !"matches".equals(segments.get(0))) return rows;
        for (Object[] row : Fixtures.tickerRows(Sync.loadAll(getContext()), System.currentTimeMillis())) {
            rows.addRow(row);
        }
        rows.setNotificationUri(getContext().getContentResolver(), MATCHES);
        return rows;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
