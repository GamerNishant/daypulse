package com.nishant.daypulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishant.daypulse.domain.Goal
import com.nishant.daypulse.domain.Log
import com.nishant.daypulse.domain.Plan
import com.nishant.daypulse.domain.dayPercent
import com.nishant.daypulse.domain.fmtValue
import com.nishant.daypulse.domain.goalFraction
import com.nishant.daypulse.domain.jsRound
import com.nishant.daypulse.domain.progressByGoal
import java.util.UUID

private fun stepFor(g: Goal) = when {
    g.unit.trim().lowercase() in setOf("h", "hr", "hrs", "hour", "hours") -> 0.5
    g.target >= 100 -> 5.0
    else -> 1.0
}

private fun round2(v: Double) = Math.round(v.coerceAtLeast(0.0) * 100) / 100.0

/**
 * Check-in form: each goal's running total for today (pre-filled with the current value).
 * Used in the main app's bottom sheet and in the unlock / notification popup.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogForm(plan: Plan, logs: List<Log>, onSave: (Map<String, Double>, String) -> Unit, onCancel: () -> Unit) {
    val p = LocalPal.current
    val current = remember(logs) { progressByGoal(logs) }
    val values = remember(plan) { mutableStateMapOf<String, Double>().apply { plan.goals.forEach { put(it.id, current[it.id] ?: 0.0) } } }
    val texts = remember(plan) { mutableStateMapOf<String, String>().apply { plan.goals.forEach { put(it.id, fmtValue(current[it.id] ?: 0.0)) } } }
    var note by remember { mutableStateOf("") }
    fun set(id: String, v: Double) {
        val r = round2(v); values[id] = r; texts[id] = fmtValue(r)
    }
    val before = dayPercent(plan, logs) ?: 0
    val after = jsRound(plan.goals.sumOf { goalFraction(it, values[it.id]) } / plan.goals.size * 100)
    val changed = plan.goals.any { (values[it.id] ?: 0.0) != (current[it.id] ?: 0.0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        plan.goals.forEach { g ->
            val v = values[g.id] ?: 0.0
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface2).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!g.isCheck) Text("target ${fmtValue(g.target)} ${g.unit}".trim(), color = p.muted, fontSize = 13.sp)
                }
                if (g.isCheck) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = v < 1, onClick = { set(g.id, 0.0) }, label = { Text("Not yet") })
                        FilterChip(selected = v >= 1, onClick = { set(g.id, 1.0) }, label = { Text("✓ Done") })
                    }
                } else {
                    val step = stepFor(g)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { set(g.id, v - step) }) { Icon(Icons.Filled.Remove, "Decrease ${g.title}") }
                        OutlinedTextField(
                            value = texts[g.id] ?: "",
                            onValueChange = { t ->
                                texts[g.id] = t
                                values[g.id] = round2(t.toDoubleOrNull() ?: 0.0)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.width(96.dp),
                        )
                        FilledTonalIconButton(onClick = { set(g.id, v + step) }) { Icon(Icons.Filled.Add, "Increase ${g.title}") }
                        Text(g.unit, color = p.muted, fontSize = 13.sp)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.25, 0.5, 0.75, 1.0).forEach { f ->
                            val t = round2(g.target * f)
                            FilterChip(selected = v == t, onClick = { set(g.id, t) }, label = { Text(if (f == 1.0) "All done" else "${(f * 100).toInt()}%") })
                        }
                    }
                    Bar(goalFraction(g, v))
                }
            }
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Note (optional)") }, placeholder = { Text("What are you on / what's blocking you?") }, singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today $before% → ", color = p.muted, fontSize = 14.sp)
            Text("$after%", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { onSave(values.toMap(), note.trim()) }, enabled = changed || note.isNotBlank()) { Text("Save check-in") }
        }
    }
}

private class EditGoal(val id: String, title: String, target: String, unit: String) {
    var title by mutableStateOf(title)
    var target by mutableStateOf(target)
    var unit by mutableStateOf(unit)
}

/** Edit a list of goals (today's plan or the default template). */
@Composable
fun PlanForm(initial: List<Goal>, isTemplate: Boolean, onSave: (List<Goal>, Boolean) -> Unit, onCancel: () -> Unit) {
    val p = LocalPal.current
    val rows = remember {
        mutableStateListOf<EditGoal>().apply {
            if (initial.isEmpty()) add(EditGoal(UUID.randomUUID().toString(), "", "1", "hours"))
            else initial.forEach { add(EditGoal(it.id, it.title, fmtValue(it.target), it.unit)) }
        }
    }
    var alsoTemplate by remember { mutableStateOf(false) }
    val valid = rows.mapNotNull { r ->
        val t = r.target.toDoubleOrNull() ?: 0.0
        if (r.title.isBlank() || t <= 0) null else Goal(r.id, r.title.trim().take(60), t, r.unit.trim().take(16))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Make each goal measurable. For a yes/no task, set target 1 and leave the unit empty.", color = p.muted, fontSize = 13.sp)
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(r.title, { r.title = it }, Modifier.weight(1f), label = { Text(if (i == 0) "Goal (e.g. Deep work)" else "Goal") }, singleLine = true)
                OutlinedTextField(r.target, { r.target = it }, Modifier.width(72.dp), label = { Text("Target") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(r.unit, { r.unit = it }, Modifier.width(84.dp), label = { Text("Unit") }, singleLine = true)
                IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove goal ${i + 1}") }
            }
        }
        OutlinedButton(onClick = { rows.add(EditGoal(UUID.randomUUID().toString(), "", "1", "hours")) }, enabled = rows.size < 12, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add goal")
        }
        if (!isTemplate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(alsoTemplate, { alsoTemplate = it })
                Text("Also use as my default for new days", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(valid, alsoTemplate) }, enabled = valid.isNotEmpty()) {
                Text("Save ${valid.size} goal${if (valid.size == 1) "" else "s"}")
            }
        }
    }
}
