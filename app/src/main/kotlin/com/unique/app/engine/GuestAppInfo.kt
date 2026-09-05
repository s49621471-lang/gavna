package com.unique.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import java.io.File

/**
 * An imported app's name and icon, read out of the APK UNIQUE stored.
 *
 * `android:label` in a manifest is almost never a string. It is a reference —
 * `@0x7f130001` — into the APK's own resource table, and UNIQUE's manifest reader is a
 * binary-XML parser with no resource table to consult, so it hands back the reference
 * spelled out. On a phone that looked exactly like it sounds:
 *
 * ```
 * @7f130001            ← every app in the list
 * ```
 *
 * Icons were worse, and silently: `PackageManager.getApplicationIcon(packageName)` only
 * answers for packages the *device* has installed, and the whole point of UNIQUE is that
 * these are not. Every imported app fell back to a monogram tile.
 *
 * Both are resolved here by handing the stored APK back to the platform's own parser.
 * `getPackageArchiveInfo` reads an APK that is not installed, and `loadLabel`/`loadIcon`
 * then resolve against an `AssetManager` built over it — which means adaptive icons,
 * density-correct bitmaps, and a label in the device's own language, all for free and all
 * exactly as the launcher would show them.
 *
 * Cached per (package, version), because parsing an APK is not cheap and the answer cannot
 * change without an import having replaced the file.
 */
object GuestAppInfo {

    data class Resolved(val label: String?, val icon: Drawable?)

    private val cache = HashMap<String, Resolved>()

    @Synchronized
    fun of(context: Context, packageName: String, versionCode: Long): Resolved {
        val key = "$packageName:$versionCode"
        cache[key]?.let { return it }
        val resolved = read(context, packageName, versionCode)
        cache[key] = resolved
        return resolved
    }

    @Synchronized
    fun forget(packageName: String) {
        cache.keys.removeAll { it.substringBeforeLast(':') == packageName }
    }

    private fun read(context: Context, packageName: String, versionCode: Long): Resolved {
        val base = File(UniqueEngine.storage.model.baseApk(packageName, versionCode))
        if (!base.isFile) return Resolved(null, null)
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(base.absolutePath, 0) }.getOrNull()
        val app = info?.applicationInfo ?: run {
            Diagnostics.warn(
                DiagChannel.STORAGE, "GUEST_INFO_UNPARSED",
                mapOf("package" to packageName, "apk" to base.name),
            )
            return Resolved(null, null)
        }
        attachSources(app, base)

        // A label that still reads as a reference is not a label. It means the resource
        // lives in a split this build did not attach, or the table could not be read at
        // all — either way the package name is a better answer than `@7f130001`.
        val label = runCatching { app.loadLabel(pm).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith("@") }
        val icon = runCatching { app.loadIcon(pm) }.getOrNull()
        Diagnostics.info(
            DiagChannel.STORAGE, "GUEST_INFO_RESOLVED",
            mapOf(
                "package" to packageName,
                "label" to (label ?: "-"),
                "icon" to (icon != null).toString(),
            ),
        )
        return Resolved(label, icon)
    }

    /**
     * Points the parsed `ApplicationInfo` at the files on disk.
     *
     * `getPackageArchiveInfo` fills in what the manifest says and leaves the paths to the
     * caller — without them `loadLabel` has no `AssetManager` to build and quietly returns
     * the package name. The splits matter as much as the base: an app whose label or icon
     * lives in a density or language split resolves to nothing without them, which is the
     * shape of bug that looks like "it works for some apps".
     */
    private fun attachSources(app: ApplicationInfo, base: File) {
        app.sourceDir = base.absolutePath
        app.publicSourceDir = base.absolutePath
        val splits = base.parentFile
            ?.listFiles { f -> f.isFile && f.name.startsWith("split_") }
            ?.map { it.absolutePath }
            ?.sorted()
            .orEmpty()
        if (splits.isNotEmpty()) {
            app.splitSourceDirs = splits.toTypedArray()
            app.splitPublicSourceDirs = splits.toTypedArray()
        }
    }
}
