package com.unique.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.unique.app.engine.UniqueEngine
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.vam.LaunchResult
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
        val service = awaitFile(serviceResult)

        // The guest's own Service class ran, believing it is itself.
        assertThat(service["className"]).isEqualTo("$probePackage.ProbeService")
        assertThat(service["packageName"]).isEqualTo(probePackage)

        // In the guest's own directory, in the guest's own process.
        assertThat(service["filesDir"]).isEqualTo(model.filesDir(instance.vuid, probePackage))
        assertThat(service["pid"]).isEqualTo(activity["pid"])

        // Both entry points were exercised.
        assertThat(service["startCount"]!!.toInt()).isAtLeast(1)
        assertThat(service["bindCount"]!!.toInt()).isAtLeast(1)
    }

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
