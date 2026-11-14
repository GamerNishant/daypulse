package com.nishant.daypulse.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nishant.daypulse.MainActivity
import com.nishant.daypulse.SheetTitle
import com.nishant.daypulse.data.Repo
import com.nishant.daypulse.domain.LogEntry
import com.nishant.daypulse.domain.Pace
import com.nishant.daypulse.domain.dayPercent
import com.nishant.daypulse.domain.expectedPercent
import com.nishant.daypulse.domain.paceOf
import com.nishant.daypulse.domain.progressByGoal
import com.nishant.daypulse.domain.today
import com.nishant.daypulse.service.Notifier
import java.time.LocalTime

/**
 * Translucent popup shown when you unlock the phone (or tap a reminder): today's expected output,
 * how far along you are, and a one-tap way to log progress right here — without opening the app.
 */
class CheckInActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LOG = "log"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo.get(this)
        if (repo.session.value == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        repo.refreshAsync()
        Notifier.clearReminder(this)
        val startInLog = intent?.getStringExtra(EXTRA_MODE) == MODE_LOG
        setContent { DayPulseTheme { CheckInContent(repo, startInLog, ::finish, ::openPlan) } }
    }

    private fun openPlan() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("open", "plan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Composable
    private fun CheckInContent(repo: Repo, startInLog: Boolean, close: () -> Unit, openPlan: () -> Unit) {
        val p = LocalPal.current
        val snap by repo.snapshot.collectAsStateWithLifecycle()
        var logging by remember { mutableStateOf(startInLog) }
        val day = today()
        val plan = snap.plans[day]
        val logs = snap.logsFor(day)
        val hour = LocalTime.now().hour
        val greeting = if (hour < 12) "Good morning" else if (hour < 17) "Good afternoon" else "Good evening"

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, null) { close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 720.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(p.surface)
                    .clickable(remember { MutableInteractionSource() }, null) {} // swallow taps
                    .navigationBarsPadding().imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                if (plan == null || plan.goals.isEmpty()) {
                    SheetTitle(greeting, "You haven't set today's expected output yet.")
                    Text("Takes 30 seconds. A clear target makes the hourly check-ins meaningful.", color = p.muted)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = close) { Text("Not now") }
                        Button(onClick = openPlan, modifier = Modifier.weight(1f)) { Text("Plan today") }
                    }
                    return@Column
                }
                if (logging) {
                    SheetTitle("Log progress", "Where are you right now? Totals for today so far.")
                    LogForm(plan, logs, onSave = { values, note ->
                        val current = progressByGoal(logs)
                        repo.addLog(day, plan.goals.map { LogEntry(it.id, values[it.id] ?: current[it.id] ?: 0.0) }, note)
                        Toast.makeText(this@CheckInActivity, "Check-in saved", Toast.LENGTH_SHORT).show()
                        close()
                    }, onCancel = close)
                    Spacer(Modifier.height(16.dp))
                    return@Column
                }
                val pct = dayPercent(plan, logs) ?: 0
                val expected = expectedPercent(snap.settings)
                val pace = paceOf(pct, expected)
                val last = logs.lastOrNull()
                SheetTitle(greeting, if (last != null) "Last check-in ${relTime(last.at)} · ${last.deviceName.ifBlank { last.device }}" else "No check-ins yet today")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(pct, expected, 92.dp, 9.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaceChip(pace, if (pace == Pace.BEHIND) "Behind by ${expected - pct}%" else pace.label)
                        Text("Expected by now: $expected%", color = p.muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Eyebrow("Today's expected output")
                Spacer(Modifier.height(8.dp))
                GoalRows(plan, progressByGoal(logs))
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = close) { Text("Not now") }
                    Button(onClick = { logging = true }, modifier = Modifier.weight(1f)) { Text("Log progress") }
                }
                Text(
                    "Open DayPulse", color = p.accent, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                        .clickable { startActivity(Intent(this@CheckInActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close() },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
