package com.nishant.daypulse

import com.nishant.daypulse.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class StatsTest {
    private fun g(id: String, target: Double) = Goal(id, id, target, "")
    private fun log(day: String, at: String, vararg e: Pair<String, Double>, deleted: Boolean = false) =
        Log(at, day, "${day}T$at:00.000Z", "web", "t", e.map { LogEntry(it.first, it.second) }, null, deleted)

    @Test fun latestLogPerGoalWins() {
        val logs = listOf(
            log("2026-11-10", "09:00", "a" to 1.0),
            log("2026-11-10", "11:00", "a" to 3.0, "b" to 1.0),
            log("2026-11-10", "12:00", "a" to 9.0, deleted = true),
        )
        val p = progressByGoal(logs)
        assertEquals(3.0, p["a"]!!, 0.0)
        assertEquals(1.0, p["b"]!!, 0.0)
    }

    @Test fun dayPercentAveragesCappedFractions() {
        val plan = Plan("2026-11-10", listOf(g("a", 4.0), g("b", 1.0)))
        assertEquals(25, dayPercent(plan, listOf(log("2026-11-10", "10:00", "a" to 2.0))))
        assertEquals(100, dayPercent(plan, listOf(log("2026-11-10", "10:00", "a" to 8.0, "b" to 1.0))))
        assertNull(dayPercent(null, emptyList()))
    }

    @Test fun paceIsLinearOverActiveHours() {
        val s = Settings(activeStart = "08:00", activeEnd = "20:00")
        assertEquals(0, expectedPercent(s, LocalTime.of(7, 0)))
        assertEquals(50, expectedPercent(s, LocalTime.of(14, 0)))
        assertEquals(100, expectedPercent(s, LocalTime.of(23, 0)))
        assertEquals(Pace.BEHIND, paceOf(40, 50))
        assertEquals(Pace.ON_PACE, paceOf(48, 50))
        assertEquals(Pace.AHEAD, paceOf(70, 50))
    }

    @Test fun summaryStreaksAndTrend() {
        val today = "2026-11-14"
        val plans = HashMap<String, Plan>()
        val logs = ArrayList<Log>()
        fun set(day: String, pct: Double) {
            plans[day] = Plan(day, listOf(g("a", 100.0)))
            logs += log(day, "10:00", "a" to pct)
        }
        set("2026-11-10", 90.0); set("2026-11-12", 85.0); set("2026-11-13", 100.0); set("2026-11-14", 20.0); set("2026-11-03", 50.0)
        val s = summarize(plans, logs, Settings(), today)
        assertEquals(3, s.streak)
        assertEquals(3, s.bestStreak)
        assertEquals(jsRound((90 + 85 + 100 + 20) / 4.0), s.rangePct(weekStart(today), today))
        assertEquals(WeekStat("2026-11-02", 50), weeklyTrend(s, 2, today)[0])
    }

    @Test fun reminderRule() {
        val s = Settings(reminderMinutes = 60, activeStart = "08:00", activeEnd = "22:00")
        val z = ZoneOffset.UTC
        fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 14, h, m)
        fun ms(h: Int, m: Int = 0) = at(h, m).toInstant(z).toEpochMilli()
        assertFalse(reminderDue(s, null, 0, at(7, 30), z))
        assertFalse(reminderDue(s, null, 0, at(8, 59), z))
        assertTrue(reminderDue(s, null, 0, at(9, 0), z))
        assertFalse(reminderDue(s, "2026-11-14T10:30:00.000Z", 0, at(11, 15), z))
        assertTrue(reminderDue(s, "2026-11-14T10:30:00.000Z", 0, at(11, 30), z))
        assertFalse(reminderDue(s, null, ms(9), at(9, 40), z))
    }

    @Test fun stableIdMatchesWebImplementation() {
        // Reference value computed with the web app's algorithm (Node crypto SHA-1).
        assertEquals("4bd90086-1986-5f4e-bb2b-64cd1a10a7dd", stableId("user-123", KIND_PLAN, "2026-11-14"))
    }

    @Test fun isoFormatMatchesJs() {
        assertEquals("2026-11-14T06:40:00.000Z", isoNow(java.time.Instant.parse("2026-11-14T06:40:00Z")))
        assertEquals("1.5", fmtValue(1.5)); assertEquals("6", fmtValue(6.0))
    }
}
