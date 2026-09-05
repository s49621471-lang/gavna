package com.unique.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.unique.app.engine.UniqueEngine
import com.unique.core.vam.LaunchResult
import com.unique.core.vpm.CreateResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Imports and launches one real, shipping application.
 *
 * The acceptance suite runs against `tools/testapp`, which is deliberately ordinary and
 * deliberately *cooperative*: it writes down what it observed, so every assertion there is
 * about a fact the app itself reported. That precision is also its limit — the probe was
 * written after the engine, and it only does what the engine already supports.
 *
 * A real app does not report anything, and does not care. Two faults were found the first
 * time this ran, and neither could have come from the probe:
 *
 *  - Termux died in `TermuxActivity.onStart` on `registerReceiver`, because the compat rule
 *    that requires `RECEIVER_EXPORTED` from Android 14 is evaluated against the *calling
 *    uid*, which is UNIQUE's — so an app built against Android 9 was held to UNIQUE's
 *    target SDK.
 *  - Fossify Gallery died in `MainActivity.onCreate` on `AppWidgetManager.getAppWidgetIds`,
 *    because `appwidget` was not proxied and the service checks the package it is handed
 *    against the calling uid.
 *
 * ## What this class does, and what it deliberately does not
 *
 * It drives one app: import, launch, and stay running. It asserts only what it can see
 * from UNIQUE's own process — that the import was accepted, that the launch was accepted,
 * and that the app's process was still alive at the end. **Everything else is read from
 * `logcat` by `tools/real-app-smoke.sh`**, which is what runs this.
 *
 * That split is deliberate. A guest's own diagnostics live in the guest's process and are
 * pulled through its slot's provider — so a crash both destroys the evidence and removes
 * the thing that would have served it, and the first version of this test reported "the
 * launch transaction was never rewritten" for two apps whose transactions had plainly been
 * rewritten. `logcat` is written as it happens and survives the process; it is the right
 * place to read this from.
 *
 * Skipped when no APK is named, so it can sit in the same APK as the acceptance suite
 * without becoming part of it: an app downloaded at run time can change under the test,
 * and a failure would then be a fact about F-Droid rather than about UNIQUE.
 */
@RunWith(AndroidJUnit4::class)
class RealAppSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Where `adb push` leaves them; readable by any app that knows the path. */
    private val staging = File("/data/local/tmp/unique-real")

    /**
     * How long the app must keep running after its launch is accepted.
     *
     * Long enough to cover a cold graft on this emulator *and* the failures that arrive
     * late: a licence check calls `System.exit(0)` seconds in, and the first activity's
     * `onStart` can be a minute after the launch when the machine is loaded. The first
     * run of this at two minutes stopped watching NewPipe while its hooks were still
     * installing, and then reported the launch as never having happened.
     */
    private val surviveMillis = 300_000L

    @Before
    fun setUp() {
        UniqueEngine.init(context)
    }

    @Test
    fun importLaunchAndStayUp(): Unit = runBlocking {
        val name = InstrumentationRegistry.getArguments().getString("apk")
        assumeTrue("no -e apk <name> given; see tools/real-app-smoke.sh", name != null)
        val apk = File(staging, name!!)
        assumeTrue("${apk.path} is not there", apk.isFile)

        val instance = when (val r = UniqueEngine.importFiles(listOf(apk))) {
            is CreateResult.Created -> r.instance
            is CreateResult.Rejected -> throw AssertionError("import rejected: ${r.reason}")
        }
        android.util.Log.i(TAG, "REAL_APP import ok package=${instance.packageName} vuid=${instance.vuid}")

        val launch = UniqueEngine.launch(context, instance.vuid)
        android.util.Log.i(TAG, "REAL_APP launch package=${instance.packageName} result=$launch")
        assertThat(launch).isInstanceOf(LaunchResult.Started::class.java)

        Thread.sleep(surviveMillis)
        android.util.Log.i(TAG, "REAL_APP done package=${instance.packageName}")
    }

    private companion object {
        const val TAG = "UniqueTest"
    }
}
