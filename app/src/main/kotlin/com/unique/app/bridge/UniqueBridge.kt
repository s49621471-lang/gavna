package com.unique.app.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagEvent
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.HiddenApi
import com.unique.core.nativebridge.UniqueNative
import com.unique.core.vam.ForegroundServiceTypes
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

/**
 * The Flutter/native bridge.
 *
 * A `MethodChannel` for request/response and an `EventChannel` for the diagnostics
 * stream. ARCHITECTURE.md section 15 reserves Pigeon and FFI for high-frequency traffic;
 * nothing on the UI path qualifies today - the busiest call is "list installed apps",
 * which happens once per screen - so introducing either now would be complexity without
 * a reason. The decision is recorded so it can be revisited when a real hot path appears.
 *
 * Every method returns *facts*, including facts about what is not implemented. The UI
 * renders engine status from these values rather than assuming anything works.
 */
object UniqueBridge {

    private const val METHOD_CHANNEL = "com.unique/bridge"
    private const val EVENT_CHANNEL = "com.unique/diagnostics"

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var diagListener: ((DiagEvent) -> Unit)? = null

    fun attach(context: Context, messenger: BinaryMessenger) {
        methodChannel = MethodChannel(messenger, METHOD_CHANNEL).apply {
            setMethodCallHandler { call, result ->
                runCatching { dispatch(context, call.method, call.arguments) }
                    .onSuccess { result.success(it) }
                    .onFailure { result.error("UNIQUE_ERROR", it.message, it.stackTraceToString()) }
            }
        }
        eventChannel = EventChannel(messenger, EVENT_CHANNEL).apply {
            setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    val listener: (DiagEvent) -> Unit = { events?.success(it.toMap()) }
                    diagListener = listener
                    Diagnostics.addListener(listener)
                }

                override fun onCancel(arguments: Any?) {
                    diagListener?.let(Diagnostics::removeListener)
                    diagListener = null
                }
            })
        }
    }

    fun detach() {
        diagListener?.let(Diagnostics::removeListener)
        diagListener = null
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(context: Context, method: String, args: Any?): Any? = when (method) {
        "engineStatus" -> engineStatus(context)
        "listInstalledApps" -> listInstalledApps(context, (args as? Map<String, Any?>))
        "appIcon" -> appIcon(context, (args as Map<String, Any?>)["package"] as String)
        "diagnosticsSnapshot" -> Diagnostics.snapshot().map { it.toMap() }
        else -> throw UnsupportedOperationException("Unknown bridge method: $method")
    }

    /**
     * What the engine can actually do on this device, right now.
     *
     * Reported honestly: `virtualLaunchImplemented` is false until phase 2 lands, and the
     * UI shows a banner saying so rather than offering a Launch button that does nothing.
     */
    private fun engineStatus(context: Context): Map<String, Any?> = mapOf(
        "versionName" to context.packageManager.getPackageInfo(context.packageName, 0).versionName,
        "sdkInt" to Build.VERSION.SDK_INT,
        "abis" to Build.SUPPORTED_ABIS.toList(),
        "is64BitOnly" to (Build.SUPPORTED_32_BIT_ABIS.isEmpty()),
        "nativeLoaded" to UniqueNative.isLoaded,
        "nativeLoadError" to UniqueNative.loadFailure,
        "pageSizeBytes" to UniqueNative.pageSize(),
        "hiddenApiGranted" to HiddenApi.isGranted,
        "hiddenApiDetail" to HiddenApi.failureDetail,
        "foregroundServiceTypes" to ForegroundServiceTypes.HOST_SUPPORTED,
        // Phase gates. The UI reads these; it does not guess.
        "virtualLaunchImplemented" to false,
        "ioRedirectImplemented" to false,
        "settingsInterceptionImplemented" to false,
    )

    private fun listInstalledApps(context: Context, args: Map<String, Any?>?): List<Map<String, Any?>> {
        val includeSystem = args?.get("includeSystem") as? Boolean ?: false
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA.toLong()
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags.toInt())
        }
        return packages.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { info ->
                mapOf(
                    "package" to info.packageName,
                    "label" to pm.getApplicationLabel(info).toString(),
                    "system" to ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                    "sourceDir" to info.sourceDir,
                    "splitCount" to (info.splitSourceDirs?.size ?: 0),
                    "minSdk" to info.minSdkVersion,
                    "targetSdk" to info.targetSdkVersion,
                    // Reported so Add App can grey out what UNIQUE cannot run, with a
                    // reason, instead of failing after the user picks it.
                    "hasArm64" to hasArm64(info),
                )
            }
            .sortedBy { (it["label"] as String).lowercase() }
            .toList()
    }

    /**
     * Whether the installed app has arm64 native code, or none at all.
     *
     * `nativeLibraryDir` ending in arm64 is the platform's own answer for an extracted
     * install; an app with no native code has nothing to check and is fine either way.
     */
    private fun hasArm64(info: ApplicationInfo): Boolean {
        val dir = info.nativeLibraryDir ?: return true
        return dir.endsWith("arm64") || dir.endsWith("arm64-v8a") ||
            java.io.File(dir).listFiles().isNullOrEmpty()
    }

    private fun appIcon(context: Context, packageName: String): ByteArray? = runCatching {
        val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
        val size = 144
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrElse {
        Diagnostics.warn(DiagChannel.LAUNCH, "ICON_RENDER_FAILED",
            mapOf("package" to packageName, "error" to it.toString()))
        null
    }

    private fun DiagEvent.toMap(): Map<String, Any?> = mapOf(
        "timestamp" to timestampMillis,
        "channel" to channel.name,
        "level" to level.name,
        "code" to code,
        "vuid" to vuid,
        "package" to packageName,
        "fields" to fields,
        "throwable" to throwable,
    )
}
