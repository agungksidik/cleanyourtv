package com.gathin.photodream;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/**
 * Today's album pictures for a Home that shows them in a panel, read only, and only for apps signed with this
 * app's key (the READ_ALBUM signature permission):
 *
 *   content://com.gathin.photodream.album/pictures              one row per picture: name
 *   content://com.gathin.photodream.album/pictures/<name>       the picture
 */
public class AlbumProvider extends ContentProvider {

    private File pictures() {
        File dir = getContext() == null ? null : getContext().getExternalFilesDir(null);
        return dir == null ? null : new File(dir, Media.PICTURES);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor rows = new MatrixCursor(new String[]{"name"});
        File folder = pictures();
        List<String> segments = uri.getPathSegments();
        if (folder == null || segments.size() != 1 || !Media.PICTURES.equals(segments.get(0))) return rows;
        String[] names = folder.list((d, name) -> name.startsWith(Album.PREFIX) && Media.image(name));
        if (names == null) return rows;
        Arrays.sort(names);
        for (String name : names) rows.addRow(new Object[]{name});
        return rows;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        File folder = pictures();
        if (!"r".equals(mode) || folder == null || segments.size() != 2 || !Media.PICTURES.equals(segments.get(0))
                || !segments.get(1).startsWith(Album.PREFIX) || !Media.image(segments.get(1))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(new File(folder, segments.get(1)), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
