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
import com.unique.core.vstorage.GuestAssetImport
import com.unique.core.vstorage.VirtualStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    @Volatile private var appRef: Context? = null
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
        appRef = app
        database = db
        storageRef = storage
        managerRef = InstanceManager(app, db, storage)
        launcherRef = VirtualLauncher(app.packageName, SLOT_COUNT, app)
    }

    /** UNIQUE's own application context, held for the work that has no caller to pass one. */
    private val app: Context get() = requireNotNull(appRef) { "UniqueEngine.init not called" }

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
        val result = instances.importAndCreate(files)
        // The installed copy is the one whose expansion files the device already has, so
        // this is the import where they can be found without asking the user for
        // anything. A game whose assets are missing looks broken in a way that has
        // nothing to do with the APK.
        if (result is CreateResult.Created) {
            importGuestAssets(result.instance.vuid, result.instance.packageName)
        }
        return result
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
     * Delivers a broadcast a guest armed through a `PendingIntent`.
     *
     * The receiver class is the guest's own, taken from the intent UNIQUE put on the stub
     * when the `PendingIntent` was built (`VirtualActivityManagerHook.routeReceiver`); the
     * rest is looked up here, because a `PendingIntent` fires long after the process that
     * created it and nothing about that process can be assumed to still exist.
     *
     * Delivery is the ordinary cold-broadcast path, which already leases the right slot,
     * joins a live guest instead of racing a second one, and retries until the guest's
     * receiver acknowledges.
     */
    suspend fun deliverPendingBroadcast(
        context: Context,
        vuid: Int,
        receiverClass: String,
        broadcast: Intent,
    ): Boolean {
        val instance = instances.instance(vuid) ?: run {
            // An instance the user removed while its PendingIntent was still armed. Not
            // an error, and not silent: this is what a stale alarm looks like.
            Diagnostics.warn(
                DiagChannel.PROCESS, "PENDING_BROADCAST_NO_INSTANCE",
                mapOf("vuid" to vuid.toString(), "receiver" to receiverClass),
            )
            return false
        }
        val manifest = runCatching { manifestOf(instance) }.getOrNull()
        val entry = manifest?.components?.firstOrNull { it.className == receiverClass }
        val started = startForBroadcast(
            context,
            ColdBroadcastTarget(
                vuid = vuid,
                packageName = instance.packageName,
                versionCode = instance.versionCode,
                receiverClass = receiverClass,
                // The receiver's own `android:process`, so a guest that puts its receivers
                // in a second process gets one, exactly as it would on a device. Falling
                // back to the package name is the platform's own default.
                processName = entry?.processName ?: instance.packageName,
            ),
            broadcast,
        )
        Diagnostics.info(
            DiagChannel.PROCESS, "PENDING_BROADCAST_DELIVERED",
            mapOf(
                "vuid" to vuid.toString(),
                "package" to instance.packageName,
                "receiver" to receiverClass,
                "action" to (broadcast.action ?: "-"),
                "started" to started.toString(),
            ),
        )
        return started
    }

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

    /**
     * Imports from explicit files: a base APK, any splits, and any expansion files.
     *
     * The `.obb` files are separated out before the bundle reader sees them, because it
     * would reject the whole selection over one file that is not an APK — and picking the
     * APK and the OBB together in one go is exactly how a sideloaded game arrives. They
     * go where the game will look for them: the new instance's own expansion directory.
     */
    suspend fun importFiles(files: List<File>): CreateResult {
        val expansions = files.filter { it.name.endsWith(".obb", ignoreCase = true) }
        val apks = files - expansions.toSet()
        val result = instances.importAndCreate(apks)
        if (result is CreateResult.Created) {
            importGuestAssets(result.instance.vuid, result.instance.packageName, expansions)
        }
        return result
    }

    /**
     * Gives an instance the expansion files and external data the device already has.
     *
     * Run after every import and clone, and available on demand from the UI, because the
     * three ways a game's assets can arrive are all real: they were downloaded by the
     * installed copy of the app, they were picked as files beside the APK, or they were
     * not obtainable and the user has to be told which.
     *
     * A failure never fails the import. An app with no OBB is the common case, and an app
     * whose OBB could not be read is still an app the user can launch — it is the game
     * that will say what is missing, and the diagnostic says why.
     */
    suspend fun importGuestAssets(
        vuid: Int,
        packageName: String,
        extraFiles: List<File> = emptyList(),
        includeExternalData: Boolean = false,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val importer = GuestAssetImport(app)
        val fromDevice = runCatching {
            importer.importFor(vuid, packageName, includeExternalData)
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.STORAGE, "GUEST_ASSET_IMPORT_FAILED",
                mapOf("package" to packageName, "vuid" to vuid.toString(), "error" to it.toString()),
            )
            GuestAssetImport.Result(GuestAssetImport.Outcome.SOURCE_UNREADABLE, detail = it.toString())
        }
        val fromFiles = if (extraFiles.isEmpty()) null else runCatching {
            importer.importFiles(vuid, packageName, extraFiles)
        }.getOrNull()
        (fromFiles ?: fromDevice).toMap()
    }

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
