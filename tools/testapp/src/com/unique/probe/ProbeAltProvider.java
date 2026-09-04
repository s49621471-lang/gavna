package com.unique.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * The same as {@link ProbeProvider}, but declared with android:process=":alt".
 *
 * That one manifest attribute is the whole point: the platform puts this provider in a
 * different process from the activity that queries it, so a query has to cross a process
 * boundary and come back. Plenty of ordinary apps are shaped this way - a sync provider
 * kept out of the UI process is the usual reason - and until UNIQUE could route between
 * its own :vappN slots, this shape did not work at all.
 */
public class ProbeAltProvider extends ContentProvider {
    private static final String TAG = ProbeApplication.TAG;
    public static final String AUTHORITY = "com.unique.probe.altprovider";

    @Override
    public boolean onCreate() {
        Log.i(TAG, "AltProvider.onCreate pid=" + android.os.Process.myPid()
                + " package=" + (getContext() == null ? "?" : getContext().getPackageName()));
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
        cursor.addRow(new Object[]{"processName", processName()});
        return cursor;
    }

    /** The name the platform gave this process, read back rather than assumed. */
    private String processName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return android.app.Application.getProcessName();
            }
        } catch (Throwable t) {
            Log.w(TAG, "process name unavailable", t);
        }
        return "?";
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/probe-alt"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
