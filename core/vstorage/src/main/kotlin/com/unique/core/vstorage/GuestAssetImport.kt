package com.unique.core.vstorage

import android.content.Context
import android.os.Environment
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import java.io.File

/**
 * Puts a game's expansion files and external app data inside the instance.
 *
 * ## Why an app cannot find its own assets without this
 *
 * A guest's external storage is not the device's. `VirtualExternalStorage` repoints the
 * primary volume at `users/<vuid>/sdcard` inside UNIQUE's own files directory, which is
 * what makes *Clear data* mean something and keeps two instances apart. The consequence
 * is that `getObbDir()` names a directory that is real, writable, and **empty** — while
 * the 3 GB of assets the user already downloaded sit in
 * `/storage/emulated/0/Android/obb/<pkg>/`, which the guest can no longer see.
 *
 * An expansion-file game reads that as its assets being missing. From the Android 15 run
 * that produced this class, with `com.axlebolt.standoff2` imported and launched:
 *
 * ```
 * I Unity: No permission to read external storage. Skipping OBB loading.   (x156)
 * ```
 *
 * The permission half of that is fixed elsewhere (`PlatformPermissions.SELF_SERVED`).
 * This is the other half: with the permission granted, the directory still has to have
 * the files in it.
 *
 * ## What is copied, and what is deliberately not
 *
 * `Android/obb/<pkg>` is copied in full. Expansion files are large, read-only, and
 * versioned in their own names (`main.203908.com.axlebolt.standoff2.obb`), so a copy is
 * safe to make once and cheap to skip afterwards.
 *
 * `Android/data/<pkg>` is copied only when [includeExternalData] asks for it, and it is
 * off by default. That directory is *live* app state — a game writes its settings, its
 * downloaded asset bundles and its logs there — and copying it means two independent
 * copies of the same save that immediately diverge. Importing it is the right answer when
 * a user is *moving* into UNIQUE and the wrong one when they are running a second copy
 * beside the first, so it is the caller's decision and not a default.
 *
 * ## Reading the source is not guaranteed, and the failure is reported, not hidden
 *
 * Since Android 11 an app cannot open another package's `Android/data`, and access to
 * `Android/obb` depends on the release, the OEM and whether the user has granted UNIQUE
 * all-files access. So every outcome here is one of four named results rather than a
 * boolean, and "the source could not be read" never looks the same as "there was nothing
 * to copy" — the first is fixable by the user and the second is not a problem at all.
 */
class GuestAssetImport(context: Context) {

    private val app = context.applicationContext ?: context
    private val model = VirtualPathModel(app.filesDir.absolutePath)

    /** What one import did, in the form the UI and the diagnostics both need. */
    data class Result(
        val outcome: Outcome,
        val files: Int = 0,
        val bytes: Long = 0,
        /** The directory that was read, when there was one. For the log and the UI. */
        val source: String? = null,
        val detail: String? = null,
    ) {
        fun toMap(): Map<String, String> = buildMap {
            put("outcome", outcome.name)
            put("files", files.toString())
            put("bytes", bytes.toString())
            source?.let { put("source", it) }
            detail?.let { put("detail", it) }
        }
    }

    enum class Outcome {
        /** Files were copied in. */
        IMPORTED,

        /** The instance already had everything the source has. */
        ALREADY_PRESENT,

        /** The device has no such directory. Not a fault: most apps have no OBB. */
        NOTHING_TO_IMPORT,

        /**
         * The directory exists and the platform would not let UNIQUE read it.
         *
         * The one outcome the user can act on — all-files access, or handing UNIQUE the
         * files another way — so it is never folded into [NOTHING_TO_IMPORT].
         */
        SOURCE_UNREADABLE,
    }

    /**
     * Copies what the device already has for [packageName] into instance [vuid].
     *
     * Idempotent: a file whose name and length already match is skipped, so this is safe
     * to run on every launch and cheap when there is nothing new. Partial copies are
     * written to a `.part` file and renamed, so an interrupted import leaves the instance
     * with the files it did finish and none it did not.
     */
    fun importFor(
        vuid: Int,
        packageName: String,
        includeExternalData: Boolean = false,
    ): Result {
        val obb = copyTree(
            source = File(externalRoot(), "Android/obb/$packageName"),
            target = File(model.obbDir(vuid, packageName)),
        )
        Diagnostics.info(
            DiagChannel.STORAGE, "GUEST_OBB_IMPORT",
            obb.toMap() + mapOf("package" to packageName, "vuid" to vuid.toString()),
        )
        if (!includeExternalData) return obb

        val data = copyTree(
            source = File(externalRoot(), "Android/data/$packageName"),
            target = File(model.externalDataDir(vuid, packageName)),
        )
        Diagnostics.info(
            DiagChannel.STORAGE, "GUEST_EXTERNAL_DATA_IMPORT",
            data.toMap() + mapOf("package" to packageName, "vuid" to vuid.toString()),
        )
        return merge(obb, data)
    }

    /**
     * The same question as [importFor], asked without copying anything.
     *
     * For a screen that has to say *before* the user launches a game that its assets are
     * not going to be there. Copying to find out would take a minute and gigabytes; the
     * only thing this needs is whether the source can be read at all, which is two
     * `File` calls.
     */
    fun status(vuid: Int, packageName: String): Result {
        val source = File(externalRoot(), "Android/obb/$packageName")
        val target = File(model.obbDir(vuid, packageName))
        if (source.exists() && source.canRead()) {
            val present = target.walkTopDown().any { it.isFile }
            return Result(
                if (present) Outcome.ALREADY_PRESENT else Outcome.NOTHING_TO_IMPORT,
                source = source.path,
            )
        }
        val parent = source.parentFile
        if (parent != null && parent.exists() && !parent.canRead()) {
            return Result(
                Outcome.SOURCE_UNREADABLE, source = source.path,
                detail = "${parent.path} is not readable by UNIQUE; grant all-files " +
                    "access, or add the files by hand",
            )
        }
        // Nothing on the device to copy. Whether the instance already has files of its
        // own is what separates "this game has no expansion files" from "this game has
        // them and they came from somewhere else", and both are fine.
        val present = target.walkTopDown().any { it.isFile }
        return Result(
            if (present) Outcome.ALREADY_PRESENT else Outcome.NOTHING_TO_IMPORT,
            source = source.path,
        )
    }

    /**
     * Copies files the user chose — an `.obb` picked beside the APK, most often — into
     * the instance's expansion directory.
     *
     * This is the route that works on every release, because the user handed UNIQUE the
     * file rather than UNIQUE reaching into a directory the platform guards. It is what
     * [Outcome.SOURCE_UNREADABLE] tells the UI to offer.
     */
    fun importFiles(vuid: Int, packageName: String, sources: List<File>): Result {
        val target = File(model.obbDir(vuid, packageName))
        if (sources.isEmpty()) return Result(Outcome.NOTHING_TO_IMPORT)
        if (!target.isDirectory && !target.mkdirs()) {
            return Result(Outcome.SOURCE_UNREADABLE, detail = "could not create ${target.path}")
        }
        var files = 0
        var bytes = 0L
        val failures = ArrayList<String>(1)
        for (source in sources) {
            if (!source.isFile) {
                failures += "${source.name}: not a readable file"
                continue
            }
            val copied = runCatching { copyFile(source, File(target, source.name)) }
                .getOrElse { failures += "${source.name}: $it"; 0L }
            if (copied > 0) {
                files++
                bytes += copied
            }
        }
        val result = when {
            files > 0 -> Result(
                Outcome.IMPORTED, files, bytes, target.path,
                failures.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(300),
            )
            failures.isNotEmpty() -> Result(
                Outcome.SOURCE_UNREADABLE, detail = failures.joinToString("; ").take(300),
            )
            else -> Result(Outcome.ALREADY_PRESENT, source = target.path)
        }
        Diagnostics.info(
            DiagChannel.STORAGE, "GUEST_OBB_FILES_IMPORT",
            result.toMap() + mapOf("package" to packageName, "vuid" to vuid.toString()),
        )
        return result
    }

    /**
     * The device's own external storage root, read in UNIQUE's process.
     *
     * `Environment.getExternalStorageDirectory()` is safe to call here and would be
     * exactly wrong inside a `:vappN`: there the `mount` shim has repointed the primary
     * volume at the instance, so this would name the very directory being copied *into*
     * and the import would copy a directory onto itself. Guarded rather than assumed —
     * an import run from the wrong process is a bug worth failing loudly on.
     */
    private fun externalRoot(): File {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        require(!root.path.startsWith(app.filesDir.path)) {
            "GuestAssetImport must run in UNIQUE's own process; external storage here is " +
                "already redirected to ${root.path}"
        }
        return root
    }

    private fun merge(a: Result, b: Result): Result = Result(
        outcome = when {
            a.outcome == Outcome.IMPORTED || b.outcome == Outcome.IMPORTED -> Outcome.IMPORTED
            a.outcome == Outcome.SOURCE_UNREADABLE || b.outcome == Outcome.SOURCE_UNREADABLE ->
                Outcome.SOURCE_UNREADABLE
            a.outcome == Outcome.ALREADY_PRESENT || b.outcome == Outcome.ALREADY_PRESENT ->
                Outcome.ALREADY_PRESENT
            else -> Outcome.NOTHING_TO_IMPORT
        },
        files = a.files + b.files,
        bytes = a.bytes + b.bytes,
        source = a.source ?: b.source,
        detail = listOfNotNull(a.detail, b.detail).joinToString("; ").takeIf { it.isNotEmpty() },
    )

    private fun copyTree(source: File, target: File): Result {
        // exists() and listFiles() disagree in exactly the case that matters: on a
        // directory the platform hides, exists() answers false and there is nothing to
        // tell it apart from a package with no OBB. canRead() on the parent is what
        // separates "not there" from "not allowed", so both are asked.
        if (!source.exists()) {
            val parent = source.parentFile
            return if (parent != null && parent.exists() && !parent.canRead()) {
                Result(
                    Outcome.SOURCE_UNREADABLE, source = source.path,
                    detail = "${parent.path} is not readable by UNIQUE; grant all-files " +
                        "access, or add the files by hand",
                )
            } else {
                Result(Outcome.NOTHING_TO_IMPORT, source = source.path)
            }
        }
        val entries = source.walkTopDown().filter { it.isFile }.toList()
        if (entries.isEmpty()) {
            return if (!source.canRead()) {
                Result(
                    Outcome.SOURCE_UNREADABLE, source = source.path,
                    detail = "${source.path} exists but cannot be listed",
                )
            } else {
                Result(Outcome.NOTHING_TO_IMPORT, source = source.path)
            }
        }

        var files = 0
        var bytes = 0L
        val failures = ArrayList<String>(2)
        for (entry in entries) {
            val relative = entry.relativeToOrNull(source)?.path ?: continue
            val destination = File(target, relative)
            val copied = runCatching { copyFile(entry, destination) }
                .getOrElse { failures += "$relative: $it"; 0L }
            if (copied > 0) {
                files++
                bytes += copied
            }
        }
        return when {
            files > 0 -> Result(
                Outcome.IMPORTED, files, bytes, source.path,
                failures.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(300),
            )
            failures.isNotEmpty() -> Result(
                Outcome.SOURCE_UNREADABLE, source = source.path,
                detail = failures.joinToString("; ").take(300),
            )
            else -> Result(Outcome.ALREADY_PRESENT, source = source.path)
        }
    }

    /**
     * Copies one file, and returns 0 when the instance already has it.
     *
     * Same name and same length is the test. A content hash would be stronger and is not
     * worth several seconds per gigabyte on every launch: expansion files are immutable
     * by construction — the version is *in the file name* — so a length match on a name
     * match is as good an answer as this needs.
     */
    private fun copyFile(source: File, destination: File): Long {
        if (destination.isFile && destination.length() == source.length()) return 0L
        destination.parentFile?.mkdirs()
        // Written beside the target and renamed, so an import interrupted by the user
        // leaving the screen or the process dying never leaves a truncated .obb that
        // looks complete to the length check above.
        val partial = File(destination.parentFile, destination.name + ".part")
        source.inputStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        if (!partial.renameTo(destination)) {
            partial.delete()
            error("could not move ${partial.name} into place")
        }
        return destination.length()
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 1 shl 20
    }
}
