package com.unique.core.vam

import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo

/**
 * Builds the platform's component-info objects for the guest, by name and in bulk.
 *
 * It exists so that [VirtualPackageManagerHook] can answer `getPackageInfo` with the
 * arrays the flags asked for without reaching back into [AppBootstrap]: the hook is
 * installed *during* the graft, before `AppBootstrap.current` is set, so anything it
 * needs has to be handed to it. The implementation closes over the manifest, the
 * `ApplicationInfo` and the launch parameters, which is exactly what the graft has.
 *
 * `receivers` returns `ActivityInfo` because that is what `PackageInfo.receivers` is; the
 * platform models a receiver as an activity-shaped component and so does this.
 */
interface GuestComponents {
    fun activity(className: String): ActivityInfo?
    fun service(className: String): ServiceInfo?
    fun provider(className: String): ProviderInfo?
    fun receiver(className: String): ActivityInfo?

    fun activities(): Array<ActivityInfo>
    fun services(): Array<ServiceInfo>
    fun providers(): Array<ProviderInfo>
    fun receivers(): Array<ActivityInfo>
}
