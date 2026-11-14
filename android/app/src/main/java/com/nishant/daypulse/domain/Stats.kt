package com.nishant.daypulse.domain

import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Port of web/src/lib/stats.ts + dates.ts + ids.ts + platform.reminderDue. Keep them in step.

/** ISO-8601 UTC with milliseconds, the same format as JS `toISOString()` (so strings sort alike). */
private val ISO_MS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun isoNow(now: Instant = Instant.now()): String = ISO_MS.format(now)

fun today(): String = LocalDate.now().toString()

fun minutesOf(hhmm: String): Int {
    val p = hhmm.split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

fun weekStart(day: String): String =
    LocalDate.parse(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

fun weekDays(start: String): List<String> = (0L until 7L).map { LocalDate.parse(start).plusDays(it).toString() }

fun addDays(day: String, n: Long): String = LocalDate.parse(day).plusDays(n).toString()

/** Current running total per goal: the value from the latest log that mentions it. */
fun progressByGoal(logs: List<Log>): Map<String, Double> {
    val latest = HashMap<String, Pair<String, Double>>()
    for (log in logs) {
        if (log.deleted) continue
        for (e in log.entries) {
            val prev = latest[e.goalId]
            if (prev == null || log.at >= prev.first) latest[e.goalId] = log.at to e.value
        }
    }
    return latest.mapValues { it.value.second }
}

fun goalFraction(goal: Goal, done: Double?): Double {
    if (goal.target <= 0) return 0.0
    return max(0.0, min(1.0, (done ?: 0.0) / goal.target))
}

/** 0–100, or null for a day with no plan (rest day — not counted). */
fun dayPercent(plan: Plan?, logs: List<Log>): Int? {
    if (plan == null || plan.goals.isEmpty()) return null
    val done = progressByGoal(logs)
    val sum = plan.goals.sumOf { goalFraction(it, done[it.id]) }
    return jsRound(sum / plan.goals.size * 100)
}

/** Math.round semantics (half up), matching the web app exactly. */
fun jsRound(x: Double): Int = kotlin.math.floor(x + 0.5).toInt()

fun expectedPercent(s: Settings, now: LocalTime = LocalTime.now()): Int {
    val start = minutesOf(s.activeStart)
    val end = minutesOf(s.activeEnd)
    if (end <= start) return 0
    val t = (now.hour * 60 + now.minute - start).toDouble() / (end - start)
    return jsRound(max(0.0, min(1.0, t)) * 100)
}

enum class Pace(val label: String) { AHEAD("Ahead of pace"), ON_PACE("On pace"), BEHIND("Behind pace"), NOT_STARTED("Day not started") }

fun paceOf(actual: Int, expected: Int): Pace = when {
    expected == 0 && actual == 0 -> Pace.NOT_STARTED
    actual >= expected + 10 -> Pace.AHEAD
    actual >= expected - 5 -> Pace.ON_PACE
    else -> Pace.BEHIND
}

class Summary(
    val days: Map<String, Int>,
    val allTimePct: Int?,
    val streak: Int,
    val bestStreak: Int,
) {
    fun dayPct(d: String): Int? = days[d]
    fun rangePct(from: String, to: String): Int? {
        val v = days.filterKeys { it in from..to }.values
        return if (v.isEmpty()) null else jsRound(v.average())
    }
}

fun summarize(plans: Map<String, Plan>, logs: List<Log>, settings: Settings, today: String = today()): Summary {
    val byDay = logs.filter { !it.deleted }.groupBy { it.day }
    val days = HashMap<String, Int>()
    for ((day, plan) in plans) {
        if (day > today) continue
        dayPercent(plan, byDay[day].orEmpty())?.let { days[day] = it }
    }
    fun hit(d: String) = (days[d] ?: -1) >= settings.hitThreshold
    val sorted = days.keys.sorted()
    var streak = 0
    for (d in sorted.reversed()) {
        if (d == today && !hit(d)) continue
        if (hit(d)) streak++ else break
    }
    var best = 0
    var run = 0
    for (d in sorted) {
        run = if (hit(d)) run + 1 else 0
        best = max(best, run)
    }
    val all = if (days.isEmpty()) null else jsRound(days.values.average())
    return Summary(days, all, streak, best)
}

data class WeekStat(val start: String, val pct: Int?)

fun weeklyTrend(summary: Summary, n: Int, today: String = today()): List<WeekStat> {
    val thisWeek = weekStart(today)
    return (0 until n).map { i ->
        val start = addDays(thisWeek, -7L * (n - 1 - i))
        val d = weekDays(start)
        WeekStat(start, summary.rangePct(d.first(), d.last()))
    }
}

fun suggestGoals(plans: Map<String, Plan>, settings: Settings, day: String): List<Goal> {
    if (settings.templateGoals.isNotEmpty()) return settings.templateGoals
    val prev = plans.keys.filter { it < day && plans[it]!!.goals.isNotEmpty() }.maxOrNull()
    return prev?.let { plans[it]!!.goals } ?: emptyList()
}

fun fmtValue(n: Double): String =
    if (n == kotlin.math.floor(n)) n.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", n).trimEnd('0').trimEnd('.')

fun goalLabel(g: Goal): String = if (g.isCheck) "yes / no" else "${fmtValue(g.target)}${if (g.unit.isNotBlank()) " ${g.unit}" else ""}"

/**
 * The cross-device reminder rule (same as web platform.ts / desktop reminder.cjs): inside active
 * hours, remind once `reminderMinutes` have passed since the latest of today's last check-in on
 * ANY device, today's active start, and this device's last reminder.
 */
fun reminderDue(s: Settings, lastLogAt: String?, lastRemindedAt: Long, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val t = now.hour * 60 + now.minute
    if (t < minutesOf(s.activeStart) || t >= minutesOf(s.activeEnd)) return false
    val startMs = now.toLocalDate().atStartOfDay().plusMinutes(minutesOf(s.activeStart).toLong()).atZone(zone).toInstant().toEpochMilli()
    val logMs = lastLogAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    val anchor = maxOf(startMs, logMs, lastRemindedAt)
    return now.atZone(zone).toInstant().toEpochMilli() - anchor >= s.reminderMinutes * 60_000L
}

/** Deterministic v5-style UUID (SHA-1 of "userId:kind:key"); identical to web ids.ts stableId(). */
fun stableId(userId: String, kind: String, key: String): String {
    val h = MessageDigest.getInstance("SHA-1").digest("$userId:$kind:$key".toByteArray(Charsets.UTF_8))
    val b = h.copyOf(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = b.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
