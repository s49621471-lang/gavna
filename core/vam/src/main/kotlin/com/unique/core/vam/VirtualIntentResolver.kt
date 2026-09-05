package com.unique.core.vam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.apk.IntentFilterEntry

/**
 * Resolves an implicit intent against a guest's own intent filters.
 *
 * `PackageManager` resolves against *installed* packages, so an implicit start from a
 * guest — `startActivity(Intent("com.example.OPEN_SETTINGS"))`, an in-app deep link, a
 * `VIEW` on the app's own scheme — matched nothing at all and did nothing visible. Which
 * is also why Mode C OAuth (§9.5) could not work: it ends with a browser handing a deep
 * link back, and a deep link is an implicit intent.
 *
 * Matching is done by [IntentFilter], built from the guest's manifest. Not by
 * reimplementing the rules: action, category, scheme, host, port, path and MIME
 * interact in ways that are easy to get *nearly* right, and nearly right here means
 * starting the wrong screen.
 *
 * ## Which side wins
 *
 * A guest may legitimately mean the host — a share sheet, a browser, the dialer — and it
 * may equally mean itself. The rule, in order:
 *
 *  1. **Scoped to itself** (`setPackage(ownName)`): the guest said so. Resolve against the
 *     guest only.
 *  2. **Unscoped, and the host can serve it**: the host wins. An `https` `VIEW` belongs in
 *     a browser, and `SEND` belongs in the chooser; sending either into the guest would
 *     break behaviour that works today.
 *  3. **Unscoped, and nothing on the device can serve it**: the guest wins, if it matches.
 *     A custom action only this app declares is certainly this app's.
 *
 * The one case this does not reproduce is the platform's own: an installed app's matching
 * activity is simply one candidate among all, and the user picks. UNIQUE cannot put a
 * virtual activity into the system chooser, so it decides — and records which rule
 * decided, because "it opened the wrong thing" needs an answer.
 */
object VirtualIntentResolver {

    /** One guest activity that matches, and how strongly. */
    data class Match(val entry: ComponentEntry, val priority: Int, val filter: IntentFilterEntry)

    /**
     * The guest's own activities that match [intent], best first.
     *
     * Empty for an intent that names a component: that is not an implicit start and the
     * caller has already handled it.
     */
    fun matchingActivities(manifest: ApkManifest, intent: Intent): List<Match> {
        if (intent.component != null) return emptyList()
        val action = intent.action
        val categories = intent.categories.orEmpty()
        val data = intent.data
        // The intent's declared type only. Resolving a `content://` URI's type would
        // mean a ContentResolver round trip on the activity-start path, and a miss here
        // sends the intent to the platform untouched — the safe direction.
        val type = intent.type

        val matches = ArrayList<Match>()
        for (entry in manifest.components) {
            if (entry.kind != ComponentKind.ACTIVITY && entry.kind != ComponentKind.ACTIVITY_ALIAS) {
                continue
            }
            if (!entry.enabled) continue
            for (filterEntry in entry.intentFilters) {
                val filter = build(filterEntry) ?: continue
                val result = filter.match(action, type, data?.scheme, data, categories, TAG)
                if (result >= 0) {
                    matches += Match(entry, filterEntry.priority, filterEntry)
                    break
                }
            }
        }
        // Priority first, as the platform orders them; declaration order breaks ties, so
        // the answer is stable across runs rather than dependent on a hash.
        return matches.sortedByDescending { it.priority }
    }

    /**
     * Whether anything installed on this device can serve [intent].
     *
     * UNIQUE's own package is excluded: UNIQUE declares a launcher activity and a pile of
     * stubs, and counting itself as "the host can handle it" would send every guest
     * implicit start nowhere. So is the resolver activity, which is not a handler but the
     * chooser standing in for the absence of one.
     */
    fun hostCanHandle(context: Context, intent: Intent, hostPackage: String): Boolean =
        hostHandlersFor(context, intent, hostPackage).isNotEmpty()

    /**
     * The installed packages, other than UNIQUE itself, that would take [intent].
     *
     * Named rather than merely counted because "the guest opened something and it was not
     * the guest" is a question somebody always ends up asking. A physical run had Gemini's
     * shell activity fire an implicit `ACTION_VIEW` within fifty milliseconds of starting,
     * which the host's own Google app answered — so the user saw their real account inside
     * what they had launched as a fresh instance, and the only trace was a debug line that
     * did not say where the intent went.
     */
    fun hostHandlersFor(context: Context, intent: Intent, hostPackage: String): List<String> {
        val probe = Intent(intent).apply { setPackage(null) }
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    probe, PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(probe, 0)
            }
        }.getOrElse { return emptyList() }
        return resolved.mapNotNull { it.activityInfo?.packageName }
            .filter { it != hostPackage && it != "android" }
            .distinct()
    }

    /**
     * Builds the platform's own matcher from a manifest filter.
     *
     * A filter with no actions can never match an implicit intent, so it is skipped rather
     * than turned into one that matches everything.
     */
    private fun build(entry: IntentFilterEntry): IntentFilter? {
        if (entry.actions.isEmpty()) return null
        val filter = IntentFilter()
        entry.actions.forEach(filter::addAction)
        entry.categories.forEach(filter::addCategory)
        entry.schemes.forEach(filter::addDataScheme)
        entry.hosts.forEachIndexed { index, host ->
            filter.addDataAuthority(host, entry.ports.getOrNull(index))
        }
        entry.paths.forEach { filter.addDataPath(it, PatternLiteral) }
        entry.pathPrefixes.forEach { filter.addDataPath(it, PatternPrefix) }
        entry.pathPatterns.forEach { filter.addDataPath(it, PatternSimpleGlob) }
        // addDataType throws on a malformed type rather than returning; one bad entry must
        // not lose the whole filter.
        entry.mimeTypes.forEach { runCatching { filter.addDataType(it) } }
        filter.priority = entry.priority
        return filter
    }

    // android.os.PatternMatcher constants, named rather than passed as 0/1/2.
    private const val PatternLiteral = 0
    private const val PatternPrefix = 1
    private const val PatternSimpleGlob = 2

    private const val TAG = "UniqueIntentResolver"

}
