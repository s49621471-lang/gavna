package com.unique.app.engine

import android.content.Context
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The physical-device test sequence, recorded on the device that runs it.
 *
 * The acceptance suite covers what can be automated. What is left needs a real phone, real
 * apps and a person — and until now the only way to report it was notes in a file the
 * tester had to pull off the device with `adb`. This holds the steps, the verdicts and the
 * notes in the app, and folds them into the diagnostics export, so a tester needs nothing
 * but the phone.
 *
 * Deliberately not a pass/fail gate on anything. Nothing in `docs/COMPATIBILITY.md` moves
 * because a checklist says so; a verdict here is a person's observation, and it travels
 * with the run so it can be *read* alongside the machine's own record rather than
 * substituted for it.
 */
object TestChecklist {

    enum class Verdict { NOT_RUN, PASS, FAIL, BLOCKED, SKIPPED }

    data class Step(
        val id: String,
        val title: String,
        val what: String,
        val verdict: Verdict = Verdict.NOT_RUN,
        val note: String = "",
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "id" to id,
            "title" to title,
            "what" to what,
            "verdict" to verdict.name,
            "note" to note,
        )
    }

    /**
     * The sequence, in the order it must be run.
     *
     * Ordered so that a failure explains the ones after it: nothing can be said about
     * WebView in a guest until a guest launches, and nothing about Unity until native code
     * runs. Each `what` says what would be *learned*, not just what to tap — a step whose
     * purpose is unclear gets a verdict that means nothing.
     */
    private val STEPS: List<Step> = listOf(
        Step("s01", "Install UNIQUE",
            "It installs on this Android version and ABI at all. A failure here is usually " +
                "a signature or 16 KB-page problem, and the Device report names the page size."),
        Step("s02", "Launch UNIQUE",
            "The UI opens and Settings → Engine shows platform access Granted and the native " +
                "library Loaded. If either is not, nothing after this can work and the reason " +
                "is on that screen."),
        Step("s03", "Import a simple installed app",
            "Add App → Installed → pick something small and ordinary. The import reads the " +
                "APK the system already has; it downloads nothing."),
        Step("s04", "Launch it",
            "The virtual app opens and draws. **Look at the screen** — the suite reads files, " +
                "it never looks at pixels, so this is the first real check that rendering works."),
        Step("s05", "Second instance, and isolation",
            "Clone it, launch both, sign in or store something in one. The other must not see " +
                "it, and App Details must show a different Android ID for each."),
        Step("s06", "WebView",
            "Open something in the app that shows a web page. **This is one of the four things " +
                "only a phone can answer** — the verification emulator's Chromium renderer " +
                "crashes even outside virtualization."),
        Step("s07", "Native / JNI",
            "Use a feature backed by native code. **First ARM64 run of the whole native path**: " +
                "library loading, JNI, and libc redirection have only ever run on x86_64."),
        Step("s08", "OpenGL / Vulkan",
            "Anything that renders — a game, a map, a video. Check the Device report first: if " +
                "it says the host's Vulkan device type is `cpu`, this phone has no hardware " +
                "driver and a failure here is not UNIQUE's."),
        Step("s09", "Notifications and background",
            "Make the app post a notification and check the icon is right, not blank. Then " +
                "close it and make something arrive — a message, an alarm — to see whether a " +
                "dead guest wakes."),
        Step("s10", "Google Play services, and Sign-In",
            "App Details → Google shows what this device has and how each flow *would* be " +
                "routed. **No sign-in flow is implemented**, so the expected result is a clean " +
                "refusal, not a login. Record exactly what happens — that is the measurement."),
        Step("s11", "A real Unity / IL2CPP app",
            "The hardest case: a large native engine, its own asset loading, its own threads. " +
                "Nothing is claimed about this today. Note load time and whether it renders."),
        Step("s12", "Export the diagnostics package",
            "Settings → Advanced → Export diagnostics, then share it. It carries the logs, this " +
                "checklist and the device report — and nothing from inside any app."),
    )

    private const val FILE = "test-checklist.json"

    fun steps(context: Context): List<Step> {
        val saved = read(context)
        return STEPS.map { step ->
            val row = saved[step.id]
            if (row == null) step else step.copy(verdict = row.first, note = row.second)
        }
    }

    fun set(context: Context, id: String, verdict: Verdict, note: String): List<Step> {
        if (STEPS.none { it.id == id }) return steps(context)
        val saved = read(context).toMutableMap()
        saved[id] = verdict to note
        write(context, saved)
        Diagnostics.info(
            DiagChannel.PROCESS, "CHECKLIST_UPDATED",
            mapOf("step" to id, "verdict" to verdict.name, "hasNote" to note.isNotBlank().toString()),
        )
        return steps(context)
    }

    fun reset(context: Context): List<Step> {
        file(context).delete()
        return steps(context)
    }

    /** The checklist as it goes into the export: every step, whether it was run or not. */
    fun lines(context: Context): List<String> = buildList {
        val steps = steps(context)
        add("steps=${steps.size}")
        add("run=${steps.count { it.verdict != Verdict.NOT_RUN }}")
        add("passed=${steps.count { it.verdict == Verdict.PASS }}")
        add("failed=${steps.count { it.verdict == Verdict.FAIL }}")
        add("")
        for (step in steps) {
            add("${step.id}  ${step.verdict.name.padEnd(8)}  ${step.title}")
            if (step.note.isNotBlank()) {
                step.note.lineSequence().forEach { add("        $it") }
            }
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): Map<String, Pair<Verdict, String>> {
        val f = file(context)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val array = JSONArray(f.readText())
            buildMap {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val verdict = runCatching { Verdict.valueOf(o.optString("verdict")) }
                        .getOrDefault(Verdict.NOT_RUN)
                    put(id, verdict to o.optString("note"))
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun write(context: Context, values: Map<String, Pair<Verdict, String>>) {
        runCatching {
            val array = JSONArray()
            values.forEach { (id, row) ->
                array.put(JSONObject().apply {
                    put("id", id)
                    put("verdict", row.first.name)
                    put("note", row.second)
                })
            }
            file(context).writeText(array.toString())
        }.onFailure {
            Diagnostics.error(
                DiagChannel.STORAGE, "CHECKLIST_WRITE_FAILED",
                mapOf("error" to it.toString()),
            )
        }
    }
}
