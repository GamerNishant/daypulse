package com.nishant.daypulse.domain

import java.net.URLDecoder

/**
 * What the user pasted to finish a password reset (mirrors web/src/lib/recoveryLink.ts):
 * the email's verify link, the page it redirected to (even a dead localhost one), or a code.
 */
sealed interface RecoveryInput {
    data class TokenHash(val tokenHash: String) : RecoveryInput
    data class Tokens(val accessToken: String, val refreshToken: String) : RecoveryInput
    data class Code(val code: String) : RecoveryInput
    data class Error(val message: String) : RecoveryInput
}

fun parseRecoveryInput(raw: String): RecoveryInput? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (Regex("^\\d{6,10}$").matches(text)) return RecoveryInput.Code(text)
    if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$").matches(text)) return null

    fun params(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
        val dec = { v: String -> runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v) }
        dec(it.substringBefore('=')) to dec(it.substringAfter('=', ""))
    }
    val hash = params(text.substringAfter('#', ""))
    val query = params(text.substringBefore('#').substringAfter('?', ""))
    fun get(k: String) = hash[k] ?: query[k]

    (get("error_description") ?: get("error"))?.let { err ->
        return RecoveryInput.Error(
            if (Regex("expired|invalid", RegexOption.IGNORE_CASE).containsMatchIn(err)) "This reset link has expired or was already used. Send a new one." else err,
        )
    }
    val type = get("type")
    if (type != null && type != "recovery") return RecoveryInput.Error("That isn't a password reset link.")
    val access = get("access_token")
    val refresh = get("refresh_token")
    if (!access.isNullOrEmpty() && !refresh.isNullOrEmpty()) return RecoveryInput.Tokens(access, refresh)
    val tokenHash = get("token_hash") ?: get("token")
    if (!tokenHash.isNullOrEmpty()) return RecoveryInput.TokenHash(tokenHash)
    return null
}
