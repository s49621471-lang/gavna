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

    /** The file this provider hands out, written on demand so a share has something real. */
    public static final String SHARED_FILE = "shared.txt";
    public static final String SHARED_CONTENT = "unique-shared-payload";

    /**
     * Serves {@link #SHARED_FILE} out of the app's own storage.
     *
     * This is what a FileProvider does, without the androidx dependency: an app hands out
     * a content:// URI and the receiver opens it. The whole question for virtualization is
     * whether a receiver *outside* the virtual app can.
     */
    @Override
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (getContext() == null) throw new java.io.FileNotFoundException("no context");
        java.io.File f = new java.io.File(getContext().getFilesDir(), SHARED_FILE);
        if (!f.isFile()) {
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
                out.write(SHARED_CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.close();
            } catch (java.io.IOException e) {
                throw new java.io.FileNotFoundException("could not create " + f + ": " + e);
            }
        }
        Log.i(TAG, "openFile " + uri + " -> " + f.getAbsolutePath());
        return android.os.ParcelFileDescriptor.open(
                f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/probe"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
