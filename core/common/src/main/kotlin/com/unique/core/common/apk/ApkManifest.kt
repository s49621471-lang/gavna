package com.unique.core.common.apk

/** Component kinds that carry an intent-filter and can be launched. */
enum class ComponentKind { ACTIVITY, ACTIVITY_ALIAS, SERVICE, RECEIVER, PROVIDER }

data class IntentFilterEntry(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val ports: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val pathPrefixes: List<String> = emptyList(),
    val pathPatterns: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val priority: Int = 0,
) {
    val isLauncher: Boolean
        get() = actions.contains("android.intent.action.MAIN") &&
            categories.contains("android.intent.category.LAUNCHER")

    /**
     * True when this filter can receive a browser/deep-link style callback.
     * The Google layer uses this to detect apps that can use the passthrough OAuth
     * path (Mode C), which is the most reliable Google flow inside virtualization.
     */
    val isDeepLink: Boolean
        get() = actions.contains("android.intent.action.VIEW") &&
            categories.contains("android.intent.category.BROWSABLE") &&
            schemes.isNotEmpty()
}

/**
 * One `<meta-data>` entry, kept in the form it was compiled in.
 *
 * A textual value is not enough. `android:value="@integer/google_play_services_version"`
 * is a *reference*, and the platform resolves it against the app's own resources before
 * putting an `int` in `ApplicationInfo.metaData`. Google Play services reads exactly that
 * int, and a missing or stringified one is a hard stop:
 *
 * ```
 * IllegalStateException: A required meta-data tag in your app's AndroidManifest.xml does
 *     not exist. You must have the following declaration within the <application>
 *     element: <meta-data android:name="com.google.android.gms.version" .../>
 * ```
 *
 * So the type and the raw datum travel together, and whoever has the guest's `Resources`
 * - which only a virtual process does - resolves them.
 */
data class MetaDataEntry(
    val name: String,
    /** `android:resource`, or 0 when the entry carries a value instead. */
    val resourceId: Int = 0,
    /** `android:value`'s compiled type, one of the `BinaryXml.TYPE_*` constants. */
    val valueType: Int = BinaryXml.TYPE_NULL,
    /** `android:value`'s compiled datum: an int, a boolean, a float's bits, or a res id. */
    val valueData: Int = 0,
    /** `android:value` when it compiled to a literal string. */
    val valueString: String? = null,
)

/**
 * The `<activity>` attributes that decide how the platform builds the window.
 *
 * Held as parsed booleans rather than as an `ActivityInfo.flags` bit set because
 * `core/common` never depends on `android.*`; the bits are composed where the real
 * constants are in scope. The default of [hardwareAccelerated] is the *application's*
 * value, which the reader has already resolved - the platform's own two-level default
 * (application, then activity, then `targetSdk >= 14`) collapsed into one answer.
 */
data class WindowAttributes(
    val hardwareAccelerated: Boolean = true,
    val softInputMode: Int = 0,
    val uiOptions: Int = 0,
    val documentLaunchMode: Int = 0,
    val maxRecents: Int = -1,
    val colorMode: Int = 0,
    val rotationAnimation: Int = -1,
    val lockTaskMode: Int = 0,
    val persistableMode: Int = -1,
    /** Null means "unstated", which the platform resolves from the target SDK. */
    val resizeable: Boolean? = null,
    val supportsPictureInPicture: Boolean = false,
    val maxAspectRatio: Float = 0f,
    val minAspectRatio: Float = 0f,
    val excludeFromRecents: Boolean = false,
    val allowTaskReparenting: Boolean = false,
    val finishOnTaskLaunch: Boolean = false,
    val clearTaskOnLaunch: Boolean = false,
    val alwaysRetainTaskState: Boolean = false,
    val stateNotNeeded: Boolean = false,
    val noHistory: Boolean = false,
    val multiprocess: Boolean = false,
    val immersive: Boolean = false,
    val showForAllUsers: Boolean = false,
    val autoRemoveFromRecents: Boolean = false,
    val relinquishTaskIdentity: Boolean = false,
    val resumeWhilePausing: Boolean = false,
    val showWhenLocked: Boolean = false,
    val turnScreenOn: Boolean = false,
    val directBootAware: Boolean = false,
)

data class ComponentEntry(
    val kind: ComponentKind,
    /** Always fully qualified, `.Foo` and `Foo` already resolved against the package. */
    val className: String,
    /** Fully-qualified process name; equals the package name when unspecified. */
    val processName: String,
    val exported: Boolean,
    val enabled: Boolean = true,
    val permission: String? = null,
    val taskAffinity: String? = null,
    val launchMode: Int = 0,
    val theme: Int = 0,
    val screenOrientation: Int = -1,
    val configChanges: Int = 0,
    /**
     * `android:foregroundServiceType`, a bit mask. Zero when unset.
     *
     * Needed at launch, not just for reporting: from Android 14 the type has to be
     * declared on the manifest entry of the service that *calls* `startForeground`, and
     * inside UNIQUE that is a stub. The guest's declaration is what the stub's superset is
     * intersected with (§6.2).
     */
    val foregroundServiceType: Int = 0,
    val authorities: List<String> = emptyList(),
    /** `<provider>` only. Null means the provider named no permission for that direction. */
    val readPermission: String? = null,
    val writePermission: String? = null,
    /**
     * `android:grantUriPermissions`.
     *
     * Was hard-coded to true wherever a `ProviderInfo` was built, which quietly widened
     * every guest provider: a provider that never opted in could have a URI grant handed
     * out for it. The manifest is the only place that decision belongs.
     */
    val grantUriPermissions: Boolean = false,
    val targetActivity: String? = null,
    val intentFilters: List<IntentFilterEntry> = emptyList(),
    val metaData: Map<String, String?> = emptyMap(),
    /** The same entries, still typed, for building the platform's `Bundle`. */
    val metaDataEntries: List<MetaDataEntry> = emptyList(),
    /** Meaningful for activities and aliases only; the default for everything else. */
    val window: WindowAttributes = WindowAttributes(),
    /** `android:label`/`android:icon` on the component itself. Zero when unset. */
    val labelResId: Int = 0,
    val iconResId: Int = 0,
    val labelText: String? = null,
) {
    val hasDeepLink: Boolean get() = intentFilters.any { it.isDeepLink }
}

data class DeclaredPermission(val name: String, val protectionLevel: Int)

/**
 * Everything UNIQUE needs to know about a package, decoded from its manifest alone.
 *
 * Deliberately a plain data class with no platform types: the virtual PackageManager
 * builds `PackageInfo`/`ApplicationInfo` from this, and the *same* instance is what the
 * importer, the compatibility resolver and the diagnostics reporter read. One decode,
 * one source of truth.
 */
data class ApkManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val sharedUserId: String?,
    val splitName: String?,
    val isFeatureSplit: Boolean,
    val applicationClassName: String?,
    val applicationProcess: String,
    val appComponentFactory: String?,
    val hasCode: Boolean,
    val extractNativeLibs: Boolean?,
    val label: String?,
    /**
     * `android:label` when it is a *reference*, which is almost always.
     *
     * Kept as the resource id alongside the textual [label] because they answer different
     * questions. [label] is what the manifest literally says — `@7f010000` for any app
     * that names itself properly — and this is what the platform needs to resolve it:
     * `ApplicationInfo.labelRes`, without which a guest asking its own PackageManager what
     * it is called gets its package name back.
     */
    val labelResId: Int,
    val iconResId: Int,
    /**
     * `android:networkSecurityConfig`, the resource that decides what the guest may
     * connect to and how.
     *
     * A virtual process inherits UNIQUE's policy, installed by `handleBindApplication`
     * before the graft, and that is both wrong and — on Android 15 — fatal: Conscrypt asks
     * the policy about a domain and, when the guest's own config was never installed,
     * dereferences null on the way out of a TLS socket. Cleartext rules and certificate
     * pinning are the guest's decisions, not the host's.
     */
    val networkSecurityConfigResId: Int,
    /** `android:usesCleartextTraffic`, when the app states it. Null means unstated. */
    val usesCleartextTraffic: Boolean?,
    val themeResId: Int,
    val usesPermissions: List<String>,
    val declaredPermissions: List<DeclaredPermission>,
    val components: List<ComponentEntry>,
    val applicationMetaData: Map<String, String?>,
    val usesNativeLibraries: List<String>,
    /** The `<application>` meta-data, still typed. See [MetaDataEntry]. */
    val applicationMetaDataEntries: List<MetaDataEntry> = emptyList(),
    /**
     * `android:hardwareAccelerated` on `<application>`, already defaulted.
     *
     * The platform's default is `targetSdk >= 14`, and every activity inherits it unless
     * it says otherwise. Resolved here so no consumer has to re-derive it.
     */
    val hardwareAccelerated: Boolean = true,
    val largeHeap: Boolean = false,
    val supportsRtl: Boolean = false,
    val requestLegacyExternalStorage: Boolean = false,
    val directBootAware: Boolean = false,
    val roundIconResId: Int = 0,
    val bannerResId: Int = 0,
    val logoResId: Int = 0,
) {
    val activities: List<ComponentEntry> get() = components.filter { it.kind == ComponentKind.ACTIVITY }
    val services: List<ComponentEntry> get() = components.filter { it.kind == ComponentKind.SERVICE }
    val receivers: List<ComponentEntry> get() = components.filter { it.kind == ComponentKind.RECEIVER }
    val providers: List<ComponentEntry> get() = components.filter { it.kind == ComponentKind.PROVIDER }

    /** Distinct manifest process names, which is what sizes the virtual process pool. */
    val processNames: Set<String>
        get() = (components.map { it.processName } + applicationProcess).toSortedSet()

    val launcherActivity: ComponentEntry?
        get() = components.firstOrNull { c ->
            (c.kind == ComponentKind.ACTIVITY || c.kind == ComponentKind.ACTIVITY_ALIAS) &&
                c.intentFilters.any { it.isLauncher }
        }

    /** Custom URI schemes the app can be returned to — the OAuth passthrough surface. */
    val deepLinkSchemes: List<String>
        get() = components.asSequence()
            .flatMap { it.intentFilters.asSequence() }
            .filter { it.isDeepLink }
            .flatMap { it.schemes.asSequence() }
            .distinct()
            .toList()

    val usesGoogleSignIn: Boolean
        get() = usesPermissions.any { it.startsWith("com.google.android.gms") } ||
            applicationMetaData.keys.any { it.startsWith("com.google.android.gms") } ||
            components.any { it.className.startsWith("com.google.android.gms") }
}
