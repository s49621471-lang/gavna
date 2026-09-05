package com.unique.core.google

import com.google.common.truth.Truth.assertThat
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.apk.IntentFilterEntry
import com.unique.core.common.compat.CompatFlag
import com.unique.core.common.compat.CompatibilityProfile
import com.unique.core.common.compat.GoogleFlow
import com.unique.core.common.compat.GoogleMode
import org.junit.Test

/**
 * The routing table is the part of the Google layer most likely to be wrong in a way that
 * produces an unusable token rather than an error, so the decisions are pinned here.
 */
class GoogleCompatRouterTest {

    private fun manifest(deepLink: Boolean): ApkManifest {
        val filters = if (deepLink) listOf(
            IntentFilterEntry(
                actions = listOf("android.intent.action.VIEW"),
                categories = listOf("android.intent.category.BROWSABLE"),
                schemes = listOf("com.example.app"),
            )
        ) else emptyList()
        return ApkManifest(
            packageName = "com.example.app", versionCode = 1, versionName = "1",
            minSdk = 26, targetSdk = 35, sharedUserId = null, splitName = null,
            isFeatureSplit = false, applicationClassName = null,
            applicationProcess = "com.example.app", appComponentFactory = null,
            hasCode = true, extractNativeLibs = null, label = null, labelResId = 0, iconResId = 0,
            networkSecurityConfigResId = 0, usesCleartextTraffic = null, themeResId = 0,
            usesPermissions = emptyList(), declaredPermissions = emptyList(),
            components = listOf(
                ComponentEntry(ComponentKind.ACTIVITY, "com.example.app.Auth", "com.example.app",
                    exported = true, intentFilters = filters)
            ),
            applicationMetaData = emptyMap(), usesNativeLibraries = emptyList(),
        )
    }

    private val neutral = CompatibilityProfile(packageName = "com.example.app")

    private val fullyCapable = GoogleCapabilities(
        hostGmsAvailable = true, virtualGmsInstalled = true, customTabsAvailable = true,
    )

    @Test fun `browser OAuth is preferred for sign-in when the app declares a redirect`() {
        val d = GoogleCompatRouter(fullyCapable).route(manifest(true), neutral, GoogleFlow.SIGN_IN)
        assertThat(d.mode).isEqualTo(GoogleMode.PASSTHROUGH)
    }

    @Test fun `legacy sign-in falls back to in-space GMS when there is no redirect`() {
        val d = GoogleCompatRouter(fullyCapable).route(manifest(false), neutral, GoogleFlow.SIGN_IN)
        assertThat(d.mode).isEqualTo(GoogleMode.VIRTUAL_GMS)
    }

    @Test fun `legacy sign-in is refused rather than bridged when in-space GMS is absent`() {
        // This is the important one: bridging would hand back a token for the wrong OAuth
        // client, which fails server-side in a way the app developer cannot debug.
        val caps = fullyCapable.copy(virtualGmsInstalled = false, customTabsAvailable = false)
        val d = GoogleCompatRouter(caps).route(manifest(false), neutral, GoogleFlow.SIGN_IN)
        assertThat(d.mode).isEqualTo(GoogleMode.UNSUPPORTED)
        assertThat(d.mode).isNotEqualTo(GoogleMode.HOST_BRIDGE)
        assertThat(d.rationale).contains("in-space Google Play services")
    }

    @Test fun `credential manager is bridged to the host because its audience is a web client`() {
        val caps = GoogleCapabilities(hostGmsAvailable = true, virtualGmsInstalled = false, customTabsAvailable = true)
        val d = GoogleCompatRouter(caps).route(manifest(false), neutral, GoogleFlow.CREDENTIAL_MANAGER)
        assertThat(d.mode).isEqualTo(GoogleMode.HOST_BRIDGE)
    }

    @Test fun `play games is unsupported for now, and says so without claiming impossibility`() {
        for (caps in listOf(fullyCapable, GoogleCapabilities.NONE)) {
            val d = GoogleCompatRouter(caps).route(manifest(true), neutral, GoogleFlow.PLAY_GAMES)
            assertThat(d.mode).isEqualTo(GoogleMode.UNSUPPORTED)
            assertThat(d.rationale).contains("Not supported yet")
        }
    }

    @Test fun `browser OAuth needs no Google Play services at all`() {
        val d = GoogleCompatRouter(GoogleCapabilities.NONE).route(manifest(true), neutral, GoogleFlow.OAUTH_WEB)
        assertThat(d.mode).isEqualTo(GoogleMode.PASSTHROUGH)
    }

    @Test fun `an explicit database policy overrides every heuristic`() {
        val forced = neutral.copy(googlePolicy = mapOf(GoogleFlow.SIGN_IN to GoogleMode.HOST_BRIDGE))
        val d = GoogleCompatRouter(fullyCapable).route(manifest(true), forced, GoogleFlow.SIGN_IN)
        assertThat(d.mode).isEqualTo(GoogleMode.HOST_BRIDGE)
        assertThat(d.rationale).contains("compatibility database")
    }

    @Test fun `disabling the layer turns every flow off`() {
        val disabled = neutral.copy(flags = setOf(CompatFlag.DISABLE_GOOGLE_LAYER))
        val all = GoogleCompatRouter(fullyCapable).routeAll(manifest(true), disabled)
        assertThat(all.map { it.mode }.toSet()).containsExactly(GoogleMode.UNSUPPORTED)
    }

    @Test fun `forcing virtual GMS without it installed reports why instead of silently bridging`() {
        val forced = neutral.copy(flags = setOf(CompatFlag.FORCE_VIRTUAL_GMS))
        val caps = fullyCapable.copy(virtualGmsInstalled = false)
        val d = GoogleCompatRouter(caps).route(manifest(true), forced, GoogleFlow.CREDENTIAL_MANAGER)
        assertThat(d.mode).isEqualTo(GoogleMode.UNSUPPORTED)
        assertThat(d.rationale).contains("not installed in this space")
    }

    @Test fun `every flow gets a decision with a rationale`() {
        val all = GoogleCompatRouter(fullyCapable).routeAll(manifest(true), neutral)
        assertThat(all.map { it.flow }).containsExactlyElementsIn(GoogleFlow.entries)
        assertThat(all.all { it.rationale.isNotBlank() }).isTrue()
    }
}
