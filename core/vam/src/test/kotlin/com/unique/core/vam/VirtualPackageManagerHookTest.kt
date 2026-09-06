package com.unique.core.vam

import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import com.unique.core.common.google.GoogleStackVisibility
import org.junit.Test

/**
 * Which packages a guest is allowed to see.
 *
 * The device this matters on is a phone with Play services, and the verification emulator
 * has none — `getPackageInfo("com.google.android.gms")` throws `NameNotFoundException`
 * there whether UNIQUE hides it or not, so the acceptance suite cannot tell the two apart.
 * The decision is therefore pinned here instead, where it is a pure function over a name
 * and a `ResolveInfo`.
 *
 * What hiding is protecting against: `GmsClient.getRemoteService` sends
 * `context.getPackageName()` to `com.google.android.gms`, which resolves the *calling*
 * uid to UNIQUE's own packages and answers `SecurityException: Unknown calling package
 * name`. On an old client library it arrives on the app's own looper and is fatal.
 *
 * Which is why it is no longer done to everyone. Hiding it unconditionally told every
 * app on a fully Googled phone to install Google Play services; the state is per
 * instance now, and these tests fix both ends of it — what happens when the decision
 * says hide, and that nothing is hidden until it does.
 */
class VirtualPackageManagerHookTest {

    private fun hideTheGoogleStack() = VirtualPackageManagerHook.bindGoogleVisibility(
        GoogleStackVisibility.decide(GoogleStackVisibility.Override.HIDE),
    )

    private fun showTheGoogleStack() = VirtualPackageManagerHook.bindGoogleVisibility(
        GoogleStackVisibility.decide(GoogleStackVisibility.Override.SHOW),
    )

    @Test fun `nothing is hidden until an instance has shown it must be`() {
        // The default, and the whole reason the unconditional hiding went: a phone that
        // has Play services should not have every app told it does not.
        showTheGoogleStack()
        for (name in listOf("com.google.android.gms", "com.google.android.gsf")) {
            assertThat(VirtualPackageManagerHook.isHiddenFromGuest(name)).isFalse()
        }
    }

    @Test fun `the two packages that kill guests are hidden and nothing else is`() {
        hideTheGoogleStack()
        assertThat(VirtualPackageManagerHook.isHiddenFromGuest("com.google.android.gms")).isTrue()
        assertThat(VirtualPackageManagerHook.isHiddenFromGuest("com.google.android.gsf")).isTrue()

        // Play's licence check is an ordinary bind that works, and a PAIRIP-protected app
        // calls System.exit(0) when it cannot reach it. Hiding this would break apps that
        // work today, to fix nothing.
        assertThat(VirtualPackageManagerHook.isHiddenFromGuest("com.android.vending")).isFalse()

        for (name in listOf(
            "com.google.android.youtube",      // Google's, and harmless
            "com.google.android.gms.location", // a prefix match must not be enough
            "com.unique.probe",
            null,
        )) {
            assertThat(VirtualPackageManagerHook.isHiddenFromGuest(name)).isFalse()
        }
    }

    @Test fun `a resolution naming a hidden package is dropped, whichever half names it`() {
        hideTheGoogleStack()
        // Intent resolution answers with whichever of these three is set, and an SDK
        // reaches GMS through all three: an activity for sign-in, a service for the API
        // client, a provider for DynamiteModule.
        for (info in listOf(
            resolveInfo(activity = "com.google.android.gms"),
            resolveInfo(service = "com.google.android.gms"),
            resolveInfo(provider = "com.google.android.gms"),
            ResolveInfo().apply { resolvePackageName = "com.google.android.gsf" },
        )) {
            assertThat(VirtualPackageManagerHook.withoutHiddenPackages(info)).isNull()
        }

        val mine = resolveInfo(activity = "com.unique.probe")
        assertThat(VirtualPackageManagerHook.withoutHiddenPackages(mine)).isSameInstanceAs(mine)
    }

    @Test fun `a list keeps everything that is not hidden, in order`() {
        hideTheGoogleStack()
        val mine = resolveInfo(activity = "com.unique.probe")
        val store = resolveInfo(activity = "com.android.vending")
        val answer = listOf(
            resolveInfo(activity = "com.google.android.gms"),
            mine,
            resolveInfo(service = "com.google.android.gsf"),
            store,
        )

        val kept = VirtualPackageManagerHook.withoutHiddenPackages(answer) as List<*>
        assertThat(kept).containsExactly(mine, store).inOrder()
    }

    @Test fun `a list with nothing hidden is handed back untouched`() {
        // Not merely equal: an untouched answer must be the platform's own object, so a
        // caller that identity-compares or mutates it behaves exactly as it would without
        // UNIQUE in the way.
        val answer = listOf(resolveInfo(activity = "com.unique.probe"))
        assertThat(VirtualPackageManagerHook.withoutHiddenPackages(answer)).isSameInstanceAs(answer)
    }

    @Test fun `a provider is recognised by either half of its own info`() {
        val byField = ProviderInfo().apply { packageName = "com.google.android.gms" }
        assertThat(VirtualPackageManagerHook.providerPackageOf(byField))
            .isEqualTo("com.google.android.gms")
        assertThat(VirtualPackageManagerHook.providerPackageOf(null)).isNull()
        assertThat(VirtualPackageManagerHook.providerPackageOf("not a ProviderInfo")).isNull()
    }

    @Test fun `resolvePackageOf prefers whichever component the answer actually carries`() {
        assertThat(VirtualPackageManagerHook.resolvePackageOf(resolveInfo(activity = "a")))
            .isEqualTo("a")
        assertThat(VirtualPackageManagerHook.resolvePackageOf(resolveInfo(service = "s")))
            .isEqualTo("s")
        assertThat(VirtualPackageManagerHook.resolvePackageOf(resolveInfo(provider = "p")))
            .isEqualTo("p")
        assertThat(VirtualPackageManagerHook.resolvePackageOf(null)).isNull()
    }

    private fun resolveInfo(
        activity: String? = null,
        service: String? = null,
        provider: String? = null,
    ) = ResolveInfo().apply {
        activity?.let { activityInfo = ActivityInfo().apply { packageName = it } }
        service?.let { serviceInfo = ServiceInfo().apply { packageName = it } }
        provider?.let { providerInfo = ProviderInfo().apply { packageName = it } }
    }
}
