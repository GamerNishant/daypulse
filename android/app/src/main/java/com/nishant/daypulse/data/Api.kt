package com.nishant.daypulse.data

import com.nishant.daypulse.BuildConfig
import com.nishant.daypulse.domain.ALL_KINDS
import com.nishant.daypulse.domain.Row
import com.nishant.daypulse.domain.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val status: Int, message: String) : IOException(message)

@Serializable
data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String)

/**
 * Minimal Supabase client over HttpURLConnection: GoTrue password auth + PostgREST on `planner_docs`.
 * No SDK needed, so the APK stays small. All calls are blocking; call them from Dispatchers.IO.
 */
object Api {
    private val base = BuildConfig.SUPABASE_URL
    private val key = BuildConfig.SUPABASE_KEY
    private const val COLS = "id,kind,day,data,updated_at"
    const val RESET_REDIRECT = "https://gamernishant.github.io/mind-dump/reset-password"

    private fun request(method: String, path: String, body: String? = null, token: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Accept", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw ApiException(code, errorMessage(code, text))
        return text
    }

    private fun errorMessage(code: Int, text: String): String {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val msg = listOf("error_description", "msg", "message", "error").firstNotNullOfOrNull { obj?.get(it)?.jsonPrimitive?.content } ?: "HTTP $code"
        return when {
            msg.contains("Invalid login credentials", true) -> "Wrong email or password."
            msg.contains("already registered", true) -> "An account with this email already exists. Try signing in."
            code == 429 -> "Too many attempts. Wait a minute and try again."
            else -> msg
        }
    }

    private fun sessionFrom(text: String, fallbackEmail: String): Session? {
        val o = json.parseToJsonElement(text).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: return null
        val user = o["user"]?.jsonObject
        return Session(
            accessToken = access,
            refreshToken = o["refresh_token"]!!.jsonPrimitive.content,
            expiresAt = System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L) * 1000,
            userId = user?.get("id")?.jsonPrimitive?.content ?: "",
            email = user?.get("email")?.jsonPrimitive?.content ?: fallbackEmail,
        )
    }

    fun signIn(email: String, password: String): Session {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        return sessionFrom(request("POST", "/auth/v1/token?grant_type=password", body), email) ?: throw IOException("Sign-in failed.")
    }

    /** Returns null when the project requires email confirmation first. */
    fun signUp(email: String, password: String): Session? {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        return sessionFrom(request("POST", "/auth/v1/signup", body), email)
    }

    fun refresh(s: Session): Session {
        val body = buildJsonObject { put("refresh_token", s.refreshToken) }.toString()
        return sessionFrom(request("POST", "/auth/v1/token?grant_type=refresh_token", body), s.email) ?: throw IOException("Session expired.")
    }

    /** Sends the reset email. Tapping its link opens Mind Dump's reset page (same account). */
    fun recover(email: String) {
        val body = buildJsonObject { put("email", email) }.toString()
        request("POST", "/auth/v1/recover?redirect_to=${URLEncoder.encode(RESET_REDIRECT, "UTF-8")}", body)
    }

    /** Exchanges a reset link's token hash, or a code + email, for an access token. */
    fun verifyRecovery(tokenHash: String?, email: String?, code: String?): String {
        val body = buildJsonObject {
            put("type", "recovery")
            if (tokenHash != null) put("token_hash", tokenHash) else { put("email", email); put("token", code) }
        }.toString()
        val o = json.parseToJsonElement(request("POST", "/auth/v1/verify", body)).jsonObject
        return o["access_token"]?.jsonPrimitive?.content ?: throw IOException("This reset link has expired or was already used. Send a new one.")
    }

    /** Sets a new password for the user behind [token]; returns their email. */
    fun updatePassword(token: String, password: String): String {
        val body = buildJsonObject { put("password", password) }.toString()
        val o = json.parseToJsonElement(request("PUT", "/auth/v1/user", body, token)).jsonObject
        return o["email"]?.jsonPrimitive?.content ?: ""
    }

    fun signOut(token: String) {
        runCatching { request("POST", "/auth/v1/logout", "{}", token) }
    }

    /** All DayPulse rows changed after [since] (or all), paged past PostgREST's 1000-row cap. */
    fun fetchRows(token: String, since: String?): List<Row> {
        val out = ArrayList<Row>()
        val kinds = URLEncoder.encode("in.(${ALL_KINDS.joinToString(",")})", "UTF-8")
        var offset = 0
        while (true) {
            var path = "/rest/v1/planner_docs?select=$COLS&kind=$kinds&order=updated_at.asc,id.asc&limit=1000&offset=$offset"
            if (since != null) path += "&updated_at=gt.${URLEncoder.encode(since, "UTF-8")}"
            val page = json.decodeFromString(ListSerializer(Row.serializer()), request("GET", path, token = token))
            out += page
            if (page.size < 1000) return out
            offset += 1000
        }
    }

    /** Most recent check-in time across all devices for [day] (cheap query for the reminder check). */
    fun latestLogAt(token: String, day: String): String? {
        val path = "/rest/v1/planner_docs?select=data&kind=eq.pulse_log&day=eq.$day&order=updated_at.desc&limit=20"
        val rows = json.parseToJsonElement(request("GET", path, token = token)) as kotlinx.serialization.json.JsonArray
        return rows.mapNotNull { r ->
            val d = r.jsonObject["data"]?.jsonObject ?: return@mapNotNull null
            if (d["deleted"]?.jsonPrimitive?.content == "true") null else d["at"]?.jsonPrimitive?.content
        }.maxOrNull()
    }

    fun upsert(token: String, id: String, kind: String, day: String?, data: JsonObject): Row {
        val body = buildJsonObject {
            put("id", id); put("kind", kind); put("day", day); put("data", data)
        }.toString()
        val text = request(
            "POST", "/rest/v1/planner_docs?on_conflict=id&select=$COLS", body, token,
            mapOf("Prefer" to "resolution=merge-duplicates,return=representation"),
        )
        return json.decodeFromString(ListSerializer(Row.serializer()), text).first()
    }
}
