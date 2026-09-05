package com.unique.core.vam

import android.content.Context
import android.os.storage.StorageVolume
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import java.io.File

/**
 * Points the guest's *external* storage at its own instance directory.
 *
 * Internal storage is redirected by construction: `ApplicationInfo.dataDir` names the
 * instance's directory, and everything `ContextImpl` derives — `getFilesDir`,
 * `getCacheDir`, `getDatabasePath` — follows from it. External storage does not work that
 * way, and the difference is not academic:
 *
 * ```java
 * // ContextImpl
 * mExternalFilesDirs = Environment.buildExternalStorageAppFilesDirs(getPackageName());
 * // -> /storage/emulated/0/Android/data/<guest package>/files
 * ```
 *
 * That path is derived from the *package name*, which is the guest's, and it is a
 * directory scoped storage will not let UNIQUE create or write: `/Android/data` is
 * per-package and UNIQUE's package is `com.unique`. A guest asking for its external files
 * directory therefore got a path that does not exist and cannot be made to, and
 * `ContextImpl.ensureExternalDirsExistOrFilter` handed back `null`. Apps that keep
 * downloads, caches, OBBs or exported files there read that as storage being unavailable.
 *
 * The native redirector does not close this: it hooks `libc` calls inside the *guest's
 * own* `.so` files, and `java.io.File` goes through the platform's, which are deliberately
 * out of scope — widening it to the platform's libraries would redirect UNIQUE's own file
 * operations in the same process.
 *
 * ## Where the rewrite goes instead
 *
 * `Environment` builds every external path from one place:
 *
 * ```java
 * public File[] getExternalDirs() {
 *     final StorageVolume[] volumes =
 *             StorageManager.getVolumeList(mUserId, StorageManager.FLAG_FOR_WRITE);
 *     ...
 * }
 * ```
 *
 * and `StorageManager.getVolumeList` resolves `mount` through `ServiceManager` on every
 * call — which UNIQUE already proxies. So one shim on `IStorageManager.getVolumeList`
 * moves `getExternalStorageDirectory`, `getExternalFilesDirs`, `getExternalCacheDirs`,
 * `getObbDirs`, `getExternalMediaDirs` and `getExternalStoragePublicDirectory` together,
 * without UNIQUE reimplementing any of them.
 *
 * The volume object is mutated rather than rebuilt: `StorageVolume`'s constructor is
 * hidden and its parameter list has changed in most releases; its `mPath` field has not.
 */
internal object VirtualExternalStorage {

    /** The instance's external root, once [prepare] has run. Null before. */
    @Volatile private var root: String? = null

    val redirectedRoot: String? get() = root

    /**
     * Creates the instance's external root and records it for the shim.
     *
     * Returns false when the directory cannot exist, in which case no rewrite is
     * installed and the guest sees the device's real external storage — the behaviour it
     * had before this existed, with a diagnostic rather than a silently wrong directory.
     */
    fun prepare(hostContext: Context, params: VirtualLaunchParams): Boolean {
        val model = VirtualPathModel(
            (hostContext.applicationContext ?: hostContext).filesDir.absolutePath,
        )
        val dir = File(model.externalRoot(params.vuid))
        // Created here as well as at instance preparation: an instance restored from an
        // older build, or one whose directory the user cleared, must not leave the guest
        // pointed at a path that does not exist.
        if (!dir.isDirectory && !dir.mkdirs()) {
            Diagnostics.warn(
                DiagChannel.STORAGE, "EXTERNAL_ROOT_UNAVAILABLE",
                mapOf("path" to dir.absolutePath),
            )
            root = null
            return false
        }
        root = dir.absolutePath
        Diagnostics.info(
            DiagChannel.STORAGE, "EXTERNAL_STORAGE_REDIRECTED",
            mapOf(
                "package" to params.packageName,
                "vuid" to params.vuid.toString(),
                "root" to dir.absolutePath,
            ),
        )
        return true
    }

    fun reset() {
        root = null
    }

    /**
     * The `mount` shim, prepended to the caller-package rewrite by
     * [VirtualIdentityHooks.guards].
     *
     * It lives there rather than being installed separately because installing a service
     * twice replaces the whole proxy: the second `SystemServiceHook.install` wraps the
     * *real* interface again, and whatever the first one bound is gone.
     */
    fun shims(): List<MethodShim> {
        if (root == null) return emptyList()
        return listOf(
            shim("volumeList") {
                matchMethods { it.name == "getVolumeList" || it.name == "getVolumes" }
                rewriteResult { result -> rewriteVolumes(result) }
            },
        )
    }

    /**
     * Repoints every volume in the result at the instance's own directory.
     *
     * Only the primary volume is kept. A device with a real SD card would otherwise offer
     * the guest a second root that is not virtualized at all — a path shared between
     * instances, and one UNIQUE cannot clear when the user clears an instance's data.
     */
    private fun rewriteVolumes(result: Any?): Any? {
        val path = root ?: return null
        val volumes = result as? Array<*> ?: return null
        val primary = volumes.filterIsInstance<StorageVolume>().firstOrNull() ?: return null
        if (!repoint(primary, path)) return null
        @Suppress("UNCHECKED_CAST")
        val out = java.lang.reflect.Array.newInstance(
            volumes.javaClass.componentType ?: StorageVolume::class.java, 1,
        ) as Array<Any?>
        out[0] = primary
        return out
    }

    /** Writes the instance's path into a `StorageVolume`, in both spellings it carries. */
    private fun repoint(volume: StorageVolume, path: String): Boolean {
        var wrote = false
        for (field in listOf("mPath", "mInternalPath")) {
            if (Reflect.set(StorageVolume::class.java, field, volume, path)) wrote = true
        }
        if (!wrote) {
            Diagnostics.warn(
                DiagChannel.STORAGE, "EXTERNAL_VOLUME_SHAPE_UNKNOWN",
                mapOf("class" to volume.javaClass.name),
            )
            return false
        }
        // A volume the platform reports as anything but "mounted" is filtered out by the
        // callers of `getExternalDirs`, and the instance's own directory is always there.
        Reflect.set(StorageVolume::class.java, "mState", volume, "mounted")
        Reflect.set(StorageVolume::class.java, "mPrimary", volume, true)
        Reflect.set(StorageVolume::class.java, "mEmulated", volume, true)
        Reflect.set(StorageVolume::class.java, "mRemovable", volume, false)
        return true
    }
}
