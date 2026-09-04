package com.unique.core.google

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * What this device can actually offer a virtualized app, asked of the device.
 *
 * The Google layer's routing has existed as a decision table with nothing feeding it: the
 * modes were reasoned about and unit-tested, but no code ever looked at a real device to
 * find out which of them were even possible. This is that missing half, and it is
 * deliberately the only part of the layer that makes a claim, because it is the only part
 * that can be checked.
 *
 * Everything here is a fact about the *host*. Nothing is inferred, defaulted, or
 * optimistic:
 *
 *  - A package is present because `PackageManager` returned a `PackageInfo` for it.
 *  - Play services is *usable* because it is present, enabled, and its version code is at
 *    least what the platform's own clients require. Present-but-disabled is a real state
 *    on de-Googled and enterprise devices, and it fails much later and much less clearly
 *    if it is read as "available".
 *  - A Custom Tabs browser exists because an intent for one resolved.
 *
 * The result feeds [GoogleCompatRouter], which turns it into a mode per flow. That any of
 * those modes then *works* is a separate claim, and one this build does not make: see
 * `docs/GOOGLE_DEVICE_TEST.md` for the run that would settle it.
 */
object GoogleEnvironment {

    const val GMS = "com.google.android.gms"
    const val VENDING = "com.android.vending"
    const val GSF = "com.google.android.gsf"

    /**
     * The minimum Play services version the platform's own clients treat as usable.
     *
     * A device with a Play services stub — some AOSP-derived and enterprise builds ship
     * one — reports the package as present and answers nothing. The version code is how
     * the official client library tells the two apart, so it is how UNIQUE does too.
     */
    private const val MIN_USABLE_GMS_VERSION = 20_000_000L

    /** One host package, as this device actually has it. */
    data class HostPackage(
        val packageName: String,
        val present: Boolean,
        val enabled: Boolean,
        val versionCode: Long,
        val versionName: String?,
    ) {
        val usable: Boolean get() = present && enabled
    }

    data class Report(
        val gms: HostPackage,
        val vending: HostPackage,
        val gsf: HostPackage,
        val customTabsPackage: String?,
        val capabilities: GoogleCapabilities,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "gmsPresent" to gms.present.toString(),
            "gmsEnabled" to gms.enabled.toString(),
            "gmsVersionCode" to gms.versionCode.toString(),
            "gmsVersionName" to (gms.versionName ?: "-"),
            "vendingPresent" to vending.present.toString(),
            "gsfPresent" to gsf.present.toString(),
            "customTabs" to (customTabsPackage ?: "-"),
            "hostGmsAvailable" to capabilities.hostGmsAvailable.toString(),
            "virtualGmsInstalled" to capabilities.virtualGmsInstalled.toString(),
            "customTabsAvailable" to capabilities.customTabsAvailable.toString(),
        )
    }

    /**
     * Inspects the device.
     *
     * @param virtualGmsPackages package names UNIQUE has imported into the virtual space.
     *   Mode A needs a GMS *inside* the space; whether one is there is a question about
     *   UNIQUE's own state, not the device's, so it is passed in rather than guessed.
     */
    fun inspect(context: Context, virtualGmsPackages: Set<String> = emptySet()): Report {
        val pm = context.packageManager
        val gms = read(pm, GMS)
        val vending = read(pm, VENDING)
        val gsf = read(pm, GSF)
        val customTabs = customTabsBrowser(pm)

        val hostGmsAvailable = gms.usable && gms.versionCode >= MIN_USABLE_GMS_VERSION
        val report = Report(
            gms = gms,
            vending = vending,
            gsf = gsf,
            customTabsPackage = customTabs,
            capabilities = GoogleCapabilities(
                hostGmsAvailable = hostGmsAvailable,
                virtualGmsInstalled = GMS in virtualGmsPackages,
                customTabsAvailable = customTabs != null,
            ),
        )
        Diagnostics.info(DiagChannel.GOOGLE, "GOOGLE_ENVIRONMENT", report.toMap())
        if (gms.present && !hostGmsAvailable) {
            // Worth its own line. "Installed but not usable" is the state that produces
            // the most confusing app-side failures, and reading it as available is how a
            // sign-in hangs with no error anywhere.
            Diagnostics.warn(
                DiagChannel.GOOGLE, "GOOGLE_GMS_PRESENT_BUT_UNUSABLE",
                mapOf(
                    "enabled" to gms.enabled.toString(),
                    "versionCode" to gms.versionCode.toString(),
                    "minimum" to MIN_USABLE_GMS_VERSION.toString(),
                ),
            )
        }
        return report
    }

    private fun read(pm: PackageManager, packageName: String): HostPackage = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        HostPackage(
            packageName = packageName,
            present = true,
            enabled = info.applicationInfo?.enabled ?: false,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            versionName = info.versionName,
        )
    } catch (e: PackageManager.NameNotFoundException) {
        HostPackage(packageName, present = false, enabled = false, versionCode = 0, versionName = null)
    }

    /**
     * A browser that can serve a Custom Tab, which is Mode C's whole requirement.
     *
     * Resolved from an ordinary `VIEW` on an https URL rather than by looking for a list
     * of browser package names. Every Custom Tabs implementation is a browser; not every
     * device has the browser anyone would have guessed.
     */
    private fun customTabsBrowser(pm: PackageManager): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        val name = resolved?.activityInfo?.packageName
        // The resolver activity is what comes back when several browsers are installed
        // and none is default. That is not a browser, and treating it as one would name a
        // package that cannot serve a tab.
        return name?.takeIf { it != "android" && !it.endsWith(".resolver") }
    }
}
