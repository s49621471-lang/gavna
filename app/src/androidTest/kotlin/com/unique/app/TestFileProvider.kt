package com.unique.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * A provider belonging to the *test* app, so a guest can be handed a URI from outside.
 *
 * The test APK is a separate package with a separate uid. That is the whole point: nothing
 * inside UNIQUE could stand in for an external app granting a URI, because the grant is
 * checked against the calling uid and UNIQUE's own components share UNIQUE's.
 *
 * Not part of the product. It exists so `t36` can ask a real question.
 */
class TestFileProvider : ContentProvider() {

    /**
     * The file is created here, in this app's own process, under this app's own uid.
     *
     * Not in [openFile]: an exception thrown out of a provider call cannot cross the
     * Binder — `Uncaught remote exception! Exceptions are not yet supported across
     * processes` — and the caller sees the provider die rather than a reason. Doing the
     * work that can fail at creation time keeps `openFile` down to one operation whose
     * only failure mode is `FileNotFoundException`, which Binder *can* carry.
     */
    override fun onCreate(): Boolean {
        runCatching {
            val context = context ?: return@runCatching
            File(context.filesDir.apply { mkdirs() }, INBOUND_FILE)
                .writeText(INBOUND_CONTENT)
        }
        return true
    }

    @Throws(java.io.FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw java.io.FileNotFoundException("no context")
        val file = File(context.filesDir, INBOUND_FILE)
        if (!file.isFile) throw java.io.FileNotFoundException("$file was not created")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val AUTHORITY = "com.unique.test.files"
        const val INBOUND_FILE = "inbound.txt"
        const val INBOUND_CONTENT = "handed-in-from-outside"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY/$INBOUND_FILE")
    }
}
