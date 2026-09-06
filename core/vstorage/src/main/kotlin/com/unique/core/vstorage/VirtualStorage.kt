package com.unique.core.vstorage

import android.content.Context
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.RedirectRule
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.nativebridge.UniqueNative
import java.io.File

data class StorageUsage(val dataBytes: Long, val cacheBytes: Long, val externalBytes: Long) {
    val totalBytes: Long get() = dataBytes + cacheBytes + externalBytes
}

/**
 * Creates and maintains the on-disk layout described in ARCHITECTURE.md section 7.
 *
 * All of it lives inside the host's app-private directory, including what a virtual app
 * sees as `/sdcard`. That is what makes *Clear data* meaningful and keeps one virtual
 * app's files unreadable by every other installed app on the device.
 */
class VirtualStorage(context: Context) {

    val model = VirtualPathModel(context.filesDir.absolutePath)

    /** Creates every directory an instance needs. Idempotent. */
    fun prepareInstance(vuid: Int, packageName: String) {
        var created = 0
        for (path in model.instanceDirectories(vuid, packageName)) {
            if (File(path).mkdirs()) created++
        }
        Diagnostics.info(
            DiagChannel.STORAGE, "INSTANCE_PREPARED",
            mapOf("vuid" to vuid.toString(), "package" to packageName, "created" to created.toString()),
        )
    }

    /**
     * Publishes this instance's redirection table to the native layer.
     *
     * Called before any virtual app code runs. The native side reports how many rules it
     * accepted so a mismatch is visible rather than silently halving the coverage.
     */
    fun publishRedirection(vuid: Int, packageName: String, versionCode: Long): List<RedirectRule> {
        val rules = model.redirectionRules(vuid, packageName, versionCode)
        UniqueNative.setRedirectRules(rules)
        val accepted = UniqueNative.redirectRuleCount()
        if (accepted != rules.size) {
            Diagnostics.warn(
                DiagChannel.STORAGE, "REDIRECT_RULE_COUNT_MISMATCH",
                mapOf("published" to rules.size.toString(), "accepted" to accepted.toString()),
            )
        }
        return rules
    }

    /**
     * Publishes what this instance's `/proc` should say, which is the other direction.
     *
     * Redirection covers the paths a guest hands out; this covers the ones it is handed.
     * `/proc/self/maps` inside a `:vappN` lists UNIQUE's APK and UNIQUE's private
     * directory by name, and reading it is what a native crash handler does anyway — so
     * the code that would find them is already in most apps, for an innocent reason.
     *
     * Published from the same place and at the same moment as the redirection table so
     * the two cannot get out of step: they are inverses of each other, and a view that
     * describes a layout the redirector no longer implements is worse than none.
     */
    fun publishProcView(
        vuid: Int,
        packageName: String,
        versionCode: Long,
        hostSourceDir: String,
        hostDataDir: String,
        abiDirName: String,
    ): List<RedirectRule> {
        val rules = model.procViewRules(
            vuid = vuid,
            packageName = packageName,
            versionCode = versionCode,
            hostSourceDir = hostSourceDir,
            hostDataDir = hostDataDir,
            abiDirName = abiDirName,
        )
        UniqueNative.setProcView(rules)
        val accepted = UniqueNative.procViewRuleCount()
        if (accepted != rules.size) {
            Diagnostics.warn(
                DiagChannel.STORAGE, "PROC_VIEW_RULE_COUNT_MISMATCH",
                mapOf("published" to rules.size.toString(), "accepted" to accepted.toString()),
            )
        }
        return rules
    }

    fun usage(vuid: Int, packageName: String): StorageUsage = StorageUsage(
        dataBytes = sizeOf(File(model.dataDir(vuid, packageName))) - sizeOf(File(model.cacheDir(vuid, packageName))),
        cacheBytes = sizeOf(File(model.cacheDir(vuid, packageName))),
        externalBytes = sizeOf(File(model.externalDataDir(vuid, packageName))) +
            sizeOf(File(model.obbDir(vuid, packageName))),
    )

    /** Clears cache only, leaving user data intact. */
    fun clearCache(vuid: Int, packageName: String) {
        deleteContents(File(model.cacheDir(vuid, packageName)))
        deleteContents(File(model.codeCacheDir(vuid, packageName)))
        deleteContents(File(model.externalCacheDir(vuid, packageName)))
        Diagnostics.info(DiagChannel.STORAGE, "CACHE_CLEARED",
            mapOf("vuid" to vuid.toString(), "package" to packageName))
    }

    /**
     * Clears everything this instance owns, then recreates the empty skeleton so the app
     * can be launched again immediately rather than failing on a missing directory.
     */
    fun clearData(vuid: Int, packageName: String) {
        deleteRecursively(File(model.dataDir(vuid, packageName)))
        deleteRecursively(File(model.externalDataDir(vuid, packageName)))
        deleteRecursively(File(model.mediaDir(vuid, packageName)))
        prepareInstance(vuid, packageName)
        Diagnostics.info(DiagChannel.STORAGE, "DATA_CLEARED",
            mapOf("vuid" to vuid.toString(), "package" to packageName))
    }

    /** Removes an instance entirely, including its OBB files. */
    fun removeInstance(vuid: Int, packageName: String) {
        deleteRecursively(File(model.dataDir(vuid, packageName)))
        deleteRecursively(File(model.externalDataDir(vuid, packageName)))
        deleteRecursively(File(model.obbDir(vuid, packageName)))
        deleteRecursively(File(model.mediaDir(vuid, packageName)))
    }

    /** Removes a package's shared APK set. Only safe once no instance references it. */
    fun removePackageFiles(packageName: String, versionCode: Long) {
        // The lib directory is made read-only after extraction, so it has to be made
        // writable again before it can be removed.
        val apkDir = File(model.apkDir(packageName, versionCode))
        makeWritableRecursively(apkDir)
        deleteRecursively(apkDir)
    }

    private fun sizeOf(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    private fun deleteContents(dir: File) {
        dir.listFiles()?.forEach { deleteRecursively(it) }
    }

    private fun deleteRecursively(f: File) {
        if (!f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        f.delete()
    }

    private fun makeWritableRecursively(f: File) {
        if (!f.exists()) return
        f.setWritable(true, true)
        if (f.isDirectory) f.listFiles()?.forEach { makeWritableRecursively(it) }
    }
}
