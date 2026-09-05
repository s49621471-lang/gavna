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
    val targetActivity: String? = null,
    val intentFilters: List<IntentFilterEntry> = emptyList(),
    val metaData: Map<String, String?> = emptyMap(),
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
