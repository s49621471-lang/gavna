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
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.unique.app.engine.UniqueEngine
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.vam.LaunchResult
import com.unique.core.vam.StubRouter
import com.unique.core.vam.VirtualLaunchIntent
import com.unique.core.vam.VirtualLaunchParams
import com.unique.core.vpm.CreateResult
import com.unique.core.vpm.Instance
import kotlinx.coroutines.runBlocking
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

        val instance = existingInstance() ?: when (val r = UniqueEngine.importFiles(listOf(apk))) {
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

        // The sibling is untouched: same process, still running.
        val after = runningVirtualPids()
        assertThat(after).contains(survivorPid)
        assertThat(after).doesNotContain(victimPid)

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

        val observed = awaitFile(receiverResult)

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
    }

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
    // Phase 5: graphics. EGL and GLES inside a virtualized process.
    // -----------------------------------------------------------------------------

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

    private fun awaitProcessGone(pid: Int, timeoutMillis: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (pid !in runningVirtualPids()) return
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
    private fun stageProbeApk(): File {
        val dest = File(context.cacheDir, "probe-under-test.apk")
        if (dest.isFile && dest.length() > 0) return dest
        InstrumentationRegistry.getInstrumentation().context.assets.open("probe.apk").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        check(dest.length() > 0) { "probe.apk asset was empty" }
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
            if (pid !in runningVirtualPids()) return
            Thread.sleep(250)
        }
        throw AssertionError("process $pid did not exit")
    }
}
