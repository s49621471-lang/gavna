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

    /**
     * Native libraries the path redirector must not hook for this instance, one per line.
     *
     * Per instance rather than per package because it is a *recovery* knob as much as a
     * configuration one: a game that dies in an anonymous page seconds after
     * `libgrave.so` was hooked needs that one library left alone, and the user must be
     * able to get there without a new build. Absent means "the built-in list only",
     * which is the normal case.
     *
     * @see com.unique.core.common.nativelib.GuestNativeExclusions
     */
    fun nativeExclusionsFile(vuid: Int, packageName: String) =
        "${runtimeDir()}/native/$vuid/$packageName.exclude"

    /**
     * Whether this instance is shown the device's Google Play services. One word.
     *
     * `show` or `hide`; anything else, or no file, leaves the rule in
     * [com.unique.core.common.google.GoogleStackVisibility] to decide. Per instance
     * rather than per package because two copies of one app can legitimately want
     * different answers — one signed in through Google, one deliberately not.
     */
    fun googleVisibilityFile(vuid: Int, packageName: String) =
        "${runtimeDir()}/google/$vuid/$packageName.visibility"

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

        // And the exact directory `procViewRules` shows in place of that one, so a guest
        // that takes a path out of its own `/proc/self/maps` and opens it finds the file.
        // A view that answers with a path nothing resolves would be a stranger fault than
        // the one it fixes: a library that is mapped and cannot be opened is a state no
        // real device produces. The library rule is separate because an installed app's
        // is `lib/arm64` where the APK's is `lib/arm64-v8a`.
        val installed = installedApkDir(packageName, installTokenFor(vuid, packageName, versionCode))
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            rules += RedirectRule(
                "$installed/lib/${installedAbiDirName(abi)}",
                nativeLibraryDir(packageName, versionCode, abi),
            )
        }
        rules += RedirectRule(installed, apkDir(packageName, versionCode))

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

    // ---- the outward view ------------------------------------------------------------

    /**
     * The other half of [redirectionRules]: real path prefix → what an installed app shows.
     *
     * Redirection covers the paths a guest *hands out*. It does nothing about the ones a
     * guest is *handed*, and there is one file that hands out all of them at once:
     *
     * ```
     * $ cat /proc/self/maps          # inside :vapp0, running Standoff 2
     * … r--p … /data/app/~~eOlB8_…/com.unique-LcSgGP…/base.apk
     * … r-xp … /data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/…/libunity.so
     * ```
     *
     * An installed app's maps names its own package and nothing else. Two lines like
     * those are all a game needs, they cost one `fopen` and no permission, and reading
     * them is something native crash handlers do anyway — so the code that would find
     * them is already in every such app for an innocent reason.
     *
     * The rewrite is a rename, never a deletion: every mapping keeps its address, its
     * permissions and its place in the file, because Unity's crash handler, Crashlytics
     * and every native unwinder read this file to turn addresses into symbols, and a view
     * with lines missing breaks them in ways that look like the app's own fault.
     *
     * @param hostSourceDir UNIQUE's own `ApplicationInfo.sourceDir`, whose directory is
     *   mapped into every `:vappN` because that is whose process it is.
     * @param hostDataDir UNIQUE's own `dataDir`, the root everything virtual lives under.
     * @param installToken the two path components Android 11+ gives an installed app's
     *   directory. Deterministic per instance — see [installTokenFor] — so a guest that
     *   remembers where it was installed last time still recognises the place.
     */
    fun procViewRules(
        vuid: Int,
        packageName: String,
        versionCode: Long,
        hostSourceDir: String,
        hostDataDir: String,
        installToken: Pair<String, String> = installTokenFor(vuid, packageName, versionCode),
        realHostUserId: Int = 0,
        abiDirName: String = "arm64-v8a",
    ): List<RedirectRule> {
        val installed = installedApkDir(packageName, installToken)
        val installedData = "/data/user/$realHostUserId/$packageName"
        val rules = ArrayList<RedirectRule>(8)

        // The guest's own code. The library directory is listed separately because an
        // installed app's is `lib/arm64`, not `lib/arm64-v8a`: the ABI directory inside an
        // APK and the one the installer extracts to are spelled differently, and an app
        // that knows the difference is exactly the kind that is looking.
        rules += RedirectRule(nativeLibraryDir(packageName, versionCode, abiDirName),
            "$installed/lib/${installedAbiDirName(abiDirName)}")
        rules += RedirectRule(apkDir(packageName, versionCode), installed)

        // The guest's own data, and what it calls the sdcard.
        rules += RedirectRule(dataDir(vuid, packageName), installedData)
        rules += RedirectRule(externalRoot(vuid), "/storage/emulated/$realHostUserId")

        // UNIQUE itself. Its APK is mapped into this process — it is UNIQUE's process —
        // and so is anything else it has open under its private directory. Both are shown
        // as more of the guest's own: an app's `base.apk` is mapped many times over, so
        // one more mapping of it is the least remarkable thing in the file.
        //
        // These come last only for readability; the native side sorts longest-first, so
        // the specific rules above always win over `hostDataDir`, which is their prefix.
        hostSourceDir.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
            ?.let { rules += RedirectRule(it, installed) }
        rules += RedirectRule(hostDataDir, installedData)

        return withDataDirAliases(rules, hostDataDir).sortedByDescending { it.from.length }
    }

    /**
     * Adds the other spellings of the host's data directory to every rule under it.
     *
     * `/data/data/<pkg>` is a symlink to `/data/user/0/<pkg>`, and which of the two ends
     * up in `/proc/self/maps` depends on the path the file happened to be opened with.
     * The first build of this view was written from `Context.getFilesDir()`, which is the
     * `/data/user/0` spelling, and its own self-check found the gap on the first phone
     * that ran it:
     *
     * ```
     * PROC_VIEW_INSTALLED package=com.axlebolt.standoff2 rules=6 named=15 leaked=2
     *     first=/data/data/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908
     * ```
     *
     * Thirteen of fifteen mappings renamed and two left naming UNIQUE — which for this
     * purpose is the same as none, since a check only has to find one. `redirectionRules`
     * has always covered both spellings for the *guest*; this is the same rule for the
     * host, which nothing needed until the view existed.
     */
    private fun withDataDirAliases(
        rules: List<RedirectRule>,
        hostDataDir: String,
    ): List<RedirectRule> {
        val aliases = dataDirAliases(hostDataDir).filter { it != hostDataDir }
        if (aliases.isEmpty()) return rules
        val out = ArrayList<RedirectRule>(rules.size * (aliases.size + 1))
        for (rule in rules) {
            out += rule
            if (rule.from != hostDataDir && !rule.from.startsWith("$hostDataDir/")) continue
            for (alias in aliases) {
                out += RedirectRule(alias + rule.from.removePrefix(hostDataDir), rule.to)
            }
        }
        return out
    }

    /** `/data/app/~~<a>/<pkg>-<b>`, as Android 11 and newer lay an installed app out. */
    fun installedApkDir(packageName: String, token: Pair<String, String>): String =
        "/data/app/~~${token.first}/$packageName-${token.second}"

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

        /**
         * The two 22-character tokens in an installed app's directory name.
         *
         * Android generates them at install time from a random 128-bit value, encoded
         * base64url without padding. UNIQUE derives them instead, from the instance —
         * which makes them stable for the life of that instance and different between two
         * instances of one app, both of which a real install would also give.
         *
         * Not a hash of anything secret and not meant to be one: the point is a name of
         * the right *shape*, in a directory the guest never opens, that does not change
         * under it between launches.
         */
        fun installTokenFor(vuid: Int, packageName: String, versionCode: Long): Pair<String, String> {
            val seed = "$vuid|$packageName|$versionCode"
            return token(seed, salt = 0x9E3779B97F4A7C15uL) to
                token(seed, salt = 0xC2B2AE3D27D4EB4FuL)
        }

        /** Twenty-two base64url characters, from a 128-bit value derived from [seed]. */
        private fun token(seed: String, salt: ULong): String {
            var hi = salt
            var lo = salt xor 0x165667B19E3779F9uL
            for (c in seed) {
                hi = (hi xor c.code.toULong()) * 0x100000001B3uL
                lo = (lo + hi).rotateLeft(23) * 0x9E3779B97F4A7C15uL
            }
            val bytes = ByteArray(16)
            for (i in 0 until 8) {
                bytes[i] = ((hi shr (8 * i)) and 0xFFuL).toByte()
                bytes[8 + i] = ((lo shr (8 * i)) and 0xFFuL).toByte()
            }
            val out = StringBuilder(22)
            var bits = 0
            var buffer = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 6) {
                    bits -= 6
                    out.append(BASE64URL[(buffer shr bits) and 0x3F])
                    if (out.length == 22) return out.toString()
                }
            }
            if (bits > 0 && out.length < 22) out.append(BASE64URL[(buffer shl (6 - bits)) and 0x3F])
            return out.toString()
        }

        private const val BASE64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /**
         * Every spelling of one app-private data directory.
         *
         * `/data/data/<pkg>` is a symlink to `/data/user/0/<pkg>`, and `/data/user_de` is
         * the device-protected sibling. All three name the same files and any of them can
         * appear in a path a process is holding.
         */
        fun dataDirAliases(dataDir: String): List<String> {
            val (user, pkg) = when {
                dataDir.startsWith("/data/data/") -> 0 to dataDir.removePrefix("/data/data/")
                dataDir.startsWith("/data/user/") ->
                    dataDir.removePrefix("/data/user/").substringBefore('/').toIntOrNull()
                        .let { it ?: return listOf(dataDir) } to
                        dataDir.removePrefix("/data/user/").substringAfter('/', "")
                dataDir.startsWith("/data/user_de/") ->
                    dataDir.removePrefix("/data/user_de/").substringBefore('/').toIntOrNull()
                        .let { it ?: return listOf(dataDir) } to
                        dataDir.removePrefix("/data/user_de/").substringAfter('/', "")
                else -> return listOf(dataDir)
            }
            if (pkg.isEmpty() || pkg.contains('/')) return listOf(dataDir)
            return listOf(
                "/data/user/$user/$pkg",
                "/data/data/$pkg",
                "/data/user_de/$user/$pkg",
            ).distinct()
        }

        /**
         * The directory name the *installer* extracts native libraries into.
         *
         * An APK carries `lib/arm64-v8a`; the installed app has `lib/arm64`. The mapping
         * is the platform's, in `VMRuntime.getInstructionSet`, and getting it wrong would
         * leave a path that is nearly right and therefore worth looking at twice.
         */
        fun installedAbiDirName(abiDirName: String): String = when (abiDirName) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> abiDirName
        }
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
