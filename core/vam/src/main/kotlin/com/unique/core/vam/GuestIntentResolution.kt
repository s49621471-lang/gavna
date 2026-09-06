package com.unique.core.vam

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ComponentInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.hook.Reflect

/**
 * Answers `PackageManager`'s resolution questions about the guest's *own* components.
 *
 * The guest's package is not installed, so `PackageManagerService` knows nothing about it
 * and every one of these comes back empty:
 *
 * ```
 * pm.getLaunchIntentForPackage(getPackageName())        -> null
 * pm.resolveActivity(new Intent(this, Foo.class), 0)    -> null
 * pm.queryIntentServices(new Intent(ACTION), 0)         -> []
 * ```
 *
 * Each of those is ordinary application code. The first is how an app restarts itself
 * after a crash or a language change; the second is the guard nearly every library puts
 * in front of `startActivity`; the third is how an SDK discovers its own worker service.
 * A null there is not a missing feature — it is an app concluding it is misconfigured,
 * and deciding to stop.
 *
 * ## The scope rule, and why it is narrow
 *
 * Only intents **scoped to the guest** are answered here: an explicit `ComponentName` in
 * the guest's package, or `setPackage(guestPackage)`. Anything wider is handed to the
 * platform untouched.
 *
 * That is deliberate. A guest's `ACTION_VIEW` for an `https` URL must reach the device's
 * browser, and a `ACTION_SEND` must reach the real chooser; answering those from the
 * guest's manifest would make a virtual app the only handler of everything it happens to
 * declare a filter for. Where an implicit start really should go is decided at the
 * *start*, by [VirtualActivityTaskManagerHook], which weighs the guest's filters against
 * the device's and records which rule won. This class only answers questions the caller
 * has already scoped to the guest, where there is exactly one right answer.
 */
internal object GuestIntentResolution {

    /** True when [intent] is asking about the guest and nobody else. */
    fun isScopedToGuest(intent: Intent?, guestPackage: String): Boolean {
        val i = intent ?: return false
        i.component?.let { return it.packageName == guestPackage }
        if (i.`package` == guestPackage) return true
        return i.selector?.`package` == guestPackage
    }

    /**
     * The guest's activities that would serve [intent], best first.
     *
     * An explicit component resolves to that component and nothing else, which is the
     * platform's rule; a package-scoped implicit intent goes through the filters.
     */
    fun activities(
        manifest: ApkManifest,
        components: GuestComponents,
        intent: Intent,
        guestPackage: String,
    ): List<ResolveInfo> {
        val explicit = intent.component?.takeIf { it.packageName == guestPackage }
        if (explicit != null) {
            val info = components.activity(explicit.className) ?: return emptyList()
            return listOf(resolveInfo(info, priority = 0))
        }
        return VirtualIntentResolver.matchingActivities(manifest, withoutPackage(intent))
            .mapNotNull { match ->
                components.activity(match.entry.className)?.let { resolveInfo(it, match.priority) }
            }
    }

    fun services(
        manifest: ApkManifest,
        components: GuestComponents,
        intent: Intent,
        guestPackage: String,
    ): List<ResolveInfo> = componentsFor(
        manifest, intent, guestPackage, ComponentKind.SERVICE,
    ).mapNotNull { entry ->
        components.service(entry.className)?.let { resolveInfo(it, 0) }
    }

    fun receivers(
        manifest: ApkManifest,
        components: GuestComponents,
        intent: Intent,
        guestPackage: String,
    ): List<ResolveInfo> = componentsFor(
        manifest, intent, guestPackage, ComponentKind.RECEIVER,
    ).mapNotNull { entry ->
        components.receiver(entry.className)?.let { resolveInfo(it, 0) }
    }

    /**
     * The guest's own service declarations that would serve [intent], **best first**.
     *
     * Exposed because a *start* has the same question as a query: an SDK that binds its
     * own worker by action rather than by class — `new Intent(ACTION_MY_SERVICE)` — is
     * asking the platform to resolve against a package the platform has never installed.
     * See `VirtualActivityManagerHook.routeService`.
     *
     * Ordered rather than returned as a set, because more than one match is the normal
     * case and not an ambiguity. Firebase Cloud Messaging is the example that matters: a
     * guest declares `com.google.firebase.MESSAGING_EVENT` on the SDK's own
     * `FirebaseMessagingService` *and* on its subclass, so two services match and
     * `PackageManagerService` picks the better one exactly as it would for any installed
     * app. Highest filter priority first, then the manifest's own order — which is the
     * platform's rule and, for equal priorities, its outcome.
     */
    fun serviceEntries(
        manifest: ApkManifest,
        intent: Intent,
        guestPackage: String,
    ): List<ComponentEntry> = componentsFor(manifest, intent, guestPackage, ComponentKind.SERVICE)

    /**
     * Filter matching for a service or a receiver.
     *
     * `VirtualIntentResolver` covers activities only, because that is the kind whose
     * *placement* is a decision. For these two the question is simply "does the filter
     * match", so the same `IntentFilter` machinery is reused directly.
     */
    private fun componentsFor(
        manifest: ApkManifest,
        intent: Intent,
        guestPackage: String,
        kind: ComponentKind,
    ): List<ComponentEntry> {
        val explicit = intent.component?.takeIf { it.packageName == guestPackage }
        if (explicit != null) {
            return manifest.components.filter { it.kind == kind && it.className == explicit.className }
        }
        val scoped = withoutPackage(intent)
        // Sorted by the highest priority among the filters that actually matched, and
        // stably — `sortedByDescending` keeps equal elements in manifest order, which is
        // what "first declared wins" means. Taking the entry's maximum priority across
        // *all* its filters would rank a component by a filter this intent never matched.
        return manifest.components.mapNotNull { entry ->
            if (entry.kind != kind || !GuestComponentState.isEnabled(entry)) return@mapNotNull null
            val priority = entry.intentFilters
                .filter { VirtualIntentResolver.matches(it, scoped) }
                .maxOfOrNull { it.priority } ?: return@mapNotNull null
            entry to priority
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * The same intent with the package scope removed.
     *
     * `IntentFilter.match` knows nothing about `setPackage`; leaving it on changes
     * nothing, but stripping it keeps the match a pure filter question and matches what
     * `PackageManagerService` does once it has narrowed to one package.
     */
    private fun withoutPackage(intent: Intent): Intent =
        if (intent.`package` == null) intent else Intent(intent).apply { setPackage(null) }

    private fun resolveInfo(info: ComponentInfo, priority: Int): ResolveInfo = ResolveInfo().apply {
        when (info) {
            is ActivityInfo -> activityInfo = info
            is ServiceInfo -> serviceInfo = info
            else -> Reflect.set(ResolveInfo::class.java, "providerInfo", this, info)
        }
        this.priority = priority
        resolvePackageName = info.packageName
        labelRes = info.labelRes
        nonLocalizedLabel = info.nonLocalizedLabel
        icon = info.icon
        // `match` is what the platform sorts a chooser by, and zero means "no match" to
        // some callers. A non-zero value that reflects the filter's own priority is the
        // honest answer for a component that did match.
        match = android.content.IntentFilter.MATCH_CATEGORY_EMPTY + priority
    }

    /**
     * Wraps a list the way the AIDL expects it, when the method returns a slice.
     *
     * `IPackageManager.queryIntent*` has returned `ParceledListSlice` since API 21 and
     * `resolveIntent` returns a bare `ResolveInfo`, so the return type is read from the
     * method rather than assumed — the same rule the shims bind by.
     */
    fun asReturnValue(returnType: Class<*>, list: List<ResolveInfo>): Any? =
        ParceledLists.wrap(returnType, list)
}
