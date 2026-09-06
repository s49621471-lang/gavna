package com.unique.app.engine

import android.content.Context
import android.content.ComponentName
import android.content.Intent
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
import com.unique.core.vam.ColdBroadcastTarget
import com.unique.core.vam.StubRouter
import com.unique.core.vam.VirtualBroadcastRouter
import com.unique.core.vam.VirtualComponentKind
import com.unique.core.vam.VirtualLaunchParams
import com.unique.core.vam.VirtualProviderRouter
import com.unique.core.vam.VirtualServiceRouter
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
        launcherRef = VirtualLauncher(app.packageName, SLOT_COUNT, app)
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
            return CreateResult.Rejected(
                "NOT_INSTALLED", "$packageName is not installed on this device.",
            )
        }
        val files = buildList {
            add(File(info.publicSourceDir ?: info.sourceDir))
            info.splitPublicSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }

        if (files.isEmpty()) {
            return CreateResult.Rejected(
                "APK_UNREADABLE", "Could not read $packageName's APK files.",
            )
        }
        Diagnostics.info(
            DiagChannel.STORAGE, "IMPORT_STARTED",
            mapOf("package" to packageName, "files" to files.size.toString()),
        )
        return instances.importAndCreate(files)
    }

    /**
     * Rebuilds what UNIQUE's main process knows on every instance's behalf.
     *
     * Called once the engine is up, and again after an import, a clone, an update or a
     * removal. Two tables, both of which exist for the same reason: a guest's components
     * live inside its own process, and something that outlives that process has to know
     * they exist.
     *
     *  - Broadcast routes, so a dead guest can be woken (§6.3.1).
     *  - Provider authorities, so a process that is not the guest's can find it (§6.4.1).
     */
    suspend fun registerBroadcastRoutes(context: Context) {
        VirtualBroadcastRouter.starter = ::startForBroadcast
        VirtualProviderRouter.slotLeaser = ::leaseSlotFor
        var registered = 0
        for (instance in instances.instances()) {
            val manifest = runCatching { manifestOf(instance) }.getOrNull() ?: continue
            VirtualBroadcastRouter.register(context, instance.vuid, manifest)
            VirtualProviderRouter.register(instance.vuid, manifest)
            registered++
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "BROADCAST_ROUTER_READY",
            mapOf(
                "instances" to registered.toString(),
                "actions" to VirtualBroadcastRouter.registeredActions.size.toString(),
                "authorities" to VirtualProviderRouter.size.toString(),
            ),
        )
    }

    /**
     * The `:vappN` slot that will serve one instance's provider process.
     *
     * The same lease the launcher hands out, and idempotent for the same reason: a
     * provider acquisition against a guest that is already running must reach the running
     * process, not start a second copy of it in another slot.
     */
    private fun leaseSlotFor(target: VirtualProviderRouter.Target): Int? =
        launcher.acquireSlot(target.vuid, target.packageName, target.processName)

    /**
     * Brings a virtual process up and hands it a broadcast.
     *
     * A stub *service* rather than an activity: starting a service is the least intrusive
     * way to make a process exist, and a broadcast must not put a window on screen.
     */
    private fun startForBroadcast(
        context: Context,
        target: ColdBroadcastTarget,
        broadcast: Intent,
    ): Boolean = runCatching {
        // The slot is leased here, not at registration: a registration outlives every
        // process it ever names. If the guest is already running this returns the slot it
        // is already in, so the broadcast joins the live process instead of racing a
        // second one into a different `:vappN`.
        val slot = launcher.acquireSlot(target.vuid, target.packageName, target.processName)
        if (slot == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "BROADCAST_NO_FREE_SLOT",
                mapOf("package" to target.packageName, "vuid" to target.vuid.toString()),
            )
            return@runCatching false
        }
        val params = VirtualLaunchParams(
            vuid = target.vuid,
            packageName = target.packageName,
            versionCode = target.versionCode,
            targetComponent = target.receiverClass,
            kind = VirtualComponentKind.RECEIVER,
            processName = target.processName,
            slot = slot,
        )
        val stub = StubRouter.stubService(slot, VirtualServiceRouter.COLD_BROADCAST_STUB_INDEX)
        val intent = Intent().apply {
            component = ComponentName(context.packageName, stub)
            params.writeTo(this)
            putExtra(VirtualLaunchParams.KEY_BROADCAST, broadcast)
        }
        context.startService(intent) != null
    }.getOrElse {
        Diagnostics.error(
            DiagChannel.PROCESS, "BROADCAST_STUB_START_FAILED",
            mapOf("package" to target.packageName, "error" to it.toString()),
        )
        false
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
            return UpdateResult.Rejected(
                "NOT_INSTALLED", "$packageName is not installed on this device.",
            )
        }
        val files = buildList {
            add(File(info.publicSourceDir ?: info.sourceDir))
            info.splitPublicSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }
        if (files.isEmpty()) {
            return UpdateResult.Rejected(
                "APK_UNREADABLE", "Could not read $packageName's APK files.",
            )
        }
        return update(context, files)
    }

    /**
     * Kills **every** virtual process before a package is updated, not only that
     * package's.
     *
     * The name and the old doc both said "serving a package" and the code never did: a
     * process name is `com.unique:vappN` and carries no package, so there is nothing here
     * to filter on. Overshooting is the safe direction for an update — a guest holding an
     * APK that is about to be replaced must not survive it — and the cost is that
     * unrelated guests are restarted. Said plainly rather than left as a comment that
     * describes a filter nobody wrote.
     *
     * The pool is not told. It does not need to be: it re-checks liveness when it next
     * allocates and reclaims whatever these kills emptied.
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
