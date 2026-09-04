package com.unique.app.engine

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.LaunchResult
import com.unique.core.vam.VirtualLauncher
import com.unique.core.vpm.CreateResult
import com.unique.core.vpm.Instance
import com.unique.core.vpm.InstanceManager
import com.unique.core.vpm.db.UniqueDatabase
import com.unique.core.vstorage.VirtualStorage
import java.io.File

/**
 * The orchestration facade: import, create, launch.
 *
 * One entry point used by both the Flutter bridge and the on-device verification suite,
 * so the interface and the tests exercise the same path. A test that reaches past the
 * facade proves less than the product needs.
 *
 * The state database is owned by the UI process for now. `:server` takes ownership in
 * phase 3, when there is more than one writer; making it a service before there is a
 * second writer would add IPC without adding safety.
 */
object UniqueEngine {

    /** Must equal `vappProcessCount` in the root build script. */
    const val SLOT_COUNT = 16

    @Volatile private var database: UniqueDatabase? = null
    @Volatile private var storageRef: VirtualStorage? = null
    @Volatile private var managerRef: InstanceManager? = null
    @Volatile private var launcherRef: VirtualLauncher? = null

    @Synchronized
    fun init(context: Context) {
        if (database != null) return
        // getApplicationContext() returns null while the Application is still being
        // attached, which is exactly when this is first called. The base context passed
        // in is a fully usable ContextImpl, so it is the correct thing to hold on to.
        val app = context.applicationContext ?: context
        val db = Room.databaseBuilder(app, UniqueDatabase::class.java, UniqueDatabase.NAME)
            .addMigrations(*UniqueDatabase.MIGRATIONS)
            .build()
        val storage = VirtualStorage(app)
        database = db
        storageRef = storage
        managerRef = InstanceManager(app, db, storage)
        launcherRef = VirtualLauncher(app.packageName, SLOT_COUNT)
    }

    val storage: VirtualStorage get() = requireNotNull(storageRef) { "UniqueEngine.init not called" }
    val instances: InstanceManager get() = requireNotNull(managerRef) { "UniqueEngine.init not called" }
    val launcher: VirtualLauncher get() = requireNotNull(launcherRef) { "UniqueEngine.init not called" }

    /**
     * Imports an app that is already installed on the device.
     *
     * The installed APK set is read straight from `ApplicationInfo`, so nothing is
     * downloaded and no file picker is involved. Splits are included; the importer
     * selects among them and refuses anything this device cannot run.
     */
    suspend fun importInstalled(context: Context, packageName: String): CreateResult {
        val info = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrElse {
            return CreateResult.Rejected("$packageName is not installed on this device.")
        }
        val files = buildList {
            add(File(info.publicSourceDir ?: info.sourceDir))
            info.splitPublicSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }

        if (files.isEmpty()) {
            return CreateResult.Rejected("Could not read $packageName's APK files.")
        }
        Diagnostics.info(
            DiagChannel.STORAGE, "IMPORT_STARTED",
            mapOf("package" to packageName, "files" to files.size.toString()),
        )
        return instances.importAndCreate(files)
    }

    /** Imports from explicit files: a base APK plus any splits. */
    suspend fun importFiles(files: List<File>): CreateResult = instances.importAndCreate(files)

    /** Reads the manifest of an imported package straight from its stored base APK. */
    fun manifestOf(instance: Instance): ApkManifest =
        ManifestReader.fromApk(
            File(storage.model.baseApk(instance.packageName, instance.versionCode))
        )

    suspend fun launch(
        context: Context,
        vuid: Int,
        targetActivity: String? = null,
    ): LaunchResult {
        val instance = instances.instance(vuid)
            ?: return LaunchResult.Failed("NO_SUCH_INSTANCE", "Instance $vuid does not exist.")
        val manifest = runCatching { manifestOf(instance) }.getOrElse {
            return LaunchResult.Failed("MANIFEST_UNREADABLE", it.toString())
        }
        val result = launcher.launch(
            context = context,
            vuid = vuid,
            packageName = instance.packageName,
            versionCode = instance.versionCode,
            manifest = manifest,
            targetActivity = targetActivity,
        )
        if (result is LaunchResult.Started) instances.markLaunched(vuid)
        return result
    }
}
