package com.unique.app.engine

import android.content.Context
import android.os.Process
import android.app.ActivityManager
import android.content.pm.PackageManager
import androidx.room.Room
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ApkBundleReader
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.LaunchResult
import com.unique.core.vam.VirtualLauncher
import com.unique.core.vpm.CreateResult
import com.unique.core.vpm.Instance
import com.unique.core.vpm.UpdateResult
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

    /**
     * Updates an imported package in place, keeping every instance's data.
     *
     * Running instances are stopped first, which is what the platform does when it
     * updates an installed app: an app whose code changes underneath it sees a class
     * loader and a resource table that no longer agree with each other.
     */
    suspend fun update(context: Context, files: List<File>): UpdateResult {
        val bundle = runCatching { ApkBundleReader.read(files) }.getOrNull()
        bundle?.manifest?.packageName?.let { stopInstancesOf(context, it) }
        return instances.update(files)
    }

    /** Updates from an app installed on the device, splits included. */
    suspend fun updateFromInstalled(context: Context, packageName: String): UpdateResult {
        val info = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrElse {
            return UpdateResult.Rejected("$packageName is not installed on this device.")
        }
        val files = buildList {
            add(File(info.publicSourceDir ?: info.sourceDir))
            info.splitPublicSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }
        if (files.isEmpty()) {
            return UpdateResult.Rejected("Could not read $packageName's APK files.")
        }
        return update(context, files)
    }

    /**
     * Kills the virtual processes serving a package.
     *
     * Only UNIQUE's own `:vappN` processes, found through `getRunningAppProcesses`, which
     * lists this app's processes and no others. `/proc` cannot be used for this: it is
     * mounted `hidepid` on modern Android and the scan comes back empty.
     */
    private fun stopInstancesOf(context: Context, packageName: String): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        val mine = runCatching { am.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
        var stopped = 0
        for (process in mine) {
            if (!process.processName.contains(":vapp")) continue
            if (process.pid == Process.myPid()) continue
            Process.killProcess(process.pid)
            stopped++
        }
        if (stopped > 0) {
            Diagnostics.info(
                DiagChannel.STORAGE, "INSTANCES_STOPPED_FOR_UPDATE",
                mapOf("package" to packageName, "processes" to stopped.toString()),
            )
        }
        return stopped
    }

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
        if (result is LaunchResult.Started) {
            instances.markLaunched(vuid)
            // Superseded APKs are reclaimed here rather than at update time: a task
            // restored between the two still has to find the version it names.
            runCatching { instances.pruneSupersededVersions(instance.packageName) }
        }
        return result
    }
}
