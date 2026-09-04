package com.unique.core.google

import com.unique.core.common.compat.GoogleFlow
import com.unique.core.common.compat.GoogleMode

/**
 * The Google Compatibility Layer's public surface.
 *
 * Five narrow bridges rather than one wide "Google helper", because each has a different
 * chance of working and a different failure mode, and lumping them together is how these
 * projects end up with a single opaque `googleSignIn()` that fails for eight unrelated
 * reasons. Each bridge is independently testable and has its own diagnostics channel
 * entries.
 */

/** Whether a token can be used by the app that asked for it, and if not, why not. */
sealed interface GoogleResult<out T> {
    data class Success<T>(val value: T, val mode: GoogleMode) : GoogleResult<T>

    /**
     * The flow ran but the result would not be usable by the calling app.
     *
     * This exists so UNIQUE never hands back a token that will fail server-side in a way
     * the app developer cannot debug. See [GoogleAuthBridge].
     */
    data class WrongAudience(val expected: String, val actual: String, val advice: String) : GoogleResult<Nothing>

    data class Unsupported(val flow: GoogleFlow, val reason: String) : GoogleResult<Nothing>
    data class Failed(val flow: GoogleFlow, val code: String, val message: String) : GoogleResult<Nothing>
    data object Cancelled : GoogleResult<Nothing>
}

data class SignInRequest(
    val vuid: Int,
    val packageName: String,
    /** SHA-1 of the virtual app's signing certificate, as GMS would compute it. */
    val signatureSha1: String?,
    val requestedScopes: List<String>,
    val serverClientId: String?,
    val requestIdToken: Boolean,
)

data class SignInAccount(
    val id: String,
    val displayName: String?,
    val idToken: String?,
    val serverAuthCode: String?,
    val grantedScopes: List<String>,
)

data class CredentialRequest(
    val vuid: Int,
    val packageName: String,
    val serverClientId: String,
    val filterByAuthorizedAccounts: Boolean,
    val nonce: String?,
)

data class CredentialResult(val idToken: String, val accountId: String, val displayName: String?)

data class FirebaseConfig(val projectId: String, val apiKey: String, val appId: String)
data class FirebaseResult(val idToken: String, val refreshToken: String?, val uid: String)

data class OAuthRequest(
    val vuid: Int,
    val packageName: String,
    val authorizationEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val extraParams: Map<String, String> = emptyMap(),
)

data class OAuthResult(val code: String, val state: String?)

data class PlayGamesResult(val playerId: String, val displayName: String?)

/**
 * Legacy `GoogleSignIn` (`Auth.GOOGLE_SIGN_IN_API`).
 *
 * GMS validates (package, SHA-1) against a registered *Android* OAuth client. Inside
 * UNIQUE every virtual app shares the host's uid, so the host's real GMS sees
 * `com.unique` and the app's client does not match — the classic `DEVELOPER_ERROR (10)`.
 * Only an in-space GMS (Mode A) makes this work.
 */
fun interface GoogleAuthBridge {
    suspend fun signIn(request: SignInRequest): GoogleResult<SignInAccount>
}

/**
 * Credential Manager / `GetGoogleIdOption`.
 *
 * The ID token's audience is a **web/server** client id, not an Android one, so a token
 * obtained through the host's GMS is genuinely valid for the app's backend. This is the
 * most useful thing the host bridge can do.
 */
fun interface CredentialBridge {
    suspend fun getCredential(request: CredentialRequest): GoogleResult<CredentialResult>
}

/** Firebase Auth, which consumes a token produced by one of the other bridges. */
fun interface FirebaseBridge {
    suspend fun exchange(idToken: String, config: FirebaseConfig): GoogleResult<FirebaseResult>
}

/**
 * Play Games.
 *
 * Expected to report [GoogleResult.Unsupported] on every path: Play Games binds to an
 * attested, installed package identity. The interface exists so the router has a
 * consistent shape and the UI can say why, not because a working implementation is
 * anticipated.
 */
fun interface PlayGamesBridge {
    suspend fun authenticate(vuid: Int, packageName: String): GoogleResult<PlayGamesResult>
}

/**
 * Plain OAuth through WebView or Chrome Custom Tabs.
 *
 * Needs nothing Google-specific — only a working WebView and a deep-link return into the
 * right instance. It is therefore the highest-reliability Google path inside
 * virtualization, and the router prefers it whenever the app supports it.
 */
fun interface OAuthBridge {
    suspend fun authorize(request: OAuthRequest): GoogleResult<OAuthResult>
}
