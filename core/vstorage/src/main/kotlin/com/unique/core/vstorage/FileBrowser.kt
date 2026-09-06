package com.unique.core.vstorage

import android.content.Context
import com.unique.core.common.path.VirtualPathModel
import java.io.File
import java.io.InputStream

/**
 * The instance's own filesystem, as a file manager sees it.
 *
 * ## Why UNIQUE needs one
 *
 * A guest's directories are real directories inside UNIQUE's private storage, and from
 * outside they are unreachable: `/data/data/com.unique/files/virtual/users/<vuid>/…` is
 * UNIQUE's own data directory, which no file manager on the phone can open. So the files
 * a virtual app owns were, until this class, visible to nothing — not the app's user, not
 * a file manager, not anything but the app itself.
 *
 * That is a gap a real device does not have, and it is also the answer to the one problem
 * UNIQUE cannot solve any other way. Since Android 11 the platform hides `Android/data`
 * and `Android/obb` from every app, **including one holding all-files access**, so
 * UNIQUE cannot copy a game's expansion files in on its own however many permissions the
 * user grants. What it can do is let the user put the file where it belongs, from
 * wherever they already have it. That is [importInto].
 *
 * ## The tree it shows
 *
 * Exactly the two roots a guest sees, under the names the guest sees them by:
 *
 * ```
 * /data/data/<package>        the app's private data      (files, databases, shared_prefs)
 * /sdcard                     the app's external storage  (Android/obb/<pkg>, Android/data/<pkg>)
 * ```
 *
 * Those are the paths the *app* uses, so a user following a game's own instructions —
 * "put the .obb in Android/obb/com.example.game" — ends up in the right place. The real
 * location on disk is never shown, because it is not a fact about the app and typing it
 * anywhere would be wrong.
 *
 * ## Staying inside the instance
 *
 * Every path from the UI is resolved against the instance's roots and then checked with
 * [File.getCanonicalPath], so `../../..` and a symlink pointing out both land outside the
 * root and are refused. This is the one class in the engine that takes a filesystem path
 * from the interface, and it is the reason that check is not optional.
 */
class FileBrowser(filesRoot: String) {

    /**
     * The path form is the primary one so this can be tested against a temporary
     * directory: every rule here is about which paths are inside an instance and which
     * are not, and a test that cannot construct a root cannot check the ones that are not.
     */
    constructor(context: Context) : this(
        (context.applicationContext ?: context).filesDir.absolutePath,
    )

    private val model = VirtualPathModel(filesRoot)

    /** One of the two trees an instance has, named the way the guest names it. */
    data class Root(val label: String, val guestPath: String, val realPath: String)

    data class Entry(
        val name: String,
        /** The guest-visible path, which is what the UI navigates by. */
        val path: String,
        val isDirectory: Boolean,
        val bytes: Long,
        val modified: Long,
        /** Children, for a directory. -1 when it could not be listed. */
        val children: Int,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "name" to name,
            "path" to path,
            "isDirectory" to isDirectory,
            "bytes" to bytes,
            "modified" to modified,
            "children" to children,
        )
    }

    fun roots(vuid: Int, packageName: String): List<Root> = listOf(
        Root("/data/data/$packageName", "/data/data/$packageName", model.dataDir(vuid, packageName)),
        Root("/sdcard", "/sdcard", model.externalRoot(vuid)),
    )

    /**
     * Lists one directory, creating the instance's roots if the app has not run yet.
     *
     * An instance that has never been launched has no directories at all, and showing the
     * user an error for that would be describing an implementation detail. The roots are
     * created instead, empty, which is what they would look like after the first launch.
     */
    fun list(vuid: Int, packageName: String, guestPath: String): List<Entry> {
        val target = resolve(vuid, packageName, guestPath) ?: return emptyList()
        if (!target.exists()) target.mkdirs()
        val children = target.listFiles() ?: return emptyList()
        return children
            .map { child ->
                Entry(
                    name = child.name,
                    path = "${guestPath.trimEnd('/')}/${child.name}",
                    isDirectory = child.isDirectory,
                    bytes = if (child.isDirectory) 0 else child.length(),
                    modified = child.lastModified(),
                    children = if (child.isDirectory) (child.list()?.size ?: -1) else 0,
                )
            }
            // Directories first, then by name, case-insensitively: the order every file
            // manager uses, and the one a user scanning for a folder expects.
            .sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /**
     * Copies one file the user picked into [guestPath].
     *
     * Takes an already-open stream rather than a path because what the picker returns is
     * a `content://` URI that may not have a path at all — a file in Google Drive, in
     * another app's private storage, or on a removable volume. Reading it is the caller's
     * job; putting it in the right place safely is this one's.
     *
     * Written to a `.part` file and renamed, so an import interrupted halfway leaves no
     * half-file for a game to load and reject.
     */
    fun importInto(
        vuid: Int,
        packageName: String,
        guestPath: String,
        name: String,
        stream: InputStream,
    ): Result<Long> {
        val directory = resolve(vuid, packageName, guestPath)
            ?: return Result.failure(IllegalArgumentException("outside the instance: $guestPath"))
        if (!directory.isDirectory && !directory.mkdirs()) {
            return Result.failure(IllegalStateException("could not create $guestPath"))
        }
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "imported" }
        val destination = File(directory, safe)
        val partial = File(directory, "$safe.part")
        return runCatching {
            val copied = stream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(destination)) {
                partial.delete()
                throw IllegalStateException("could not finish writing ${destination.name}")
            }
            copied
        }.onFailure { partial.delete() }
    }

    fun createFolder(vuid: Int, packageName: String, guestPath: String, name: String): Boolean {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isEmpty() || safe == "." || safe == "..") return false
        val parent = resolve(vuid, packageName, guestPath) ?: return false
        return File(parent, safe).mkdirs()
    }

    /** Deletes a file, or a directory and everything under it. */
    fun delete(vuid: Int, packageName: String, guestPath: String): Boolean {
        val target = resolve(vuid, packageName, guestPath) ?: return false
        // Never the root itself: "delete /sdcard" from a mis-tap would take the instance
        // with it, and the UI already has *Clear data* for that, with a confirmation.
        if (roots(vuid, packageName).any { it.realPath == target.canonicalPath }) return false
        return target.deleteRecursively()
    }

    /** The real file behind a guest path, for a caller that has to open it. */
    fun realFile(vuid: Int, packageName: String, guestPath: String): File? =
        resolve(vuid, packageName, guestPath)

    /**
     * A guest path turned into the real one, or null if it leaves the instance.
     *
     * The canonical form is compared, not the literal one, so `..` segments and a symlink
     * planted by a guest are both caught. A path that does not yet exist canonicalises to
     * where it *would* be, which is exactly the check an import needs.
     */
    internal fun resolve(vuid: Int, packageName: String, guestPath: String): File? {
        val normalised = guestPath.trim().ifEmpty { return null }
        for (root in roots(vuid, packageName)) {
            if (normalised != root.guestPath && !normalised.startsWith("${root.guestPath}/")) continue
            val relative = normalised.removePrefix(root.guestPath).trimStart('/')
            val real = if (relative.isEmpty()) File(root.realPath) else File(root.realPath, relative)
            val canonical = runCatching { real.canonicalPath }.getOrNull() ?: return null
            val rootCanonical = runCatching { File(root.realPath).canonicalPath }.getOrNull()
                ?: return null
            if (canonical != rootCanonical && !canonical.startsWith("$rootCanonical/")) return null
            return File(canonical)
        }
        return null
    }
}
