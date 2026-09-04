package com.unique.core.vpm

import android.content.Context
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.common.profile.DeviceProfile
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
import java.util.UUID

/** A virtual instance as the rest of the system uses it. */
data class Instance(
    val vuid: Int,
    val packageName: String,
    val versionCode: Long,
    val displayName: String,
    val profile: DeviceProfile,
)

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
        val installer = PackageInstaller(model, UniqueNative.pageSize())
        return when (val result = installer.import(files)) {
            is ImportResult.Rejected -> CreateResult.Rejected(result.reason.message)
            is ImportResult.Installed -> {
                registerPackage(result.manifest, result.apkDir, result.nativeLibraryDir)
                createInstance(result.manifest.packageName, displayName)
            }
        }
    }

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
