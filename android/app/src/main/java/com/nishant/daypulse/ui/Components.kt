package com.nishant.daypulse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishant.daypulse.domain.Goal
import com.nishant.daypulse.domain.Pace
import com.nishant.daypulse.domain.Plan
import com.nishant.daypulse.domain.fmtValue
import com.nishant.daypulse.domain.goalFraction
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalPal.current.faint)
}

@Composable
fun ProgressRing(pct: Int, expected: Int?, size: Dp = 128.dp, stroke: Dp = 12.dp) {
    val p = LocalPal.current
    val anim by animateFloatAsState((pct.coerceIn(0, 100)) / 100f, label = "ring")
    Box(Modifier.size(size).semantics { contentDescription = "$pct percent done" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
            drawArc(p.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            drawArc(if (pct >= 100) p.good else p.accent, -90f, 360f * anim, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (expected != null && expected in 1..99) {
                val a = Math.toRadians(expected * 3.6 - 90).toFloat()
                val r = this.size.width / 2 - inset
                val c = Offset(this.size.width / 2, this.size.height / 2)
                drawLine(
                    p.text.copy(alpha = 0.55f),
                    c + Offset(cos(a) * (r - sw / 2 - 3), sin(a) * (r - sw / 2 - 3)),
                    c + Offset(cos(a) * (r + sw / 2 + 3), sin(a) * (r + sw / 2 + 3)),
                    strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        Text("$pct%", fontSize = (size.value * 0.22f).sp, fontWeight = FontWeight.Bold, color = p.text)
    }
}

@Composable
fun Bar(value: Double, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val anim by animateFloatAsState(value.toFloat().coerceIn(0f, 1f), label = "bar")
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(p.track)) {
        Box(Modifier.fillMaxWidth(anim).height(8.dp).clip(RoundedCornerShape(99.dp)).background(if (value >= 1) p.good else p.accent))
    }
}

@Composable
fun PaceChip(pace: Pace, text: String = pace.label) {
    val p = LocalPal.current
    val (bg, fg) = when (pace) {
        Pace.AHEAD, Pace.ON_PACE -> p.goodSoft to p.good
        Pace.BEHIND -> p.warnSoft to p.warn
        Pace.NOT_STARTED -> p.surface2 to p.muted
    }
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun GoalRows(plan: Plan, done: Map<String, Double>) {
    val p = LocalPal.current
    plan.goals.forEachIndexed { i, g ->
        if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(p.border))
        GoalRow(g, done[g.id] ?: 0.0)
    }
}

@Composable
private fun GoalRow(g: Goal, v: Double) {
    val p = LocalPal.current
    val f = goalFraction(g, v)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(g.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), color = p.text)
        if (g.isCheck) {
            Text(if (f >= 1) "✓ Done" else "Not yet", color = if (f >= 1) p.good else p.muted, fontWeight = if (f >= 1) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
        } else {
            Text("${fmtValue(v)} / ${fmtValue(g.target)} ${g.unit}".trim(), color = p.muted, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(6.dp))
    Bar(f)
}
