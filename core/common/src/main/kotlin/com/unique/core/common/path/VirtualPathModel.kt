package com.unique.core.common.path

/**
 * The single definition of where every virtual file lives.
 *
 * Path bugs are the largest source of silent data corruption in app virtualization: an
 * app that resolves `getFilesDir()` to instance 1 but hard-codes
 * `/data/data/<pkg>/files` and lands in instance 2 will quietly merge two users' data.
 * Keeping the whole contract in one pure-Kotlin object means it can be — and is —
 * exhaustively unit-tested without a device.
 *
 * @param hostFilesRoot the host app's private files directory, e.g.
 *        `/data/user/0/com.unique/files`. Everything UNIQUE owns lives under it.
 */
class VirtualPathModel(private val hostFilesRoot: String) {

    private val root = "$hostFilesRoot/virtual"

    // ---- shared, read-only ----------------------------------------------------------

    /** Where a package's APK set lives. Shared by every instance of that package. */
    fun apkDir(packageName: String, versionCode: Long): String =
        "$root/apk/$packageName/$versionCode"

    fun baseApk(packageName: String, versionCode: Long): String =
        "${apkDir(packageName, versionCode)}/base.apk"

    fun splitApk(packageName: String, versionCode: Long, splitName: String): String =
        "${apkDir(packageName, versionCode)}/split_$splitName.apk"

    /**
     * Extracted native libraries. Made read-only (0555) after extraction because Android
     * 10+ refuses to `dlopen` code from a writable location for targetSdk >= 29.
     */
    fun nativeLibraryDir(packageName: String, versionCode: Long, abiDirName: String = "arm64-v8a"): String =
        "${apkDir(packageName, versionCode)}/lib/$abiDirName"

    /**
     * The parent of the per-ABI library directories.
     *
     * A virtual process picks its own ABI from what is actually on disk rather than being
     * told, for the same reason it reads the manifest itself: no round trip to `:server`
     * on the launch path.
     */
    fun nativeLibraryRoot(packageName: String, versionCode: Long): String =
        "${apkDir(packageName, versionCode)}/lib"

    // ---- per-instance, writable -----------------------------------------------------

    fun userRoot(vuid: Int): String = "$root/users/$vuid"

    /** `ApplicationInfo.dataDir` and `deviceProtectedDataDir` for this instance. */
    fun dataDir(vuid: Int, packageName: String): String = "${userRoot(vuid)}/data/$packageName"

    fun filesDir(vuid: Int, packageName: String) = "${dataDir(vuid, packageName)}/files"
    fun cacheDir(vuid: Int, packageName: String) = "${dataDir(vuid, packageName)}/cache"
    fun codeCacheDir(vuid: Int, packageName: String) = "${dataDir(vuid, packageName)}/code_cache"
    fun noBackupDir(vuid: Int, packageName: String) = "${dataDir(vuid, packageName)}/no_backup"
    fun databasesDir(vuid: Int, packageName: String) = "${dataDir(vuid, packageName)}/databases"
    fun sharedPrefsDir(vuid: Int, packageName: String) = "${dataDir(vuid, packageName)}/shared_prefs"

    /** Virtual external storage root — what the app sees as `/sdcard`. */
    fun externalRoot(vuid: Int): String = "${userRoot(vuid)}/sdcard"

    fun externalDataDir(vuid: Int, packageName: String) =
        "${externalRoot(vuid)}/Android/data/$packageName"

    fun externalFilesDir(vuid: Int, packageName: String) =
        "${externalDataDir(vuid, packageName)}/files"

    fun externalCacheDir(vuid: Int, packageName: String) =
        "${externalDataDir(vuid, packageName)}/cache"

    fun obbDir(vuid: Int, packageName: String) =
        "${externalRoot(vuid)}/Android/obb/$packageName"

    fun mediaDir(vuid: Int, packageName: String) =
        "${externalRoot(vuid)}/Android/media/$packageName"

    /** Engine-internal state that is not part of any app's data. */
    fun runtimeDir(): String = "$root/runtime"

    fun diagnosticsDir(vuid: Int, packageName: String) =
        "${runtimeDir()}/diagnostics/$vuid/$packageName"

    /**
     * Where an instance's runtime permission decisions are kept.
     *
     * Under `runtime/`, not under the app's data directory: this is UNIQUE's record of
     * what the user allowed, and a guest that can rewrite its own grants has none. It is
     * inside UNIQUE's app-private storage, which is also where anything resembling a
     * security decision has to live.
     */
    /**
     * Where a scheduled job's routing record lives.
     *
     * A job outlives the process that scheduled it, so the mapping from host job id back
     * to a guest's `JobService` cannot be in memory. Keyed by host job id alone because
     * that is all the system hands back when the job fires.
     */
    /**
     * The instance's device profile, in the one form a `:vappN` process can read.
     *
     * The profile lives in the state database, which the virtual process deliberately has
     * no IPC to on the launch path; this is its copy.
     */
    fun profileFile(vuid: Int) = "${runtimeDir()}/profiles/$vuid.properties"

    fun jobRecord(hostJobId: Int) = "${runtimeDir()}/jobs/$hostJobId.properties"

    fun permissionsFile(vuid: Int, packageName: String) =
        "${runtimeDir()}/permissions/$vuid/$packageName.properties"

    /**
     * The enabled state of the guest's own components, per instance.
     *
     * `PackageManagerService` cannot hold this: the components belong to a package it has
     * never installed, so it refuses the call rather than storing anything. An app
     * enabling or disabling its own alias — the usual way of changing a launcher icon —
     * expects the change to survive a restart, so UNIQUE keeps it beside the permission
     * record, in the one form a `:vappN` process can read.
     */
    fun componentStateFile(vuid: Int, packageName: String) =
        "${runtimeDir()}/components/$vuid/$packageName.properties"

    /** Directories that must exist before an app is first launched. */
    fun instanceDirectories(vuid: Int, packageName: String): List<String> = listOf(
        dataDir(vuid, packageName),
        filesDir(vuid, packageName),
        cacheDir(vuid, packageName),
        codeCacheDir(vuid, packageName),
        noBackupDir(vuid, packageName),
        databasesDir(vuid, packageName),
        sharedPrefsDir(vuid, packageName),
        externalRoot(vuid),
        externalDataDir(vuid, packageName),
        externalFilesDir(vuid, packageName),
        externalCacheDir(vuid, packageName),
        obbDir(vuid, packageName),
        mediaDir(vuid, packageName),
    )

    /**
     * The prefix-rewrite table handed to the native redirection layer at process start.
     *
     * Order matters and is *longest-prefix-first*, which the native side relies on: a
     * rule for `/data/data/<pkg>/cache` must be tested before one for `/data/data/<pkg>`.
     * The list is frozen after `attachBaseContext`, so the hot path is a short sorted scan.
     *
     * @param realHostUserId the host's Android user id, so `/data/user/<n>/...` forms
     *        that appear on multi-user devices are covered too.
     */
    fun redirectionRules(
        vuid: Int,
        packageName: String,
        versionCode: Long,
        realHostUserId: Int = 0,
    ): List<RedirectRule> {
        val data = dataDir(vuid, packageName)
        val ext = externalRoot(vuid)
        val rules = ArrayList<RedirectRule>(16)

        // App-private data, in every spelling the platform and apps use.
        for (prefix in listOf(
            "/data/data/$packageName",
            "/data/user/$realHostUserId/$packageName",
            "/data/user_de/$realHostUserId/$packageName",
        )) {
            rules += RedirectRule(prefix, data)
        }

        // Installed-app code locations. An app that hard-codes its own /data/app path is
        // pointed at the shared read-only APK directory.
        rules += RedirectRule("/data/app/$packageName", apkDir(packageName, versionCode))

        // External storage, in every spelling.
        for (prefix in listOf(
            "/sdcard",
            "/storage/emulated/$realHostUserId",
            "/storage/self/primary",
            "/mnt/sdcard",
        )) {
            rules += RedirectRule(prefix, ext)
        }

        return rules.sortedByDescending { it.from.length }
    }

    // ---- validation -----------------------------------------------------------------

    /** True when [path] belongs to the instance identified by [vuid]. */
    fun belongsToInstance(path: String, vuid: Int): Boolean {
        val normalized = normalize(path)
        return normalized == userRoot(vuid) || normalized.startsWith(userRoot(vuid) + "/")
    }

    /** True when [path] is inside UNIQUE's own tree at all. */
    fun isVirtual(path: String): Boolean {
        val normalized = normalize(path)
        return normalized == root || normalized.startsWith("$root/")
    }

    companion object {
        /** Collapses `.`/`..`/duplicate separators without touching the filesystem. */
        fun normalize(path: String): String {
            val absolute = path.startsWith("/")
            val out = ArrayDeque<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                        else if (!absolute) out.addLast("..")
                    else -> out.addLast(seg)
                }
            }
            val joined = out.joinToString("/")
            return if (absolute) "/$joined" else joined
        }
    }
}

/** One entry of the native redirection table: everything under [from] resolves under [to]. */
data class RedirectRule(val from: String, val to: String) {
    /** Applies the rule to [path], or returns null when it does not match. */
    fun apply(path: String): String? {
        val p = VirtualPathModel.normalize(path)
        return when {
            p == from -> to
            p.startsWith("$from/") -> to + p.substring(from.length)
            else -> null
        }
    }
}

/** Applies an ordered rule table; the first match wins, which is why order is significant. */
class RedirectTable(rules: List<RedirectRule>) {
    private val rules = rules.sortedByDescending { it.from.length }

    fun redirect(path: String): String = rules.firstNotNullOfOrNull { it.apply(path) } ?: path

    fun asPairs(): List<Pair<String, String>> = rules.map { it.from to it.to }
}
