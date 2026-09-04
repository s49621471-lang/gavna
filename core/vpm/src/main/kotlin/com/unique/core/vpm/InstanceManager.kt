package com.unique.core.vpm

import android.content.Context
import android.content.res.Resources
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.unique.core.common.apk.Abi
import com.unique.core.common.apk.ApkBundleReader
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.DeviceSpec
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.common.profile.DeviceProfile
import com.unique.core.common.profile.DeviceProfileCodec
import com.unique.core.common.profile.DeviceProfileFactory
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.nativebridge.UniqueNative
import com.unique.core.vpm.db.DeviceProfileRecord
import com.unique.core.vpm.db.InstanceEntity
import com.unique.core.vpm.db.PackageEntity
import com.unique.core.vpm.db.UniqueDatabase
import com.unique.core.vstorage.VirtualStorage
import java.io.File
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID

/** A virtual instance as the rest of the system uses it. */
data class Instance(
    val vuid: Int,
    val packageName: String,
    val versionCode: Long,
    val displayName: String,
    val profile: DeviceProfile,
)

/**
 * What happened to an update.
 *
 * A separate result type from [CreateResult] because the interesting outcomes are
 * different: an update can be refused for reasons an install cannot (a downgrade, a
 * different signer) and can succeed while changing nothing (the same version again).
 */
sealed interface UpdateResult {
    data class Updated(
        val packageName: String,
        val fromVersionCode: Long,
        val toVersionCode: Long,
        val instances: Int,
    ) : UpdateResult

    /** The same version, already imported. Not an error and not work. */
    data class Unchanged(val packageName: String, val versionCode: Long) : UpdateResult

    data class Rejected(val reason: String) : UpdateResult
}

sealed interface CreateResult {
    data class Created(val instance: Instance, val manifest: ApkManifest) : CreateResult
    data class Rejected(val reason: String) : CreateResult
}

/**
 * Owns the lifecycle of virtual packages and their instances.
 *
 * Import, registration and instance creation are one unit of work on purpose: a package
 * row without files, or an instance row without directories, is a state the launcher
 * would fail on later and a user could not repair. Anything that cannot be completed is
 * rolled back to "not there" rather than left half-done.
 */
class InstanceManager(
    private val context: Context,
    private val database: UniqueDatabase,
    private val storage: VirtualStorage,
    private val profileFactory: DeviceProfileFactory = DeviceProfileFactory(),
) {
    private val model: VirtualPathModel get() = storage.model
    private val dao get() = database.dao()

    /**
     * Imports an APK set and creates the first instance of it.
     *
     * @param files base APK, plus any splits.
     */
    suspend fun importAndCreate(files: List<File>, displayName: String? = null): CreateResult {
        val installer = PackageInstaller(model, UniqueNative.pageSize(), deviceSpec())
        return when (val result = installer.import(files)) {
            is ImportResult.Rejected -> CreateResult.Rejected(result.reason.message)
            is ImportResult.Installed -> {
                registerPackage(result.manifest, result.apkDir, result.nativeLibraryDir)
                createInstance(result.manifest.packageName, displayName)
            }
        }
    }

    /**
     * Writes the instance's profile where its virtual process can read it.
     *
     * Under `runtime/`, not in the app's data directory: a guest that can rewrite its own
     * device identity has none.
     */
    private fun writeProfileFile(vuid: Int, profile: DeviceProfile) {
        runCatching {
            val file = File(model.profileFile(vuid))
            file.parentFile?.mkdirs()
            file.writeText(DeviceProfileCodec.encode(profile))
        }.onFailure {
            Diagnostics.error(
                DiagChannel.PROCESS, "PROFILE_FILE_WRITE_FAILED",
                mapOf("vuid" to vuid.toString(), "error" to it.toString()),
            )
        }
    }

    /**
     * What this device actually is, rather than what the product targets.
     *
     * `Build.SUPPORTED_ABIS` is in the platform's own preference order and drives both
     * split selection and which `lib/<abi>/` is extracted. Leaving it at the ARM64 default
     * meant an x86_64 device picked the wrong ABI split and extracted no native code at
     * all - so the native path could not be exercised anywhere but a phone.
     */
    private fun deviceSpec(): DeviceSpec = DeviceSpec(
        abis = Build.SUPPORTED_ABIS.orEmpty().mapNotNull { Abi.fromDirName(it) },
        densityDpi = Resources.getSystem().displayMetrics.densityDpi,
        languages = listOf(Locale.getDefault().language),
    )

    private suspend fun registerPackage(
        manifest: ApkManifest,
        apkDir: File,
        nativeLibraryDir: File?,
    ) {
        val splits = apkDir.listFiles()
            ?.filter { it.name.startsWith("split_") }
            ?.joinToString(",") { it.name }
            .orEmpty()
        dao.upsertPackage(
            PackageEntity(
                packageName = manifest.packageName,
                label = manifest.label,
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
                minSdk = manifest.minSdk,
                targetSdk = manifest.targetSdk,
                apkDir = apkDir.absolutePath,
                nativeLibraryDir = nativeLibraryDir?.absolutePath,
                splits = splits,
                importedAtMillis = System.currentTimeMillis(),
                hostUpdateVersionCode = null,
            )
        )
    }

    /**
     * Replaces an imported package with a newer version, keeping every instance's data.
     *
     * Instances carry no version of their own - they read it from the package row - so
     * repointing that row updates all of them at once, and `users/<vuid>/` is never
     * touched. That is the whole reason the layout separates the two.
     *
     * Two refusals, both deliberate and both matching what the platform does for an
     * installed app:
     *
     *  - **A downgrade.** An older version reading a newer version's data is how data
     *    loss happens; the platform refuses it and so does this.
     *  - **A different signing certificate.** This is the one that matters for safety: a
     *    package that is not signed by the same key must not be able to take over an
     *    existing instance's data. It is exactly what `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
     *    protects against.
     */
    suspend fun update(files: List<File>): UpdateResult {
        val installer = PackageInstaller(model, UniqueNative.pageSize(), deviceSpec())
        val bundle = runCatching { ApkBundleReader.read(files) }
            .getOrElse { return UpdateResult.Rejected("Could not read the APK: ${it.message}") }
        val incoming = bundle.manifest
        val existing = dao.packageOf(incoming.packageName)
            ?: return UpdateResult.Rejected(
                "${incoming.packageName} has not been imported, so there is nothing to update."
            )

        if (incoming.versionCode < existing.versionCode) {
            return UpdateResult.Rejected(
                "That is version ${incoming.versionCode}, older than the installed " +
                    "${existing.versionCode}. Downgrading would let an older build read a " +
                    "newer build's data."
            )
        }
        if (incoming.versionCode == existing.versionCode) {
            return UpdateResult.Unchanged(incoming.packageName, existing.versionCode)
        }

        val oldBase = File(model.baseApk(incoming.packageName, existing.versionCode))
        val newBase = bundle.base.file
        val verdict = signerMatch(oldBase, newBase)
        if (verdict is SignerVerdict.Mismatch) return UpdateResult.Rejected(verdict.reason)
        if (verdict is SignerVerdict.Unknown) {
            return UpdateResult.Rejected(
                "Could not verify that the update is signed by the same key: ${verdict.reason}"
            )
        }

        return when (val result = installer.import(bundle)) {
            is ImportResult.Rejected -> UpdateResult.Rejected(result.reason.message)
            is ImportResult.Installed -> {
                registerPackage(result.manifest, result.apkDir, result.nativeLibraryDir)
                val count = dao.instancesOf(incoming.packageName).size
                Diagnostics.info(
                    DiagChannel.STORAGE, "PACKAGE_UPDATED",
                    mapOf(
                        "package" to incoming.packageName,
                        "from" to existing.versionCode.toString(),
                        "to" to incoming.versionCode.toString(),
                        "instances" to count.toString(),
                    ),
                )
                UpdateResult.Updated(
                    incoming.packageName, existing.versionCode, incoming.versionCode, count,
                )
            }
        }
    }

    private sealed interface SignerVerdict {
        data object Match : SignerVerdict
        data class Mismatch(val reason: String) : SignerVerdict
        data class Unknown(val reason: String) : SignerVerdict
    }

    /**
     * Compares the signing certificates of two APKs, using the platform's own parser.
     *
     * Not UNIQUE's own: signature verification is the wrong place for a second
     * implementation, and the platform's answer is by definition the one the device would
     * give. An APK it cannot parse is [SignerVerdict.Unknown] rather than a match — an
     * unverifiable update must not be allowed to inherit data.
     */
    private fun signerMatch(oldApk: File, newApk: File): SignerVerdict {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val pm = context.packageManager
        val oldInfo = runCatching { pm.getPackageArchiveInfo(oldApk.absolutePath, flags) }.getOrNull()
            ?: return SignerVerdict.Unknown("the installed APK could not be parsed")
        val newInfo = runCatching { pm.getPackageArchiveInfo(newApk.absolutePath, flags) }.getOrNull()
            ?: return SignerVerdict.Unknown("the new APK could not be parsed")

        val oldCerts = certificatesOf(oldInfo)
        val newCerts = certificatesOf(newInfo)
        if (oldCerts.isEmpty() || newCerts.isEmpty()) {
            return SignerVerdict.Unknown("one of the APKs carries no signature")
        }
        return if (oldCerts == newCerts) SignerVerdict.Match
        else SignerVerdict.Mismatch(
            "The update is signed by a different key. Replacing an app with one signed by " +
                "someone else would hand that signer the instance's existing data."
        )
    }

    private fun certificatesOf(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val legacy = info.signatures?.map { it.toCharsString() }.orEmpty()
        if (legacy.isNotEmpty()) return legacy.toSet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners
        else signing.signingCertificateHistory
        return certs.orEmpty().map { it.toCharsString() }.toSet()
    }

    /**
     * Removes every version of a package except the one the package row names.
     *
     * Deliberately *not* done at update time. Killing a virtual process leaves its task
     * behind, and the system relaunches the activity from the intent it stored - which
     * still names the old version. Deleting it immediately turned "update an app" into
     * "the app can never be reopened from Recents". `AppBootstrap` also tolerates a stale
     * version now; this simply stops the disk filling up with superseded APKs.
     *
     * Safe while an instance is still running on an old version: the pages are already
     * mapped, and on Linux an unlinked file stays valid until every mapping is dropped.
     */
    suspend fun pruneSupersededVersions(packageName: String): Int {
        val current = dao.packageOf(packageName)?.versionCode ?: return 0
        val root = File(model.apkDir(packageName, 0L)).parentFile ?: return 0
        var removed = 0
        root.listFiles()?.forEach { dir ->
            val version = dir.name.toLongOrNull() ?: return@forEach
            if (version == current) return@forEach
            if (pruneOldVersion(packageName, version)) removed++
        }
        if (removed > 0) {
            Diagnostics.info(
                DiagChannel.STORAGE, "OLD_VERSIONS_PRUNED",
                mapOf("package" to packageName, "removed" to removed.toString()),
            )
        }
        return removed
    }

    private fun pruneOldVersion(packageName: String, versionCode: Long): Boolean {
        return runCatching {
            val dir = File(model.apkDir(packageName, versionCode))
            if (!dir.isDirectory) return@runCatching false
            dir.walkBottomUp().forEach { it.setWritable(true, false); it.delete() }
            true
        }.getOrElse {
            // Not fatal: a stale directory costs disk, not correctness, and the package
            // row already points at the new version.
            Diagnostics.warn(
                DiagChannel.STORAGE, "OLD_VERSION_PRUNE_FAILED",
                mapOf("package" to packageName, "versionCode" to versionCode.toString()),
            )
            false
        }
    }

    /**
     * Creates an additional independent instance of an already-imported package.
     *
     * The APK is not copied again: instances share one read-only package directory and
     * differ only in `users/<vuid>/`.
     */
    suspend fun createInstance(packageName: String, displayName: String? = null): CreateResult {
        val pkg = dao.packageOf(packageName)
            ?: return CreateResult.Rejected("$packageName has not been imported.")

        val baseApk = File(model.baseApk(packageName, pkg.versionCode))
        val manifest = runCatching { ManifestReader.fromApk(baseApk) }.getOrElse {
            return CreateResult.Rejected("Could not read the imported manifest: $it")
        }

        val vuid = dao.nextVuid()
        val existing = dao.instancesOf(packageName).size
        val name = displayName ?: if (existing == 0) "Profile 1" else "Profile ${existing + 1}"
        val profile = profileFactory.create(name)
        // A copy where the virtual process can read it. The profile lives in the state
        // database, which a :vappN deliberately has no IPC to on the launch path.
        writeProfileFile(vuid, profile)

        storage.prepareInstance(vuid, packageName)

        runCatching {
            dao.upsertInstance(
                InstanceEntity(
                    vuid = vuid,
                    packageName = packageName,
                    spaceId = DEFAULT_SPACE,
                    displayName = name,
                    createdAtMillis = System.currentTimeMillis(),
                    lastLaunchedAtMillis = null,
                    profile = profile.toRecord(),
                )
            )
        }.onFailure {
            // The directories exist but the row does not, so nothing references them.
            // Removing them keeps "not there" as the only failed state.
            storage.removeInstance(vuid, packageName)
            return CreateResult.Rejected("Could not record the instance: $it")
        }

        Diagnostics.info(
            DiagChannel.STORAGE, "INSTANCE_CREATED",
            mapOf(
                "package" to packageName,
                "vuid" to vuid.toString(),
                "name" to name,
                "androidId" to profile.androidId,
            ),
        )
        return CreateResult.Created(
            Instance(vuid, packageName, pkg.versionCode, name, profile),
            manifest,
        )
    }

    /** Every instance, resolved against its package. */
    suspend fun instances(): List<Instance> =
        dao.instances().first().mapNotNull { row ->
            val pkg = dao.packageOf(row.packageName) ?: return@mapNotNull null
            Instance(
                row.vuid, row.packageName, pkg.versionCode,
                row.displayName, row.profile.toProfile(),
            )
        }

    suspend fun instance(vuid: Int): Instance? {
        val row = dao.instance(vuid) ?: return null
        val pkg = dao.packageOf(row.packageName) ?: return null
        return Instance(row.vuid, row.packageName, pkg.versionCode, row.displayName, row.profile.toProfile())
    }

    suspend fun markLaunched(vuid: Int) {
        val row = dao.instance(vuid) ?: return
        dao.updateInstance(row.copy(lastLaunchedAtMillis = System.currentTimeMillis()))
    }

    suspend fun removeInstance(vuid: Int) {
        val row = dao.instance(vuid) ?: return
        storage.removeInstance(vuid, row.packageName)
        dao.removeInstanceAndOrphanPackage(vuid)
    }

    companion object {
        const val DEFAULT_SPACE = "default"
    }
}

internal fun DeviceProfile.toRecord() = DeviceProfileRecord(
    profileId = profileId,
    displayName = displayName,
    androidId = androidId,
    instanceId = instanceId.toString(),
    installId = installId.toString(),
    gsfId = gsfId,
    mediaDrmId = mediaDrmId,
    wifiMac = wifiMac,
    bluetoothMac = bluetoothMac,
    serial = serial,
    locale = locale,
    timeZone = timeZone,
    generation = generation,
)

internal fun DeviceProfileRecord.toProfile() = DeviceProfile(
    profileId = profileId,
    displayName = displayName,
    androidId = androidId,
    instanceId = UUID.fromString(instanceId),
    installId = UUID.fromString(installId),
    gsfId = gsfId,
    mediaDrmId = mediaDrmId,
    wifiMac = wifiMac,
    bluetoothMac = bluetoothMac,
    serial = serial,
    locale = locale,
    timeZone = timeZone,
    generation = generation,
)
