package com.nishant.daypulse.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nishant.daypulse.data.Repo
import com.nishant.daypulse.data.Snapshot
import com.nishant.daypulse.domain.Log
import com.nishant.daypulse.domain.Plan
import com.nishant.daypulse.domain.dayPercent
import com.nishant.daypulse.domain.expectedPercent
import com.nishant.daypulse.domain.fmtValue
import com.nishant.daypulse.domain.goalFraction
import com.nishant.daypulse.domain.minutesOf
import com.nishant.daypulse.domain.paceOf
import com.nishant.daypulse.domain.progressByGoal
import com.nishant.daypulse.domain.suggestGoals
import com.nishant.daypulse.service.Notifier
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun fmtTime(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}.getOrDefault("")

fun relTime(iso: String): String {
    val m = runCatching { Duration.between(Instant.parse(iso), Instant.now()).toMinutes() }.getOrDefault(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ${if (m % 60 > 0) "${m % 60} min " else ""}ago"
        else -> "${m / 1440} d ago"
    }
}

@Composable
fun TodayScreen(snap: Snapshot, repo: Repo, today: String, onLog: () -> Unit, onPlan: () -> Unit, toast: (String, String?, (() -> Unit)?) -> Unit) {
    val p = LocalPal.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick

    val plan = snap.plans[today]
    val logs = snap.logsFor(today)
    val dateLabel = LocalDate.parse(today).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Eyebrow("Today")
            Text(dateLabel, style = MaterialTheme.typography.headlineSmall)
        }
        SetupCard()

        if (plan == null || plan.goals.isEmpty()) {
            val suggestion = suggestGoals(snap.plans, snap.settings, today)
            Card {
                Text("🎯", fontSize = 36.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(6.dp))
                Text("What output do you expect from today?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set a few measurable targets like “Deep work · 6 hours”. DayPulse checks in every ${snap.settings.reminderMinutes} minutes and compares at the end of the day.",
                    color = p.muted, fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                if (suggestion.isNotEmpty()) {
                    Button(onClick = {
                        repo.savePlan(Plan(today, suggestion))
                        toast("Planned ${suggestion.size} goals for today", null, null)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Reuse ${if (snap.settings.templateGoals.isNotEmpty()) "my template" else "last plan"} (${suggestion.size})") }
                    OutlinedButton(onClick = onPlan, modifier = Modifier.fillMaxWidth()) { Text("Customise") }
                } else {
                    Button(onClick = onPlan, modifier = Modifier.fillMaxWidth()) { Text("Plan today") }
                }
            }
            return@Column
        }

        val pct = dayPercent(plan, logs) ?: 0
        val expected = expectedPercent(snap.settings)
        val pace = paceOf(pct, expected)
        val done = progressByGoal(logs)
        val last = logs.lastOrNull()

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(pct, expected, 112.dp, 11.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaceChip(pace)
                    Text("Expected by now: $expected%", color = p.muted, fontSize = 13.sp)
                    Text(if (last != null) "Last check-in ${relTime(last.at)} · ${last.deviceName.ifBlank { last.device }}" else "No check-ins yet today", color = p.muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLog, modifier = Modifier.weight(1f)) { Text("Log progress") }
                OutlinedButton(onClick = onPlan) { Text("Edit plan") }
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Expected output", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${plan.goals.count { goalFraction(it, done[it.id]) >= 1 }}/${plan.goals.size} complete", color = p.faint, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            GoalRows(plan, done)
        }

        Card {
            Text("Check-ins", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (logs.isEmpty()) Text("Each check-in records where you are right now. Your first one shows up here.", color = p.muted, fontSize = 13.sp)
            logs.reversed().forEach { l -> TimelineRow(l, plan) { repo.deleteLog(l); toast("Check-in removed", "Undo") { repo.deleteLog(l, false) } } }
        }

        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        if (now >= minutesOf(snap.settings.activeEnd) - 90 || pct >= 100 || !plan.review.isNullOrBlank()) {
            Reflection(plan) { repo.savePlan(plan.copy(review = it)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TimelineRow(l: Log, plan: Plan, onDelete: () -> Unit) {
    val p = LocalPal.current
    val goals = plan.goals.associateBy { it.id }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(fmtTime(l.at), color = p.muted, fontSize = 13.sp, modifier = Modifier.width(64.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                l.entries.mapNotNull { e -> goals[e.goalId]?.let { g -> if (g.isCheck) "${g.title} ${if (e.value >= 1) "✓" else "–"}" else "${g.title} ${fmtValue(e.value)}/${fmtValue(g.target)}" } }.joinToString(" · "),
                fontSize = 14.sp,
            )
            if (!l.note.isNullOrBlank()) Text("“${l.note}”", color = p.muted, fontSize = 13.sp)
            Text(l.deviceName.ifBlank { l.device }, color = p.faint, fontSize = 11.sp)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Remove check-in at ${fmtTime(l.at)}", tint = p.faint) }
    }
}

@Composable
private fun Reflection(plan: Plan, onSave: (String) -> Unit) {
    var text by remember(plan.review) { mutableStateOf(plan.review ?: "") }
    Card {
        Text("End-of-day reflection", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            text, { text = it },
            Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != (plan.review ?: "")) onSave(text) },
            placeholder = { Text("What went well? What got in the way? One thing to change tomorrow?") }, minLines = 3,
        )
        Text("Saved when you leave the field.", color = LocalPal.current.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Lists the permissions that make unlock cards and reminders reliable, with one-tap fixes. */
@Composable
fun SetupCard() {
    val ctx = LocalContext.current
    val p = LocalPal.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ } }
    @Suppress("UNUSED_EXPRESSION") refresh

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val needNotif = !Notifier.canNotify(ctx)
    val needOverlay = !AndroidSettings.canDrawOverlays(ctx)
    val needBattery = !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    var dismissed by remember { mutableStateOf(ctx.getSharedPreferences("daypulse", 0).getBoolean("setupDismissed", false)) }
    if (!(needNotif || needOverlay || needBattery) || (dismissed && !needNotif)) return

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!needNotif) TextButton(onClick = { ctx.getSharedPreferences("daypulse", 0).edit().putBoolean("setupDismissed", true).apply(); dismissed = true }) { Text("Hide") }
        }
        Text("So DayPulse can show today's targets when you unlock and remind you every hour:", color = p.muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (needNotif && Build.VERSION.SDK_INT >= 33) SetupRow("Allow notifications", "Needed for hourly check-ins.") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if (needOverlay) SetupRow("Show on unlock", "Allow “Display over other apps” so the check-in card pops up when you unlock. Without it you get a notification instead.") {
            ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        if (needBattery) SetupRow("Keep reminders running", "Exempt DayPulse from battery optimisation so Android doesn't stop it.") {
            @Suppress("BatteryLife")
            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
    }
}

@Composable
private fun SetupRow(title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(desc, color = LocalPal.current.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) { Text("Allow") }
    }
}
