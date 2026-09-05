package com.unique.core.vam

import android.content.Context
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import java.lang.reflect.Proxy

/**
 * Throws away every content provider this process acquired before it became the guest.
 *
 * A `:vappN` is UNIQUE's own process for its first second of life. It loads UNIQUE's code,
 * and the framework runs its ordinary startup — and both read settings. `GraphicsEnvironment`
 * does it during `handleBindApplication`, before a single line of guest code exists:
 *
 * ```
 * V GraphicsEnvironment: Global.Settings values are invalid: number of packages: 0
 * ```
 *
 * That acquisition caches a raw `IContentProvider` in two places that outlive the graft:
 * `ActivityThread.mProviderMap`, and the *static* `NameValueCache` inside each of
 * `Settings.Secure`, `Settings.Global` and `Settings.System`. The wrapper UNIQUE installs
 * in the `getContentProvider` shim — the thing that substitutes the host's
 * `AttributionSource` on every call — never gets a chance to sit in front of them, because
 * nothing acquires them again.
 *
 * What that costs is every settings read the guest makes, and on a real device that is the
 * app's whole life:
 *
 * ```
 * SecurityException: Package com.example.app does not belong to 10300
 *     at android.provider.Settings$NameValueCache.getStringForUser
 *     at android.database.sqlite.SQLiteCompatibilityWalFlags.initIfNeeded
 *     at android.database.sqlite.SQLiteDatabase.<init>
 * ```
 *
 * — a guest that cannot open a database, cannot attach an Activity, and never starts. It
 * did not show up on the verification emulator: `aosp_atd` is a `userdebug` build whose
 * settings provider is laxer about the caller, and the graft there happened to precede the
 * first settings read.
 *
 * So the caches are emptied at the end of the graft. The next read re-acquires through
 * `IActivityManager.getContentProvider`, which is hooked, and gets the wrapper. Emptying is
 * safe in a way that rewriting is not: nothing is fabricated, the platform simply fetches
 * again.
 */
internal object VirtualProviderCaches {

    /**
     * The three `Settings` inner classes that hold a process-wide provider.
     *
     * `Config` exists from Android 10 and is included when present. Each keeps a static
     * `NameValueCache sNameValueCache` whose `mProviderHolder.mContentProvider` is the
     * cached binder; both the provider and the value cache are dropped, because a value
     * read under UNIQUE's identity is not a value the guest should see either.
     */
    private val SETTINGS_CLASSES = listOf(
        "android.provider.Settings\$Secure",
        "android.provider.Settings\$Global",
        "android.provider.Settings\$System",
        "android.provider.Settings\$Config",
    )

    fun evict(context: Context) {
        val providers = evictActivityThreadProviders(context)
        val settings = evictSettingsCaches()
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_CACHES_EVICTED",
            mapOf(
                "activityThreadProviders" to providers.toString(),
                "settingsCaches" to settings.toString(),
            ),
        )
    }

    /**
     * Empties `ActivityThread.mProviderMap`.
     *
     * A record already wrapped by UNIQUE is left alone — that is the normal case for a
     * second graft in a slot, and dropping it would cost a re-acquisition for nothing.
     *
     * Releasing rather than clearing was considered and rejected: `releaseProvider` needs
     * the very `IContentProvider` it is being asked to release, and the point here is that
     * this process is no longer the one that acquired them. Dropping the map leaves
     * ActivityManager's own reference counts as they are; they are reclaimed when the
     * process dies, which for a `:vappN` is soon and certain.
     */
    private fun evictActivityThreadProviders(context: Context): Int {
        val activityThreadClass = Reflect.findClass("android.app.ActivityThread") ?: return 0
        val activityThread = runCatching {
            Reflect.findMethod(activityThreadClass, "currentActivityThread")?.invoke(null)
        }.getOrNull() ?: return 0
        val map = runCatching {
            Reflect.get(activityThreadClass, "mProviderMap", activityThread)
        }.getOrNull() as? MutableMap<*, *> ?: return 0

        return runCatching {
            synchronized(map) {
                val doomed = map.keys.filter { key ->
                    val record = map[key] ?: return@filter false
                    val provider = runCatching {
                        record.javaClass.getDeclaredField("mProvider")
                            .apply { isAccessible = true }.get(record)
                    }.getOrNull()
                    provider == null || !Proxy.isProxyClass(provider.javaClass)
                }
                doomed.forEach { map.remove(it) }
                doomed.size
            }
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_MAP_EVICT_FAILED",
                mapOf("error" to it.toString(), "context" to context.javaClass.simpleName),
            )
            0
        }
    }

    /** Drops each `Settings` class's cached provider and its cached values. */
    private fun evictSettingsCaches(): Int {
        var cleared = 0
        for (className in SETTINGS_CLASSES) {
            val cls = Reflect.findClass(className) ?: continue
            val cache = runCatching {
                cls.getDeclaredField("sNameValueCache").apply { isAccessible = true }.get(null)
            }.getOrNull() ?: continue

            val holderCleared = runCatching {
                val holderField = cache.javaClass.getDeclaredField("mProviderHolder")
                    .apply { isAccessible = true }
                val holder = holderField.get(cache) ?: return@runCatching false
                val providerField = holder.javaClass.getDeclaredField("mContentProvider")
                    .apply { isAccessible = true }
                synchronized(holder) { providerField.set(holder, null) }
                true
            }.getOrElse {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "SETTINGS_CACHE_EVICT_FAILED",
                    mapOf("class" to className, "error" to it.toString()),
                )
                false
            }

            // The value cache too. It was filled with what UNIQUE's own identity could
            // read, and a guest asking for `android_id` must reach the shim that answers
            // from its own device profile rather than a value already in a map.
            runCatching {
                val values = cache.javaClass.getDeclaredField("mValues")
                    .apply { isAccessible = true }.get(cache) as? MutableMap<*, *>
                values?.let { synchronized(it) { it.clear() } }
            }
            if (holderCleared) cleared++
        }
        return cleared
    }
}
