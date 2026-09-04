package com.unique.core.vam

import android.content.ContentProvider
import android.content.pm.ProviderInfo
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect

/**
 * Publishes a guest's content providers inside the virtual process.
 *
 * `ActivityManagerService` resolves an authority against installed packages, so a query
 * for a guest's authority comes back null and the caller sees
 * `IllegalArgumentException: Unknown URL`. Since the provider's code lives in this
 * process anyway, UNIQUE instantiates it directly and answers the acquisition itself.
 *
 * Providers are created at bootstrap rather than lazily, matching the platform: it
 * instantiates a process's providers before any other component, and apps rely on that
 * ordering.
 *
 * ## What this cannot do
 *
 * Only callers inside the same virtual process are served. A provider is normally a
 * *cross-process* interface, and answering another process's acquisition requires
 * `:server` to hold the authority table and route the Binder. Until then an authority
 * queried from outside its own virtual process resolves to nothing, which is reported.
 */
object VirtualProviderRegistry {

    private data class Published(val authority: String, val provider: ContentProvider, val binder: Any)

    private val byAuthority = LinkedHashMap<String, Published>()

    val authorities: Set<String> get() = synchronized(this) { byAuthority.keys.toSet() }

    @Synchronized
    fun owns(authority: String?): Boolean = authority != null && authority in byAuthority

    /** The `ContentProviderHolder` for an authority, or null when it is not ours. */
    @Synchronized
    fun holderFor(authority: String, ready: AppBootstrap.Result.Ready): Any? {
        val published = byAuthority[authority] ?: return null
        return buildHolder(providerInfoFor(ready, published), published.binder)
    }

    @Synchronized
    fun install(ready: AppBootstrap.Result.Ready) {
        val providers = ready.manifest.components.filter {
            it.kind == ComponentKind.PROVIDER && it.enabled && it.authorities.isNotEmpty()
        }
        var published = 0
        for (entry in providers) {
            val result = runCatching {
                val clazz = Class.forName(entry.className, true, ready.application.classLoader)
                val provider = clazz.getDeclaredConstructor().newInstance() as ContentProvider
                val info = ProviderInfo().apply {
                    name = entry.className
                    packageName = ready.params.packageName
                    processName = ready.params.processName
                    applicationInfo = ready.applicationInfo
                    authority = entry.authorities.joinToString(";")
                    exported = entry.exported
                    enabled = entry.enabled
                    grantUriPermissions = true
                }
                // attachInfo runs the provider's own onCreate, exactly as the platform
                // does at process start.
                provider.attachInfo(ready.application, info)
                val binder = Reflect.findMethodByName(ContentProvider::class.java, "getIContentProvider")
                    ?.invoke(provider)
                    ?: error("ContentProvider.getIContentProvider() is unavailable")
                entry.authorities.forEach { auth ->
                    byAuthority[auth] = Published(auth, provider, binder)
                }
                entry.authorities.size
            }
            result.onSuccess { published += it }.onFailure {
                Diagnostics.error(
                    DiagChannel.PROCESS, "PROVIDER_PUBLISH_FAILED",
                    mapOf("provider" to entry.className, "error" to it.toString()),
                )
            }
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDERS_PUBLISHED",
            mapOf(
                "package" to ready.params.packageName,
                "declared" to providers.size.toString(),
                "authorities" to byAuthority.keys.joinToString(",").take(300),
            ),
        )
    }

    @Synchronized
    fun reset() = byAuthority.clear()

    private fun providerInfoFor(ready: AppBootstrap.Result.Ready, published: Published): ProviderInfo =
        ProviderInfo().apply {
            name = published.provider.javaClass.name
            packageName = ready.params.packageName
            processName = ready.params.processName
            applicationInfo = ready.applicationInfo
            authority = published.authority
            exported = false
            enabled = true
            grantUriPermissions = true
        }

    /**
     * Builds the `ContentProviderHolder` the framework expects back.
     *
     * `noReleaseNeeded` is set because UNIQUE owns the provider's lifetime, not
     * ActivityManagerService; without it the framework tries to release a connection it
     * never made.
     */
    private fun buildHolder(info: ProviderInfo, binder: Any): Any? = runCatching {
        val holderClass = Reflect.findClass("android.app.ContentProviderHolder")
            ?: return@runCatching null
        val holder = holderClass.getConstructor(ProviderInfo::class.java).newInstance(info)
        holderClass.getField("provider").set(holder, binder)
        runCatching { holderClass.getField("noReleaseNeeded").setBoolean(holder, true) }
        holder
    }.getOrElse {
        Diagnostics.error(
            DiagChannel.PROCESS, "PROVIDER_HOLDER_BUILD_FAILED",
            mapOf("authority" to info.authority.orEmpty(), "error" to it.toString()),
        )
        null
    }
}
