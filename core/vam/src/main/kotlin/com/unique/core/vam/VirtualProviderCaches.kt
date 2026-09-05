package com.unique.core.vam

import android.content.AttributionSource
import android.content.Context
import android.provider.Settings
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

    fun evict(context: Context, hostSource: AttributionSource) {
        val providers = evictActivityThreadProviders(context)
        val refCounts = evictProviderRefCounts()
        val settings = evictSettingsCaches()
        val wrapped = installIntoSettingsCaches(context, hostSource)
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_CACHES_EVICTED",
            mapOf(
                "activityThreadProviders" to providers.toString(),
                "providerRefCounts" to refCounts.toString(),
                "settingsCaches" to settings.toString(),
                "settingsWrapped" to wrapped.toString(),
                // The one fact that decides whether any of this can work. If this is not
                // the host's package name, every substitution below puts the wrong name in
                // and the guest is refused exactly as before.
                "hostSource" to hostSource.packageName.orEmpty(),
                "hostSourceUid" to hostSource.uid.toString(),
            ),
        )
    }

    /**
     * Puts UNIQUE's wrapper *into* each `Settings` cache, rather than hoping for one.
     *
     * Emptying the caches was supposed to be enough: the next read would re-acquire through
     * `IActivityManager.getContentProvider`, which is hooked, and get a wrapped provider. On
     * a Xiaomi Android 15 device it was not enough — the guest still reached
     * `ContentProviderProxy.call` directly, with no wrapper in the stack, and was refused:
     *
     * ```
     * SecurityException: Package com.openai.chatgpt does not belong to 10300
     *     at android.content.ContentProviderProxy.call
     *     at android.provider.Settings$NameValueCache.getStringForUser
     * ```
     *
     * So the wrapper is installed by hand instead of arranged for. One read through the
     * *host's* own resolver — which is still UNIQUE's, because the service context this
     * runs on was never grafted — forces the holder to be populated, and whatever it holds
     * is then wrapped in place. Nothing is left depending on which path a later acquisition
     * happens to take.
     *
     * The value cache is cleared again afterwards. The priming read filled it under
     * UNIQUE's identity, and a guest asking for `ANDROID_ID` must reach the shim that
     * answers from its own device profile rather than a value already in a map.
     */
    private fun installIntoSettingsCaches(context: Context, hostSource: AttributionSource): Int {
        val resolver = context.contentResolver
        // A key that exists on every device and means nothing, read only to make the
        // platform acquire the provider.
        runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }
        runCatching { Settings.Secure.getString(resolver, "unique_priming_read") }
        runCatching { Settings.System.getString(resolver, "unique_priming_read") }

        var wrapped = 0
        var alreadyWrapped = 0
        var empty = 0
        for (className in SETTINGS_CLASSES) {
            val cls = Reflect.findClass(className) ?: continue
            val cache = runCatching {
                cls.getDeclaredField("sNameValueCache").apply { isAccessible = true }.get(null)
            }.getOrNull() ?: continue
            runCatching {
                val holderField = cache.javaClass.getDeclaredField("mProviderHolder")
                    .apply { isAccessible = true }
                val holder = holderField.get(cache) ?: return@runCatching
                val providerField = holder.javaClass.getDeclaredField("mContentProvider")
                    .apply { isAccessible = true }
                synchronized(holder) {
                    val provider = providerField.get(holder)
                    if (provider == null) {
                        // The priming read did not populate it — `Settings.Config` has no
                        // public accessor, so this is expected there and a finding anywhere
                        // else.
                        empty++
                        return@synchronized
                    }
                    if (VirtualProviderProxy.isWrapped(provider)) {
                        // The happy path: re-acquisition went through the hook and came
                        // back wrapped. Counted separately from `wrapped` because "zero
                        // wrapped" otherwise reads the same whether everything was already
                        // right or nothing was there at all.
                        alreadyWrapped++
                        return@synchronized
                    }
                    val proxy = VirtualProviderProxy.wrap(provider, hostSource)
                        ?: return@synchronized
                    providerField.set(holder, proxy)
                    wrapped++
                }
            }.onFailure {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "SETTINGS_CACHE_WRAP_FAILED",
                    mapOf("class" to className, "error" to it.toString()),
                )
            }
            clearValues(cache)
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "SETTINGS_CACHES_PRIMED",
            mapOf(
                "wrappedByHand" to wrapped.toString(),
                "alreadyWrapped" to alreadyWrapped.toString(),
                "empty" to empty.toString(),
            ),
        )
        return wrapped + alreadyWrapped
    }

    /** Drops whatever a `NameValueCache` has memorised, values and generation alike. */
    private fun clearValues(cache: Any) {
        runCatching {
            val values = cache.javaClass.getDeclaredField("mValues")
                .apply { isAccessible = true }.get(cache) as? MutableMap<*, *>
            values?.let { synchronized(it) { it.clear() } }
        }
        // Android 12 added a generation tracker that lets the cache skip a read entirely.
        // Left in place it can answer from what UNIQUE read, which is the same leak as the
        // value map and harder to see.
        runCatching {
            cache.javaClass.getDeclaredField("mGenerationTracker")
                .apply { isAccessible = true }.set(cache, null)
        }
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
    /**
     * The second map, and the one that actually decided the outcome.
     *
     * `ActivityThread.installProvider` does this with a provider handed back by
     * `getContentProvider`:
     *
     * ```java
     * IBinder jBinder = provider.asBinder();
     * ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
     * if (prc != null) {
     *     provider = prc.holder.provider;   // the wrapper is dropped here
     * }
     * ```
     *
     * UNIQUE's wrapper answers `asBinder()` with the *raw* binder — it has to, that is what
     * makes it a usable `IContentProvider` — so a provider this process had acquired before
     * the graft is found in this map by its own binder, and the wrapper is thrown away in
     * favour of the record already there. Clearing `mProviderMap` alone was therefore not
     * enough, and the guest kept reaching `ContentProviderProxy.call` directly:
     *
     * ```
     * SecurityException: Package com.openai.chatgpt does not belong to 10300
     *   at android.content.ContentProviderProxy.call
     *   at android.provider.Settings$NameValueCache.getStringForUser
     * ```
     *
     * The reference counts belong to acquisitions this process made as UNIQUE and will
     * never release as the guest; the process's death reclaims them, and for a `:vappN`
     * that is soon and certain.
     */
    private fun evictProviderRefCounts(): Int {
        val activityThreadClass = Reflect.findClass("android.app.ActivityThread") ?: return 0
        val activityThread = runCatching {
            Reflect.findMethod(activityThreadClass, "currentActivityThread")?.invoke(null)
        }.getOrNull() ?: return 0
        val map = runCatching {
            Reflect.get(activityThreadClass, "mProviderRefCountMap", activityThread)
        }.getOrNull() as? MutableMap<*, *> ?: return 0
        return runCatching {
            synchronized(map) {
                val size = map.size
                map.clear()
                size
            }
        }.getOrDefault(0)
    }

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
            clearValues(cache)
            if (holderCleared) cleared++
        }
        return cleared
    }
}
