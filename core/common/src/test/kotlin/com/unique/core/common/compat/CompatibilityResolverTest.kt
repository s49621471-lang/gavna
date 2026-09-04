package com.unique.core.common.compat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompatibilityResolverTest {

    private val shipped = listOf(
        CompatibilityProfile(
            packageName = "com.example.game",
            flags = setOf(CompatFlag.NO_PROC_REDIRECT),
            googlePolicy = mapOf(GoogleFlow.SIGN_IN to GoogleMode.VIRTUAL_GMS),
            support = SupportLevel.PARTIAL,
            note = "Sign-in requires in-space GMS.",
        ),
        CompatibilityProfile(
            packageName = "com.example.banking",
            flags = setOf(CompatFlag.REQUIRES_ATTESTATION),
            support = SupportLevel.UNSUPPORTED,
            note = "Requires Play Integrity, which app-level virtualization cannot provide.",
        ),
        CompatibilityProfile(
            packageName = "com.example.legacy",
            maxVersionCode = 100,
            flags = setOf(CompatFlag.SINGLE_PROCESS),
            support = SupportLevel.SUPPORTED,
        ),
    )

    private val resolver = CompatibilityResolver(shipped)

    @Test fun `unknown packages resolve to a neutral default`() {
        val p = resolver.resolve("com.unheard.of", 1)
        assertThat(p.packageName).isEqualTo("com.unheard.of")
        assertThat(p.flags).isEmpty()
        assertThat(p.support).isEqualTo(SupportLevel.UNKNOWN)
    }

    @Test fun `version ranges are respected`() {
        assertThat(resolver.resolve("com.example.legacy", 50).flags)
            .containsExactly(CompatFlag.SINGLE_PROCESS)
        assertThat(resolver.resolve("com.example.legacy", 500).flags).isEmpty()
    }

    @Test fun `unsupported packages carry a reason for the user`() {
        val p = resolver.resolve("com.example.banking", 1)
        assertThat(p.support).isEqualTo(SupportLevel.UNSUPPORTED)
        assertThat(p.note).contains("Play Integrity")
    }

    @Test fun `local overrides merge over the shipped database`() {
        val local = listOf(
            CompatibilityProfile(
                packageName = "com.example.game",
                flags = setOf(CompatFlag.REBUILD_NOTIFICATIONS),
                googlePolicy = mapOf(GoogleFlow.SIGN_IN to GoogleMode.PASSTHROUGH),
                support = SupportLevel.SUPPORTED,
            )
        )
        val p = CompatibilityResolver(shipped, local).resolve("com.example.game", 1)

        assertThat(p.flags)
            .containsExactly(CompatFlag.NO_PROC_REDIRECT, CompatFlag.REBUILD_NOTIFICATIONS)
        assertThat(p.googlePolicy[GoogleFlow.SIGN_IN]).isEqualTo(GoogleMode.PASSTHROUGH)
        assertThat(p.support).isEqualTo(SupportLevel.SUPPORTED)
        assertThat(p.note).contains("in-space GMS") // shipped note survives
    }

    @Test fun `a local override that states no support level keeps the shipped one`() {
        val local = listOf(CompatibilityProfile(packageName = "com.example.banking"))
        val p = CompatibilityResolver(shipped, local).resolve("com.example.banking", 1)
        assertThat(p.support).isEqualTo(SupportLevel.UNSUPPORTED)
    }
}
