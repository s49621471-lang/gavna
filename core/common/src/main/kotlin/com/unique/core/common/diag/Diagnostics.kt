package com.unique.core.common.diag

/** Diagnostic channels, matching the sections shown in App Details -> Diagnostics. */
enum class DiagChannel { LAUNCH, PROCESS, NATIVE, STORAGE, GOOGLE, WEBVIEW, NOTIFICATION, CRASH, HOOK }

enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/**
 * A structured diagnostic record.
 *
 * Structured rather than free text because the whole point is to be filterable and
 * exportable: [code] is a stable identifier the compatibility database can key on, and
 * [fields] carries the specifics. Nothing here is ever formatted into a user-facing
 * string — the UI renders from the fields.
 */
data class DiagEvent(
    val timestampMillis: Long,
    val channel: DiagChannel,
    val level: DiagLevel,
    /** Stable, greppable identifier, e.g. `FGS_TYPE_UNSUPPORTED`, `HOOK_BIND_FAILED`. */
    val code: String,
    val vuid: Int?,
    val packageName: String?,
    val fields: Map<String, String> = emptyMap(),
    val throwable: String? = null,
)

/**
 * Removes anything that must never leave the device in a diagnostics export.
 *
 * A leaky diagnostics package is a security bug, so this is a deny-by-pattern redactor
 * with its own tests rather than a best-effort scrub. It runs over every exported event
 * and every exported log line.
 */
object DiagRedactor {

    private val SENSITIVE_KEYS = listOf(
        "token", "access_token", "id_token", "refresh_token", "authorization", "auth",
        "cookie", "password", "passwd", "secret", "credential", "session", "apikey",
        "api_key", "client_secret", "signature", "bearer", "email", "account",
    )

    private val PATTERNS = listOf(
        // OAuth/JWT-shaped values.
        Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}"""),
        // Google auth tokens.
        Regex("""\bya29\.[A-Za-z0-9._-]{10,}"""),
        Regex("""\b1//[A-Za-z0-9._-]{10,}"""),
        // Bearer headers.
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{10,}"""),
        // Email addresses.
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
    )

    const val PLACEHOLDER = "[redacted]"

    fun redact(text: String): String {
        var out = text
        for (p in PATTERNS) out = p.replace(out, PLACEHOLDER)
        return out
    }

    fun redact(event: DiagEvent): DiagEvent = event.copy(
        fields = event.fields.mapValues { (k, v) ->
            if (isSensitiveKey(k)) PLACEHOLDER else redact(v)
        },
        throwable = event.throwable?.let(::redact),
    )

    internal fun isSensitiveKey(key: String): Boolean {
        // Separators are stripped so `api_key`, `api-key`, `apiKey` and `API KEY` all
        // match the same entry — field naming varies across the code we log from, and a
        // near-miss here means a token reaches the export.
        val k = key.lowercase().replace(Regex("[^a-z0-9]"), "")
        return SENSITIVE_KEYS.any { k.contains(it.replace("_", "")) }
    }
}
