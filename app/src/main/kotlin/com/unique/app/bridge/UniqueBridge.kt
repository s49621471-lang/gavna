package com.unique.app.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagEvent
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.HiddenApi
import com.unique.core.nativebridge.UniqueNative
import com.unique.core.compat.CompatDatabase
import com.unique.core.google.GoogleCompatRouter
import com.unique.core.google.GoogleEnvironment
import com.unique.core.vam.ForegroundServiceTypes
import com.unique.app.engine.DeviceReport
import com.unique.app.engine.DiagnosticsExport
import com.unique.app.engine.GuestAppInfo
import com.unique.app.engine.InstancePermissions
import com.unique.app.engine.TestChecklist
import com.unique.app.engine.UniqueEngine
import com.unique.core.vam.LaunchResult
import com.unique.core.vpermission.PermissionGroup
import com.unique.core.vpermission.PermissionState
import com.unique.core.vpm.CreateResult
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

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

    /**
     * Engine calls touch the database and the filesystem, so they run off the main
     * thread and reply through the channel afterwards. Doing them inline would block the
     * Flutter UI thread for the length of an APK import.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var diagListener: ((DiagEvent) -> Unit)? = null

    fun attach(context: Context, messenger: BinaryMessenger) {
        methodChannel = MethodChannel(messenger, METHOD_CHANNEL).apply {
            setMethodCallHandler { call, result ->
                scope.launch {
                    val outcome = runCatching { dispatch(context, call.method, call.arguments) }
                    withContext(Dispatchers.Main) {
                        outcome
                            .onSuccess { result.success(it) }
                            .onFailure {
                                Diagnostics.error(
                                    DiagChannel.LAUNCH, "BRIDGE_CALL_FAILED",
                                    mapOf("method" to call.method, "error" to it.toString()),
                                )
                                result.error("UNIQUE_ERROR", it.message, it.stackTraceToString())
                            }
                    }
                }
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

    /**
     * Bridge methods that change which packages exist, and so which broadcast actions
     * UNIQUE must hold registrations for.
     *
     * Re-registering here rather than inside each handler keeps the rule in one place:
     * whenever the instance set changes, the router is rebuilt from it. The alternative -
     * remembering to call it from every mutating path - is the kind of thing that stays
     * correct only until the next path is added.
     */
    private val instanceSetChanging = setOf(
        "importInstalled", "importApk", "importApkFromPicker", "cloneInstance",
        "removeInstance",
    )

    @Suppress("UNCHECKED_CAST")
    private suspend fun dispatch(context: Context, method: String, args: Any?): Any? {
        val a = args as? Map<String, Any?> ?: emptyMap()
        if (method in instanceSetChanging) {
            return dispatchInner(context, method, a).also {
                runCatching { UniqueEngine.registerBroadcastRoutes(context) }
            }
        }
        return dispatchInner(context, method, a)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun dispatchInner(
        context: Context,
        method: String,
        a: Map<String, Any?>,
    ): Any? {
        return when (method) {
            "engineStatus" -> engineStatus(context)
            "listInstalledApps" -> listInstalledApps(context, a)
            "appIcon" -> appIcon(context, a["package"] as String)
            "diagnosticsSnapshot" -> Diagnostics.snapshot().map { it.toMap() }
            "googleStatus" -> googleStatus(context)
            "googleRouting" -> googleRouting(context, (a["vuid"] as Number).toInt())
            "exportDiagnostics" -> exportDiagnostics(context)
            "uiSettings" -> uiSettings(context)
            "setUiSetting" -> setUiSetting(context, a["key"] as String, a["value"])
            "shareDiagnostics" -> shareDiagnostics(context)
            "deviceReport" -> deviceReport(context)
            "checklist" -> TestChecklist.steps(context).map { it.toMap() }
            "setChecklistStep" -> setChecklistStep(context, a)
            "resetChecklist" -> TestChecklist.reset(context).map { it.toMap() }

            "listInstances" -> listInstances(context)
            "importInstalled" -> importInstalled(context, a["package"] as String)
            "importApk" -> importApk((a["paths"] as List<*>).map { File(it as String) })
            "importApkFromPicker" -> importApkFromPicker(context)
            "cloneInstance" -> cloneInstance(a["package"] as String)
            "launchInstance" -> launchInstance(context, (a["vuid"] as Number).toInt())
            "removeInstance" -> removeInstance((a["vuid"] as Number).toInt())
            "clearCache" -> clearStorage((a["vuid"] as Number).toInt(), dataToo = false)
            "clearData" -> clearStorage((a["vuid"] as Number).toInt(), dataToo = true)
            "instanceStorage" -> instanceStorage((a["vuid"] as Number).toInt())
            "instancePermissions" ->
                InstancePermissions.rows(context, (a["vuid"] as Number).toInt())
                    .map { it.toMap() }
            "setInstancePermission" -> setInstancePermission(context, a)

            else -> throw UnsupportedOperationException("Unknown bridge method: $method")
        }
    }

    /**
     * What this device can offer a virtualized app's Google flows.
     *
     * Read from the device every time rather than cached: Play services can be disabled,
     * updated or side-loaded between two openings of this screen, and a stale "available"
     * is exactly the answer that sends a user chasing a failure that is not theirs.
     */
    private suspend fun googleStatus(context: Context): Map<String, Any?> {
        val imported = UniqueEngine.instances.instances().map { it.packageName }.toSet()
        val report = GoogleEnvironment.inspect(context, imported)
        return report.toMap() + mapOf(
            // The bridges have no bodies. Said here, once, rather than left for the UI to
            // infer from an empty list - which is how "not implemented" turns into
            // "nothing to report".
            "bridgesImplemented" to false,
            "note" to "Routing is implemented and the device is read here; no Google " +
                "flow has an implementation yet. See docs/GOOGLE_DEVICE_TEST.md.",
        )
    }

    /**
     * How each Google flow would be served for one instance, and why.
     *
     * The router has always decided this and recorded it on the GOOGLE channel; nothing
     * ever *showed* it. A mode is not a promise that the flow works — no bridge has a body
     * — but "what UNIQUE would do, and on what evidence" is the difference between a
     * layer you can reason about and a black box, and it is answerable today.
     */
    private suspend fun googleRouting(context: Context, vuid: Int): List<Map<String, Any?>> {
        val instance = UniqueEngine.instances.instance(vuid) ?: return emptyList()
        val manifest = runCatching {
            com.unique.core.common.apk.ManifestReader.fromApk(
                File(UniqueEngine.storage.model.baseApk(instance.packageName, instance.versionCode))
            )
        }.getOrNull() ?: return emptyList()

        val imported = UniqueEngine.instances.instances().map { it.packageName }.toSet()
        val capabilities = GoogleEnvironment.inspect(context, imported).capabilities
        val profile = CompatDatabase.resolve(instance.packageName, instance.versionCode)
        return GoogleCompatRouter(capabilities).routeAll(manifest, profile).map { decision ->
            mapOf(
                "flow" to decision.flow.name,
                "mode" to decision.mode.name,
                "why" to decision.rationale,
            )
        }
    }

    /**
     * Interface preferences, which live on the Kotlin side because they must survive.
     *
     * They were in-memory until the interface had a language switch, and a language that
     * resets to English every time the app is opened is not a language switch — it is a
     * setting that lies. `SharedPreferences` in UNIQUE's own app-private storage, so
     * nothing here is world-readable and none of it belongs to a guest.
     */
    private fun prefs(context: Context) =
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private const val UI_PREFS = "unique.ui"

    private fun uiSettings(context: Context): Map<String, Any?> = prefs(context).let { p ->
        mapOf(
            // "system" rather than a guess at the device's language: the resolution belongs
            // to the platform, which knows about regional variants and fallback chains that
            // this would only approximate.
            "language" to p.getString("language", "system"),
            "dynamicColor" to p.getBoolean("dynamicColor", true),
            "reducedMotion" to p.getBoolean("reducedMotion", false),
        )
    }

    private fun setUiSetting(context: Context, key: String, value: Any?): Map<String, Any?> {
        val editor = prefs(context).edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> return mapOf("ok" to false, "message" to "unsupported value for $key")
        }
        editor.apply()
        Diagnostics.info(
            DiagChannel.STORAGE, "UI_SETTING_CHANGED",
            mapOf("key" to key, "value" to value.toString()),
        )
        return mapOf("ok" to true)
    }

    /**
     * Everything UNIQUE can establish about this device, for a tester with no computer.
     *
     * Collected fresh each time. A Vulkan probe that creates a real device and queue costs
     * a few hundred milliseconds once; caching it would mean showing a stale answer after
     * the very thing a tester just changed.
     */
    private suspend fun deviceReport(context: Context): List<Map<String, Any?>> {
        val packages = UniqueEngine.instances.instances().map { it.packageName }.toSet()
        return DeviceReport.collect(context, packages).map { section ->
            mapOf("title" to section.title, "values" to section.values)
        }
    }

    private fun setChecklistStep(context: Context, a: Map<String, Any?>): List<Map<String, Any?>> {
        val id = a["id"] as? String ?: return TestChecklist.steps(context).map { it.toMap() }
        val verdict = runCatching {
            TestChecklist.Verdict.valueOf(a["verdict"] as String)
        }.getOrDefault(TestChecklist.Verdict.NOT_RUN)
        val note = a["note"] as? String ?: ""
        return TestChecklist.set(context, id, verdict, note).map { it.toMap() }
    }

    /**
     * Writes a diagnostics package and hands it to the share sheet.
     *
     * The whole point of this path is that a tester needs no `adb` and no computer: a file
     * whose location the UI merely *reports* is a file on a phone, and getting it off one
     * is exactly the step this is supposed to remove.
     */
    private suspend fun shareDiagnostics(context: Context): Map<String, Any?> {
        val written = exportDiagnostics(context)
        if (written["ok"] != true) return written
        val path = written["path"] as? String
            ?: return mapOf("ok" to false, "message" to "The package was written but has no path.")
        val sharer = sharer
            ?: return written + mapOf(
                "shared" to false,
                "message" to "No window is open to share from; the file is saved at $path.",
            )
        val started = sharer.shareFile(java.io.File(path), "application/zip")
        return written + mapOf("shared" to started)
    }

    /**
     * How the bridge puts a file into the share sheet, when it has no window of its own.
     *
     * Implemented by [com.unique.app.MainActivity], for the same reason [ApkPicker] is.
     */
    interface FileSharer {
        fun shareFile(file: java.io.File, mimeType: String): Boolean
    }

    @Volatile
    var sharer: FileSharer? = null

    /**
     * Writes a diagnostics package and reports where it is.
     *
     * The live slots are read from the launcher rather than guessed: an export that asks a
     * slot with no process wastes a Binder round trip and, worse, would make an empty
     * `vappN.log` look like a process that recorded nothing.
     */
    private suspend fun exportDiagnostics(context: Context): Map<String, Any?> {
        val liveSlots = UniqueEngine.launcher.snapshot()
            .filter { it.occupant != null }
            .map { it.index }
        val instances = UniqueEngine.instances.instances().map { instance ->
            mapOf(
                "vuid" to instance.vuid.toString(),
                "package" to instance.packageName,
                "versionCode" to instance.versionCode.toString(),
                "profile" to instance.displayName,
                "androidId" to instance.profile.androidId,
            )
        }
        return runCatching { DiagnosticsExport.write(context, liveSlots, instances) }.fold(
            onSuccess = { result ->
                mapOf(
                    "ok" to true,
                    "path" to result.file.absolutePath,
                    "name" to result.file.name,
                    "bytes" to result.bytes,
                    "processes" to result.processes,
                    "lines" to result.lines,
                )
            },
            onFailure = { mapOf("ok" to false, "message" to it.toString()) },
        )
    }

    // ---------------------------------------------------------------------------------
    // Instances
    // ---------------------------------------------------------------------------------

    private suspend fun listInstances(context: Context): List<Map<String, Any?>> =
        UniqueEngine.instances.instances().map { instance ->
            val usage = UniqueEngine.storage.usage(instance.vuid, instance.packageName)
            mapOf(
                "vuid" to instance.vuid,
                "package" to instance.packageName,
                "versionCode" to instance.versionCode,
                "label" to labelOf(context, instance.packageName, instance.versionCode),
                "profileName" to instance.displayName,
                "androidId" to instance.profile.androidId,
                "instanceId" to instance.profile.instanceId.toString(),
                "generation" to instance.profile.generation,
                "dataBytes" to usage.dataBytes,
                "cacheBytes" to usage.cacheBytes,
                "externalBytes" to usage.externalBytes,
            )
        }

    /**
     * What to call an imported app.
     *
     * The APK's own resources first, because `android:label` is a *reference* into them and
     * UNIQUE's binary-XML reader has no resource table — it hands back `@7f130001`, which
     * is what every app in the list was called on a real phone. The manifest's own value is
     * the fallback for the apps that do spell their label out, and the package name is the
     * last resort. A reference is never shown: it is not a name, it is a number.
     */
    private fun labelOf(context: Context, packageName: String, versionCode: Long): String {
        GuestAppInfo.of(context, packageName, versionCode).label?.let { return it }
        val fromManifest = runCatching {
            com.unique.core.common.apk.ManifestReader
                .fromApk(File(UniqueEngine.storage.model.baseApk(packageName, versionCode)))
                .label
        }.getOrNull()
        return fromManifest?.takeIf { it.isNotBlank() && !it.startsWith("@") } ?: packageName
    }

    private suspend fun importInstalled(context: Context, packageName: String): Map<String, Any?> =
        UniqueEngine.importInstalled(context, packageName).toMap()

    private suspend fun importApk(files: List<File>): Map<String, Any?> =
        UniqueEngine.importFiles(files).toMap()

    /**
     * Puts the system file picker on screen, then imports whatever came back.
     *
     * The chosen files are copied into UNIQUE's own cache before the importer sees them.
     * A `content://` URI is a grant, not a path: it is scoped to this task, can be
     * revoked the moment the picker's host process is killed, and the importer needs to
     * read each file more than once — the manifest first, then the bytes. Copying makes
     * the import independent of how long the grant lasts.
     */
    private suspend fun importApkFromPicker(context: Context): Map<String, Any?> {
        val picker = picker
            ?: return mapOf("ok" to false, "message" to "No window is open to pick a file in.")
        val uris = picker.pickApks()
        if (uris.isEmpty()) return mapOf("ok" to true, "cancelled" to true)

        val staged = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "import").apply {
                deleteRecursively()
                mkdirs()
            }
            uris.mapIndexedNotNull { index, uri ->
                runCatching {
                    val name = displayNameOf(context, uri) ?: "picked-$index.apk"
                    val dest = File(dir, name.substringAfterLast('/'))
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("could not open $uri")
                    dest
                }.getOrElse {
                    Diagnostics.warn(
                        DiagChannel.STORAGE, "IMPORT_STAGE_FAILED",
                        mapOf("uri" to uri.toString(), "error" to it.toString()),
                    )
                    null
                }
            }
        }
        if (staged.isEmpty()) {
            return mapOf("ok" to false, "message" to "None of the selected files could be read.")
        }
        Diagnostics.info(
            DiagChannel.STORAGE, "IMPORT_PICKED",
            mapOf("files" to staged.size.toString(), "selected" to uris.size.toString()),
        )
        return UniqueEngine.importFiles(staged).toMap()
    }

    /** The name the picker's document provider gives a file, if it gives one. */
    private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * How the bridge asks for a file, when it has no window of its own.
     *
     * Implemented by [com.unique.app.MainActivity], which is the only thing that can put
     * a picker on screen. Null whenever no UI is attached, and the bridge says so rather
     * than pretending the user cancelled.
     */
    interface ApkPicker {
        suspend fun pickApks(): List<Uri>
    }

    @Volatile
    var picker: ApkPicker? = null

    private suspend fun cloneInstance(packageName: String): Map<String, Any?> =
        UniqueEngine.instances.createInstance(packageName).toMap()

    private suspend fun launchInstance(context: Context, vuid: Int): Map<String, Any?> =
        when (val r = UniqueEngine.launch(context, vuid)) {
            is LaunchResult.Started -> mapOf("ok" to true, "activity" to r.activity)
            is LaunchResult.Failed -> mapOf("ok" to false, "code" to r.code, "message" to r.message)
        }

    private suspend fun removeInstance(vuid: Int): Map<String, Any?> {
        UniqueEngine.launcher.release(vuid, "removed")
        UniqueEngine.instances.removeInstance(vuid)
        return mapOf("ok" to true)
    }

    private suspend fun clearStorage(vuid: Int, dataToo: Boolean): Map<String, Any?> {
        val instance = UniqueEngine.instances.instance(vuid)
            ?: return mapOf("ok" to false, "message" to "Instance $vuid does not exist.")
        if (dataToo) UniqueEngine.storage.clearData(vuid, instance.packageName)
        else UniqueEngine.storage.clearCache(vuid, instance.packageName)
        return mapOf("ok" to true)
    }

    private suspend fun instanceStorage(vuid: Int): Map<String, Any?> {
        val instance = UniqueEngine.instances.instance(vuid)
            ?: return mapOf("ok" to false)
        val usage = UniqueEngine.storage.usage(vuid, instance.packageName)
        return mapOf(
            "ok" to true,
            "dataBytes" to usage.dataBytes,
            "cacheBytes" to usage.cacheBytes,
            "externalBytes" to usage.externalBytes,
            "dataDir" to UniqueEngine.storage.model.dataDir(vuid, instance.packageName),
        )
    }

    private suspend fun setInstancePermission(
        context: Context,
        a: Map<String, Any?>,
    ): Map<String, Any?> {
        val vuid = (a["vuid"] as Number).toInt()
        val group = runCatching { PermissionGroup.valueOf(a["group"] as String) }.getOrNull()
            ?: return mapOf("ok" to false, "message" to "Unknown permission group.")
        val granted = a["granted"] as? Boolean ?: false
        val state = if (granted) PermissionState.GRANTED else PermissionState.DENIED
        val ok = InstancePermissions.set(context, vuid, group, state)
        return if (ok) mapOf("ok" to true)
        else mapOf("ok" to false, "message" to "This app does not ask for ${group.label}.")
    }

    private fun CreateResult.toMap(): Map<String, Any?> = when (this) {
        is CreateResult.Created -> mapOf(
            "ok" to true,
            "vuid" to instance.vuid,
            "package" to instance.packageName,
            "profileName" to instance.displayName,
        )
        is CreateResult.Rejected -> mapOf("ok" to false, "message" to reason)
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
        //
        // These are capability flags, not live state: "the mechanism exists in this
        // build". Each is true because there is a device acceptance test that fails if
        // it stops being - t02 for the launch, t17 for the redirect, t22 for settings -
        // so a flag can only go stale in the direction of the suite going red first.
        // They were all false while nothing was implemented, which was right then and
        // would understate the build now.
        "virtualLaunchImplemented" to true,
        "ioRedirectImplemented" to true,
        "settingsInterceptionImplemented" to true,
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

    /**
     * An app's icon as PNG bytes, from the imported APK before the host's package manager.
     *
     * The order matters and used to be the other way round, which meant no imported app
     * ever had an icon: `getApplicationIcon(packageName)` answers only for packages the
     * *device* has installed, and the whole point of UNIQUE is that these are not. It stays
     * as the fallback for an app that is also installed here, where it is the cheaper call.
     */
    private suspend fun appIcon(context: Context, packageName: String): ByteArray? = runCatching {
        val drawable: Drawable = fromImportedApk(context, packageName)
            ?: context.packageManager.getApplicationIcon(packageName)
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

    private suspend fun fromImportedApk(context: Context, packageName: String): Drawable? {
        val version = UniqueEngine.instances.instances()
            .firstOrNull { it.packageName == packageName }?.versionCode ?: return null
        return GuestAppInfo.of(context, packageName, version).icon
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
