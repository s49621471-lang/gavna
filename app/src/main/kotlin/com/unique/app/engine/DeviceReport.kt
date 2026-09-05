package com.unique.app.engine

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.google.GoogleEnvironment
import com.unique.core.hook.HiddenApi
import com.unique.core.nativebridge.UniqueNative
import com.unique.core.vam.ForegroundServiceTypes

/**
 * Everything about this device UNIQUE can establish on its own.
 *
 * Written for a tester with a phone and nothing else — no `adb`, no root, no computer. The
 * acceptance suite answers what happens *inside* a virtual app; this answers what the
 * device is, which is the other half of every result. "The guest could not bring Vulkan
 * up" means one thing on a phone with a working driver and nothing at all on one without.
 *
 * Every field is measured, not assumed. Where a capability is probed, the probe does the
 * real thing — a Vulkan device and queue, not a `dlopen` — because on Android the library
 * being present is not evidence that anything works.
 *
 * Grouped into sections rather than one flat map, because the reader is a person deciding
 * whether a failure they just saw is theirs or UNIQUE's.
 */
object DeviceReport {

    data class Section(val title: String, val values: Map<String, String>)

    fun collect(context: Context, virtualPackages: Set<String> = emptySet()): List<Section> {
        val sections = listOf(
            device(),
            runtime(context),
            engine(),
            graphics(),
            web(),
            google(context, virtualPackages),
        )
        Diagnostics.info(
            DiagChannel.PROCESS, "DEVICE_REPORT_COLLECTED",
            mapOf("sections" to sections.size.toString()),
        )
        return sections
    }

    /** Flattened for the diagnostics export, where structure costs more than it gives. */
    fun lines(sections: List<Section>): List<String> = buildList {
        for (section in sections) {
            add("[${section.title}]")
            section.values.forEach { (k, v) -> add("$k=$v") }
            add("")
        }
    }

    private fun device() = Section(
        "Device",
        mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "fingerprint" to Build.FINGERPRINT,
            "androidRelease" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT.toString(),
            "securityPatch" to (Build.VERSION.SECURITY_PATCH ?: "-"),
        ),
    )

    private fun runtime(context: Context) = Section(
        "Runtime",
        buildMap {
            put("abis", Build.SUPPORTED_ABIS.joinToString(", "))
            put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
            put("is64BitOnly", Build.SUPPORTED_32_BIT_ABIS.isEmpty().toString())
            // The single most valuable line on an Android 15 device: 16384 means the
            // 16 KB-page world, which no emulator here could exercise.
            put("pageSizeBytes", UniqueNative.pageSize().toString())
            put("uses16KbPages", (UniqueNative.pageSize() > 4096).toString())
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            put("totalRamMb", (memory.totalMem / (1024 * 1024)).toString())
            put("lowRamDevice", (am?.isLowRamDevice ?: false).toString())
            put("uniqueVersion", runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "-")
        },
    )

    private fun engine() = Section(
        "Engine",
        mapOf(
            "hiddenApiGranted" to HiddenApi.isGranted.toString(),
            "hiddenApiDetail" to (HiddenApi.failureDetail ?: "-"),
            "nativeLoaded" to UniqueNative.isLoaded.toString(),
            "nativeLoadError" to (UniqueNative.loadFailure ?: "-"),
            "foregroundServiceTypes" to ForegroundServiceTypes.HOST_SUPPORTED.toString(),
        ),
    )

    /**
     * The host's own Vulkan, which is what a guest's result is compared against.
     *
     * `hardwareAccelerated` distinguishes a real GPU from a software rasteriser. A pass on
     * `llvmpipe` is a pass on a CPU, and calling that "Vulkan supported" is exactly the
     * kind of claim `docs/COMPATIBILITY.md` exists to prevent.
     */
    private fun graphics(): Section {
        val vulkan = UniqueNative.probeVulkan()
        val type = vulkan["deviceType"]
        return Section(
            "Graphics (host)",
            buildMap {
                put("vulkanLibraryLoaded", vulkan["libraryLoaded"] ?: "false")
                put("vulkanInstanceCreated", vulkan["instanceCreated"] ?: "false")
                put("vulkanPhysicalDevices", vulkan["physicalDevices"] ?: "0")
                put("vulkanDeviceName", vulkan["deviceName"] ?: "-")
                put("vulkanDeviceType", type ?: "-")
                put("vulkanApiVersion",
                    "${vulkan["apiVersionMajor"] ?: "-"}.${vulkan["apiVersionMinor"] ?: "-"}")
                put("vulkanLogicalDevice", vulkan["deviceCreated"] ?: "false")
                put("vulkanGraphicsQueue", vulkan["queueAcquired"] ?: "false")
                put("vulkanIsHardware", (type != null && type != "cpu" && type != "other").toString())
                vulkan["error"]?.let { put("vulkanError", it) }
                vulkan["dlerror"]?.let { put("vulkanDlError", it) }
            },
        )
    }

    private fun web() = Section(
        "WebView (host)",
        buildMap {
            val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            put("provider", provider?.packageName ?: "none")
            put("providerVersion", provider?.versionName ?: "-")
        },
    )

    private fun google(context: Context, virtualPackages: Set<String>) =
        Section("Google (host)", GoogleEnvironment.inspect(context, virtualPackages).toMap())

    /** True when the device declares Vulkan at all, which gates what a guest can be asked. */
    fun deviceDeclaresVulkan(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }.getOrDefault(false)
}
