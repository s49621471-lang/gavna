package com.unique.core.vam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.common.shim.ArgRule
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import com.unique.core.hook.SystemServiceHook
import java.lang.reflect.Method

/**
 * Posts a guest's notifications as UNIQUE's, without letting two instances collide.
 *
 * Four separate things have to be true for a virtual app's notification to appear and
 * behave, and each fails differently:
 *
 * 1. **Identity.** `INotificationManager.enqueueNotificationWithTag` takes the posting
 *    package and `system_server` checks it against the uid, so the virtual name is
 *    rejected outright — nothing appears at all.
 * 2. **Channel.** Two instances of one app declare the same channel id, so they would
 *    share the user's sound, importance and "do not disturb" settings. The user sees one
 *    app in Settings and cannot separate them, which is the opposite of what a second
 *    instance is for. Channel ids are namespaced per instance.
 * 3. **Notification id.** Apps pick small constants, and both instances pick the same
 *    one, so instance 2 posting silently replaces instance 1's notification. Ids are
 *    namespaced the same way job ids are.
 * 4. **Icon.** A guest's small icon is a resource in an APK the system has never
 *    installed. SystemUI resolves it against the *posting* package — UNIQUE — and finds
 *    either nothing or, worse, an unrelated drawable at the same numeric id. The virtual
 *    process *can* load it, so the icon is rendered here and travels as a bitmap.
 *
 * The content `PendingIntent` needs no work here: it was already routed onto a stub when
 * the guest built it (§6.4.1), including the identifier that keeps two instances' taps
 * apart.
 */
object VirtualNotificationHook {

    /** Bigger than any status-bar icon; small enough that the Binder transaction is safe. */
    private const val MAX_ICON_PX = 192

    private data class Binding(val vuid: Int, val packageName: String)

    @Volatile private var binding: Binding? = null
    @Volatile private var guestContext: Context? = null

    val boundPackage: String? get() = binding?.packageName

    /**
     * Methods that carry a notification id, matched so that the id can be told from the
     * user id.
     *
     * This is where an `Int` rewrite differs in kind from a `String` one. A package-name
     * rewrite is always guarded by value — `matching = { it == virtualPackage }` — so an
     * unrelated string is never touched. An `Int` carries no such evidence about itself,
     * and `rewriteAll<Int>` on `enqueueNotificationWithTag(pkg, opPkg, tag, id,
     * notification, userId)` namespaces the **user id** as well:
     *
     * ```
     * SecurityException: enqueueNotification from com.unique asks to run as user 1048576
     * ```
     *
     * 1048576 is `1 shl 20` — the namespacing applied to a `userId` of 0.
     *
     * These AIDL methods all end with `userId`, so a method carrying a *single* int has no
     * notification id at all: `cancelAllNotifications(pkg, userId)` is the trap. An id
     * exists only when there are at least two ints, and it is the first. The shim
     * therefore rewrites the *first* int and only on methods that have two.
     */
    internal fun carriesNotificationId(method: Method): Boolean {
        val name = method.name
        if (!name.contains("Notification")) return false
        if (!name.startsWith("enqueue") && !name.startsWith("cancel")) return false
        return method.parameterTypes.count { it == Int::class.javaPrimitiveType } >= 2
    }

    @Synchronized
    fun install(ready: AppBootstrap.Result.Ready, hostPackage: String): Boolean {
        val target = SystemServiceHook.TARGETS.firstOrNull { it.serviceName == "notification" }
            ?: return false
        binding = Binding(ready.params.vuid, ready.params.packageName)
        guestContext = ready.application

        val report = SystemServiceHook.install(
            target,
            shims(ready.params.packageName, hostPackage, ready.params.vuid),
        )
        if (!report.installed) {
            Diagnostics.warn(
                DiagChannel.HOOK, "NOTIFICATION_HOOK_FAILED",
                mapOf("package" to ready.params.packageName, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        Diagnostics.info(
            DiagChannel.HOOK, "NOTIFICATION_HOOKED",
            mapOf(
                "package" to ready.params.packageName,
                "vuid" to ready.params.vuid.toString(),
                "matched" to (report.bind?.describeMatches()?.take(400) ?: "-"),
            ),
        )
        return true
    }

    @Synchronized
    fun reset() {
        binding = null
        guestContext = null
    }

    private fun shims(
        virtualPackage: String,
        hostPackage: String,
        vuid: Int,
    ): List<MethodShim> = listOf(
        // Channels first, because a notify naming a channel that was never created is
        // dropped by the platform with nothing but a logcat line to show for it - which
        // is exactly how this failed while every UNIQUE diagnostic said the notification
        // had been adapted and posted.
        shim("channels") {
            matchMethods { it.name.contains("NotificationChannel") }
            // The package, then whatever String is left. On these methods the only
            // strings are package names and channel ids, so once the package has become
            // the host's, anything else is an id. Value-matched, like every other rewrite
            // here, rather than positional.
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteAll<String>(matching = { it != hostPackage && !isNamespaced(it) }) { id ->
                StubRouter.hostChannelId(vuid, virtualPackage, id)
            }
            rewriteAll<NotificationChannel> { channel -> namespaceChannel(channel, vuid) }
            rewriteAll<NotificationChannelGroup> { group -> namespaceGroup(group, vuid) }
            // NotificationManager.createNotificationChannel hands the list to
            // system_server inside a ParceledListSlice, so a rule on List<*> never fires:
            // the channel reached the platform with the guest's own id while the
            // notification pointed at the namespaced one, and the platform dropped it.
            parceledListSliceClass?.let { slice ->
                @Suppress("UNCHECKED_CAST")
                addRule(slice as Class<Any>, ArgRule.Scope.ALL, { true }) {
                    namespaceSlice(it, vuid)
                }
            }
        },

        shim("enqueue") {
            matchMethods { method -> carriesNotificationId(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            // First int only: the last one is userId. See carriesNotificationId.
            rewriteFirst<Int> { id -> StubRouter.hostNotificationId(vuid, id) }
            rewriteAll<Notification> { notification -> adapt(notification, vuid) }
        },

        // Everything else that merely names the caller.
        shim("notificationIdentity") {
            matchMethods { method ->
                method.parameterTypes.any { it == String::class.java }
            }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
        },
    )

    // ---------------------------------------------------------------------------------

    private fun namespaceChannel(channel: NotificationChannel, vuid: Int): NotificationChannel? {
        val b = binding ?: return null
        if (isNamespaced(channel.id)) return null
        val hostId = StubRouter.hostChannelId(vuid, b.packageName, channel.id)
        // NotificationChannel's id is final; the copy carries every user-visible setting
        // the guest asked for, which is what the user will then be able to change.
        return NotificationChannel(hostId, channel.name, channel.importance).apply {
            description = channel.description
            group = channel.group?.let { StubRouter.hostChannelId(vuid, b.packageName, it) }
            setShowBadge(channel.canShowBadge())
            setSound(channel.sound, channel.audioAttributes)
            enableLights(channel.shouldShowLights())
            lightColor = channel.lightColor
            enableVibration(channel.shouldVibrate())
            vibrationPattern = channel.vibrationPattern
            lockscreenVisibility = channel.lockscreenVisibility
            setBypassDnd(channel.canBypassDnd())
        }
    }

    /** Already ours; rewriting twice would produce `vu0:pkg:vu0:pkg:id`. */
    private fun isNamespaced(id: String): Boolean = StubRouter.parseChannelId(id) != null

    private val parceledListSliceClass: Class<*>? by lazy {
        Reflect.findClass("android.content.pm.ParceledListSlice")
    }

    /**
     * Namespaces the channels inside a `ParceledListSlice`.
     *
     * The slice is opaque to the shim's type-based rules — it is a hidden class and the
     * elements are only reachable through `getList()`. Rebuilt rather than mutated: the
     * slice's list is not guaranteed writable.
     */
    private fun namespaceSlice(slice: Any, vuid: Int): Any? = runCatching {
        val items = slice.javaClass.getMethod("getList").invoke(slice) as? List<*>
            ?: return@runCatching null
        var changed = false
        val mapped = items.map { item ->
            when (item) {
                is NotificationChannel -> namespaceChannel(item, vuid)?.also { changed = true } ?: item
                is NotificationChannelGroup -> namespaceGroup(item, vuid)?.also { changed = true } ?: item
                else -> item
            }
        }
        if (!changed) null
        else slice.javaClass.getConstructor(List::class.java).newInstance(mapped)
    }.getOrElse {
        Diagnostics.error(
            DiagChannel.PROCESS, "NOTIFICATION_CHANNEL_SLICE_FAILED",
            mapOf("error" to it.toString()),
        )
        null
    }

    private fun namespaceGroup(group: NotificationChannelGroup, vuid: Int): NotificationChannelGroup? {
        val b = binding ?: return null
        if (isNamespaced(group.id)) return null
        val hostId = StubRouter.hostChannelId(vuid, b.packageName, group.id)
        return NotificationChannelGroup(hostId, group.name).apply {
            description = group.description
        }
    }

    /**
     * Rewrites the parts of a `Notification` that name the guest.
     *
     * The object is copied rather than edited: the guest keeps a reference to what it
     * built and may post it again, and a guest whose notification silently mutated under
     * it would re-post something it never assembled.
     */
    /**
     * The same adaptation, for a notification that does not travel through
     * `enqueueNotificationWithTag`.
     *
     * A foreground service hands its notification to `setServiceForeground` on
     * `IActivityManager`, and the platform posts it on the app's behalf — so it needs the
     * namespaced channel and the flattened icon just as much, and gets neither if only
     * the notification service is hooked.
     */
    internal fun adaptForeground(notification: Notification): Notification? {
        val b = binding ?: return null
        return adapt(notification, b.vuid)
    }

    private fun adapt(notification: Notification, vuid: Int): Notification? {
        val b = binding ?: return null
        val copy = notification.clone()
        copy.channelId?.let { channelId ->
            val hostId =
                if (isNamespaced(channelId)) channelId
                else StubRouter.hostChannelId(vuid, b.packageName, channelId)
            if (hostId != channelId) {
                runCatching {
                    Notification::class.java.getDeclaredField("mChannelId")
                        .apply { isAccessible = true }
                        .set(copy, hostId)
                }.onFailure {
                    Diagnostics.warn(
                        DiagChannel.PROCESS, "NOTIFICATION_CHANNEL_REWRITE_FAILED",
                        mapOf("channel" to channelId, "error" to it.toString()),
                    )
                }
            }
        }
        // Both icons are set through their backing fields: `Notification` exposes getters
        // and keeps the setters hidden, and the large icon additionally travels in extras
        // where `Notification.Builder` put it.
        flatten(copy.smallIcon)?.let { setIconField(copy, "mSmallIcon", it) }
        flatten(copy.getLargeIcon())?.let { flat ->
            setIconField(copy, "mLargeIcon", flat)
            copy.extras?.putParcelable(Notification.EXTRA_LARGE_ICON, flat)
        }
        copy.extras?.putAll(StubRouter.notificationRouting(vuid, b.packageName))
        Diagnostics.event(
            DiagChannel.PROCESS, DiagLevel.DEBUG, "NOTIFICATION_ADAPTED",
            mapOf(
                "package" to b.packageName,
                "vuid" to vuid.toString(),
                "channel" to (copy.channelId ?: "-"),
                "icon" to (copy.smallIcon?.type?.toString() ?: "-"),
            ),
        )
        return copy
    }

    private fun setIconField(notification: Notification, field: String, icon: Icon) {
        runCatching {
            Notification::class.java.getDeclaredField(field)
                .apply { isAccessible = true }
                .set(notification, icon)
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "NOTIFICATION_ICON_SET_FAILED",
                mapOf("field" to field, "error" to it.toString()),
            )
        }
    }

    /**
     * Turns a resource icon into a bitmap icon, or returns null to leave it alone.
     *
     * SystemUI resolves a resource icon against the *posting* package, which is UNIQUE, so
     * a guest's resource id lands on nothing — or, worse, on an unrelated drawable that
     * happens to share the number. The virtual process has the guest's resources, so the
     * drawable is rendered here and travels as pixels.
     */
    private fun flatten(icon: Icon?): Icon? {
        if (icon == null || icon.type != Icon.TYPE_RESOURCE) return null
        val context = guestContext ?: return null
        return runCatching {
            val drawable = icon.loadDrawable(context) ?: return null
            val width = drawable.intrinsicWidth.coerceIn(1, MAX_ICON_PX)
            val height = drawable.intrinsicHeight.coerceIn(1, MAX_ICON_PX)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            Icon.createWithBitmap(bitmap)
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.PROCESS, "NOTIFICATION_ICON_FLATTEN_FAILED",
                mapOf("error" to it.toString()),
            )
            null
        }
    }
}
