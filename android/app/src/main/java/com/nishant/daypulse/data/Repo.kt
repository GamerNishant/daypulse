package com.nishant.daypulse.data

import android.content.Context
import android.os.Build
import com.nishant.daypulse.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

enum class SyncStatus { SYNCED, SYNCING, OFFLINE }

data class Snapshot(
    val ready: Boolean = false,
    val plans: Map<String, Plan> = emptyMap(),
    val logs: List<Log> = emptyList(),
    val settings: Settings = Settings(),
    val status: SyncStatus = SyncStatus.SYNCING,
    val pending: Int = 0,
) {
    fun logsFor(day: String) = logs.filter { it.day == day && !it.deleted }
}

@Serializable
private data class Op(val id: String, val kind: String, val day: String?, val data: JsonObject)

@Serializable
private data class Cache(val rows: List<Row> = emptyList(), val outbox: List<Op> = emptyList(), val syncedAt: String? = null)

/**
 * Local-first store (same design as web/src/lib/store.ts):
 * rows cached on disk, writes applied locally + queued in a persisted outbox, flushed in order;
 * pulls are incremental by `updated_at` with a 2-minute overlap. One instance per process, so the
 * UI, the check-in popup and the background service all share one session (no token races).
 */
class Repo private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var instance: Repo? = null
        fun get(ctx: Context): Repo = instance ?: synchronized(this) { instance ?: Repo(ctx.applicationContext).also { instance = it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = ctx.getSharedPreferences("daypulse", Context.MODE_PRIVATE)
    private val authLock = Mutex()
    private val syncLock = Mutex()

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val rows = LinkedHashMap<String, Row>()
    private var outbox = ArrayList<Op>()
    private var syncedAt: String? = null
    private var status = SyncStatus.SYNCING
    private var ready = false

    private val _snap = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snap.asStateFlow()

    init {
        _session.value?.let { loadCache(it.userId) }
        emit(persist = false)
    }

    // ---- device ---------------------------------------------------------------------------------

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: (Build.MODEL ?: "Phone")
        set(v) = prefs.edit().putString("deviceName", v.trim()).apply()

    // ---- auth -----------------------------------------------------------------------------------

    private fun loadSession(): Session? = prefs.getString("session", null)?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }

    private fun saveSession(s: Session?) {
        prefs.edit().apply { if (s == null) remove("session") else putString("session", json.encodeToString(Session.serializer(), s)) }.apply()
        _session.value = s
    }

    suspend fun signIn(email: String, password: String, create: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            val s = if (create) Api.signUp(email.trim(), password) else Api.signIn(email.trim(), password)
            if (s == null) return@withContext "Check your inbox to confirm your email, then sign in."
            synchronized(rows) { rows.clear(); outbox.clear(); syncedAt = null; ready = false }
            loadCache(s.userId)
            saveSession(s)
            emit()
            refresh()
            null
        } catch (e: IOException) {
            if (e is ApiException) e.message else "You appear to be offline."
        }
    }

    fun signOut() {
        val s = _session.value ?: return
        scope.launch { Api.signOut(s.accessToken) }
        saveSession(null)
        synchronized(rows) { rows.clear(); outbox.clear(); syncedAt = null; ready = false }
        emit(persist = false)
    }

    /** A valid access token, refreshing it (once, under a lock) when close to expiry. */
    private suspend fun token(forceRefresh: Boolean = false): String? = authLock.withLock {
        val s = _session.value ?: return null
        if (!forceRefresh && s.expiresAt - 60_000 > System.currentTimeMillis()) return s.accessToken
        return try {
            val fresh = Api.refresh(s)
            saveSession(fresh.copy(userId = fresh.userId.ifEmpty { s.userId }))
            fresh.accessToken
        } catch (e: ApiException) {
            if (e.status in 400..499) saveSession(null) // refresh token revoked: sign in again
            null
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun <T> authed(block: (String) -> T): T {
        val t = token() ?: throw IOException("Not signed in")
        return try {
            block(t)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            block(token(forceRefresh = true) ?: throw e)
        }
    }

    // ---- cache ----------------------------------------------------------------------------------

    private fun cacheFile(uid: String) = File(ctx.filesDir, "cache-$uid.json")

    private fun loadCache(uid: String) {
        val f = cacheFile(uid)
        if (!f.exists()) return
        runCatching {
            val c = json.decodeFromString(Cache.serializer(), f.readText())
            synchronized(rows) {
                c.rows.forEach { rows[it.id] = it }
                outbox = ArrayList(c.outbox)
                syncedAt = c.syncedAt
                ready = true
            }
        }
    }

    private fun persist() {
        val uid = _session.value?.userId ?: return
        val text = synchronized(rows) { json.encodeToString(Cache.serializer(), Cache(rows.values.toList(), outbox.toList(), syncedAt)) }
        runCatching {
            val tmp = File(ctx.filesDir, "cache-$uid.tmp")
            tmp.writeText(text)
            tmp.renameTo(cacheFile(uid))
        }
    }

    private fun emit(persist: Boolean = true) {
        val snap = synchronized(rows) { derive() }
        _snap.value = snap
        if (persist) persist()
    }

    private fun derive(): Snapshot {
        val plans = HashMap<String, Plan>()
        val logs = ArrayList<Log>()
        var settings = Settings()
        for (r in rows.values) {
            when (r.kind) {
                KIND_PLAN -> if (r.day != null) runCatching {
                    val d = json.decodeFromJsonElement(PlanData.serializer(), r.data)
                    plans[r.day] = Plan(r.day, d.goals, d.review)
                }
                KIND_LOG -> if (r.day != null) runCatching {
                    val d = json.decodeFromJsonElement(LogData.serializer(), r.data)
                    logs += Log(r.id, r.day, d.at, d.device, d.deviceName, d.entries, d.note, d.deleted == true)
                }
                KIND_SETTINGS -> runCatching { settings = json.decodeFromJsonElement(Settings.serializer(), r.data) }
            }
        }
        logs.sortBy { it.at }
        return Snapshot(ready, plans, logs, settings, status, outbox.size)
    }

    // ---- sync -----------------------------------------------------------------------------------

    /** Flush pending writes, then pull remote changes. Safe to call often. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (_session.value == null) return@withContext
        flush()
        pull()
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    private suspend fun pull() = syncLock.withLock {
        try {
            val since = syncedAt?.let { runCatching { Instant.parse(it.toInstantString()).minusSeconds(120).toString() }.getOrNull() }
            val fetched = authed { Api.fetchRows(it, since) }
            synchronized(rows) {
                val pending = outbox.map { it.id }.toSet()
                for (r in fetched) {
                    if (r.id !in pending) rows[r.id] = r
                    if (syncedAt == null || newer(r.updated_at, syncedAt!!)) syncedAt = r.updated_at
                }
                ready = true
                status = if (outbox.isEmpty()) SyncStatus.SYNCED else SyncStatus.SYNCING
            }
        } catch (e: IOException) {
            status = SyncStatus.OFFLINE
        }
        emit()
    }

    private fun newer(a: String, b: String): Boolean =
        runCatching { Instant.parse(a.toInstantString()) > Instant.parse(b.toInstantString()) }.getOrDefault(a > b)

    /** Postgres returns "+00:00" offsets; Instant.parse wants an ISO instant. */
    private fun String.toInstantString() = java.time.OffsetDateTime.parse(this).toInstant().toString()

    private suspend fun flush() = syncLock.withLock {
        if (outbox.isEmpty()) return@withLock
        status = SyncStatus.SYNCING
        emit(persist = false)
        try {
            while (true) {
                val op = synchronized(rows) { outbox.firstOrNull() } ?: break
                val saved = authed { Api.upsert(it, op.id, op.kind, op.day, op.data) }
                synchronized(rows) {
                    outbox.remove(op)
                    if (outbox.none { it.id == op.id }) rows[saved.id] = saved
                }
            }
            status = SyncStatus.SYNCED
        } catch (e: IOException) {
            status = SyncStatus.OFFLINE
        }
        emit()
    }

    private fun put(op: Op) {
        synchronized(rows) {
            rows[op.id] = Row(op.id, op.kind, op.day, op.data, isoNow())
            val head = outbox.firstOrNull()
            outbox.removeAll { it.id == op.id && it !== head }
            outbox.add(op)
        }
        emit()
        scope.launch { flush() }
    }

    // ---- writes ---------------------------------------------------------------------------------

    private val uid get() = _session.value?.userId ?: ""

    fun savePlan(plan: Plan) {
        val data = json.encodeToJsonElement(PlanData(plan.goals, plan.review ?: "")).jsonObject
        put(Op(stableId(uid, KIND_PLAN, plan.day), KIND_PLAN, plan.day, data))
    }

    fun addLog(day: String, entries: List<LogEntry>, note: String): Log {
        val log = Log(UUID.randomUUID().toString(), day, isoNow(), "android", deviceName, entries, note.ifBlank { null }, false)
        writeLog(log)
        return log
    }

    fun deleteLog(log: Log, deleted: Boolean = true) = writeLog(log.copy(deleted = deleted))

    private fun writeLog(log: Log) {
        val data = json.encodeToJsonElement(LogData(log.at, log.device, log.deviceName, log.entries, log.note, if (log.deleted) true else null)).jsonObject
        put(Op(log.id, KIND_LOG, log.day, data))
    }

    fun saveSettings(s: Settings) {
        put(Op(stableId(uid, KIND_SETTINGS, "default"), KIND_SETTINGS, null, json.encodeToJsonElement(s).jsonObject))
    }

    /**
     * For the reminder check: today's latest check-in on any device, asking the server directly
     * (cheap) so a check-in made on the laptop silences the phone. Falls back to local data offline.
     */
    suspend fun latestLogAtAnyDevice(day: String): String? = withContext(Dispatchers.IO) {
        val local = _snap.value.logsFor(day).maxOfOrNull { it.at }
        val remote = runCatching { authed { Api.latestLogAt(it, day) } }.getOrNull()
        listOfNotNull(local, remote).maxOrNull()
    }
}
