package com.unique.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.unique.app.bridge.UniqueBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CompletableDeferred

/**
 * The UNIQUE UI.
 *
 * Flutter hosts the entire interface and nothing else: no virtualization code runs in
 * this process. See [UniqueApplication] for why.
 *
 * It also owns the one thing the bridge cannot do from an application context: putting a
 * system file picker on screen and waiting for what comes back.
 */
class MainActivity : FlutterActivity(), UniqueBridge.ApkPicker, UniqueBridge.HostPermissions {

    /**
     * The pick currently on screen, if any.
     *
     * `FlutterActivity` extends the platform `Activity` rather than an AndroidX one, so
     * `registerForActivityResult` is not available here and the classic request-code path
     * is what there is. A deferred rather than a callback because the bridge's side of
     * this is a suspending function and everything else it does is too.
     */
    private var pending: CompletableDeferred<List<Uri>>? = null

    /**
     * The runtime-permission request currently on screen, if any.
     *
     * Same shape and the same reason as [pending]: `FlutterActivity` extends the platform
     * `Activity`, so `registerForActivityResult` is unavailable and the request-code path
     * is what there is.
     */
    private var pendingPermissions: CompletableDeferred<Map<String, Boolean>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash theme covers only the window before Flutter's first frame; swapping
        // it here stops the mark showing through the UI afterwards.
        setTheme(R.style.Theme_Unique)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        UniqueBridge.attach(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
        UniqueBridge.picker = this
        UniqueBridge.hostPermissions = this
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        if (UniqueBridge.picker === this) UniqueBridge.picker = null
        if (UniqueBridge.hostPermissions === this) UniqueBridge.hostPermissions = null
        UniqueBridge.detach()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onDestroy() {
        // A pick left waiting would keep the bridge coroutine parked forever, and with it
        // whatever the UI is showing a spinner for.
        pending?.complete(emptyList())
        pending = null
        pendingPermissions?.complete(emptyMap())
        pendingPermissions = null
        if (UniqueBridge.picker === this) UniqueBridge.picker = null
        if (UniqueBridge.hostPermissions === this) UniqueBridge.hostPermissions = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------
    // Host runtime permissions
    // ---------------------------------------------------------------------------------

    /**
     * Asks Android for permissions on UNIQUE's own behalf and reports what it granted.
     *
     * This is what makes the per-app permission switches usable. UNIQUE can only ever
     * narrow what it holds, so a group UNIQUE does not hold is a group no instance can be
     * given — and until now the switch for it was simply disabled, which left the user
     * looking at "needs UNIQUE permission" with nothing to press. Now pressing it asks.
     *
     * The answer is read back from the platform rather than from the callback's result
     * array alone, because the two disagree in one case that matters: a permission already
     * held is not re-reported, and a partial grant (coarse location without fine) comes
     * back as one denial and one grant for what the user experiences as one decision.
     */
    override suspend fun request(permissions: List<String>): Map<String, Boolean> {
        val wanted = permissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isEmpty()) return permissions.associateWith { true }

        pendingPermissions?.complete(emptyMap())
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pendingPermissions = deferred
        return try {
            requestPermissions(wanted.toTypedArray(), REQUEST_HOST_PERMISSIONS)
            deferred.await()
            // Whatever the callback said, the platform is the record.
            permissions.associateWith {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } catch (t: Throwable) {
            pendingPermissions = null
            permissions.associateWith { false }
        }
    }

    /**
     * Whether Android has stopped offering a dialog for this permission.
     *
     * "Not granted and no rationale" is the platform's own definition of *don't ask
     * again* — but only after the first request, because it is also true before one. This
     * is called after a refused request, which is exactly when it means what it says.
     */
    override fun permanentlyDenied(permission: String): Boolean =
        checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(permission)

    override fun openAppSettings(): Boolean = runCatching {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrElse { false }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQUEST_HOST_PERMISSIONS) {
            val deferred = pendingPermissions
            pendingPermissions = null
            deferred?.complete(
                permissions.mapIndexed { i, name ->
                    name to (grantResults.getOrNull(i) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
                }.toMap()
            )
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * Opens the system document picker and returns what the user chose.
     *
     * Multiple selection, because an app split across a base APK and its splits is one
     * import and must be picked in one go: importing a base without its `config.arm64_v8a`
     * split produces an app with no native code, which fails much later and much less
     * clearly.
     *
     * The MIME filter is `*&#47;*` on purpose. Split APKs arrive from real devices as
     * `application/octet-stream` at least as often as with the package MIME type, and a
     * filter that hides the file the user is looking at is worse than one that shows too
     * much - the importer reads each file's manifest and says exactly what it refuses.
     */
    override suspend fun pickApks(): List<Uri> {
        pending?.complete(emptyList())
        val deferred = CompletableDeferred<List<Uri>>()
        pending = deferred
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            startActivityForResult(intent, REQUEST_PICK_APKS)
            deferred.await()
        } catch (t: Throwable) {
            pending = null
            emptyList()
        }
    }

    @Deprecated("The platform Activity has no other result path; see pickApks.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_APKS) {
            val deferred = pending
            pending = null
            deferred?.complete(if (resultCode == RESULT_OK) urisOf(data) else emptyList())
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** A multi-select comes back in `clipData`; a single pick comes back in `data`. */
    private fun urisOf(data: Intent?): List<Uri> {
        val intent = data ?: return emptyList()
        intent.clipData?.let { clip ->
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }

    private companion object {
        const val REQUEST_PICK_APKS = 0x5041
        const val REQUEST_HOST_PERMISSIONS = 0x5042
    }
}
