package com.nishant.daypulse.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Mirrors web/src/lib/types.ts. Rows live in the shared Supabase `planner_docs` table;
// these are the shapes of their `data` JSON.

@Serializable
data class Goal(val id: String, val title: String, val target: Double, val unit: String = "") {
    val isCheck: Boolean get() = target == 1.0 && unit.isBlank()
}

@Serializable
data class LogEntry(val goalId: String, val value: Double)

@Serializable
data class PlanData(val goals: List<Goal> = emptyList(), val review: String? = null)

@Serializable
data class LogData(
    val at: String,
    val device: String = "android",
    val deviceName: String = "",
    val entries: List<LogEntry> = emptyList(),
    val note: String? = null,
    val deleted: Boolean? = null,
)

@Serializable
data class Settings(
    val templateGoals: List<Goal> = emptyList(),
    val reminderMinutes: Int = 60,
    val activeStart: String = "08:00",
    val activeEnd: String = "22:00",
    val hitThreshold: Int = 80,
)

data class Plan(val day: String, val goals: List<Goal>, val review: String? = null)

data class Log(
    val id: String,
    val day: String,
    val at: String,
    val device: String,
    val deviceName: String,
    val entries: List<LogEntry>,
    val note: String?,
    val deleted: Boolean,
)

/** A raw `planner_docs` row. */
@Serializable
data class Row(
    val id: String,
    val kind: String,
    val day: String? = null,
    val data: JsonObject,
    @Suppress("PropertyName") val updated_at: String = "",
)

const val KIND_PLAN = "pulse_plan"
const val KIND_LOG = "pulse_log"
const val KIND_SETTINGS = "pulse_settings"
val ALL_KINDS = listOf(KIND_PLAN, KIND_LOG, KIND_SETTINGS)

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
