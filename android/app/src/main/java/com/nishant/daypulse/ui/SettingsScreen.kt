package com.nishant.daypulse.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.nishant.daypulse.data.Repo
import com.nishant.daypulse.data.Snapshot
import com.nishant.daypulse.domain.goalLabel
import com.nishant.daypulse.domain.minutesOf
import com.nishant.daypulse.service.PulseService

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(snap: Snapshot, repo: Repo, email: String, onEditTemplate: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val s = snap.settings
    var deviceName by remember { mutableStateOf(repo.deviceName) }
    var cooldown by remember { mutableIntStateOf(PulseService.unlockCooldown(ctx)) }
    var confirmSignOut by remember { mutableStateOf(false) }

    fun pickTime(current: String, onPick: (String) -> Unit) {
        val m = minutesOf(current)
        TimePickerDialog(ctx, { _, h, min -> onPick("%02d:%02d".format(h, min)) }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Eyebrow("Settings")
            Text("Make it fit your day", style = MaterialTheme.typography.headlineSmall)
        }

        Card {
            Text("Check-in reminders", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Label("Remind me every")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 45, 60, 90, 120).forEach { m ->
                    FilterChip(s.reminderMinutes == m, { repo.saveSettings(s.copy(reminderMinutes = m)) }, { Text(if (m < 60) "$m min" else if (m == 60) "1 h" else "${m / 60.0} h".replace(".0", "")) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Label("Active hours")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickTime(s.activeStart) { repo.saveSettings(s.copy(activeStart = it)) } }) { Text(s.activeStart) }
                Text("to", color = p.muted)
                OutlinedButton(onClick = { pickTime(s.activeEnd) { repo.saveSettings(s.copy(activeEnd = it)) } }) { Text(s.activeEnd) }
            }
            Spacer(Modifier.height(10.dp))
            Label("A day counts as “hit” at")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(60, 70, 80, 90, 100).forEach { v -> FilterChip(s.hitThreshold == v, { repo.saveSettings(s.copy(hitThreshold = v)) }, { Text("$v%") }) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Reminders only fire while you're using this phone, inside active hours, and never within ${s.reminderMinutes} minutes of a check-in on any of your devices. These settings sync everywhere.",
                color = p.muted, fontSize = 13.sp,
            )
        }

        Card {
            Text("When I unlock this phone", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10 to "Every unlock*", 30 to "At most every 30 min", 60 to "At most hourly", 0 to "Never").forEach { (v, label) ->
                    FilterChip(cooldown == v, { cooldown = v; PulseService.setUnlockCooldown(ctx, v) }, { Text(label) })
                }
            }
            Text("Show today's expected output with a “Log progress” button. *At most once per 10 minutes.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Default daily goals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onEditTemplate) { Text("Edit") }
            }
            Spacer(Modifier.height(6.dp))
            if (s.templateGoals.isEmpty()) Text("None yet. New days start from your most recent plan instead.", color = p.muted, fontSize = 13.sp)
            s.templateGoals.forEach { Text("• ${it.title} · ${goalLabel(it)}", fontSize = 14.sp) }
        }

        Card {
            Text("This device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                deviceName, { deviceName = it.take(24) },
                Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && deviceName.isNotBlank()) repo.deviceName = deviceName },
                label = { Text("Device name (shown on check-ins)") }, singleLine = true,
            )
        }

        Card {
            Text("Signed in as", color = p.muted, fontSize = 13.sp)
            Text(email, fontWeight = FontWeight.SemiBold)
            if (snap.pending > 0) Text("${snap.pending} change(s) waiting to sync", color = p.warn, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (!confirmSignOut) {
                TextButton(onClick = { if (snap.pending > 0) confirmSignOut = true else { PulseService.stop(ctx); repo.signOut() } }) { Text("Sign out", color = p.bad) }
            } else {
                Text("Unsynced changes will be lost.", color = p.bad, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { confirmSignOut = false }) { Text("Keep me signed in") }
                    Button(onClick = { PulseService.stop(ctx); repo.signOut() }) { Text("Sign out anyway") }
                }
            }
        }
        Text("DayPulse ${com.nishant.daypulse.BuildConfig.VERSION_NAME}", color = p.faint, fontSize = 12.sp, modifier = Modifier.clickable { repo.refreshAsync() })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Label(t: String) = Text(t, color = LocalPal.current.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
