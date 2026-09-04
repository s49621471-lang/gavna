package com.unique.core.common.diag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A leaky diagnostics export is a security bug, so the redactor is tested rather than
 * trusted.
 */
class DiagRedactorTest {

    @Test fun `strips jwt-shaped tokens`() {
        val text = "id_token=eyJhbGciOiJSUzI1NiIsImtpZCI6IjEyMyJ9.eyJzdWIiOiJ1c2VyIn0.SflKxwRJSMeKKF2QT4"
        assertThat(DiagRedactor.redact(text)).doesNotContain("eyJhbGciOiJSUzI1NiI")
        assertThat(DiagRedactor.redact(text)).contains(DiagRedactor.PLACEHOLDER)
    }

    @Test fun `strips google access and refresh tokens`() {
        assertThat(DiagRedactor.redact("ya29.a0AfB_byC3xample_TOKEN_value"))
            .doesNotContain("a0AfB_byC3xample")
        assertThat(DiagRedactor.redact("1//0gExampleRefreshTokenValue"))
            .doesNotContain("0gExampleRefreshTokenValue")
    }

    @Test fun `strips bearer headers`() {
        assertThat(DiagRedactor.redact("Authorization: Bearer abcdef0123456789xyz"))
            .doesNotContain("abcdef0123456789xyz")
    }

    @Test fun `strips email addresses`() {
        assertThat(DiagRedactor.redact("signed in as person.name@example.com"))
            .doesNotContain("person.name@example.com")
    }

    @Test fun `drops values under sensitive keys entirely`() {
        val event = DiagEvent(
            timestampMillis = 1L,
            channel = DiagChannel.GOOGLE,
            level = DiagLevel.INFO,
            code = "SIGN_IN_OK",
            vuid = 0,
            packageName = "com.example.sample",
            fields = mapOf(
                "accessToken" to "abcdefghijklmnop",
                "id_token" to "whatever",
                "Cookie" to "SID=xyz",
                "accountEmail" to "a@b.com",
                "flow" to "CREDENTIAL_MANAGER",
                "durationMs" to "812",
            ),
        )
        val red = DiagRedactor.redact(event)
        assertThat(red.fields["accessToken"]).isEqualTo(DiagRedactor.PLACEHOLDER)
        assertThat(red.fields["id_token"]).isEqualTo(DiagRedactor.PLACEHOLDER)
        assertThat(red.fields["Cookie"]).isEqualTo(DiagRedactor.PLACEHOLDER)
        assertThat(red.fields["accountEmail"]).isEqualTo(DiagRedactor.PLACEHOLDER)
        // Non-sensitive diagnostic content must survive, or the export is useless.
        assertThat(red.fields["flow"]).isEqualTo("CREDENTIAL_MANAGER")
        assertThat(red.fields["durationMs"]).isEqualTo("812")
    }

    @Test fun `redacts throwable text too`() {
        val event = DiagEvent(
            1L, DiagChannel.CRASH, DiagLevel.ERROR, "CRASH", 0, "p",
            throwable = "IllegalStateException: token ya29.SomeVeryRealLookingToken",
        )
        assertThat(DiagRedactor.redact(event).throwable).doesNotContain("SomeVeryRealLookingToken")
    }

    @Test fun `sensitive key detection is case and separator insensitive`() {
        for (k in listOf("token", "Token", "ACCESS_TOKEN", "refreshToken", "x-api-key", "clientSecret")) {
            assertThat(DiagRedactor.isSensitiveKey(k)).isTrue()
        }
        for (k in listOf("flow", "durationMs", "packageName", "vuid")) {
            assertThat(DiagRedactor.isSensitiveKey(k)).isFalse()
        }
    }
}
