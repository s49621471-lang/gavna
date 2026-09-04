package com.unique.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * A trivial provider that answers with the identity it sees.
 *
 * Providers matter for virtualization beyond their own feature: the platform instantiates
 * them during process start, before any other component, so they are the earliest point
 * at which guest code runs.
 */
public class ProbeProvider extends ContentProvider {
    private static final String TAG = ProbeApplication.TAG;
    public static final String AUTHORITY = "com.unique.probe.provider";

    @Override
    public boolean onCreate() {
        Log.i(TAG, "Provider.onCreate package="
                + (getContext() == null ? "?" : getContext().getPackageName()));
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        String pkg = getContext() == null ? "?" : getContext().getPackageName();
        String files = getContext() == null ? "?" : getContext().getFilesDir().getAbsolutePath();
        cursor.addRow(new Object[]{"packageName", pkg});
        cursor.addRow(new Object[]{"filesDir", files});
        cursor.addRow(new Object[]{"pid", String.valueOf(android.os.Process.myPid())});
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/probe"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
