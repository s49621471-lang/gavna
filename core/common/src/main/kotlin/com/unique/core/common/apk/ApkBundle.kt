package com.unique.core.common.apk

import java.io.File
import java.util.Locale

/** Every ABI Android knows about, plus which of them UNIQUE will execute. */
/**
 * @param supported whether UNIQUE will run a virtual app on this ABI.
 *
 * 64-bit only, and no CPU emulation: a guest executes the device's own instruction set or
 * it does not run. `arm64-v8a` is the product target; `x86_64` is supported because that
 * is what an emulator and a Chromebook are, and refusing it would mean the native path
 * could never be exercised anywhere but a phone.
 */
enum class Abi(val dirName: String, val is64Bit: Boolean, val supported: Boolean) {
    ARM64_V8A("arm64-v8a", true, true),
    ARMEABI_V7A("armeabi-v7a", false, false),
    ARMEABI("armeabi", false, false),
    X86_64("x86_64", true, true),
    X86("x86", false, false),
    RISCV64("riscv64", true, false);

    /** Split-name form: `arm64-v8a` appears in split names as `arm64_v8a`. */
    val splitToken: String get() = dirName.replace('-', '_')

    companion object {
        fun fromDirName(name: String): Abi? = entries.firstOrNull { it.dirName == name }

        /**
         * The ABI to extract, given what the device runs and what the APK carries.
         *
         * `Build.SUPPORTED_ABIS` is in the platform's own preference order, so the first
         * entry that is both supported here and present in the APK is the same choice the
         * platform would make for an installed app. Hard-coding `arm64-v8a` instead meant
         * a device that is not ARM64 extracted nothing at all and the native path could
         * not be exercised there — which is the emulator, and therefore every test.
         *
         * Returns null when the APK carries no ABI this device can execute. That is a
         * refusal, not a fallback: running ARM code on x86 would need an emulator UNIQUE
         * deliberately does not have.
         */
        fun preferred(deviceAbis: List<String>, apkAbis: Collection<String>): Abi? {
            val available = apkAbis.mapNotNull { fromDirName(it) }.toSet()
            for (name in deviceAbis) {
                val abi = fromDirName(name) ?: continue
                if (abi.supported && abi in available) return abi
            }
            return null
        }
        fun fromSplitToken(token: String): Abi? = entries.firstOrNull { it.splitToken == token }
        val supportedSet: List<Abi> get() = entries.filter { it.supported }
    }
}

enum class SplitKind { BASE, FEATURE, ABI, DENSITY, LANGUAGE, UNKNOWN }

/** One APK file within a package: the base, a feature split, or a configuration split. */
data class ApkPart(
    val file: File,
    val splitName: String?,
    val kind: SplitKind,
    /** Set for [SplitKind.ABI]. */
    val abi: Abi? = null,
    /** Set for [SplitKind.DENSITY], e.g. "xxhdpi". */
    val density: String? = null,
    /** Set for [SplitKind.LANGUAGE], e.g. "en" or "pt-rBR". */
    val language: String? = null,
    /** Feature splits carry their own components; config splits carry only resources. */
    val featureOf: String? = null,
) {
    val isBase: Boolean get() = kind == SplitKind.BASE
}

/**
 * A package as it exists on disk before import: one base APK plus zero or more splits.
 *
 * Split *selection* is done here rather than at install time so the importer can report
 * exactly which parts it will keep — and refuse an unsupported package before copying
 * hundreds of megabytes.
 */
data class ApkBundle(
    val parts: List<ApkPart>,
    val manifest: ApkManifest,
) {
    val base: ApkPart get() = parts.first { it.isBase }

    /**
     * Chooses the splits to install for a device.
     *
     * Rules mirror the Play Store's own resolution, minus the parts that need a resource
     * table: the ABI split for the running ABI, the density split nearest to (and not
     * below) the device density where available, the language splits for the user's
     * locales plus the default, and every feature split. Feature splits are always kept
     * because dropping one silently removes components from the app.
     */
    fun select(device: DeviceSpec): SplitSelection {
        val kept = ArrayList<ApkPart>()
        val dropped = ArrayList<Pair<ApkPart, String>>()

        kept += base
        parts.filter { it.kind == SplitKind.FEATURE }.forEach { kept += it }

        val abiParts = parts.filter { it.kind == SplitKind.ABI }
        if (abiParts.isNotEmpty()) {
            val chosen = device.abis.firstNotNullOfOrNull { abi -> abiParts.firstOrNull { it.abi == abi } }
            if (chosen != null) kept += chosen
            abiParts.filter { it !== chosen }.forEach { dropped += it to "ABI not selected" }
        }

        val densityParts = parts.filter { it.kind == SplitKind.DENSITY }
        if (densityParts.isNotEmpty()) {
            val chosen = pickDensity(densityParts, device.densityDpi)
            if (chosen != null) kept += chosen
            densityParts.filter { it !== chosen }.forEach { dropped += it to "density not selected" }
        }

        val langParts = parts.filter { it.kind == SplitKind.LANGUAGE }
        if (langParts.isNotEmpty()) {
            val wanted = device.languages.map { it.lowercase(Locale.ROOT) }.toSet()
            langParts.forEach { p ->
                val lang = p.language?.lowercase(Locale.ROOT)?.substringBefore('-')
                // Keep the device's languages and English, which is what apps fall back to.
                if (lang != null && (lang in wanted || lang == "en")) kept += p
                else dropped += p to "language not selected"
            }
        }

        parts.filter { it.kind == SplitKind.UNKNOWN }.forEach {
            // Unknown config dimensions (e.g. a texture-compression split) are kept:
            // dropping something we do not understand risks removing required assets.
            kept += it
        }

        // De-duplicate by split identity, not by file path: two parts are the same part
        // only when they are the same split. Keying on the path would silently drop a
        // legitimate split whenever a caller passes the same file under two names.
        return SplitSelection(kept.distinctBy { it.splitName }, dropped)
    }

    private fun pickDensity(parts: List<ApkPart>, dpi: Int): ApkPart? {
        val ordered = listOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
            "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
        )
        val byName = parts.associateBy { it.density }
        // Smallest bucket that is >= the device density, else the largest available.
        return ordered.firstOrNull { it.second >= dpi && byName.containsKey(it.first) }
            ?.let { byName[it.first] }
            ?: ordered.lastOrNull { byName.containsKey(it.first) }?.let { byName[it.first] }
    }
}

data class SplitSelection(
    val keep: List<ApkPart>,
    val drop: List<Pair<ApkPart, String>>,
) {
    val totalBytes: Long get() = keep.sumOf { it.file.length() }
}

/** The device attributes that drive split selection. */
data class DeviceSpec(
    val abis: List<Abi>,
    val densityDpi: Int,
    val languages: List<String>,
) {
    companion object {
        /** ARM64-only, which is UNIQUE's stated target. */
        val ARM64 = DeviceSpec(listOf(Abi.ARM64_V8A), 480, listOf("en"))
    }
}

object ApkBundleReader {

    private val DENSITIES = setOf(
        "ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi",
    )

    /**
     * Builds a bundle from a base APK and a set of candidate split files.
     *
     * The base is identified by parsing manifests rather than by filename, because
     * filenames coming out of `.xapk`/`.apks` containers and third-party mirrors are not
     * reliable. A file whose manifest declares no `split` attribute is the base.
     */
    fun read(files: List<File>): ApkBundle {
        require(files.isNotEmpty()) { "no APK files supplied" }
        val parsed = files.map { it to ManifestReader.fromApk(it) }
        val baseEntry = parsed.firstOrNull { it.second.splitName.isNullOrEmpty() }
            ?: throw BinaryXmlException("no base APK among ${files.size} file(s): every one declares a split name")
        val basePkg = baseEntry.second.packageName

        val mismatched = parsed.filter { it.second.packageName != basePkg }
        if (mismatched.isNotEmpty()) {
            throw BinaryXmlException(
                "split(s) belong to a different package: " +
                    mismatched.joinToString { "${it.first.name}=${it.second.packageName}" }
            )
        }

        val parts = parsed.map { (file, m) ->
            val split = m.splitName
            if (split.isNullOrEmpty()) ApkPart(file, null, SplitKind.BASE) else classify(file, split, m)
        }
        return ApkBundle(parts, baseEntry.second)
    }

    internal fun classify(file: File, splitName: String, manifest: ApkManifest): ApkPart {
        // bundletool split names: "config.<dim>" or "<feature>.config.<dim>" or "<feature>".
        val configIdx = splitName.lastIndexOf("config.")
        if (configIdx >= 0) {
            val dim = splitName.substring(configIdx + "config.".length)
            val feature = splitName.substring(0, configIdx).trimEnd('.').ifEmpty { null }
            Abi.fromSplitToken(dim)?.let {
                return ApkPart(file, splitName, SplitKind.ABI, abi = it, featureOf = feature)
            }
            if (dim in DENSITIES) {
                return ApkPart(file, splitName, SplitKind.DENSITY, density = dim, featureOf = feature)
            }
            if (looksLikeLanguage(dim)) {
                return ApkPart(file, splitName, SplitKind.LANGUAGE, language = dim, featureOf = feature)
            }
            return ApkPart(file, splitName, SplitKind.UNKNOWN, featureOf = feature)
        }
        // No "config." marker: a feature split declares isFeatureSplit or carries components.
        val isFeature = manifest.isFeatureSplit || manifest.components.isNotEmpty()
        return ApkPart(file, splitName, if (isFeature) SplitKind.FEATURE else SplitKind.UNKNOWN)
    }

    /** `en`, `pt`, `zh_CN`, `b+sr+Latn` — anything that is plausibly a locale token. */
    private fun looksLikeLanguage(dim: String): Boolean {
        val head = dim.substringBefore('_').substringBefore('-')
        return head.length in 2..3 && head.all { it.isLetter() }
    }
}
