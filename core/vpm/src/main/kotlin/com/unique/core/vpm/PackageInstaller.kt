package com.unique.core.vpm

import com.unique.core.common.apk.Abi
import com.unique.core.common.apk.ApkBundle
import com.unique.core.common.apk.ApkBundleReader
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ApkPart
import com.unique.core.common.apk.DeviceSpec
import com.unique.core.common.compat.CompatFlag
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.elf.ElfInspector
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import java.io.File
import java.util.zip.ZipFile

/** Why an import was refused, in terms the UI can show verbatim. */
sealed interface ImportRejection {
    val message: String

    data class NoArm64(override val message: String = "This app has no arm64-v8a native code. UNIQUE runs ARM64 only.") : ImportRejection
    data class NotAligned16K(val libraries: List<String>, override val message: String) : ImportRejection
    data class Unreadable(override val message: String) : ImportRejection
    data class Unsupported(override val message: String) : ImportRejection
}

sealed interface ImportResult {
    data class Installed(
        val manifest: ApkManifest,
        val apkDir: File,
        val nativeLibraryDir: File?,
        val extractedLibraries: Int,
        val compatFlags: Set<CompatFlag>,
        val bytesWritten: Long,
    ) : ImportResult

    data class Rejected(val reason: ImportRejection) : ImportResult
}

/** Progress callback so the UI can show a real progress bar rather than a spinner. */
fun interface ImportProgress {
    fun onProgress(stage: String, done: Long, total: Long)
}

/**
 * Copies a package into the virtual space.
 *
 * Two decisions here are load-bearing:
 *
 *  - **Validation happens at import, not at launch.** An APK with no arm64 slice, or with
 *    4 KB-aligned libraries on a 16 KB-page device, is refused now with a sentence the
 *    user can act on — instead of producing a `dlopen` failure at first launch that looks
 *    like a crash in the app.
 *  - **Extraction is atomic.** Libraries go to a temp directory and are renamed into
 *    place, then made read-only. A killed install can therefore never leave a partially
 *    populated `lib/` directory that a later launch would happily `dlopen` from.
 */
class PackageInstaller(
    private val model: VirtualPathModel,
    private val devicePageSize: Int,
    private val device: DeviceSpec = DeviceSpec.ARM64,
) {

    fun import(files: List<File>, progress: ImportProgress? = null): ImportResult {
        val bundle = runCatching { ApkBundleReader.read(files) }.getOrElse {
            return ImportResult.Rejected(ImportRejection.Unreadable(it.message ?: "Could not read the APK."))
        }
        return import(bundle, progress)
    }

    fun import(bundle: ApkBundle, progress: ImportProgress? = null): ImportResult {
        val m = bundle.manifest
        val selection = bundle.select(device)

        val targetDir = File(model.apkDir(m.packageName, m.versionCode))
        val staging = File(targetDir.parentFile, "${targetDir.name}.staging")
        staging.deleteRecursively()
        staging.mkdirs()

        var written = 0L
        val total = selection.totalBytes
        val copied = ArrayList<Pair<ApkPart, File>>()
        for (part in selection.keep) {
            val dest = if (part.isBase) File(staging, "base.apk")
            else File(staging, "split_${part.splitName}.apk")
            part.file.copyTo(dest, overwrite = true)
            written += dest.length()
            copied += part to dest
            progress?.onProgress("Copying", written, total)
        }

        // Native libraries: extract, validate, then lock down.
        val libStaging = File(staging, "lib/${Abi.ARM64_V8A.dirName}")
        val extraction = extractNativeLibraries(copied.map { it.second }, libStaging, progress)

        if (extraction.foundAnyNativeCode && extraction.extracted == 0) {
            staging.deleteRecursively()
            return ImportResult.Rejected(ImportRejection.NoArm64())
        }

        val misaligned = extraction.misalignedFor(devicePageSize.toLong())
        val flags = mutableSetOf<CompatFlag>()
        if (misaligned.isNotEmpty()) {
            flags += CompatFlag.NATIVE_ALIGNMENT_16K
            // Refuse only when this device actually has the larger page size; the same
            // APK is perfectly loadable on a 4 KB device, and refusing there would be
            // wrong.
            if (devicePageSize > 4096) {
                staging.deleteRecursively()
                return ImportResult.Rejected(
                    ImportRejection.NotAligned16K(
                        libraries = misaligned,
                        message = "This app's native libraries are aligned for 4 KB memory pages, " +
                            "but this device uses ${devicePageSize / 1024} KB pages. " +
                            "Android cannot load them. The app needs an update from its developer.",
                    )
                )
            }
            Diagnostics.warn(
                DiagChannel.NATIVE, "NATIVE_ALIGNMENT_16K",
                mapOf("package" to m.packageName, "libraries" to misaligned.joinToString(",")),
            )
        }

        // Atomic publish.
        targetDir.parentFile?.mkdirs()
        if (targetDir.exists()) {
            makeWritable(targetDir)
            targetDir.deleteRecursively()
        }
        if (!staging.renameTo(targetDir)) {
            staging.deleteRecursively()
            return ImportResult.Rejected(ImportRejection.Unsupported("Could not finalise the install."))
        }

        // W^X.
        //
        // Android 10+ refuses to load code from a writable file. This applies to the APK
        // itself, not only to extracted .so files: ART rejects a writable dex with
        // "Writable dex file '...' is not allowed", which surfaces as a failure to create
        // the Application and looks nothing like a permissions problem. Every APK and
        // every extracted library is therefore made read-only before first launch.
        val locked = lockDownApks(targetDir)
        val libDir = File(targetDir, "lib/${Abi.ARM64_V8A.dirName}")
        if (libDir.isDirectory) lockDown(libDir)

        Diagnostics.info(
            DiagChannel.STORAGE, "PACKAGE_IMPORTED",
            mapOf(
                "package" to m.packageName,
                "versionCode" to m.versionCode.toString(),
                "splits" to selection.keep.size.toString(),
                "libs" to extraction.extracted.toString(),
                "bytes" to written.toString(),
                "readOnlyApks" to locked.toString(),
            ),
        )
        return ImportResult.Installed(
            manifest = m,
            apkDir = targetDir,
            nativeLibraryDir = libDir.takeIf { it.isDirectory },
            extractedLibraries = extraction.extracted,
            compatFlags = flags,
            bytesWritten = written,
        )
    }

    private data class Extraction(
        val extracted: Int,
        val foundAnyNativeCode: Boolean,
        val alignments: Map<String, Long>,
    ) {
        fun misalignedFor(pageSize: Long) =
            alignments.filterValues { it < pageSize }.keys.sorted()
    }

    private fun extractNativeLibraries(
        apks: List<File>, destDir: File, progress: ImportProgress?,
    ): Extraction {
        val prefix = "lib/${Abi.ARM64_V8A.dirName}/"
        var extracted = 0
        var sawNative = false
        val alignments = HashMap<String, Long>()

        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/") }
                    .toList()
                if (entries.isNotEmpty()) sawNative = true
                for (entry in entries.filter { it.name.startsWith(prefix) }) {
                    val name = entry.name.substringAfterLast('/')
                    if (!name.endsWith(".so")) continue
                    destDir.mkdirs()
                    val dest = File(destDir, name)
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    runCatching { ElfInspector.inspect(dest.readBytes()) }
                        .onSuccess { info ->
                            alignments[name] = info.maxPageSize
                            if (!info.isArm64) {
                                // A mislabelled library would fail at dlopen; drop it now.
                                Diagnostics.warn(
                                    DiagChannel.NATIVE, "NATIVE_ABI_MISMATCH",
                                    mapOf("library" to name, "machine" to info.machine.name),
                                )
                                dest.delete()
                                return@onSuccess
                            }
                            extracted++
                        }
                        .onFailure {
                            Diagnostics.warn(
                                DiagChannel.NATIVE, "NATIVE_ELF_UNREADABLE",
                                mapOf("library" to name, "error" to it.toString()),
                            )
                            dest.delete()
                        }
                    progress?.onProgress("Extracting native libraries", extracted.toLong(), entries.size.toLong())
                }
            }
        }
        return Extraction(extracted, sawNative, alignments)
    }

    /**
     * Makes every APK in [dir] read-only, and verifies it.
     *
     * Verified rather than assumed: `File.setWritable` returns false on some filesystems
     * without throwing, and a silently-still-writable APK fails much later with an error
     * that points at the wrong thing.
     */
    private fun lockDownApks(dir: File): Int {
        var locked = 0
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.forEach { apk ->
            apk.setWritable(false, false)
            apk.setReadable(true, false)
            if (apk.canWrite()) {
                Diagnostics.warn(
                    DiagChannel.STORAGE, "APK_STILL_WRITABLE",
                    mapOf("path" to apk.absolutePath),
                )
            } else {
                locked++
            }
        }
        return locked
    }

    private fun lockDown(dir: File) {
        dir.listFiles()?.forEach {
            it.setWritable(false, false)
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
        dir.setWritable(false, false)
    }

    private fun makeWritable(f: File) {
        f.setWritable(true, true)
        if (f.isDirectory) f.listFiles()?.forEach { makeWritable(it) }
    }
}
