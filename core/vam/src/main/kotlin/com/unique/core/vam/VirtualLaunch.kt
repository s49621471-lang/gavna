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
        }
    }
}
