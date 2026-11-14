package com.nishant.daypulse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nishant.daypulse.data.Repo
import com.nishant.daypulse.data.SyncStatus
import com.nishant.daypulse.domain.LogEntry
import com.nishant.daypulse.domain.Plan
import com.nishant.daypulse.domain.progressByGoal
import com.nishant.daypulse.domain.suggestGoals
import com.nishant.daypulse.domain.today
import com.nishant.daypulse.service.Notifier
import com.nishant.daypulse.service.PulseService
import com.nishant.daypulse.ui.DayPulseTheme
import com.nishant.daypulse.ui.LocalPal
import com.nishant.daypulse.ui.LogForm
import com.nishant.daypulse.ui.LoginScreen
import com.nishant.daypulse.ui.PlanForm
import com.nishant.daypulse.ui.ProgressScreen
import com.nishant.daypulse.ui.SettingsScreen
import com.nishant.daypulse.ui.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRequest.value = intent?.getStringExtra("open")
        val repo = Repo.get(this)
        setContent { DayPulseTheme { AppRoot(repo, openRequest.value) { openRequest.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra("open")
    }

    override fun onResume() {
        super.onResume()
        val repo = Repo.get(this)
        if (repo.session.value != null) {
            repo.refreshAsync()
            PulseService.start(this)
        }
        Notifier.clearReminder(this)
    }
}

private enum class Tab { TODAY, PROGRESS, SETTINGS }
private enum class SheetKind { LOG, PLAN, TEMPLATE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(repo: Repo, open: String?, consumeOpen: () -> Unit) {
    val ctx = LocalContext.current
    val session by repo.session.collectAsStateWithLifecycle()
    val snap by repo.snapshot.collectAsStateWithLifecycle()
    val p = LocalPal.current

    Box(Modifier.fillMaxSize().background(p.bg)) {
        val s = session
        if (s == null) {
            LoginScreen(repo)
            return@Box
        }

        // Once signed in: start the background service and ask for notification permission once.
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { PulseService.start(ctx) }
        LaunchedEffect(s.userId) {
            PulseService.start(ctx)
            val prefs = ctx.getSharedPreferences("daypulse", 0)
            if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx) && !prefs.getBoolean("askedNotif", false)) {
                prefs.edit().putBoolean("askedNotif", true).apply()
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        var day by remember { mutableStateOf(today()) }
        LaunchedEffect(Unit) { while (true) { delay(30_000); day = today() } }

        var tab by remember { mutableStateOf(Tab.TODAY) }
        var sheet by remember { mutableStateOf<SheetKind?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val toast: (String, String?, (() -> Unit)?) -> Unit = { msg, action, onAction ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(msg, action, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
            }
        }

        val plan = snap.plans[day]
        LaunchedEffect(open, snap.ready) {
            if (open != null && snap.ready) {
                tab = Tab.TODAY
                sheet = if (open == "log" && plan?.goals?.isNotEmpty() == true) SheetKind.LOG else SheetKind.PLAN
                consumeOpen()
            }
        }

        Scaffold(
            containerColor = p.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(26.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("DayPulse", fontSize = 18.sp)
                        }
                    },
                    actions = {
                        val (c, label) = when (snap.status) {
                            SyncStatus.SYNCED -> p.good to "Synced"
                            SyncStatus.SYNCING -> p.warn to "Syncing"
                            SyncStatus.OFFLINE -> p.bad to "Offline"
                        }
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = p.surface) {
                    NavigationBarItem(tab == Tab.TODAY, { tab = Tab.TODAY }, { Icon(Icons.Filled.Today, null) }, label = { Text("Today") })
                    NavigationBarItem(tab == Tab.PROGRESS, { tab = Tab.PROGRESS }, { Icon(Icons.Filled.Insights, null) }, label = { Text("Progress") })
                    NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (!snap.ready) {
                        Text(if (snap.status == SyncStatus.OFFLINE) "You're offline. Your days will load when you reconnect." else "Loading your days…", color = p.muted)
                    } else when (tab) {
                        Tab.TODAY -> TodayScreen(snap, repo, day, onLog = { sheet = SheetKind.LOG }, onPlan = { sheet = SheetKind.PLAN }, toast = toast)
                        Tab.PROGRESS -> ProgressScreen(snap, day)
                        Tab.SETTINGS -> SettingsScreen(snap, repo, s.email) { sheet = SheetKind.TEMPLATE }
                    }
                }
            }
        }

        if (sheet != null) {
            val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = state, containerColor = p.surface) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
                    val close = { scope.launch { state.hide() }.invokeOnCompletion { sheet = null } }
                    when (sheet) {
                        SheetKind.LOG -> if (plan != null && plan.goals.isNotEmpty()) {
                            SheetTitle("Log progress", "Where are you right now? Enter totals for today so far.")
                            val logs = snap.logsFor(day)
                            LogForm(plan, logs, onSave = { values, note ->
                                val current = progressByGoal(logs)
                                val entries = plan.goals.map { LogEntry(it.id, values[it.id] ?: current[it.id] ?: 0.0) }
                                val log = repo.addLog(day, entries, note)
                                Notifier.clearReminder(ctx)
                                close()
                                toast("Check-in saved", "Undo") { repo.deleteLog(log) }
                            }, onCancel = { close() })
                        }
                        SheetKind.PLAN -> {
                            SheetTitle("Plan your day", "Expected output for today")
                            PlanForm(if (plan?.goals?.isNotEmpty() == true) plan.goals else suggestGoals(snap.plans, snap.settings, day), false, onSave = { goals, alsoTemplate ->
                                repo.savePlan(Plan(day, goals, plan?.review))
                                if (alsoTemplate) repo.saveSettings(snap.settings.copy(templateGoals = goals))
                                close()
                                toast("Plan saved", null, null)
                            }, onCancel = { close() })
                        }
                        SheetKind.TEMPLATE -> {
                            SheetTitle("Default daily goals", "These pre-fill each new day.")
                            PlanForm(snap.settings.templateGoals, true, onSave = { goals, _ ->
                                repo.saveSettings(snap.settings.copy(templateGoals = goals))
                                close()
                                toast("Default goals saved", null, null)
                            }, onCancel = { close() })
                        }
                        null -> {}
                    }
                }
            }
        }
    }
}

@Composable
fun SheetTitle(title: String, sub: String) {
    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
    Text(sub, color = LocalPal.current.muted, fontSize = 13.sp)
    Spacer(Modifier.size(14.dp))
}
