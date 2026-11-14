package com.nishant.daypulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishant.daypulse.data.Snapshot
import com.nishant.daypulse.domain.addDays
import com.nishant.daypulse.domain.fmtValue
import com.nishant.daypulse.domain.goalFraction
import com.nishant.daypulse.domain.progressByGoal
import com.nishant.daypulse.domain.summarize
import com.nishant.daypulse.domain.weekDays
import com.nishant.daypulse.domain.weekStart
import com.nishant.daypulse.domain.weeklyTrend
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val SHORT = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun ProgressScreen(snap: Snapshot, today: String) {
    val p = LocalPal.current
    val summary = remember(snap, today) { summarize(snap.plans, snap.logs, snap.settings, today) }
    var weekOf by remember { mutableStateOf(weekStart(today)) }
    var selected by remember { mutableStateOf<String?>(null) }
    val days = weekDays(weekOf)
    val weekPct = summary.rangePct(days.first(), days.last())
    val prev = weekDays(addDays(weekOf, -7))
    val prevPct = summary.rangePct(prev.first(), prev.last())
    val delta = if (weekPct != null && prevPct != null) weekPct - prevPct else null
    val isThisWeek = weekOf == weekStart(today)
    val threshold = snap.settings.hitThreshold

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Progress")
                Text(if (isThisWeek) "This week" else "Week of ${LocalDate.parse(weekOf).format(SHORT)}", style = MaterialTheme.typography.headlineSmall)
            }
            FilledTonalIconButton(onClick = { weekOf = addDays(weekOf, -7); selected = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week") }
            FilledTonalIconButton(onClick = { weekOf = addDays(weekOf, 7); selected = null }, enabled = !isThisWeek) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Week", weekPct?.let { "$it%" } ?: "–", delta?.let { "${if (it >= 0) "▲" else "▼"} ${kotlin.math.abs(it)}% vs last" }, if ((delta ?: 0) >= 0) p.good else p.bad, Modifier.weight(1f))
            Tile("All time", summary.allTimePct?.let { "$it%" } ?: "–", "${summary.days.size} planned days", p.faint, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Streak", "${summary.streak}", "best ${summary.bestStreak} · days ≥$threshold%", p.faint, Modifier.weight(1f))
            val planned = days.count { summary.dayPct(it) != null }
            Tile("Days hit", "${days.count { (summary.dayPct(it) ?: -1) >= threshold }}/$planned", "this week", p.faint, Modifier.weight(1f))
        }

        Card {
            Row {
                Text("Daily completion", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Tap a day", color = p.faint, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEach { d ->
                    val pct = summary.dayPct(d)
                    val future = d > today
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable(enabled = !future) { selected = if (selected == d) null else d },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(pct?.let { "$it%" } ?: "", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Box(
                            Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(p.track)
                                .then(if (selected == d) Modifier.border(2.dp, p.accent, RoundedCornerShape(10.dp)) else Modifier),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(((pct ?: 0) / 100f)).clip(RoundedCornerShape(10.dp)).background(if ((pct ?: -1) >= threshold) p.good else p.accent))
                        }
                        val label = LocalDate.parse(d).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                        Text(label, fontSize = 12.sp, color = if (d == today) p.accent else p.muted, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            selected?.let { DayDetail(snap, it) }
        }

        Card {
            Text("12-week trend", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val trend = weeklyTrend(summary, 12, today)
            if (trend.all { it.pct == null }) Text("Your weekly trend appears after your first planned day.", color = p.muted, fontSize = 13.sp)
            else TrendChart(trend.map { it.pct })
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(LocalDate.parse(trend.first().start).format(SHORT), color = p.faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("now", color = p.faint, fontSize = 11.sp)
            }
        }

        Card {
            Text("Last 20 weeks", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Heatmap(today) { summary.dayPct(it) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String?, subColor: Color, modifier: Modifier) {
    val p = LocalPal.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(label, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = subColor, fontSize = 12.sp)
    }
}

@Composable
private fun DayDetail(snap: Snapshot, day: String) {
    val p = LocalPal.current
    val plan = snap.plans[day]
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
    Spacer(Modifier.height(10.dp))
    val label = LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))
    if (plan == null) {
        Text("$label: no plan (rest day).", color = p.muted, fontSize = 13.sp)
        return
    }
    val logs = snap.logsFor(day)
    val done = progressByGoal(logs)
    Row {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${logs.size} check-in${if (logs.size == 1) "" else "s"}", color = p.faint, fontSize = 12.sp)
    }
    Spacer(Modifier.height(6.dp))
    plan.goals.forEach { g ->
        val v = done[g.id] ?: 0.0
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(g.title, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (g.isCheck) (if (v >= 1) "done" else "not done") else "${fmtValue(v)} / ${fmtValue(g.target)} ${g.unit}".trim(), color = p.muted, fontSize = 13.sp)
            Text("  ${(goalFraction(g, v) * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (!plan.review.isNullOrBlank()) Text("“${plan.review}”", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun TrendChart(points: List<Int?>) {
    val p = LocalPal.current
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val w = size.width
        val h = size.height
        fun x(i: Int) = i / (points.size - 1f) * w
        fun y(v: Int) = (1 - v / 100f) * h
        listOf(0, 50, 100).forEach { v -> drawLine(p.border, Offset(0f, y(v)), Offset(w, y(v)), 1.dp.toPx()) }
        var path: Path? = null
        points.forEachIndexed { i, v ->
            if (v == null) {
                path?.let { drawPath(it, p.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                path = null
            } else {
                if (path == null) path = Path().apply { moveTo(x(i), y(v)) } else path!!.lineTo(x(i), y(v))
            }
        }
        path?.let { drawPath(it, p.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        points.forEachIndexed { i, v ->
            if (v != null) {
                drawCircle(p.surface, 4.dp.toPx(), Offset(x(i), y(v)))
                drawCircle(p.accent, 4.dp.toPx(), Offset(x(i), y(v)), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun Heatmap(today: String, pctOf: (String) -> Int?) {
    val p = LocalPal.current
    val start = addDays(weekStart(today), -7L * 19)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (w in 0 until 20) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in 0 until 7) {
                    val day = addDays(start, (w * 7 + d).toLong())
                    val pct = if (day > today) null else pctOf(day)
                    val c = if (pct == null) p.track else lerp(p.track, p.accent, 0.15f + pct / 100f * 0.85f)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(if (day > today) p.track.copy(alpha = 0.3f) else c))
                }
            }
        }
    }
}
