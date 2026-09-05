package com.unique.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.os.Build
import android.net.Uri
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.unique.app.engine.DeviceReport
import com.unique.app.engine.GuestAppInfo
import com.unique.app.engine.DiagnosticsExport
import com.unique.app.engine.TestChecklist
import com.unique.app.engine.UniqueEngine
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.app.runtime.WebViewProbeService
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.compat.GoogleFlow
import com.unique.core.common.compat.GoogleMode
import com.unique.core.compat.CompatDatabase
import com.unique.core.google.GoogleCompatRouter
import com.unique.core.google.GoogleEnvironment
import com.unique.core.vam.LaunchResult
import com.unique.core.vam.VirtualBroadcastRouter
import com.unique.core.vam.VirtualProviderBridge
import com.unique.core.vam.StubRouter
import com.unique.core.vam.VirtualLaunchIntent
import com.unique.core.vam.VirtualLaunchParams
import com.unique.core.vpm.CreateResult
import com.unique.core.vpm.Instance
import com.unique.core.vpm.UpdateResult
import kotlinx.coroutines.runBlocking
import java.util.zip.ZipInputStream
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * PHASE 2 acceptance: a real application, imported and launched, on a real Android
 * runtime.
 *
 * This exists because unit tests cannot prove any of it. The whole mechanism is a graft
 * onto a live `ActivityThread`, and the only thing that can tell us whether the graft
 * holds is a real app reporting what the platform told it.
 *
 * The probe (`com.unique.probe`, built by tools/testapp/build.sh) must be installed on
 * the device before this runs; tools/verify-device.sh does that.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VirtualLaunchTest {

    private lateinit var context: Context
    private lateinit var model: VirtualPathModel

    private val probePackage = "com.unique.probe"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UniqueEngine.init(context)
        model = UniqueEngine.storage.model
    }

    // -----------------------------------------------------------------------------
    // 1. Import, register, create an instance.
    // -----------------------------------------------------------------------------

    @Test
    fun t01_importsAndCreatesAnInstance() = runBlocking {
        // The probe must not be installed: the whole point is running an app the device
        // does not have. The APK travels as an asset of this test APK, so the suite does
        // not depend on the shell being able to write into another app's storage.
        assertThat(isInstalledOnHost(probePackage)).isFalse()
        val apk = stageProbeApk()
        // The feature split travels with the base. Importing them together is what an
        // installed app looks like, and it is the only way the split path is exercised.
        val split = stageAsset("split_feature.apk")

        val instance = existingInstance() ?: when (val r = UniqueEngine.importFiles(listOf(apk, split))) {
            is CreateResult.Created -> r.instance
            is CreateResult.Rejected -> throw AssertionError("import rejected: ${r.reason}")
        }

        assertThat(instance.packageName).isEqualTo(probePackage)
        assertThat(instance.profile.androidId).matches("[0-9a-f]{16}")

        // The APK set is stored once, shared by every instance of this version.
        val baseApk = File(model.baseApk(probePackage, instance.versionCode))
        assertThat(baseApk.isFile).isTrue()
        assertThat(baseApk.length()).isGreaterThan(0L)

        // W^X: Android 10+ refuses to load code from a writable file, and rejects a
        // writable dex with an error that looks nothing like a permissions problem.
        assertThat(baseApk.canWrite()).isFalse()

        // The instance's own writable tree exists and is empty of the app's own data.
        assertThat(File(model.dataDir(instance.vuid, probePackage)).isDirectory).isTrue()
        assertThat(File(model.filesDir(instance.vuid, probePackage)).isDirectory).isTrue()
    }

    // -----------------------------------------------------------------------------
    // 2-6. Launch it, and check what the app itself observed.
    // -----------------------------------------------------------------------------

    @Test
    fun t02_launchesAndTheAppSeesItsOwnIdentity() = runBlocking {
        val instance = requireInstance()
        clearResult(instance)

        val result = UniqueEngine.launch(context, instance.vuid)
        assertThat(result).isInstanceOf(LaunchResult.Started::class.java)

        val observed = awaitResult(instance)

        // The single most important assertion in PHASE 2: the app believes it is itself.
        assertThat(observed["packageName"]).isEqualTo(probePackage)
        assertThat(observed["componentName"])
            .isEqualTo("$probePackage/$probePackage.ProbeActivity")
        assertThat(observed["activityClass"]).isEqualTo("$probePackage.ProbeActivity")

        // The app's own Application subclass was instantiated, and before the Activity.
        assertThat(observed["applicationClass"]).isEqualTo("$probePackage.ProbeApplication")
        assertThat(observed["applicationOnCreateRan"]).isEqualTo("true")
        assertThat(observed["applicationBeforeActivity"]).isEqualTo("true")

        // Storage resolves to this instance, through every accessor the app used.
        assertThat(observed["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
        assertThat(observed["dataDir"]).isEqualTo(model.dataDir(instance.vuid, probePackage))
        assertThat(observed["cacheDir"]).isEqualTo(model.cacheDir(instance.vuid, probePackage))
        assertThat(observed["codeCacheDir"]).isEqualTo(model.codeCacheDir(instance.vuid, probePackage))
        assertThat(observed["appInfoDataDir"]).isEqualTo(model.dataDir(instance.vuid, probePackage))
        assertThat(observed["packageCodePath"]).isEqualTo(model.baseApk(probePackage, instance.versionCode))
        assertThat(observed["appInfoSourceDir"]).isEqualTo(model.baseApk(probePackage, instance.versionCode))
        assertThat(observed["dbPath"])
            .isEqualTo("${model.databasesDir(instance.vuid, probePackage)}/probe.db")

        // It ran in a virtual process, not in the UI process.
        assertThat(observed["pid"]!!.toInt()).isNotEqualTo(Process.myPid())
        // Same Linux uid: UNIQUE is not a privilege boundary and does not pretend to be.
        assertThat(observed["uid"]!!.toInt()).isEqualTo(Process.myUid())

        // Every store the app used actually wrote something.
        assertThat(observed["launchCount"]!!.toInt()).isAtLeast(1)
        assertThat(observed["fileLineCount"]!!.toInt()).isAtLeast(1)
        assertThat(observed["dbRowCount"]!!.toInt()).isAtLeast(1)
    }

    @Test
    fun t03_theAppDoesNotWriteIntoUniquesOwnDirectories() = runBlocking {
        val instance = requireInstance()

        // Assert the positive first. Without it this test passes trivially whenever the
        // launch failed and the app wrote nothing anywhere - which is exactly what
        // happened while the interceptor was silently broken.
        val instanceFiles = File(model.filesDir(instance.vuid, probePackage))
        assertThat(File(instanceFiles, "probe-result.properties").isFile).isTrue()
        assertThat(File(instanceFiles, "launches.log").isFile).isTrue()
        assertThat(File(model.databasesDir(instance.vuid, probePackage), "probe.db").isFile).isTrue()
        assertThat(File(model.sharedPrefsDir(instance.vuid, probePackage), "probe.xml").isFile).isTrue()

        val hostFiles = context.filesDir
        // Everything UNIQUE owns lives under files/virtual/. Nothing the probe wrote may
        // appear beside UNIQUE's own state.
        val strays = hostFiles.listFiles()
            .orEmpty()
            .filter { it.name != "virtual" && it.name.contains("probe", ignoreCase = true) }
        assertThat(strays.map { it.name }).isEmpty()

        for (dir in listOf("shared_prefs", "databases")) {
            val hostDir = File(hostFiles.parentFile, dir)
            val leaked = hostDir.listFiles().orEmpty()
                .filter { it.name.contains("probe", ignoreCase = true) }
            assertThat(leaked.map { it.name }).isEmpty()
        }
    }

    // -----------------------------------------------------------------------------
    // 7-10. Kill the process, relaunch, prove the data survived.
    // -----------------------------------------------------------------------------

    @Test
    fun t04_dataSurvivesAFullProcessRestart() = runBlocking {
        val instance = requireInstance()

        val first = awaitResult(instance)
        val firstPid = first["pid"]!!.toInt()
        val firstCount = first["launchCount"]!!.toInt()

        killAndWait(firstPid)

        clearResult(instance)
        val relaunch = UniqueEngine.launch(context, instance.vuid)
        assertThat(relaunch).isInstanceOf(LaunchResult.Started::class.java)

        val second = awaitResult(instance)
        assertThat(second["pid"]!!.toInt()).isNotEqualTo(firstPid)

        // The three independent stores all continued from where they were.
        assertThat(second["launchCount"]!!.toInt()).isEqualTo(firstCount + 1)
        assertThat(second["fileLineCount"]!!.toInt()).isEqualTo(first["fileLineCount"]!!.toInt() + 1)
        assertThat(second["dbRowCount"]!!.toInt()).isEqualTo(first["dbRowCount"]!!.toInt() + 1)

        // And it is still the same instance's directory.
        assertThat(second["filesDir"]).isEqualTo(first["filesDir"])
    }

    // -----------------------------------------------------------------------------
    // Two instances of the same APK must not share anything writable.
    // -----------------------------------------------------------------------------

    @Test
    fun t05_aSecondInstanceIsFullyIndependent() = runBlocking {
        val first = requireInstance()
        val firstBefore = awaitResult(first)

        val second = when (val r = UniqueEngine.instances.createInstance(probePackage, "Profile 2")) {
            is CreateResult.Created -> r.instance
            is CreateResult.Rejected -> throw AssertionError("second instance rejected: ${r.reason}")
        }
        assertThat(second.vuid).isNotEqualTo(first.vuid)

        // Separate identity, separate directories.
        assertThat(second.profile.androidId).isNotEqualTo(first.profile.androidId)
        assertThat(model.dataDir(second.vuid, probePackage))
            .isNotEqualTo(model.dataDir(first.vuid, probePackage))

        // One APK, shared read-only between them: this is what makes a clone cheap.
        assertThat(model.baseApk(probePackage, second.versionCode))
            .isEqualTo(model.baseApk(probePackage, first.versionCode))

        clearResult(second)
        assertThat(UniqueEngine.launch(context, second.vuid))
            .isInstanceOf(LaunchResult.Started::class.java)
        val secondObserved = awaitResult(second)

        // The second instance starts from nothing, in its own directory...
        assertThat(secondObserved["packageName"]).isEqualTo(probePackage)
        assertThat(secondObserved["filesDir"]).isEqualTo(model.filesDir(second.vuid, probePackage))
        assertThat(secondObserved["launchCount"]).isEqualTo("1")
        assertThat(secondObserved["dbRowCount"]).isEqualTo("1")
        assertThat(secondObserved["fileLineCount"]).isEqualTo("1")

        // ...and it runs in its own process, not the first instance's.
        assertThat(secondObserved["pid"]).isNotEqualTo(firstBefore["pid"])

        // The first instance's data is untouched by any of that.
        val firstAfter = readResult(first)
        assertThat(firstAfter["launchCount"]).isEqualTo(firstBefore["launchCount"])
        assertThat(firstAfter["dbRowCount"]).isEqualTo(firstBefore["dbRowCount"])
    }

    // -----------------------------------------------------------------------------
    // A crashing virtual app must take down nothing but itself.
    // -----------------------------------------------------------------------------

    @Test
    fun t06_aCrashingInstanceKillsNeitherUniqueNorItsSibling() = runBlocking {
        val uniquePid = Process.myPid()
        val victim = requireInstance()
        val survivor = requireSecondInstance()

        // Both instances running, in processes of their own.
        val survivorBefore = awaitResult(survivor)
        val survivorPid = survivorBefore["pid"]!!.toInt()
        val victimFirstPid = awaitResult(victim)["pid"]!!.toInt()
        assertThat(survivorPid).isNotEqualTo(victimFirstPid)
        assertThat(runningVirtualPids()).containsAtLeast(survivorPid, victimFirstPid)

        // Restart the victim so the next launch genuinely runs onCreate. Launching into a
        // live instance correctly *resumes* it instead, which is right behaviour and
        // would make this test silently measure nothing.
        killAndWait(victimFirstPid)
        clearResult(victim)

        // The stub intent is built here rather than through the launcher so an extra can
        // be added - which also proves extras survive the transaction rewrite.
        val params = VirtualLaunchParams(
            vuid = victim.vuid,
            packageName = probePackage,
            versionCode = victim.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(victim.vuid),
        )
        // CLEAR_TASK matters. With NEW_TASK alone the system finds the existing task for
        // this affinity and recreates its top activity from the *stored* intent, so the
        // extra below never arrives and the app never crashes - which is what made the
        // first version of this test wait for a death that was never requested.
        val intent = VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
            .putExtra("probe.crash", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        // The probe records its observations before it throws, so this confirms the extra
        // survived the rewrite and reached the virtual activity.
        val crashed = awaitResult(victim)
        assertThat(crashed["packageName"]).isEqualTo(probePackage)
        val victimPid = crashed["pid"]!!.toInt()

        awaitProcessGone(victimPid)

        // UNIQUE is still alive - this test is running inside it.
        assertThat(Process.myPid()).isEqualTo(uniquePid)

        // The sibling is untouched: same process, still running. Asked of the kernel,
        // because ActivityManager's own list has been seen to outlive a process.
        assertThat(isAlive(survivorPid)).isTrue()
        assertThat(isAlive(victimPid)).isFalse()

        // And its data was not disturbed by the crash next door.
        val survivorAfter = readResult(survivor)
        assertThat(survivorAfter["launchCount"]).isEqualTo(survivorBefore["launchCount"])
        assertThat(survivorAfter["filesDir"]).isEqualTo(survivorBefore["filesDir"])
    }

    // -----------------------------------------------------------------------------
    // Phase 3: the guest's own Service, started by the guest itself.
    // -----------------------------------------------------------------------------

    @Test
    fun t07_theGuestsOwnServiceRuns() = runBlocking {
        val instance = requireInstance()
        val serviceResult = File(model.filesDir(instance.vuid, probePackage), "probe-service.properties")
        serviceResult.delete()
        File(model.filesDir(instance.vuid, probePackage), "probe-connection.properties").delete()
        clearResult(instance)

        // Started and bound in one launch: they take different paths through
        // ActivityThread (SERVICE_ARGS vs BIND_SERVICE) and a layer can get one right and
        // the other wrong.
        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.startService", true)
                .putExtra("probe.bindService", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        val activity = awaitResult(instance)
        val service = awaitFileWhere(serviceResult) { it["stage"] == "bound" }

        // The guest's own Service class ran, believing it is itself.
        assertThat(service["className"]).isEqualTo("$probePackage.ProbeService")
        assertThat(service["packageName"]).isEqualTo(probePackage)

        // In the guest's own directory, in the guest's own process.
        assertThat(service["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
        assertThat(service["pid"]).isEqualTo(activity["pid"])

        // Both entry points were exercised. bindService is the one that regressed
        // invisibly: ContextImpl calls IActivityManager.bindServiceInstance from Android
        // 12 on, and a shim registered for the older name "bindService" still bound - the
        // method is on the interface, it is just never called - so the bind went out
        // unrewritten and AMS answered "not found" while startService kept working.
        assertThat(service["startCount"]!!.toInt()).isAtLeast(1)
        assertThat(service["bindCount"]!!.toInt()).isAtLeast(1)

        // The service saw its own component in the bind Intent, not the stub's.
        assertThat(service["bindComponent"]).isEqualTo("$probePackage/.ProbeService")

        // The client side connected. The ComponentName the framework hands back is AMS's,
        // which is the stub's - recorded rather than asserted, and tracked in
        // docs/COMPATIBILITY.md. What matters here is that the binder is the guest's own.
        val connection = awaitFile(
            File(model.filesDir(instance.vuid, probePackage), "probe-connection.properties")
        )
        assertThat(connection["connected"]).isEqualTo("true")
        assertThat(connection["binderIsLocal"]).isEqualTo("true")
        assertThat(connection["binderClass"]).isEqualTo("$probePackage.ProbeService\$LocalBinder")

        // The name the app is handed is its *own* service, not the stub AMS started. Plenty
        // of code switches on getClassName() to tell two of its own services apart, and
        // every such branch would silently take the wrong path.
        assertThat(connection["connectedComponent"])
            .isEqualTo("$probePackage/.ProbeService")
    }

    // -----------------------------------------------------------------------------
    // Phase 3: the guest's manifest-declared BroadcastReceiver.
    // -----------------------------------------------------------------------------

    @Test
    fun t08_theGuestsManifestReceiverGetsBroadcasts() = runBlocking {
        val instance = requireInstance()
        val receiverResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-receiver.properties")
        receiverResult.delete()

        // The guest has to be running: a dynamic registration lives only as long as the
        // process, so a manifest receiver cannot yet wake a dead one. t07 left it up.
        assertThat(runningVirtualPids()).isNotEmpty()

        // Scoped to UNIQUE's own package, not sent implicitly.
        //
        // Android 14 turns on IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS (compat
        // change 229362273) for targetSdk 34: an intent with neither a component nor a
        // package is matched only against *exported* filters. ProbeReceiver is declared
        // android:exported="false", UNIQUE mirrors that, and so an implicit send is
        // silently dropped by the platform - it is not a delivery failure to fix in the
        // engine. A sender inside UNIQUE that means a particular guest scopes the intent,
        // which is what the eventual notification and broadcast bridge does too.
        context.sendBroadcast(
            Intent("com.unique.probe.PING")
                .setPackage(context.packageName)
                .putExtra("probe.extra", "hello-from-unique")
        )

        // Longer than the default. Waking a dead guest means a cold graft, and the router
        // does it for *every* instance that declared the action — two here, in sequence.
        // A run that measured 207 seconds against a 180-second wait reported a working
        // feature as broken.
        val observed = awaitFile(receiverResult, timeoutMillis = 420_000)

        assertThat(observed["action"]).isEqualTo("com.unique.probe.PING")
        assertThat(observed["packageName"]).isEqualTo(probePackage)
        // The payload survived the trip into the guest.
        assertThat(observed["extra"]).isEqualTo("hello-from-unique")
        // And it ran against the guest's own storage, not UNIQUE's.
        assertThat(observed["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
    }

    // -----------------------------------------------------------------------------
    // Phase 3: the guest's own ContentProvider, acquired through the ContentResolver.
    // -----------------------------------------------------------------------------

    @Test
    fun t09_theGuestsOwnProviderAnswersItsAuthority() = runBlocking {
        val instance = requireInstance()
        val providerResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-provider.properties")
        providerResult.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.queryProvider", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        val activity = awaitResult(instance)
        val observed = awaitFile(providerResult)

        // The resolver went to ActivityManagerService for the authority. Without UNIQUE
        // answering it, AMS resolves authorities against installed packages and the
        // caller gets "IllegalArgumentException: Unknown URL".
        assertThat(observed["error"]).isNull()
        assertThat(observed["rowCount"]).isEqualTo("3")

        // The provider ran as the guest, in the guest's storage, in the guest's process.
        assertThat(observed["provider.packageName"]).isEqualTo(probePackage)
        assertThat(observed["provider.filesDir"])
            .isEqualTo(model.filesDir(instance.vuid, probePackage))
        assertThat(observed["provider.pid"]).isEqualTo(activity["pid"])
        assertThat(observed["callerPid"]).isEqualTo(activity["pid"])
        assertThat(observed["type"]).isEqualTo("vnd.android.cursor.dir/probe")
    }

    // -----------------------------------------------------------------------------
    // Phase 3: an activity the guest starts for itself.
    // -----------------------------------------------------------------------------

    @Test
    fun t10_theGuestStartsItsOwnSecondActivity() = runBlocking {
        val instance = requireInstance()
        val secondResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-second.properties")
        secondResult.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.startSecond", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        val first = awaitResult(instance)
        val second = awaitFile(secondResult)

        // UNIQUE launches the first activity of an instance; every screen after it is the
        // app's own startActivity, naming a component the system has never installed.
        // Those go to IActivityTaskManager, not IActivityManager.
        assertThat(second["activityClass"]).isEqualTo("$probePackage.ProbeSecondActivity")
        assertThat(second["packageName"]).isEqualTo(probePackage)
        assertThat(second["componentName"])
            .isEqualTo("$probePackage/$probePackage.ProbeSecondActivity")

        // The payload the app put on its own intent survived the wrap and unwrap.
        assertThat(second["extra"]).isEqualTo("carried-through")

        // Same instance, same process, same task as the activity that started it.
        assertThat(second["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
        assertThat(second["pid"]).isEqualTo(first["pid"])
        assertThat(second["taskId"]).isEqualTo(first["taskId"])
    }

    // -----------------------------------------------------------------------------
    // Phase 3: a PendingIntent the guest builds for itself.
    // -----------------------------------------------------------------------------

    @Test
    fun t11_aPendingIntentTheGuestBuiltFiresIntoTheGuest() = runBlocking {
        val instance = requireInstance()
        val dir = model.filesDir(instance.vuid, probePackage)
        val secondResult = File(dir, "probe-second.properties")
        val pendingResult = File(dir, "probe-pending.properties")
        secondResult.delete()
        pendingResult.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.pendingIntent", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val pending = awaitFile(pendingResult)
        assertThat(pending["error"]).isNull()
        assertThat(pending["created"]).isEqualTo("true")
        assertThat(pending["sent"]).isEqualTo("true")

        // system_server assembles a PendingIntent at creation time and fires it later,
        // with nothing of the app's still running to fix up the component. So the stub
        // has to be baked in at creation, and the inbound rewrite has to recover the
        // guest's activity from it.
        val second = awaitFile(secondResult)
        assertThat(second["activityClass"]).isEqualTo("$probePackage.ProbeSecondActivity")
        assertThat(second["extra"]).isEqualTo("via-pending-intent")
        assertThat(second["filesDir"]).isEqualTo(dir)
    }

    // -----------------------------------------------------------------------------
    // Phase 3: runtime permissions belong to the instance, not to UNIQUE.
    // -----------------------------------------------------------------------------

    @Test
    fun t12_aRuntimePermissionBelongsToTheInstance() = runBlocking {
        val instance = requireInstance()
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-permissions.properties")
        result.delete()
        clearResult(instance)

        // The host must hold CAMERA outright for this test to mean anything: if the guest
        // simply saw the host's grants it would read GRANTED before ever asking. `install
        // -g` does grant it, but the suite's `pm clear` revokes it again, so it is granted
        // here — where the precondition is stated rather than assumed.
        //
        // CAMERA is also chosen because the platform answers a request for a permission
        // the caller already holds without showing a dialog, which keeps the test
        // deterministic on a headless emulator. The other direction — a guest asking for
        // something the *host* does not hold — needs that dialog and is NOT_TESTED.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.CAMERA")
        assertThat(context.checkSelfPermission("android.permission.CAMERA"))
            .isEqualTo(PackageManager.PERMISSION_GRANTED)
        assertThat(context.checkSelfPermission("android.permission.RECORD_AUDIO"))
            .isEqualTo(PackageManager.PERMISSION_DENIED)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.permissions", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val observed = awaitFile(result)
        assertThat(observed["error"]).isNull()

        // Before asking: denied, through both routes the platform offers, even though
        // UNIQUE itself holds it.
        assertThat(observed["cameraBefore"]).isEqualTo("DENIED")
        assertThat(observed["cameraViaPm"]).isEqualTo("DENIED")
        assertThat(observed["micBefore"]).isEqualTo("DENIED")

        // The app asked, the platform answered for UNIQUE, and UNIQUE recorded the grant
        // against this instance.
        assertThat(observed["requestCode"]).isEqualTo("4242")
        assertThat(observed["result.android.permission.CAMERA"]).isEqualTo("GRANTED")
        assertThat(observed["cameraAfter"]).isEqualTo("GRANTED")

        // And through PackageManager, which reaches a different system service than
        // Context.checkSelfPermission. Before the grant both said DENIED, which the
        // unhooked platform would also say for a package it has never installed — so it
        // is this assertion, after the grant, that shows the route is actually answered.
        assertThat(observed["cameraViaPmAfter"]).isEqualTo("GRANTED")

        // "Explain yourself before asking again" is a question about this instance's
        // history: false before the first request and false after a grant. The true case
        // needs a recorded denial, which needs the system dialog, and is NOT_TESTED.
        assertThat(observed["cameraRationaleBefore"]).isEqualTo("false")
        assertThat(observed["cameraRationaleAfter"]).isEqualTo("false")

        // The decision is UNIQUE's record, kept under runtime/ rather than in the app's
        // own data directory: a guest that can rewrite its own grants has none.
        val state = File(model.permissionsFile(instance.vuid, probePackage))
        assertThat(state.isFile).isTrue()
        assertThat(state.readText()).contains("android.permission.CAMERA=GRANTED")
        assertThat(state.path).doesNotContain(model.dataDir(instance.vuid, probePackage))

        // A permission the app declared and never asked for stays denied: the grant is
        // per permission, not a blanket switch to the host's state. RECORD_AUDIO is also
        // one the host does not hold, so both reasons agree here.
        assertThat(observed["micAfter"]).isEqualTo("DENIED")
    }

    @Test
    fun t13_aGrantSurvivesTheProcessBeingKilled() = runBlocking {
        // Depends on t12 having granted CAMERA to this instance, the way t08 depends on
        // t07 leaving the guest running. After a full process kill the app must not have
        // to ask again: being re-prompted on every cold start is what users read as the
        // app being broken.
        val instance = requireInstance()
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-permissions.properties")

        val before = awaitResult(instance)
        killAndWait(before["pid"]!!.toInt())
        result.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.permissions", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        val relaunched = awaitResult(instance)
        assertThat(relaunched["pid"]).isNotEqualTo(before["pid"])
        val observed = awaitFile(result)

        // Granted before the app asks anything this time: the decision was restored.
        assertThat(observed["cameraBefore"]).isEqualTo("GRANTED")
        assertThat(observed["cameraViaPm"]).isEqualTo("GRANTED")
        // A permission never decided is still denied, so this is a restore and not a
        // blanket fallback to the host's own grants.
        assertThat(observed["micBefore"]).isEqualTo("DENIED")
    }

    // -----------------------------------------------------------------------------
    // Phase 3: app ops, which the framework consults on the way into half the platform.
    // -----------------------------------------------------------------------------

    @Test
    fun t14_appOpsAcceptTheGuestsIdentity() = runBlocking {
        val instance = requireInstance()
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-appops.properties")
        result.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.appops", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val observed = awaitFile(result)

        // AppOpsManager.checkPackage throws when the name does not belong to the uid, and
        // the framework calls it on the way into the camera, the microphone, the
        // clipboard and notifications. Unhandled, it surfaces as a SecurityException from
        // an API with nothing obviously to do with app ops.
        assertThat(observed["checkPackage"]).isEqualTo("ok")

        // MODE_ERRORED (2) is what an unknown package gets. Anything else means the op
        // was resolved against a package the platform actually knows.
        assertThat(observed["cameraOp"]).isNotEqualTo("2")

        // And the app still believes it is itself.
        assertThat(observed["packageName"]).isEqualTo(probePackage)
    }

    // -----------------------------------------------------------------------------
    // Phase 3: a guest's notification, posted as UNIQUE without colliding with a sibling.
    // -----------------------------------------------------------------------------

    @Test
    fun t15_theGuestsNotificationIsPostedAndNamespaced() = runBlocking {
        val instance = requireInstance()
        // Android 13+ drops a notification silently when POST_NOTIFICATIONS is denied, and
        // the suite's `pm clear` revokes it. Stated here rather than assumed.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")

        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancelAll()

        val second = secondInstance()
        for (target in listOf(instance, second)) {
            File(model.filesDir(target.vuid, probePackage), "probe-notification.properties")
                .delete()
            clearResult(target)
            val params = VirtualLaunchParams(
                vuid = target.vuid,
                packageName = probePackage,
                versionCode = target.versionCode,
                targetComponent = "$probePackage.ProbeActivity",
                processName = probePackage,
                slot = slotOf(target.vuid),
            )
            context.startActivity(
                VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                    .putExtra("probe.notify", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            awaitResult(target)
            val written = awaitFile(
                File(model.filesDir(target.vuid, probePackage), "probe-notification.properties")
            )
            assertThat(written["error"]).isNull()
            assertThat(written["posted"]).isEqualTo("4711")
        }

        // Posted as UNIQUE, so UNIQUE can read them back. Nothing appears at all if the
        // posting package was not rewritten: system_server checks it against the uid.
        val posted = awaitNotification(StubRouter.hostNotificationId(instance.vuid, 4711))
        val sibling = awaitNotification(StubRouter.hostNotificationId(second.vuid, 4711))
        assertThat(posted.notification.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo("probe-notification")

        // Both instances posted id 4711 and both survive. Unnamespaced, the second would
        // have replaced the first, and the two would share the user's sound and
        // importance settings under one entry in Settings.
        assertThat(posted.id).isNotEqualTo(sibling.id)
        assertThat(StubRouter.virtualNotificationId(posted.id)).isEqualTo(4711)
        assertThat(StubRouter.virtualNotificationId(sibling.id)).isEqualTo(4711)
        assertThat(StubRouter.notificationOwner(posted.id)).isEqualTo(instance.vuid)
        assertThat(StubRouter.notificationOwner(sibling.id)).isEqualTo(second.vuid)

        assertThat(posted.notification.channelId)
            .isEqualTo(StubRouter.hostChannelId(instance.vuid, probePackage, "probe-channel"))
        assertThat(sibling.notification.channelId).isNotEqualTo(posted.notification.channelId)
        assertThat(notifications.notificationChannels.map { it.id })
            .containsAtLeast(posted.notification.channelId, sibling.notification.channelId)

        // The icon is a resource in an APK the system has never installed. SystemUI would
        // resolve it against UNIQUE and land on nothing, or on an unrelated drawable at
        // the same numeric id, so it travels as pixels instead.
        assertThat(posted.notification.smallIcon.type).isEqualTo(Icon.TYPE_BITMAP)

        // And a tap is routed back to the instance that posted it.
        assertThat(posted.notification.extras.getInt(StubRouter.EXTRA_VUID, -1))
            .isEqualTo(instance.vuid)
        assertThat(sibling.notification.extras.getInt(StubRouter.EXTRA_VUID, -1))
            .isEqualTo(second.vuid)
    }

    /** The instance t05 created, or a new one when t15 is run on its own. */
    private suspend fun secondInstance(): Instance {
        val first = requireInstance()
        UniqueEngine.instances.instances()
            .firstOrNull { it.packageName == probePackage && it.vuid != first.vuid }
            ?.let { return it }
        return when (val r = UniqueEngine.instances.createInstance(probePackage, "Profile 2")) {
            is CreateResult.Created -> r.instance
            is CreateResult.Rejected -> throw AssertionError("second instance rejected: ${r.reason}")
        }
    }

    private fun awaitNotification(id: Int, timeoutMillis: Long = 60_000): StatusBarNotification {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            notifications.activeNotifications.firstOrNull { it.id == id }?.let { return it }
            Thread.sleep(250)
        }
        throw AssertionError(
            "no notification with id $id within ${timeoutMillis}ms; active ids: " +
                notifications.activeNotifications.joinToString(",") { it.id.toString() }
        )
    }

    // -----------------------------------------------------------------------------
    // Phase 3: a foreground service, which Android 14 checks against the *stub's*
    // manifest rather than the guest's.
    // -----------------------------------------------------------------------------

    @Test
    fun t16_theGuestsForegroundServiceStarts() = runBlocking {
        val instance = requireInstance()
        val serviceResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-service.properties")
        serviceResult.delete()
        clearResult(instance)

        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.startService", true)
                .putExtra("probe.foreground", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val service = awaitFileWhere(serviceResult) { it["stage"] == "started" }

        // startForeground reaches AMS naming the component it thinks it is. AMS finds the
        // ServiceRecord by token and then checks the name against it, and that record's
        // name is the *stub's* - so an unrewritten name is rejected with "Service not
        // registered", from inside startForeground, seconds before the platform kills the
        // app for not having called it.
        assertThat(service["foreground"]).isEqualTo("true")

        // And the platform agrees it is foreground.
        val running = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(200)
            .filter { it.service.packageName == context.packageName }
        assertThat(running.any { it.foreground }).isTrue()
    }

    // -----------------------------------------------------------------------------
    // Phase 4: the guest's own native library, out of an APK the system never installed.
    // -----------------------------------------------------------------------------

    @Test
    fun t17_theGuestLoadsItsOwnNativeLibrary() = runBlocking {
        val instance = requireInstance()
        val result = File(model.filesDir(instance.vuid, probePackage), "probe-native.properties")
        result.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.native", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        val activity = awaitResult(instance)
        val observed = awaitFile(result)

        // System.loadLibrary resolves through the ClassLoader's library search path, which
        // the platform builds from ApplicationInfo.nativeLibraryDir. It fails unless the
        // graft got that directory right *and* the importer extracted an ABI this device
        // can execute.
        assertThat(observed["error"]).isNull()
        assertThat(observed["loaded"]).isEqualTo("true")

        // The library runs in the guest's process, not somewhere else.
        assertThat(observed["nativePid"]).isEqualTo(observed["javaPid"])
        assertThat(observed["nativePid"]).isEqualTo(activity["pid"])

        // Compiled for this machine and executed directly: no CPU emulation anywhere.
        assertThat(observed["arch"]).isEqualTo(Build.SUPPORTED_ABIS.first())
        assertThat(observed["nativeLibraryDir"])
            .endsWith("/lib/${Build.SUPPORTED_ABIS.first()}")

        // JNI works in both directions, not just outward.
        assertThat(observed["echo"]).isEqualTo("native:hello")

        // The page size the loader is actually running with. 4096 on this emulator; a
        // 16 KB Android 15 device is what docs/PHYSICAL_DEVICE_TEST.md exists to cover.
        assertThat(observed["pageSize"]!!.toLong()).isAtLeast(4096L)

        // libc file IO through the path the Context gives. Works with or without native
        // interception, and is the common case.
        assertThat(observed["libcWrite"]).startsWith("ok:")
        assertThat(observed["libcFileExists"]).isEqualTo("true")
        assertThat(observed["libcWrite"])
            .contains(model.filesDir(instance.vuid, probePackage))

        // And through the path an app *hard-codes* in native code. Nothing in Java
        // rewrites "/data/data/<pkg>/files/…", so without libc interception this either
        // fails outright or lands in a directory that is not the instance's — which is
        // the whole reason the interception exists.
        assertThat(observed["libcRawPath"]).isEqualTo("/data/data/$probePackage/files/probe-libc-raw.txt")
        assertThat(observed["libcRawWrite"]).startsWith("ok:")
        assertThat(observed["libcRawLandedInInstance"]).isEqualTo("true")

        // And from a library loaded *after* the interception was installed. The initial
        // scan walks what is loaded at that moment, so a late arrival has its own
        // untouched GOT — this is the case that used to be recorded as broken.
        assertThat(observed["lateLoaded"]).isEqualTo("true")
        assertThat(observed["lateWrite"]).startsWith("ok:")
        assertThat(observed["lateLandedInInstance"]).isEqualTo("true")
    }

    // -----------------------------------------------------------------------------
    // Phase 6: what the guest believes about itself and about the Google stack.
    // -----------------------------------------------------------------------------

    @Test
    fun t21_theGuestReadsItsOwnSignatureAndTheTruthAboutGms() = runBlocking {
        val instance = requireInstance()
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-identity.properties")
        result.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.identity", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val observed = awaitFile(result)

        // An app that cannot read its own certificate concludes it has been tampered with
        // and refuses to start — which looks like UNIQUE breaking it. Every Google API
        // whose key is bound to a signing certificate needs this too.
        assertThat(observed["signatureError"]).isNull()
        assertThat(observed["signatureCount"]!!.toInt()).isAtLeast(1)

        // The deprecated `signatures` array too, not only `signingInfo`. The real
        // PackageManager still fills it on API 28+ and most apps and libraries still read
        // it; the archive parser does not, so UNIQUE has to.
        assertThat(observed["legacyArrayCount"]!!.toInt()).isAtLeast(1)
        assertThat(observed["hasSigningInfo"]).isEqualTo("true")

        // And it is the *probe's* certificate, not UNIQUE's. The probe is signed by
        // tools/testapp/.keystore, which UNIQUE is not.
        val hostSha = hostSignatureSha256()
        assertThat(observed["signatureSha256"]).isNotNull()
        assertThat(observed["signatureSha256"]).isNotEqualTo(hostSha)

        // What the app believes about the Google stack must be the truth about this
        // device. This emulator is aosp_atd and has none of it; an app told otherwise
        // fails later, somewhere less obvious.
        val gmsOnHost = isInstalledOnHost("com.google.android.gms")
        assertThat(observed["present.com.google.android.gms"]).isEqualTo(gmsOnHost.toString())
        assertThat(observed["present.com.android.vending"])
            .isEqualTo(isInstalledOnHost("com.android.vending").toString())

        // The instance's own ANDROID_ID, not the device's. Two clones that report the
        // same one look like a single installation to anything that fingerprints, which
        // is most of the apps worth cloning.
        assertThat(observed["androidIdError"]).isNull()
        assertThat(observed["androidId"]).isEqualTo(instance.profile.androidId)
        assertThat(observed["androidId"]).isNotEqualTo(hostAndroidId())

        // And the serial comes from the profile too.
        assertThat(observed["buildSerial"]).isEqualTo(instance.profile.serial)

        // A class that exists only in the feature split. Reachable only because the graft
        // populated ApplicationInfo.splitSourceDirs, so the split's dex reached the class
        // loader — an app whose feature split is silently dropped fails at the first
        // screen that needs it.
        assertThat(observed["splitClass"]).isEqualTo("hello-from-split")
    }

    @Test
    fun t22_twoInstancesHaveDifferentDeviceIdentities() = runBlocking {
        val first = requireInstance()
        val second = secondInstance()
        assertThat(second.profile.androidId).isNotEqualTo(first.profile.androidId)

        val observed = mutableMapOf<Int, Map<String, String>>()
        for (target in listOf(first, second)) {
            val result =
                File(model.filesDir(target.vuid, probePackage), "probe-identity.properties")
            result.delete()
            clearResult(target)
            val params = VirtualLaunchParams(
                vuid = target.vuid,
                packageName = probePackage,
                versionCode = target.versionCode,
                targetComponent = "$probePackage.ProbeActivity",
                processName = probePackage,
                slot = slotOf(target.vuid),
            )
            context.startActivity(
                VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                    .putExtra("probe.identity", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            awaitResult(target)
            observed[target.vuid] = awaitFile(result)
        }

        // The property the whole profile mechanism exists for: the same app, cloned, is
        // two devices as far as it can tell.
        assertThat(observed[first.vuid]!!["androidId"])
            .isNotEqualTo(observed[second.vuid]!!["androidId"])
        assertThat(observed[first.vuid]!!["androidId"]).isEqualTo(first.profile.androidId)
        assertThat(observed[second.vuid]!!["androidId"]).isEqualTo(second.profile.androidId)
        assertThat(observed[first.vuid]!!["buildSerial"])
            .isNotEqualTo(observed[second.vuid]!!["buildSerial"])
    }

    @Suppress("DEPRECATION")
    private fun hostAndroidId(): String? = android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
    )

    private fun hostSignatureSha256(): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNATURES,
        )
        @Suppress("DEPRECATION")
        val bytes = info.signatures!!.first().toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // -----------------------------------------------------------------------------
    // Phase 9: updating an imported package without losing the instance's data.
    // -----------------------------------------------------------------------------

    @Test
    fun t23_anUpdateKeepsTheInstancesData() = runBlocking {
        val instance = requireInstance()
        val before = awaitResult(instance)
        val launchesBefore = before["launchCount"]!!.toInt()
        val versionBefore = instance.versionCode

        // A genuinely different build: same code and same signing key, versionCode + 1.
        val next = stageAsset("probe-next.apk")

        when (val result = UniqueEngine.update(context, listOf(next))) {
            is UpdateResult.Updated -> {
                assertThat(result.fromVersionCode).isEqualTo(versionBefore)
                assertThat(result.toVersionCode).isGreaterThan(versionBefore)
                assertThat(result.instances).isAtLeast(1)
            }
            is UpdateResult.Unchanged ->
                throw AssertionError("the staged update has the same version as the import")
            is UpdateResult.Rejected -> throw AssertionError("update rejected: ${result.reason}")
        }

        // The package row now points at the new version, and every instance reads its
        // version from that row rather than carrying one of its own.
        val updated = requireInstance()
        assertThat(updated.vuid).isEqualTo(instance.vuid)
        assertThat(updated.versionCode).isGreaterThan(versionBefore)

        // The identity is the same instance's, so nothing was recreated underneath it.
        assertThat(updated.profile.androidId).isEqualTo(instance.profile.androidId)

        clearResult(updated)
        assertThat(UniqueEngine.launch(context, updated.vuid))
            .isInstanceOf(LaunchResult.Started::class.java)
        val after = awaitResult(updated)

        // The point of the whole exercise: the app's own data continued across the
        // update. launchCount lives in the instance's SharedPreferences, which is keyed
        // by vuid and package and never by version.
        assertThat(after["launchCount"]!!.toInt()).isEqualTo(launchesBefore + 1)
        assertThat(after["filesDir"]).isEqualTo(before["filesDir"])
        assertThat(after["packageCodePath"]).isNotEqualTo(before["packageCodePath"])
    }

    @Test
    fun t24_anUpdateSignedBySomeoneElseIsRefused() = runBlocking {
        requireInstance()
        // The instrumentation APK is a different package signed by a different key. It is
        // the closest thing to hand to a hostile update, and the check that matters is
        // that a different signer cannot inherit an instance's data.
        val foreign = File(
            InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir
        )
        assertThat(foreign.isFile).isTrue()

        val result = UniqueEngine.update(context, listOf(foreign))
        assertThat(result).isInstanceOf(UpdateResult.Rejected::class.java)

        // And the instance is untouched.
        assertThat(requireInstance().packageName).isEqualTo(probePackage)
    }

    // -----------------------------------------------------------------------------
    // Phase 3: waking a guest that is not running, which is what a manifest receiver
    // is mostly for.
    // -----------------------------------------------------------------------------

    @Test
    fun t25_aDeadGuestIsWokenByABroadcast() = runBlocking {
        val instance = requireInstance()
        val receiverResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-receiver.properties")

        // Registrations are rebuilt from the current instance set, the same way the bridge
        // rebuilds them after an import. t01 ran after the Application did, so without
        // this the router would be holding routes for an empty device.
        UniqueEngine.registerBroadcastRoutes(context)
        assertThat(VirtualBroadcastRouter.registeredActions).contains("com.unique.probe.PING")

        // Whatever earlier tests left running has to go. Delivering into a live process is
        // t08's job and would pass here without exercising a single line of the cold path.
        val hostPid = Process.myPid()
        val killed = runningVappProcesses().values.toSet()
        killed.forEach { killAndWait(it) }
        // Only that the ones we killed are gone. Asserting the pool is *empty* would be
        // asserting that nothing restarted one, and a `:vappN` publishes a stub
        // ContentProvider, which ActivityManager is entitled to bring back for a client
        // that still holds a reference. That would be a test about ActivityManager.
        killed.forEach { assertThat(isAlive(it)).isFalse() }

        receiverResult.delete()
        assertThat(receiverResult.exists()).isFalse()

        context.sendBroadcast(
            Intent("com.unique.probe.PING")
                .setPackage(context.packageName)
                .putExtra("probe.extra", "cold-start")
        )

        // Longer than the default. Waking a dead guest means a cold graft, and the router
        // does it for *every* instance that declared the action — two here, in sequence.
        // A run that measured 207 seconds against a 180-second wait reported a working
        // feature as broken.
        val observed = awaitFile(receiverResult, timeoutMillis = 420_000)

        assertThat(observed["action"]).isEqualTo("com.unique.probe.PING")
        assertThat(observed["packageName"]).isEqualTo(probePackage)
        assertThat(observed["extra"]).isEqualTo("cold-start")
        // The guest's own storage, so the graft ran rather than the receiver being
        // instantiated in UNIQUE's process against UNIQUE's directories.
        assertThat(observed["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))

        // And in a process that did not exist when the broadcast was sent.
        val pid = observed["pid"]!!.toInt()
        assertThat(pid).isNotEqualTo(hostPid)
        assertThat(killed).doesNotContain(pid)
    }

    // -----------------------------------------------------------------------------
    // Phase 3: a content provider read from a process that is not the guest's.
    // -----------------------------------------------------------------------------

    @Test
    fun t26_uniqueItselfReadsAGuestsProvider() = runBlocking {
        val instance = requireInstance()
        UniqueEngine.registerBroadcastRoutes(context)

        // This test runs in UNIQUE's own process, which is exactly the caller the feature
        // is for: sharing a file out of a guest, or reading one, means crossing from :core
        // into a :vappN. Nothing here is inside the virtual process.
        assertThat(Process.myPid()).isEqualTo(uniqueCorePid())

        val client = VirtualProviderBridge.open(
            context, instance.vuid, "com.unique.probe.provider",
        ) ?: throw AssertionError("UNIQUE could not reach the guest's provider")

        val rows = try {
            client.query(
                Uri.parse("content://com.unique.probe.provider/rows"), null, null, null, null,
            ).use { cursor ->
                checkNotNull(cursor) { "the guest's provider returned no cursor" }
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        } finally {
            client.close()
        }

        // The answer came from the guest's own provider object, running as the guest.
        assertThat(rows["packageName"]).isEqualTo(probePackage)
        assertThat(rows["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
        // And from a different process than this one. Only that: asserting the serving
        // process is *still alive* now would be asserting that nothing restarted a
        // `:vappN` in the meantime, which is a test about ActivityManager's scheduling
        // and went red on a slot that had legitimately been replaced.
        assertThat(rows["pid"]!!.toInt()).isNotEqualTo(Process.myPid())
        assertThat(rows["pid"]!!.toInt()).isNotEqualTo(uniqueCorePid())
    }

    @Test
    fun t27_aGuestReachesItsOwnProviderInAnotherProcess() = runBlocking {
        val instance = requireInstance()
        UniqueEngine.registerBroadcastRoutes(context)

        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-altprovider.properties")
        result.delete()
        clearResult(instance)

        // ProbeAltProvider is declared android:process=":alt", so the provider the guest
        // is about to query lives in a slot of its own. Until slots could route between
        // themselves this shape returned nothing at all.
        launchProbeWith(instance) { it.putExtra("probe.queryAltProvider", true) }

        // Longer than the default: this needs a cold graft of a *second* slot, which on
        // the verification emulator runs to forty seconds on its own (§17.1).
        val observed = awaitFile(result, timeoutMillis = 300_000)
        assertThat(observed["error"]).isNull()
        assertThat(observed["rowCount"]!!.toInt()).isAtLeast(3)
        assertThat(observed["provider.packageName"]).isEqualTo(probePackage)
        assertThat(observed["provider.filesDir"])
            .isEqualTo(model.filesDir(instance.vuid, probePackage))
        assertThat(observed["type"]).isEqualTo("vnd.android.cursor.dir/probe-alt")

        // The two really are different processes: the caller wrote its own pid, the
        // provider wrote the pid it answered from.
        val callerPid = observed["callerPid"]!!.toInt()
        val providerPid = observed["provider.pid"]!!.toInt()
        assertThat(providerPid).isNotEqualTo(callerPid)
        assertThat(observed["provider.processName"]).isEqualTo("$probePackage:alt")
    }

    // -----------------------------------------------------------------------------
    // Phase 3: implicit starts, resolved against the guest's own intent filters.
    // -----------------------------------------------------------------------------

    @Test
    fun t31_theGuestStartsItsOwnActivityImplicitly() = runBlocking {
        val instance = requireInstance()
        val result = File(model.filesDir(instance.vuid, probePackage), "probe-deeplink.properties")

        // By custom action, with neither a component nor a package. Nothing else on the
        // device declares this action, so UNIQUE is the only thing that can resolve it.
        result.delete()
        clearResult(instance)
        launchProbeWith(instance) { it.putExtra("probe.implicitAction", true) }
        val byAction = awaitFile(result)

        assertThat(byAction["action"]).isEqualTo("com.unique.probe.CUSTOM_OPEN")
        assertThat(byAction["extra"]).isEqualTo("by-action")
        assertThat(byAction["packageName"]).isEqualTo(probePackage)
        // The guest's own activity class, not a stub: the graft happened.
        assertThat(byAction["componentName"])
            .isEqualTo("$probePackage/$probePackage.ProbeDeepLinkActivity")
        assertThat(byAction["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))

        // By the app's own URI scheme — the shape a browser uses to hand an OAuth result
        // back, and so the prerequisite for the passthrough Google flow.
        result.delete()
        clearResult(instance)
        launchProbeWith(instance) { it.putExtra("probe.implicitScheme", true) }
        val byScheme = awaitFile(result)

        assertThat(byScheme["action"]).isEqualTo("android.intent.action.VIEW")
        assertThat(byScheme["scheme"]).isEqualTo("unique-probe")
        assertThat(byScheme["data"]).isEqualTo("unique-probe://open/callback?code=abc")
        assertThat(byScheme["extra"]).isEqualTo("by-scheme")
        assertThat(byScheme["packageName"]).isEqualTo(probePackage)
    }

    /**
     * A guest's provider process dying leaves UNIQUE alive and working.
     *
     * Precisely that, and not more. `ActivityManagerService` *does* kill a client whose
     * **stable** provider reference points into a dying process —
     *
     * ```
     * Killing …:com.unique:vapp0 (adj 0): depends on provider
     *     com.unique/.stub.ProviderStub_p2 in dying proc com.unique:vapp2
     * ```
     *
     * — and when those two are one instance's main process and the same instance's
     * `:alt`, that is the platform's own contract, which an installed app with a provider
     * in `android:process=":alt"` gets too. Asserting it away would be asserting that
     * UNIQUE is *less* faithful than the platform, and the attempt to (by rewriting the
     * `stable` flag in flight) desynchronised `ActivityThread`'s reference counts from
     * ActivityManager's and broke provider access outright.
     *
     * What §3 promises is that a guest cannot take UNIQUE down. So the check is on UNIQUE:
     * still alive, and still *able to do its job* afterwards — a process that survives as
     * a corpse would pass a liveness check and fail the user.
     */
    @Test
    fun t32_aProviderProcessDyingLeavesUniqueWorking() = runBlocking {
        val instance = requireInstance()
        UniqueEngine.registerBroadcastRoutes(context)

        // Make the guest reach across into another slot, which is what creates a provider
        // dependency between two virtual processes at all.
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-altprovider.properties")
        result.delete()
        clearResult(instance)
        launchProbeWith(instance) { it.putExtra("probe.queryAltProvider", true) }
        // Longer than the default: this needs a cold graft of a *second* slot.
        val observed = awaitFile(result, timeoutMillis = 300_000)
        assertThat(observed["error"]).isNull()

        val providerPid = observed["provider.pid"]!!.toInt()
        val uniquePid = Process.myPid()
        assertThat(providerPid).isNotEqualTo(uniquePid)

        killAndWait(providerPid)

        // Watch for long enough that a kill on its way would arrive. Asserting once,
        // immediately, would pass even while ActivityManager was still deciding.
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            assertThat(isAlive(uniquePid)).isTrue()
            Thread.sleep(500)
        }

        // Alive is not enough. UNIQUE reaches guest providers through an *unstable*
        // client precisely so that a dead one costs it a reconnection rather than its
        // process, and this is where that is checked rather than assumed: the same
        // authority, read again, after the process serving it was killed.
        val client = VirtualProviderBridge.open(
            context, instance.vuid, "com.unique.probe.provider",
        ) ?: throw AssertionError("UNIQUE could not reach a guest provider after the kill")
        val rows = try {
            client.query(
                Uri.parse("content://com.unique.probe.provider/rows"), null, null, null, null,
            ).use { cursor ->
                checkNotNull(cursor) { "the guest's provider returned no cursor" }
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        } finally {
            client.close()
        }
        assertThat(rows["packageName"]).isEqualTo(probePackage)
        assertThat(rows["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
    }

    // -----------------------------------------------------------------------------
    // Phase 3: sharing a file out of a virtual app, and the Google routing decision.
    // -----------------------------------------------------------------------------

    @Test
    fun t34_aGuestSharesAFileWithSomethingOutsideIt() = runBlocking {
        val instance = requireInstance()
        UniqueEngine.registerBroadcastRoutes(context)

        val record = File(model.filesDir(instance.vuid, probePackage), "probe-share.properties")
        record.delete()
        clearResult(instance)
        launchProbeWith(instance) { it.putExtra("probe.shareFile", true) }
        val shared = awaitFile(record)

        assertThat(shared["error"]).isNull()
        assertThat(shared["started"]).isEqualTo("true")
        // What the guest handed out: its own authority, which nothing outside UNIQUE can
        // resolve. That is the problem the rewrite exists for.
        assertThat(shared["sharedUri"])
            .isEqualTo("content://com.unique.probe.provider/shared.txt")

        // And what UNIQUE turns it into: an authority UNIQUE really owns, carrying the
        // instance and the guest authority in the path.
        val rewritten = Uri.parse(
            "content://${context.packageName}.shared/${instance.vuid}" +
                "/com.unique.probe.provider/shared.txt"
        )

        // Read it from UNIQUE's own process — a different process, and the same thing a
        // receiving app would do with the URI it was handed.
        val payload = context.contentResolver.openInputStream(rewritten).use { stream ->
            checkNotNull(stream) { "UNIQUE's shared authority returned no stream" }
            stream.readBytes().toString(Charsets.UTF_8)
        }
        // Forwarded to the guest's own provider, not copied: this is the guest's file.
        assertThat(payload).isEqualTo("unique-shared-payload")
    }

    @Test
    fun t35_theGoogleRoutingDecisionIsRealAndSpecificToTheApp() = runBlocking {
        val instance = requireInstance()
        val manifest = ManifestReader.fromApk(
            File(UniqueEngine.storage.model.baseApk(probePackage, instance.versionCode))
        )
        val environment = GoogleEnvironment.inspect(context, setOf(probePackage))
        val decisions = GoogleCompatRouter(environment.capabilities)
            .routeAll(manifest, CompatDatabase.resolve(probePackage, instance.versionCode))

        // Every flow gets an answer, and every answer says why. A mode with no rationale is
        // a decision nobody can argue with, which is the same as one nobody can check.
        assertThat(decisions).hasSize(GoogleFlow.entries.size)
        assertThat(decisions.map { it.flow }).containsExactlyElementsIn(GoogleFlow.entries)
        decisions.forEach { assertThat(it.rationale).isNotEmpty() }

        // The decisions follow the *device*, which is the only thing that makes them more
        // than a table. This emulator has no Google stack at all.
        assertThat(environment.capabilities.hostGmsAvailable).isFalse()
        assertThat(environment.gms.present).isFalse()
        assertThat(decisions.none { it.mode == GoogleMode.HOST_BRIDGE }).isTrue()
        assertThat(decisions.none { it.mode == GoogleMode.VIRTUAL_GMS }).isTrue()

        // And the guest is told the same truth (t21 asserts the guest's own view).
        assertThat(context.packageManager.getInstalledPackages(0).none {
            it.packageName == GoogleEnvironment.GMS
        }).isTrue()
    }

    /**
     * A guest reaching a `content://` URI that belongs to a genuinely different app.
     *
     * The other direction from `t34`. What is asserted is the part UNIQUE is responsible
     * for and which holds either way: the request is *not* diverted into the virtual
     * provider table — the router says `PROVIDER_ROUTE_UNKNOWN` and the call goes to the
     * platform — and whatever comes back is a well-formed answer rather than a hang or a
     * dead process.
     *
     * Whether the read then succeeds is the device's answer, not UNIQUE's, and it has been
     * observed both ways on this emulator: once `ActivityManagerService` resolved the
     * authority and started the owning app's process for the guest, once it returned no
     * provider info at all. So the test records which happened instead of demanding one.
     *
     * A *temporary URI grant* — the case a photo picker actually uses — is not covered.
     * Instrumentation runs in the target app's process under the target app's uid, so it
     * can neither write the test app's private files nor call `grantUriPermission` for the
     * test app's authority; arranging one needs a third APK. That path stays `NOT_TESTED`
     * and is written down as such rather than approximated by something that merely looks
     * like it.
     */
    @Test
    fun t36_aGuestReachingAnotherAppsProviderGetsAWellFormedAnswer() = runBlocking {
        val instance = requireInstance()
        val result = File(model.filesDir(instance.vuid, probePackage), "probe-inbound.properties")
        result.delete()
        clearResult(instance)

        val testContext = InstrumentationRegistry.getInstrumentation().context
        assertThat(testContext.packageName).isNotEqualTo(context.packageName)

        val uri = TestFileProvider.uri()
        launchProbeWith(instance) { it.putExtra("probe.readUri", uri.toString()) }
        val observed = awaitFile(result)

        // It ran in the guest, in the guest's own process.
        assertThat(observed["uri"]).isEqualTo(uri.toString())
        assertThat(observed["packageName"]).isEqualTo(probePackage)
        assertThat(observed["callerPid"]!!.toInt()).isNotEqualTo(Process.myPid())

        val content = observed["content"]
        if (content != null) {
            // The device resolved it: the guest read a file belonging to a package it has
            // never heard of, through the real provider.
            assertThat(content).isEqualTo("handed-in-from-outside")
            assertThat(observed["type"]).isEqualTo("text/plain")
            return@runBlocking
        }

        // It did not. The requirement is then that the guest was *told* so — a readable
        // exception naming the authority it asked for, not a timeout and not a crash.
        val error = observed["error"]
        assertThat(error).isNotNull()
        assertThat(error).contains(TestFileProvider.AUTHORITY)
        android.util.Log.i(
            "UniqueTest",
            "t36: this device did not resolve another app's provider for the guest; " +
                "the guest was told so cleanly: $error",
        )
    }

    // -----------------------------------------------------------------------------
    // Phase 8: a native crash leaves a record UNIQUE can read afterwards.
    // -----------------------------------------------------------------------------

    @Test
    fun t33_aNativeCrashLeavesADiagnosticRecord() = runBlocking {
        val instance = requireInstance()
        val diagnostics = File(model.diagnosticsDir(instance.vuid, probePackage))
        clearResult(instance)

        // Establish the process first. Crashing one that never got as far as installing a
        // handler would test nothing.
        launchProbeWith(instance) { }
        val before = awaitResult(instance)
        val victimPid = before["pid"]!!.toInt()
        assertThat(isAlive(victimPid)).isTrue()

        // The record is named for the process, and deliberately *not* deleted first. The
        // handler holds its fd open from the moment the process was grafted, so unlinking
        // the path leaves it writing into a nameless inode: the write succeeds and nothing
        // ever appears. That is what the first version of this test did.
        val record = File(diagnostics, "native-crash-$victimPid.properties")

        // A *native* crash: a null dereference inside the guest's own .so. A Java
        // exception takes an entirely different path — the JVM's uncaught-exception
        // handler, which t06 covers — and would not touch a signal handler at all.
        launchProbeWith(instance) { it.putExtra("probe.nativeCrash", true) }

        val observed = awaitFileWhere(record, timeoutMillis = 240_000) {
            it["signal"] != null
        }
        assertThat(observed["signal"]).isEqualTo("SIGSEGV")
        // A null dereference, which is the most common shape of native crash there is.
        assertThat(observed["faultAddress"]).isEqualTo("0x0")
        // The record is the crashing process's own, not some earlier one's.
        assertThat(observed["pid"]!!.toInt()).isEqualTo(victimPid)
        assertThat(victimPid).isNotEqualTo(Process.myPid())

        // The process really did die: the handler chains to the platform's rather than
        // swallowing the signal, because a process that survives a SIGSEGV is in an
        // undefined state and produces worse failures later.
        awaitProcessGone(victimPid)

        // And UNIQUE is still here, running this test.
        assertThat(isAlive(Process.myPid())).isTrue()
    }

    // -----------------------------------------------------------------------------
    // Phase 6: WebView, which is the shape most likely to expose the whole trick.
    // -----------------------------------------------------------------------------

    @Test
    fun t30_theGuestRunsAWebView() = runBlocking {
        val instance = requireInstance()
        val result = File(model.filesDir(instance.vuid, probePackage), "probe-webview.properties")
        result.delete()
        clearResult(instance)

        launchProbeWith(instance) { it.putExtra("probe.webview", true) }

        // The probe writes as soon as the WebView exists, before it loads anything. That
        // is what makes the two halves below separable: everything virtualization is
        // responsible for is already decided by this point.
        val created = awaitFileWhere(result, timeoutMillis = 240_000) {
            it["created"] != null || it["createError"] != null
        }

        assertThat(created["createError"]).isNull()
        assertThat(created["created"]).isEqualTo("true")
        assertThat(created["packageName"]).isEqualTo(probePackage)
        val guestPid = created["callerPid"]!!.toInt()
        assertThat(guestPid).isNotEqualTo(Process.myPid())

        // WebView is a separate APK loaded into the guest's process as a shared library.
        // Creating one at all means the class loader and the native loader both coped with
        // a package the system never installed.
        assertThat(created["provider"]).isNotEmpty()
        assertThat(created["userAgent"]).contains("Chrome")

        // And its data directory is derived from the app's, which UNIQUE redirects. If
        // this were UNIQUE's own directory the guest would be writing its cookies into the
        // host's storage. This is the single most important line in the test.
        assertThat(created["databasePath"])
            .isEqualTo(model.dataDir(instance.vuid, probePackage))

        // Whether Chromium then renders anything is a fact about the *device*, not about
        // virtualization, so it is settled by asking the device rather than assumed. The
        // same page is loaded in UNIQUE's own process, un-virtualized; if it fails there
        // too, there is nothing here for UNIQUE to have broken.
        val hostLoaded = hostWebViewLoads()
        if (!hostLoaded) {
            android.util.Log.w(
                "UniqueTest",
                "t30: this device cannot load a page in a WebView even outside " +
                    "virtualization, so rendering is NOT_TESTED here. The guest created " +
                    "one correctly: provider=${created["provider"]} " +
                    "dataDir=${created["databasePath"]}",
            )
            retireWebViewProcess(guestPid)
            return@runBlocking
        }

        // The host can render, so the guest must too.
        val loaded = awaitFileWhere(result, timeoutMillis = 240_000) {
            it["stage"] == "finished"
        }
        assertThat(loaded["timedOut"]).isNull()
        assertThat(loaded["pageError"]).isNull()
        assertThat(loaded["pageFinished"]).isEqualTo("true")
        // JavaScript in the page set the title. A document that is created and never
        // executed still reports "loaded", which is the failure this catches.
        assertThat(loaded["title"]).isEqualTo("ready:2")
        assertThat(loaded["destroyed"]).isEqualTo("true")
        retireWebViewProcess(guestPid)
    }

    /**
     * Ends the process that hosted a WebView, and waits for it to be gone.
     *
     * Not tidiness. When a Chromium renderer dies the embedding process is aborted with
     * it, on purpose:
     *
     * ```
     * F/DEBUG: Abort message: '[FATAL:crashpad_client_linux.cc(732)] Render process
     *   (12997)'s crash wasn't handled by all associated webviews, triggering
     *   application crash.'
     * I/ActivityManager: Process com.unique:vapp0 (pid 12943) has died: fg TOP
     * ```
     *
     * On this emulator that renderer crash is reliable — it is why WebView rendering is
     * `NOT_TESTED` here at all — and it arrives *asynchronously*, a second or two later.
     * In one run it landed two seconds into the next test's launch and killed the process
     * that test had just started, reporting a Chromium fault as `t31` failing to resolve
     * an implicit intent. Retiring the process here means the abort has nothing left to
     * take with it.
     *
     * Failures are swallowed: the process may already be gone, which is the same outcome.
     */
    private fun retireWebViewProcess(pid: Int) {
        runCatching { killAndWait(pid) }
        Thread.sleep(2_000)
    }

    /**
     * Whether this device can load a page in a WebView at all, outside virtualization.
     *
     * Run in `:webviewprobe`, a process of its own, and **not here**. When a Chromium
     * renderer dies the embedding process goes with it, and on this emulator that happens
     * reliably: doing the check in the instrumentation process took the whole run down
     * twice, each time reporting the failure against whichever test was running.
     *
     * Contained, "no result" is itself the answer — the process died, so the device cannot
     * render — and the suite carries on.
     */
    private fun hostWebViewLoads(timeoutMillis: Long = 120_000): Boolean {
        val result = File(context.cacheDir, "webview-host-probe.properties")
        result.delete()
        context.startService(
            Intent(context, WebViewProbeService::class.java)
                .putExtra(WebViewProbeService.EXTRA_RESULT_PATH, result.absolutePath)
                .putExtra(WebViewProbeService.EXTRA_TIMEOUT_MILLIS, timeoutMillis / 2)
        )
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (result.isFile && result.length() > 0) {
                val read = runCatching { readProperties(result) }.getOrDefault(emptyMap())
                android.util.Log.i("UniqueTest", "host WebView probe: $read")
                return read["rendered"] == "true"
            }
            Thread.sleep(500)
        }
        android.util.Log.w(
            "UniqueTest",
            "host WebView probe produced no result in ${timeoutMillis}ms; treating this " +
                "device as unable to render",
        )
        return false
    }

    // -----------------------------------------------------------------------------
    // Phase 8: what it costs. Measured and recorded; not asserted against a budget.
    // -----------------------------------------------------------------------------

    /**
     * Measures cold start, warm start and the virtual process's memory, and writes the
     * numbers where a run directory will keep them.
     *
     * **It asserts almost nothing about the numbers themselves, on purpose.** This runs on
     * a headless x86_64 emulator with no hardware acceleration, sharing a machine with
     * whatever else is building. A wall-clock budget asserted here would not measure
     * UNIQUE; it would measure the CI host, and it would fail on the day someone else's
     * job was busy. What is asserted is that every phase happened in the right order and
     * that a warm start really did reuse the process — the two things that would actually
     * be *broken* rather than slow.
     *
     * The measurement itself is real and is what a physical-device run compares against:
     * `PERF_COLD_START`, `PERF_WARM_START` and `PERF_MEMORY` on the PROCESS channel end up
     * in the run's `engine.log`.
     */
    @Test
    fun t29_startupAndMemoryAreMeasured() = runBlocking {
        val instance = requireInstance()

        // Cold: no process at all, so this includes the fork, the graft, the guest's
        // Application.onCreate and its first Activity.
        //
        // Killing is not by itself enough to guarantee that. A `:vappN` publishes a stub
        // ContentProvider, so ActivityManager may restart it for a client of its own
        // between the kill and the launch — and the first version of this test measured
        // exactly that: a 6-second "cold start" into a process that had been up for three
        // minutes. Coldness is therefore *checked* rather than arranged, and one retry is
        // allowed before the measurement is declared unusable and skipped.
        var cold: Map<String, String> = emptyMap()
        var coldRequestedAt = 0L
        var attempts = 0
        var cameUpForUs = false
        while (attempts < 3 && !cameUpForUs) {
            attempts++
            // Kill until nothing is left, re-reading each time. One pass is not enough:
            // ActivityManager's list lags a death, so a single sweep can leave a process
            // it had not yet reported, and a restart can arrive between two sweeps.
            val killDeadline = SystemClock.uptimeMillis() + 60_000
            while (SystemClock.uptimeMillis() < killDeadline) {
                val live = runningVappProcesses().values.filter { isAlive(it) }
                if (live.isEmpty()) break
                live.forEach { runCatching { killAndWait(it) } }
                Thread.sleep(500)
            }
            val before = runningVappProcesses().values.filter { isAlive(it) }.toSet()

            clearResult(instance)
            coldRequestedAt = SystemClock.uptimeMillis()
            launchProbeWith(instance) { }
            cold = awaitResult(instance)
            cameUpForUs = cold["pid"]!!.toInt() !in before &&
                cold["processStartUptime"]!!.toLong() >= coldRequestedAt
        }

        val coldPid = cold["pid"]!!.toInt()
        val processStart = cold["processStartUptime"]!!.toLong()
        val appOnCreate = cold["applicationOnCreateUptime"]!!.toLong()
        val activityOnCreate = cold["activityOnCreateUptime"]!!.toLong()
        val coldWritten = cold["resultWrittenUptime"]!!.toLong()

        // The order is the part that can be wrong rather than merely slow: the guest's
        // Application must exist before its Activity, and both after the fork.
        assertThat(processStart).isGreaterThan(0L)
        assertThat(appOnCreate).isAtLeast(processStart)
        assertThat(activityOnCreate).isAtLeast(appOnCreate)
        assertThat(coldWritten).isAtLeast(activityOnCreate)

        // Measured on the guest's own clock. `resultWritten - processStart` is
        // self-consistent whatever the test was doing, unlike a total anchored to when the
        // test happened to ask.
        val wasCold = cameUpForUs
        val coldTotal = coldWritten - processStart
        Diagnostics.info(
            DiagChannel.PROCESS,
            if (wasCold) "PERF_COLD_START" else "PERF_COLD_START_NOT_COLD",
            mapOf(
                "package" to probePackage,
                "wasCold" to wasCold.toString(),
                "forkToResultMillis" to coldTotal.toString(),
                "forkToApplicationMillis" to (appOnCreate - processStart).toString(),
                "applicationToActivityMillis" to (activityOnCreate - appOnCreate).toString(),
                "activityToResultMillis" to (coldWritten - activityOnCreate).toString(),
                "requestToResultMillis" to (coldWritten - coldRequestedAt).toString(),
                "attempts" to attempts.toString(),
            ),
        )

        // Warm: the same process is still up, so this is the launch path with the graft
        // already done - which is what a user experiences returning to an open app.
        assertThat(isAlive(coldPid)).isTrue()
        clearResult(instance)
        val warmRequestedAt = SystemClock.uptimeMillis()
        launchProbeWith(instance) { }
        val warm = awaitResult(instance)

        // A warm start that forked a new process is not a warm start, and the number would
        // be meaningless rather than merely large.
        assertThat(warm["pid"]!!.toInt()).isEqualTo(coldPid)
        assertThat(warm["processStartUptime"]!!.toLong()).isEqualTo(processStart)

        val warmTotal = warm["resultWrittenUptime"]!!.toLong() - warmRequestedAt
        Diagnostics.info(
            DiagChannel.PROCESS, "PERF_WARM_START",
            mapOf(
                "package" to probePackage,
                "requestToResultMillis" to warmTotal.toString(),
                "reusedPid" to coldPid.toString(),
            ),
        )

        // Memory. PSS rather than RSS: shared pages - and a virtual process shares the
        // whole framework and UNIQUE's own code - would be counted in full by RSS in every
        // process at once, which is how a virtualization layer gets accused of using
        // several times the memory it uses.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = am.getProcessMemoryInfo(intArrayOf(coldPid)).firstOrNull()
        assertThat(info).isNotNull()
        Diagnostics.info(
            DiagChannel.PROCESS, "PERF_MEMORY",
            mapOf(
                "package" to probePackage,
                "pid" to coldPid.toString(),
                "totalPssKb" to info!!.totalPss.toString(),
                "dalvikPssKb" to info.dalvikPss.toString(),
                "nativePssKb" to info.nativePss.toString(),
                "otherPssKb" to info.otherPss.toString(),
            ),
        )
        assertThat(info.totalPss).isGreaterThan(0)

        // One outer bound, wide enough to be a statement about *hanging* rather than about
        // speed: a launch that takes five minutes is not slow, it is stuck. Applied to the
        // cold number only when the launch really was cold; otherwise it would be timing
        // however long the process happened to sit idle before the test asked.
        if (wasCold) assertThat(coldTotal).isLessThan(300_000L)
        assertThat(warmTotal).isLessThan(300_000L)
    }

    // -----------------------------------------------------------------------------
    // Phase 5: graphics. EGL, GLES and Vulkan inside a virtualized process.
    // -----------------------------------------------------------------------------

    @Test
    fun t28_theGuestBringsUpVulkanIfTheDeviceHasIt() = runBlocking {
        val instance = requireInstance()
        val result = File(model.filesDir(instance.vuid, probePackage), "probe-vulkan.properties")
        result.delete()
        clearResult(instance)

        launchProbeWith(instance) { it.putExtra("probe.vulkan", true) }
        val observed = awaitFile(result)

        // Whatever the device turns out to have, the probe ran inside the virtual process
        // and the library loaded. That much is UNIQUE's business: a redirect scope that
        // was too wide would stop the loader finding /system/lib64/libvulkan.so, and this
        // is where that would show.
        assertThat(observed["ran"]).isEqualTo("true")
        assertThat(observed["packageName"]).isEqualTo(probePackage)
        assertThat(observed["callerPid"]!!.toInt()).isNotEqualTo(Process.myPid())
        assertThat(observed["libraryLoaded"]).isEqualTo("true")
        assertThat(observed["symbolsResolved"]).isEqualTo("true")

        // What the *device* claims, asked outside virtualization. UNIQUE cannot conjure a
        // GPU, so this is the only honest bar: a guest must see exactly what the host has.
        val pm = context.packageManager
        val hostHasVulkan =
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        if (!hostHasVulkan) {
            // A headless software emulator is this case, and there is nothing to prove
            // here beyond the probe running and saying so. Recorded rather than skipped:
            // a test that quietly passes on a device that cannot run it is how
            // NOT_TESTED gets written down as SUPPORTED.
            assertThat(observed).containsKey("createInstanceResult")
            android.util.Log.i(
                "UniqueTest",
                "t28: this device declares no Vulkan; guest reported " +
                    "instance=${observed["instanceCreated"]} " +
                    "devices=${observed["physicalDevices"]}",
            )
            return@runBlocking
        }

        // The device has Vulkan, so the guest must get all the way to a queue.
        assertThat(observed["instanceCreated"]).isEqualTo("true")
        assertThat(observed["physicalDevices"]!!.toInt()).isAtLeast(1)
        assertThat(observed["deviceName"]).isNotEmpty()
        assertThat(observed["deviceCreated"]).isEqualTo("true")
        assertThat(observed["queueAcquired"]).isEqualTo("true")
        assertThat(observed["instanceDestroyed"]).isEqualTo("true")
    }


    @Test
    fun t20_theGuestRendersWithOpenGl() = runBlocking {
        val instance = requireInstance()
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-graphics.properties")
        result.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.graphics", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val observed = awaitFile(result)

        assertThat(observed["error"]).isNull()
        assertThat(observed["eglInitialised"]).isEqualTo("true")
        assertThat(observed["eglMadeCurrent"]).isEqualTo("true")
        assertThat(observed["eglConfigs"]!!.toInt()).isAtLeast(1)
        assertThat(observed["glError"]).isEqualTo("0")

        // The assertion that matters. A graphics stack that reports success and draws
        // nothing is the usual failure, and GL_VENDOR would not catch it: this reads back
        // the pixel that was actually rasterised. 0.25/0.5/0.75 in 8-bit, within one LSB
        // of rounding.
        assertThat(observed["pixelR"]!!.toInt()).isIn(63..65)
        assertThat(observed["pixelG"]!!.toInt()).isIn(127..129)
        assertThat(observed["pixelB"]!!.toInt()).isIn(190..192)
        assertThat(observed["pixelA"]!!.toInt()).isEqualTo(255)

        // Recorded for the physical-device run: this emulator rasterises in software, and
        // a real GPU driver is a different code path entirely.
        assertThat(observed).containsKey("glRenderer")
    }

    // -----------------------------------------------------------------------------
    // Phase 3: alarms and the clipboard — identity, and nothing but identity.
    // -----------------------------------------------------------------------------

    @Test
    fun t19_theGuestSetsAlarmsAndUsesTheClipboard() = runBlocking {
        val instance = requireInstance()
        val result =
            File(model.filesDir(instance.vuid, probePackage), "probe-alarm-clip.properties")
        result.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.alarmClipboard", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val observed = awaitFile(result)

        // Both services check the calling package against the uid, so an unhandled
        // identity layer shows up as a SecurityException from an API with nothing
        // obviously to do with package names.
        assertThat(observed["alarmError"]).isNull()
        assertThat(observed["alarmSet"]).isEqualTo("true")
        assertThat(observed["alarmCancelled"]).isEqualTo("true")

        // Writing the clipboard is what UNIQUE is responsible for: an unrewritten package
        // is rejected by the identity check. Reading it back is *not* asserted — Android
        // 10 restricted getPrimaryClip to the app holding input focus, and this headless
        // emulator never gives an activity focus, so the read is recorded rather than
        // relied on. `hadFocus` says which case a given run was.
        assertThat(observed["clipError"]).isNull()
        assertThat(observed["clipSet"]).isEqualTo("true")
        assertThat(observed).containsKey("clipRead")
        if (observed["hadFocus"] == "true") {
            assertThat(observed["clipRead"]).isEqualTo("clip-from-$probePackage")
        }

        // Exact alarms depend on the *host's* permission, which Android 14 denies by
        // default for targetSdk 33+. Recorded, not asserted either way: what matters is
        // that UNIQUE reports it rather than silently downgrading the alarm.
        assertThat(observed).containsKey("canScheduleExact")

        // And the app still believes it is itself.
        assertThat(observed["packageName"]).isEqualTo(probePackage)
    }

    // -----------------------------------------------------------------------------
    // Phase 3: JobScheduler. The first path where the *system* starts the guest.
    // -----------------------------------------------------------------------------

    @Test
    fun t18_theGuestsJobIsScheduledAndRun() = runBlocking {
        val instance = requireInstance()
        val dir = model.filesDir(instance.vuid, probePackage)
        val scheduled = File(dir, "probe-job-schedule.properties")
        val ran = File(dir, "probe-job.properties")
        scheduled.delete()
        ran.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.job", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        awaitResult(instance)
        val observed = awaitFile(scheduled)

        // JobInfo.service names a component of a package the system has never installed,
        // so an unrewritten schedule is rejected with a bare RESULT_FAILURE and nothing in
        // the log to say why.
        assertThat(observed["error"]).isNull()
        assertThat(observed["scheduleResult"]).isEqualTo("1")   // JobScheduler.RESULT_SUCCESS

        // Read back through the same API an app would use: it must see the id it chose
        // and its own class, not what UNIQUE scheduled underneath.
        assertThat(observed["pendingFound"]).isEqualTo("true")
        assertThat(observed["pendingJobId"]).isEqualTo("31")
        assertThat(observed["pendingService"])
            .isEqualTo("$probePackage/.ProbeJobService")

        // UNIQUE scheduled it under a namespaced id, so two instances cannot cancel each
        // other's work.
        val hostJobId = StubRouter.hostJobId(instance.vuid, 31)
        assertThat(StubRouter.virtualJobId(hostJobId)).isEqualTo(31)

        // And the system ran it, in the guest's own class, storage and process.
        val executed = awaitFile(ran, timeoutMillis = 120_000)
        assertThat(executed["ran"]).isEqualTo("true")
        assertThat(executed["jobId"]).isEqualTo("31")
        assertThat(executed["className"]).isEqualTo("$probePackage.ProbeJobService")
        assertThat(executed["packageName"]).isEqualTo(probePackage)
        assertThat(executed["filesDir"]).isEqualTo(dir)
    }

    // -----------------------------------------------------------------------------
    // Phase 8: what a real device found that this emulator never did.

    /**
     * A guest reads a setting, and is called by its own name.
     *
     * Both of these were reported as working and were not. They are here together because
     * they failed for the same reason — the emulator answers a question the phone asks
     * differently — and because a physical-device run is what found each of them.
     *
     * **The settings read.** `Settings.Global` and `Settings.Secure` are content-provider
     * calls carrying an `AttributionSource`, and the provider checks it against the uid.
     * UNIQUE wraps the provider to substitute the host's — but the framework reads a
     * setting during `handleBindApplication`, before any guest code exists, and the raw
     * provider it acquires is cached process-wide in `ActivityThread.mProviderMap` and in
     * each `Settings` class's own static holder. Nothing acquires it again, so the wrapper
     * never gets in front of it, and on a Xiaomi Android 15 device every guest died with
     *
     * ```
     * SecurityException: Package com.example does not belong to 10300
     *     at android.provider.Settings$NameValueCache.getStringForUser
     *     at android.database.sqlite.SQLiteCompatibilityWalFlags.initIfNeeded
     *     at android.database.sqlite.SQLiteDatabase.<init>
     * ```
     *
     * — an app that cannot open a database, which is every app. It passed here because
     * this emulator's settings provider is laxer about the caller.
     *
     * **The label.** `android:label` is a *reference* into the APK's resource table, and
     * UNIQUE's binary-XML reader has no table, so it spelled the reference out: every
     * imported app on the phone was called `@7f010000`. The probe now names itself through
     * `@string/app_name`, in two languages, so this asserts resolution rather than the
     * absence of a bug nobody wrote a test for.
     */
    @Test
    fun t38_theGuestReadsASettingAndIsCalledByItsOwnName() = runBlocking {
        val instance = requireInstance()
        clearResult(instance)
        launchProbeWith(instance) { }
        val observed = awaitResult(instance)

        // The read has to have happened, not merely not-thrown somewhere else.
        assertThat(observed["globalSettingError"]).isNull()
        assertThat(observed["globalSettingRead"]).isEqualTo("true")
        assertThat(observed["secureSettingError"]).isNull()
        assertThat(observed["secureSettingRead"]).isEqualTo("true")

        // And the eviction that makes it possible is recorded rather than inferred.
        val evictions = Diagnostics.snapshot(DiagChannel.PROCESS)
            .filter { it.code == "PROVIDER_CACHES_EVICTED" }
        android.util.Log.i("UniqueTest", "t38: provider cache evictions seen: ${evictions.size}")

        // The platform's own answer for what this app is called, read through the guest's
        // resources. A reference would mean the resource table never reached the loader.
        val label = observed["resolvedLabel"]
        assertThat(label).isNotNull()
        assertThat(label).doesNotContain("@")
        assertThat(label).isNotEmpty()

        // UNIQUE's own answer, from the stored APK, for the same app. This is what the
        // list on Home shows, and it is a different code path from the one above: the
        // guest asked its own PackageManager, this asks the host's about an archive.
        val resolved = GuestAppInfo.of(context, probePackage, instance.versionCode)
        assertThat(resolved.label).isNotNull()
        assertThat(resolved.label!!).doesNotContain("@")
        // Both routes must agree on the name, or one of them is reading the wrong table.
        assertThat(resolved.label).isEqualTo(label)
        Diagnostics.info(
            DiagChannel.STORAGE, "LABEL_AGREEMENT",
            mapOf("guest" to label!!, "host" to resolved.label!!),
        )

        // And the icon, which had the same shape of bug for a different reason:
        // `getApplicationIcon(packageName)` answers only for installed packages, and an
        // imported app is by definition not one.
        assertThat(resolved.icon).isNotNull()
    }

    // -----------------------------------------------------------------------------
    // Phase 8: the evidence a tester can gather with nothing but the phone.

    /**
     * The on-device report, the checklist and the export that carries them.
     *
     * This is the path a physical-device run actually depends on: no `adb`, no root, no
     * computer, so if it is wrong there is no second way to find out what happened. Three
     * separable claims, asserted separately.
     *
     * The third is a *requirement*, not a nicety. The export is a file a user is invited to
     * mail to a stranger, and it must carry nothing an app stored — no databases, no
     * shared preferences, no cookies, no tokens. The guarantee comes from the export never
     * opening those directories rather than from filtering at the end, which is stronger,
     * but a guarantee by construction is exactly the kind that a later refactor removes
     * without noticing. So a marker is planted inside the guest's own storage, in the two
     * places a token really would live, and every byte of the zip is searched for it.
     */
    @Test
    fun t37_theDeviceReportChecklistAndExportAreRealAndCarryNoAppData() = runBlocking {
        val instance = requireInstance()

        // 1. The device report. Every section, and values that were measured rather than
        // defaulted — the Vulkan section in particular comes from UNIQUE's own native
        // probe, so an answer at all proves the JNI entry point exists and ran.
        val sections = DeviceReport.collect(context, setOf(probePackage))
        val byTitle = sections.associate { it.title to it.values }
        assertThat(byTitle.keys).containsAtLeast(
            "Device", "Runtime", "Engine", "Graphics (host)", "WebView (host)", "Google (host)",
        )
        assertThat(byTitle["Runtime"]!!["abis"]).isNotEmpty()
        assertThat(byTitle["Runtime"]!!["pageSizeBytes"]!!.toInt()).isAtLeast(4096)
        assertThat(byTitle["Engine"]!!["nativeLoaded"]).isEqualTo("true")
        // The probe answers even where there is no driver; what must not happen is no
        // answer, which is what a missing or misnamed JNI symbol would produce.
        assertThat(byTitle["Graphics (host)"]!!["vulkanLibraryLoaded"]).isAnyOf("true", "false")
        assertThat(byTitle["Graphics (host)"]).containsKey("vulkanIsHardware")
        Diagnostics.info(
            DiagChannel.PROCESS, "DEVICE_REPORT_OBSERVED",
            mapOf(
                "pageSize" to byTitle["Runtime"]!!["pageSizeBytes"]!!,
                "abis" to byTitle["Runtime"]!!["abis"]!!,
                "vulkanDeviceType" to (byTitle["Graphics (host)"]!!["vulkanDeviceType"] ?: "-"),
                "vulkanIsHardware" to (byTitle["Graphics (host)"]!!["vulkanIsHardware"] ?: "-"),
                "webViewProvider" to (byTitle["WebView (host)"]!!["provider"] ?: "-"),
            ),
        )

        // 2. The checklist. Twelve steps in the order the tester is asked to run them, a
        // verdict that survives being written and read back, and a note that survives with
        // it — the note is the part a person actually writes, and losing it silently would
        // be worse than losing the verdict.
        val fresh = TestChecklist.reset(context)
        assertThat(fresh.map { it.id }).containsExactly(
            "s01", "s02", "s03", "s04", "s05", "s06",
            "s07", "s08", "s09", "s10", "s11", "s12",
        ).inOrder()
        assertThat(fresh.all { it.verdict == TestChecklist.Verdict.NOT_RUN }).isTrue()

        val note = "observed on the acceptance run"
        TestChecklist.set(context, "s07", TestChecklist.Verdict.PASS, note)
        val reread = TestChecklist.steps(context).first { it.id == "s07" }
        assertThat(reread.verdict).isEqualTo(TestChecklist.Verdict.PASS)
        assertThat(reread.note).isEqualTo(note)

        // 3. The export, and what it must not contain. The marker goes where a real token
        // would: an app's SharedPreferences XML and a file under databases/.
        val marker = "UNIQUE_MUST_NOT_LEAK_" + SystemClock.uptimeMillis()
        val dataDir = File(model.dataDir(instance.vuid, probePackage))
        val prefs = File(dataDir, "shared_prefs/probe_secrets.xml").apply {
            parentFile?.mkdirs()
            writeText(
                "<?xml version='1.0'?><map>" +
                    "<string name=\"oauth_token\">$marker</string></map>"
            )
        }
        val db = File(dataDir, "databases/probe_accounts.db").apply {
            parentFile?.mkdirs()
            writeText("account row: refresh_token=$marker")
        }
        // And in a place UNIQUE *does* read, to prove the redactor is on that path too:
        // an app is free to log its own secrets, and UNIQUE records what apps log.
        Diagnostics.info(
            DiagChannel.STORAGE, "TEST_PLANTED_MARKER",
            mapOf("oauth_token" to marker, "note" to "must be redacted on export"),
        )

        val liveSlots = UniqueEngine.launcher.snapshot()
            .filter { it.occupant != null }
            .map { it.index }
        val instances = UniqueEngine.instances.instances().map {
            mapOf(
                "vuid" to it.vuid.toString(),
                "package" to it.packageName,
                "versionCode" to it.versionCode.toString(),
                "androidId" to it.profile.androidId,
            )
        }
        val export = DiagnosticsExport.write(context, liveSlots, instances)

        assertThat(export.file.isFile).isTrue()
        assertThat(export.bytes).isGreaterThan(0L)
        // App-private storage, never anywhere world-readable.
        assertThat(export.file.absolutePath).startsWith(context.cacheDir.absolutePath)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(export.file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertThat(entries.keys).containsAtLeast(
            "environment.txt", "device-report.txt", "test-checklist.txt",
            "instances.txt", "unique.log", "logcat.txt", "README.txt",
        )
        // The two new entries carry what the screens showed, not placeholders.
        assertThat(entries["device-report.txt"]).contains("[Runtime]")
        assertThat(entries["device-report.txt"]).contains("pageSizeBytes=")
        assertThat(entries["test-checklist.txt"]).contains("s07")
        assertThat(entries["test-checklist.txt"]).contains(note)

        // The system log is the half UNIQUE's own events cannot see, so an empty entry
        // would make the leak check below pass for the wrong reason.
        assertThat(entries["logcat.txt"]!!.lineSequence().count()).isGreaterThan(3)

        // The event really was recorded — otherwise "no marker anywhere" would be true of
        // a package that simply never saw it.
        assertThat(entries["unique.log"]).contains("TEST_PLANTED_MARKER")
        assertThat(entries["unique.log"]).contains("oauth_token=[redacted]")

        // The requirement. Not one entry, anywhere in the package.
        val leaked = entries.filterValues { it.contains(marker) }.keys
        assertThat(leaked).isEmpty()
        assertThat(prefs.readText()).contains(marker)   // it really was there to find
        assertThat(db.readText()).contains(marker)

        Diagnostics.info(
            DiagChannel.STORAGE, "EXPORT_CONTAINS_NO_APP_DATA",
            mapOf(
                "entries" to entries.size.toString(),
                "bytes" to export.bytes.toString(),
                "slots" to liveSlots.size.toString(),
            ),
        )
    }

    /**
     * Waits for a file whose contents satisfy [until].
     *
     * The plain [awaitFile] returns the first non-empty version it sees, which is a race
     * whenever the probe writes more than once per launch: `ProbeService` writes on
     * `onCreate`, again on `onStartCommand` and again on `onBind`, so a test asserting on
     * the later state can read the earlier one and fail with values that are simply not
     * finished yet. Naming the state to wait for removes the race instead of widening a
     * sleep.
     */
    private fun awaitFileWhere(
        file: File,
        timeoutMillis: Long = 180_000,
        until: (Map<String, String>) -> Boolean,
    ): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: Map<String, String> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) {
                last = runCatching { readProperties(file) }.getOrDefault(emptyMap())
                if (until(last)) return last
            }
            Thread.sleep(250)
        }
        throw AssertionError(
            "$file never reached the expected state within ${timeoutMillis}ms; last saw $last"
        )
    }

    private fun readProperties(file: File): Map<String, String> =
        file.readLines()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun awaitFile(file: File, timeoutMillis: Long = 180_000): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) {
                Thread.sleep(250)
                return file.readLines()
                    .filter { it.contains('=') }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            }
            Thread.sleep(500)
        }
        throw AssertionError("nothing appeared at ${file.path} within ${timeoutMillis}ms")
    }

    /**
     * The pids of UNIQUE's own live processes.
     *
     * `getRunningAppProcesses` returns only the caller's own processes on modern Android,
     * which is exactly the question here and avoids depending on `/proc` visibility - the
     * emulator mounts it `hidepid=invisible`.
     */
    private fun runningVirtualPids(): List<Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty().map { it.pid }
    }

    /**
     * Launches the probe's main activity with an extra, from a clean task.
     *
     * CLEAR_TASK for the same reason t06 needs it: with NEW_TASK alone the system finds
     * the existing task and recreates its top activity from the *stored* intent, so the
     * extra never arrives and the test waits for something nobody was asked to do.
     */
    private fun launchProbeWith(instance: Instance, decorate: (Intent) -> Unit) {
        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            // Leased from the pool, not read back from a snapshot. `slotOf` answers 0 for
            // an instance that has never launched, and slot 0 belongs to the *first*
            // instance — so a second instance launched through it is refused with
            // SLOT_ALREADY_BOUND, correctly and confusingly.
            slot = UniqueEngine.launcher.acquireSlot(instance.vuid, probePackage, probePackage)
                ?: slotOf(instance.vuid),
        )
        val intent = VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        decorate(intent)
        context.startActivity(intent)
    }

    /** The pid of UNIQUE's own `:core` process, which is where this test runs. */
    private fun uniqueCorePid(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == context.packageName }
            ?.pid ?: Process.myPid()
    }

    /** UNIQUE's live `:vappN` processes, by process name. Never UNIQUE's own. */
    private fun runningVappProcesses(): Map<String, Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty()
            .filter { it.processName.contains(":vapp") }
            .associate { it.processName to it.pid }
    }

    private fun awaitProcessGone(pid: Int, timeoutMillis: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(pid)) return
            Thread.sleep(500)
        }
        throw AssertionError("process $pid was still running ${timeoutMillis}ms after the crash")
    }

    // -----------------------------------------------------------------------------

    private suspend fun requireSecondInstance(): Instance =
        checkNotNull(
            UniqueEngine.instances.instances()
                .filter { it.packageName == probePackage }
                .getOrNull(1)
        ) { "no second probe instance; t05 must run first" }

    /** Mirrors the launcher's assignment for the crash test, which bypasses it. */
    private fun slotOf(vuid: Int): Int =
        UniqueEngine.launcher.snapshot().firstOrNull { it.occupant?.vuid == vuid }?.index ?: 0

    private fun readResult(instance: Instance): Map<String, String> =
        resultFile(instance).readLines()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    // -----------------------------------------------------------------------------

    private suspend fun existingInstance(): Instance? =
        UniqueEngine.instances.instances().firstOrNull { it.packageName == probePackage }

    private suspend fun requireInstance(): Instance =
        checkNotNull(existingInstance()) { "no probe instance; t01 must run first" }

    /** Copies the probe out of the test APK's assets into a place the engine can read. */
    private fun stageProbeApk(): File = stageAsset("probe.apk")

    private fun stageAsset(name: String): File {
        val dest = File(context.cacheDir, "staged-$name")
        if (dest.isFile && dest.length() > 0) return dest
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        check(dest.length() > 0) { "$name asset was empty" }
        return dest
    }

    private fun isInstalledOnHost(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun resultFile(instance: Instance) =
        File(model.filesDir(instance.vuid, probePackage), "probe-result.properties")

    private fun clearResult(instance: Instance) {
        resultFile(instance).delete()
    }

    /**
     * Waits for the probe to report.
     *
     * The timeout is generous because this suite is expected to run on an emulator
     * without hardware acceleration, where a cold app start takes tens of seconds.
     */
    private fun awaitResult(instance: Instance, timeoutMillis: Long = 180_000): Map<String, String> {
        val file = resultFile(instance)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) {
                Thread.sleep(250) // let the writer finish flushing
                val parsed = file.readLines()
                    .filter { it.contains('=') }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                if (parsed.containsKey("dbRowCount")) return parsed
            }
            Thread.sleep(500)
        }
        throw AssertionError(
            "the probe did not report within ${timeoutMillis}ms; expected ${file.path}"
        )
    }

    /**
     * Kills a virtual process and waits for it to go.
     *
     * Same uid as UNIQUE, so the kill is permitted. Liveness is checked through
     * `getRunningAppProcesses` rather than `/proc`, which the emulator mounts
     * `hidepid=invisible`.
     */
    private fun killAndWait(pid: Int) {
        Process.killProcess(pid)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(pid)) return
            Thread.sleep(250)
        }
        throw AssertionError("process $pid did not exit")
    }

    /**
     * Whether a pid still exists, asked of the kernel rather than of ActivityManager.
     *
     * `getRunningAppProcesses` has listed a process three minutes after `system_server`
     * logged its obituary, which turned a test about broadcasts into a test about
     * ActivityManager's bookkeeping. `kill(pid, 0)` performs the permission check and the
     * existence check and delivers nothing; UNIQUE and its `:vappN` processes share a uid,
     * so the permission half always passes and `ESRCH` means exactly what it says.
     */
    private fun isAlive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (e: ErrnoException) {
        e.errno != OsConstants.ESRCH
    }

    // -----------------------------------------------------------------------------
    // The window the platform built, and the manifest attributes behind it.
    // -----------------------------------------------------------------------------

    /**
     * A virtual activity is hardware accelerated, and knows it.
     *
     * `Activity.attach` decides this from one bit:
     *
     * ```java
     * mWindow.setWindowManager(…, (info.flags & FLAG_HARDWARE_ACCELERATED) != 0);
     * ```
     *
     * and `info` is the `ActivityInfo` UNIQUE substitutes. That substitution never set
     * `flags` at all, so the bit was clear for every app UNIQUE has ever run and every one
     * of them drew in software. It was reported as the screen being laggy, and for
     * anything that renders through a `RenderNode` it was not slow but fatal:
     *
     * ```
     * CRASH u1 clear.una UNCAUGHT_EXCEPTION thread=main
     *   reason=IllegalArgumentException: Software rendering doesn't support drawRenderNode
     * ```
     *
     * The probe declares nothing about acceleration, which is the case that matters: the
     * platform's default is on for `targetSdk >= 14`, and reproducing that default is what
     * was missing.
     */
    @Test
    fun t39_theGuestsWindowIsHardwareAccelerated() = runBlocking {
        val instance = requireInstance()
        val windowResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-window.properties")
        windowResult.delete()
        clearResult(instance)
        // Cleared rather than resumed: this test is about what the guest
        // observes at create, and a launch onto a task that already has the
        // activity is delivered to it instead (`t45` is the test for that).
        launchProbeWith(instance) { }
        val observed = awaitResult(instance)

        // What the app is told about its own manifest entry, through UNIQUE's
        // PackageManager — which is also the value `ActivityThread` launched it with.
        assertThat(observed["activityInfoHardwareAccelerated"]).isEqualTo("true")

        // And what the window actually is, read after it was attached. `onCreate` is too
        // early for this: the decor has no `ViewRootImpl` yet.
        val window = awaitFile(windowResult)
        assertThat(window["windowHardwareAccelerated"]).isEqualTo("true")
    }

    /**
     * A landscape activity opens landscape.
     *
     * The system builds the window from the manifest entry it has *installed*, which under
     * UNIQUE is a stub declaring `unspecified`; the guest's own `android:screenOrientation`
     * reaches the client and nothing else. `ProbeSecondActivity` declares `landscape`, and
     * a tester's report that games opened vertically is exactly this.
     *
     * Asserted on `getRequestedOrientation`, not on the resulting configuration: whether
     * the display actually rotates depends on the device, and a headless emulator's does
     * not. What UNIQUE is responsible for is that the request reached the platform.
     */
    @Test
    fun t40_theGuestsDeclaredOrientationIsApplied() = runBlocking {
        val instance = requireInstance()
        val secondResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-second.properties")
        secondResult.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.startSecond", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        awaitResult(instance)
        val second = awaitFile(secondResult)

        // SCREEN_ORIENTATION_LANDSCAPE
        assertThat(second["requestedOrientation"]).isEqualTo("0")
    }

    /**
     * The guest's own meta-data is there, and a reference in it resolved to a number.
     *
     * `ApplicationInfo.metaData` was always null, because UNIQUE built the
     * `ApplicationInfo` and never filled it. Google Play services reads exactly one
     * integer out of it and throws when it is absent:
     *
     * ```
     * FA: Task exception on worker thread: java.lang.IllegalStateException: A required
     *   meta-data tag in your app's AndroidManifest.xml does not exist. You must have the
     *   following declaration within the <application> element:
     *   <meta-data android:name="com.google.android.gms.version" …/>
     * ```
     *
     * The probe declares the same *shape*: a literal string and a reference to an integer
     * resource. The reference is the half that needs the guest's own resources, which is
     * why it can only be resolved inside the virtual process.
     */
    @Test
    fun t41_theGuestReadsItsOwnMetaData() = runBlocking {
        val instance = requireInstance()
        clearResult(instance)
        // Cleared rather than resumed: this test is about what the guest
        // observes at create, and a launch onto a task that already has the
        // activity is delivered to it instead (`t45` is the test for that).
        launchProbeWith(instance) { }
        val observed = awaitResult(instance)

        assertThat(observed["metaDataPresent"]).isEqualTo("true")
        assertThat(observed["metaDataText"]).isEqualTo("probe-meta")
        // @integer/probe_number, resolved against the guest's resource table.
        assertThat(observed["metaDataNumber"]).isEqualTo("240508")
    }

    /**
     * The guest can look itself up in the package manager.
     *
     * Every call here is ordinary application code that returned null or an empty list,
     * because `PackageManagerService` has never installed the package being asked about:
     * `getLaunchIntentForPackage(getPackageName())` is how an app restarts itself,
     * `resolveActivity` is the guard in front of most `startActivity` calls, and
     * `getPackageInfo(GET_ACTIVITIES)` is how a launcher-shortcut or deep-link helper
     * checks its own manifest.
     */
    @Test
    fun t42_theGuestResolvesItsOwnComponents() = runBlocking {
        val instance = requireInstance()
        clearResult(instance)
        // Cleared rather than resumed: this test is about what the guest
        // observes at create, and a launch onto a task that already has the
        // activity is delivered to it instead (`t45` is the test for that).
        launchProbeWith(instance) { }
        val observed = awaitResult(instance)

        assertThat(observed["launchIntentForSelf"]).isEqualTo("true")
        assertThat(observed["resolveSelfActivity"]).isEqualTo("$probePackage.ProbeActivity")
        assertThat(observed["resolveSelfService"]!!.toInt()).isAtLeast(1)
        assertThat(observed["ownActivityCount"]!!.toInt()).isAtLeast(3)
        assertThat(observed["ownServiceCount"]!!.toInt()).isAtLeast(1)
        // An installed app is in the device's own list; UNIQUE is not in the guest's.
        assertThat(observed["installedListHasSelf"]).isEqualTo("true")
        assertThat(observed["installedListHasHost"]).isEqualTo("false")
        // `getInstallerPackageName` threw `IllegalArgumentException: Unknown package` for
        // a package the platform never installed — unchecked, so it took the app with it.
        // Answered as "null", which is what a sideloaded app gets and what UNIQUE is.
        assertThat(observed["installerPackageName"]).isEqualTo("null")
    }

    /**
     * The guest looks like itself from inside the process, not like UNIQUE.
     *
     * `/proc/self/cmdline` is the cheapest virtualization check there is — one read, no
     * permission, no API — and it read `com.unique:vapp0` for every guest UNIQUE has run.
     * `ActivityThread.handleBindApplication` sets it with `Process.setArgV0` for an
     * installed app; UNIQUE now does the same with the guest's process name, which is what
     * the platform would have done.
     *
     * The process table is the same question asked through the framework: an app checking
     * whether its own process is running found UNIQUE's name and concluded it was not.
     */
    @Test
    fun t43_theGuestSeesItsOwnProcessAndNotUniques() = runBlocking {
        val instance = requireInstance()
        clearResult(instance)
        // Cleared rather than resumed: this test is about what the guest
        // observes at create, and a launch onto a task that already has the
        // activity is delivered to it instead (`t45` is the test for that).
        launchProbeWith(instance) { }
        val observed = awaitResult(instance)

        assertThat(observed["procCmdline"]).isEqualTo(probePackage)
        assertThat(observed["runningProcessName"]).isEqualTo(probePackage)
        // No other process of UNIQUE's uid — neither :core nor a sibling instance.
        assertThat(observed["runningSawSiblingProcess"]).isEqualTo("false")
    }

    /**
     * External storage resolves inside the instance, and can be written to.
     *
     * `ContextImpl` derives it from the *package name*, so a guest was handed
     * `/storage/emulated/0/Android/data/<guest>/files` — a scoped-storage directory
     * UNIQUE may not create, because the package it is named for is not UNIQUE. The
     * platform's own `ensureExternalDirsExistOrFilter` then returned null, and an app that
     * keeps downloads or caches there sees storage as unavailable.
     */
    @Test
    fun t44_theGuestsExternalStorageIsItsOwnAndUsable() = runBlocking {
        val instance = requireInstance()
        clearResult(instance)
        // Cleared rather than resumed: this test is about what the guest
        // observes at create, and a launch onto a task that already has the
        // activity is delivered to it instead (`t45` is the test for that).
        launchProbeWith(instance) { }
        val observed = awaitResult(instance)

        // The layout the platform builds from a volume path is exactly the one the path
        // model describes: <root>/Android/data/<pkg>/files. Asserted as equality rather
        // than as a prefix, because a near-miss here is a directory two apps share.
        assertThat(observed["externalStorageDirectory"])
            .isEqualTo(model.externalRoot(instance.vuid))
        assertThat(observed["externalFilesDir"])
            .isEqualTo(model.externalFilesDir(instance.vuid, probePackage))
        assertThat(observed["externalFilesDirWritable"]).isEqualTo("true")
    }

    /**
     * A launch delivered to an activity that is already running arrives as the guest's own.
     *
     * Not every launch creates an activity. `FLAG_ACTIVITY_SINGLE_TOP` — and, for a task
     * already running the component, `FLAG_ACTIVITY_NEW_TASK` on its own — makes the
     * platform hand the intent to the activity on top instead:
     *
     * ```
     * START u0 {…cmp=com.unique/.stub.ActivityStub_p0_m0_a0} … result code=3
     * ```
     *
     * `START_DELIVERED_TO_TOP`. It is what returning to an app you already opened is, and
     * what every `singleTop` screen, notification tap and deep link relies on. It happened
     * five times in the run that added the tests above.
     *
     * The intent that arrives is UNIQUE's stub intent, and `ActivityThread` assigns it to
     * `Activity.mIntent`, so it is also what `getIntent()` answers for the rest of the
     * activity's life. An app reading it in `onNewIntent` would find a component that is
     * not its own and none of the extras that were sent.
     *
     * Driven from *inside* the guest, from `onResume`, because that is the only way to be
     * sure the platform delivers rather than queues. `ActivityRecord.deliverNewIntentLocked`
     * sends the transaction only while the activity is `RESUMED` or `PAUSED`; otherwise it
     * parks the intent for the next resume, and on this headless emulator the home activity
     * holds focus, so a guest activity that has drawn is stopped moments later and never
     * resumed again. A start the guest itself makes while it is on screen has no such race.
     */
    @Test
    fun t45_aRedeliveredIntentIsTheGuestsOwn() = runBlocking {
        val instance = requireInstance()
        val redelivery =
            File(model.filesDir(instance.vuid, probePackage), "probe-newintent.properties")
        redelivery.delete()
        clearResult(instance)

        launchProbeWith(instance) { it.putExtra("probe.reenterSingleTop", true) }
        awaitResult(instance)
        val observed = awaitFile(redelivery)

        assertThat(observed["component"]).isEqualTo("$probePackage/.ProbeActivity")
        // And the same after `ActivityThread` stored it: `getIntent()` is what most apps
        // actually read, and it is assigned from the delivered intent.
        assertThat(observed["getIntentComponent"]).isEqualTo("$probePackage/.ProbeActivity")
        assertThat(observed["extra"]).isEqualTo("carried-to-new-intent")
        // None of UNIQUE's routing keys reach the app.
        assertThat(observed["extraKeys"]!!.split(",")).doesNotContain("unique.package")
        assertThat(observed["extraKeys"]!!.split(",")).doesNotContain("unique.component")
        assertThat(observed["extraKeys"]!!.split(",")).doesNotContain("unique.vuid")
        // The guest's own activity was not created a second time: this was a redelivery.
        assertThat(observed["createCount"]).isEqualTo("1")
    }

    /**
     * The guest starts its own service by action, with no class named.
     *
     * `new Intent(ACTION_MY_SERVICE)` is how a great many SDKs reach their own worker: the
     * action is a constant they own, and on a device the resolution never leaves their
     * package. Under virtualization it resolved to nothing — `PackageManagerService` has
     * no filters for a package it never installed — and the start silently reached no one.
     * Thirty of these went out in one device log, all of them from real apps.
     *
     * Resolved against the guest's own manifest instead, which is the same answer the
     * platform would have given, and only when exactly one service matches.
     */
    @Test
    fun t46_theGuestStartsItsOwnServiceByAction() = runBlocking {
        val instance = requireInstance()
        val serviceResult =
            File(model.filesDir(instance.vuid, probePackage), "probe-service.properties")
        serviceResult.delete()
        clearResult(instance)

        val params = VirtualLaunchParams(
            vuid = instance.vuid,
            packageName = probePackage,
            versionCode = instance.versionCode,
            targetComponent = "$probePackage.ProbeActivity",
            processName = probePackage,
            slot = slotOf(instance.vuid),
        )
        context.startActivity(
            VirtualLaunchIntent.build(context.packageName, params, launchMode = 0)
                .putExtra("probe.startServiceByAction", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        awaitResult(instance)
        val service = awaitFileWhere(serviceResult) { it["stage"] == "started" }

        assertThat(service["className"]).isEqualTo("$probePackage.ProbeService")
        // The action survives the trip through the stub, which is what the service reads
        // to tell one kind of start from another.
        assertThat(service["startAction"]).isEqualTo("com.unique.probe.START_BY_ACTION")
        assertThat(service["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
    }
}
