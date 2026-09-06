package com.unique.app.engine

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The accesses a user grants on a *screen*, not in a dialog — and what they do for a guest.
 *
 * Android splits its permissions in two. The runtime ones are asked for with a dialog and
 * are what App Details already shows a switch for. The rest — drawing over other apps,
 * exact alarms, running unrestricted in the background — cannot be requested at all: an
 * app can only send the user to a Settings screen and hope.
 *
 * A virtual app cannot even do that much. It is not a package the platform has installed,
 * so a Settings screen named for it opens nothing (`VirtualActivityTaskManagerHook`
 * retargets the intent at UNIQUE for exactly this reason), and the uid that would hold
 * the access is UNIQUE's regardless. Which makes the honest presentation the one here:
 * these are **UNIQUE's**, they apply to every copy of every app at once, and the button
 * opens the screen that grants them.
 *
 * The list is short on purpose. Each entry is something UNIQUE declares, can actually
 * obtain, and that changes what a guest can do:
 *
 *  - **Overlay.** A guest asking to draw over other apps was refused with nothing shown;
 *    `SYSTEM_ALERT_WINDOW` appeared denied three times in one phone log.
 *  - **Exact alarms.** Android 14 denies these by default for a modern target SDK, and
 *    UNIQUE reports `ALARM_EXACT_UNAVAILABLE` and hands the guest the platform's own
 *    refusal rather than a silent downgrade — six of them in the same log.
 *  - **Battery.** An instance killed by background management looks exactly like an app
 *    that crashed, and this is the only switch that changes it.
 *
 * Two are deliberately absent. **All-files access** would not help: a guest's external
 * storage is redirected into its own instance directory, so the device's real storage is
 * not what it is reading. **Usage access** would hand every guest the *host's* usage
 * history, which breaks the isolation the rest of the engine is built on.
 */
object SpecialAccess {

    /** Ids are stable and are what the UI sends back to [open]. */
    const val OVERLAY = "overlay"
    const val EXACT_ALARM = "exactAlarm"
    const val BATTERY = "battery"

    data class Access(val id: String, val granted: Boolean)

    fun list(context: Context): List<Access> = listOf(
        Access(OVERLAY, canDrawOverlays(context)),
        Access(EXACT_ALARM, canScheduleExactAlarms(context)),
        Access(BATTERY, ignoresBatteryOptimizations(context)),
    )

    /**
     * Opens the system screen that grants [id], or reports why it could not.
     *
     * Every one of these is an ordinary activity start with `FLAG_ACTIVITY_NEW_TASK`,
     * from UNIQUE's own process — nothing here goes near a guest.
     */
    fun open(context: Context, id: String): Map<String, Any?> {
        val intent = when (id) {
            OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )
            // ACTION_REQUEST_SCHEDULE_EXACT_ALARM arrived in Android 12, which is
            // minSdk, so there is no older path to fall back to.
            EXACT_ALARM -> Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.fromParts("package", context.packageName, null),
            )
            BATTERY -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", context.packageName, null),
            )
            else -> null
        } ?: return mapOf(
            "ok" to false, "code" to "NO_SUCH_ACCESS", "message" to "no such access: $id",
        )

        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mapOf<String, Any?>("ok" to true)
        }.getOrElse {
            // A device whose Settings has no such screen. Reported rather than swallowed:
            // a button that silently does nothing is worse than one that says why.
            mapOf("ok" to false, "code" to "NO_SETTINGS_SCREEN", "message" to it.toString())
        }
    }

    private fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    private fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun ignoresBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching {
            manager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
}
