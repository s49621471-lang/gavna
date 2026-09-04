package com.unique.core.vam

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/** Which kind of component a launch targets. */
enum class VirtualComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

/**
 * Everything a `:vappN` process needs in order to bootstrap, carried in the launch intent.
 *
 * Deliberately self-contained: the virtual process reads this and the package's own
 * manifest, and needs no round trip to `:server` before it can create the application.
 * That removes an IPC dependency from the single most timing-sensitive path in the
 * product, and it removes an ordering hazard - there is no window in which a process is
 * alive but does not yet know what it is supposed to be.
 */
data class VirtualLaunchParams(
    val vuid: Int,
    val packageName: String,
    val versionCode: Long,
    /**
     * Fully-qualified component to start; null means the package's launcher activity.
     *
     * Named for the component rather than the activity because services, receivers and
     * providers travel through the same contract. Two contracts for the same job is what
     * produced the earlier mismatch between what the launcher wrote and what the stub read.
     */
    val targetComponent: String?,
    val kind: VirtualComponentKind = VirtualComponentKind.ACTIVITY,
    /** Manifest process this slot serves, e.g. `com.example.app` or `com.example.app:push`. */
    val processName: String,
    /** Index of the `:vappN` slot serving this launch. */
    val slot: Int,
) {
    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(KEY_VUID, vuid)
        putExtra(KEY_PACKAGE, packageName)
        putExtra(KEY_VERSION_CODE, versionCode)
        putExtra(KEY_COMPONENT, targetComponent)
        putExtra(KEY_KIND, kind.name)
        putExtra(KEY_PROCESS, processName)
        putExtra(KEY_SLOT, slot)
    }

    fun toBundle(): Bundle = Bundle().also { b ->
        b.putInt(KEY_VUID, vuid)
        b.putString(KEY_PACKAGE, packageName)
        b.putLong(KEY_VERSION_CODE, versionCode)
        b.putString(KEY_COMPONENT, targetComponent)
        b.putString(KEY_KIND, kind.name)
        b.putString(KEY_PROCESS, processName)
        b.putInt(KEY_SLOT, slot)
    }

    companion object {
        const val KEY_VUID = "unique.vuid"
        const val KEY_PACKAGE = "unique.package"
        const val KEY_VERSION_CODE = "unique.versionCode"
        const val KEY_COMPONENT = "unique.component"
        const val KEY_KIND = "unique.kind"
        const val KEY_PROCESS = "unique.process"
        const val KEY_SLOT = "unique.slot"

        /** Reads the parameters back, or null when this intent is not a UNIQUE launch. */
        fun from(intent: Intent?): VirtualLaunchParams? {
            val i = intent ?: return null
            val pkg = i.getStringExtra(KEY_PACKAGE) ?: return null
            val vuid = i.getIntExtra(KEY_VUID, -1)
            if (vuid < 0) return null
            return VirtualLaunchParams(
                vuid = vuid,
                packageName = pkg,
                versionCode = i.getLongExtra(KEY_VERSION_CODE, 0L),
                targetComponent = i.getStringExtra(KEY_COMPONENT),
                kind = i.getStringExtra(KEY_KIND)
                    ?.let { runCatching { VirtualComponentKind.valueOf(it) }.getOrNull() }
                    ?: VirtualComponentKind.ACTIVITY,
                processName = i.getStringExtra(KEY_PROCESS) ?: pkg,
                slot = i.getIntExtra(KEY_SLOT, 0),
            )
        }
    }
}

/** Builds the intent that starts a virtual app on a given stub slot. */
object VirtualLaunchIntent {

    /** Where a guest intent's own identifier is parked while the stub carries UNIQUE's. */
    const val KEY_GUEST_IDENTIFIER = "unique.identifier"

    /**
     * The stub the system will actually launch.
     *
     * Launch mode and task affinity come from the *stub's* manifest entry, because the
     * system has already used them by the time any UNIQUE code runs in the target
     * process. Picking the stub that matches the virtual activity's declared launch mode
     * is therefore how launch-mode fidelity is achieved at all.
     */
    fun build(
        hostPackage: String,
        params: VirtualLaunchParams,
        launchMode: Int,
        affinityIndex: Int = 0,
    ): Intent {
        val stub = StubRouter.stubActivity(params.slot, launchMode.coerceIn(0, 3), affinityIndex)
        return Intent().apply {
            component = ComponentName(hostPackage, stub)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            params.writeTo(this)
            stampIdentity(this, guest = null, params = params)
        }
    }

    /**
     * Makes a stub intent distinguishable from every other stub intent in its slot.
     *
     * Every activity of a virtual process shares a pool of eight stub classes, so to the
     * task system two different guest activities look like the *same component*. With
     * `FLAG_ACTIVITY_NEW_TASK` that is not a cosmetic difference: `ActivityStarter`
     * compares the incoming intent against the tasks it already has with
     * `Intent.filterEquals`, finds a match, and delivers `onNewIntent` to the activity
     * already on top instead of starting the one that was asked for —
     *
     * ```
     * START u0 {flg=0x10000000 cmp=com.unique/.stub.ActivityStub_p0_m0_a0} … result code=3
     * ```
     *
     * `START_DELIVERED_TO_TOP`. The same collision makes two `PendingIntent`s for two
     * different guest screens compare equal, so `FLAG_UPDATE_CURRENT` silently overwrites
     * one with the other — which is exactly how a notification opens the wrong screen.
     *
     * `Intent.setIdentifier` exists for this and is part of `filterEquals` since API 29:
     * it gives the intent an identity without touching action, data or categories, none of
     * which are UNIQUE's to invent. Two stub intents for the same guest component still
     * compare equal, so genuine `singleTop` and "deliver to top" behaviour is preserved.
     *
     * The guest's own identifier is parked in an extra and restored by
     * [restoreGuestIdentity], because it is the guest's, not UNIQUE's.
     */
    fun stampIdentity(stub: Intent, guest: Intent?, params: VirtualLaunchParams) {
        stub.putExtra(KEY_GUEST_IDENTIFIER, guest?.identifier)
        stub.identifier = "u${params.vuid}/${params.targetComponent ?: "<launcher>"}"
    }

    /** Gives [real] back the identifier the guest set, if any, and removes UNIQUE's key. */
    fun restoreGuestIdentity(real: Intent, stub: Intent) {
        real.identifier = stub.getStringExtra(KEY_GUEST_IDENTIFIER)
        real.removeExtra(KEY_GUEST_IDENTIFIER)
    }
}
