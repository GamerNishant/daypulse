package com.nishant.daypulse.service

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nishant.daypulse.data.Repo
import com.nishant.daypulse.domain.minutesOf
import com.nishant.daypulse.domain.reminderDue
import com.nishant.daypulse.domain.today
import com.nishant.daypulse.ui.CheckInActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Long-running foreground service (shows the "Today x%" status notification). It:
 * 1. listens for ACTION_USER_PRESENT (phone unlocked) → shows today's targets (popup or heads-up);
 * 2. every minute, while the screen is on and unlocked, checks the reminder rule and notifies.
 *    Reminders therefore only reach you on a device you're actually using; a check-in on any
 *    device (checked against the server) resets the clock everywhere.
 */
class PulseService : LifecycleService() {
    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PulseService::class.java)) }
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, PulseService::class.java))

        /** How often the unlock card may appear, in minutes (0 = never). Device-local preference. */
        fun unlockCooldown(ctx: Context) = prefs(ctx).getInt("unlockCooldownMin", 30)
        fun setUnlockCooldown(ctx: Context, min: Int) = prefs(ctx).edit().putInt("unlockCooldownMin", min).apply()
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("daypulse-service", Context.MODE_PRIVATE)
    }

    private lateinit var repo: Repo
    private val prefs by lazy { prefs(this) }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) onUnlock()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = Repo.get(this)
        Notifier.createChannels(this)
        ServiceCompat.startForeground(
            this, Notifier.ID_STATUS, Notifier.status(this, repo.snapshot.value, repo.session.value != null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // USER_PRESENT is sent by System UI (not uid 1000), so a NOT_EXPORTED receiver never gets it.
        // It's a protected broadcast — only the system can send it — so EXPORTED is safe here.
        ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)

        lifecycleScope.launch {
            repo.snapshot.combine(repo.session) { s, sess -> s to (sess != null) }.collect { (s, signedIn) -> updateStatus(s, signedIn) }
        }
        lifecycleScope.launch {
            repo.refresh()
            while (true) {
                tick()
                delay(60_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    private fun updateStatus(s: com.nishant.daypulse.data.Snapshot = repo.snapshot.value, signedIn: Boolean = repo.session.value != null) {
        if (!Notifier.canNotify(this)) return
        getSystemService(NotificationManager::class.java).notify(Notifier.ID_STATUS, Notifier.status(this, s, signedIn))
    }

    private fun userIsUsingPhone(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private suspend fun tick() {
        updateStatus() // "expected by now" moves with the clock
        if (repo.session.value == null || !userIsUsingPhone()) return
        val snap = repo.snapshot.value
        val day = today()
        val last = prefs.getLong("lastRemindedAt", 0)
        val localLast = snap.logsFor(day).maxOfOrNull { it.at }
        if (!reminderDue(snap.settings, localLast, last)) return
        // Ask the server too: a check-in on the laptop in the last hour means no ping here.
        val anyLast = repo.latestLogAtAnyDevice(day)
        if (!reminderDue(snap.settings, anyLast, last)) {
            repo.refreshAsync()
            return
        }
        prefs.edit().putLong("lastRemindedAt", System.currentTimeMillis()).apply()
        Notifier.checkIn(this, repo.snapshot.value, "reminder")
    }

    private fun onUnlock() {
        if (repo.session.value == null) return
        val cooldown = unlockCooldown(this)
        if (cooldown <= 0) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastUnlockCard", 0) < cooldown * 60_000L) return
        val snap = repo.snapshot.value
        val t = LocalTime.now().let { it.hour * 60 + it.minute }
        val hasPlan = snap.plans[today()]?.goals?.isNotEmpty() == true
        // Inside active hours; or (no plan yet) any time after 5 AM, so the morning unlock prompts a plan.
        val inHours = t >= minutesOf(snap.settings.activeStart) && t < minutesOf(snap.settings.activeEnd)
        if (!inHours && (hasPlan || t < 5 * 60 || t >= minutesOf(snap.settings.activeEnd))) return
        prefs.edit().putLong("lastUnlockCard", now).apply()
        repo.refreshAsync()
        if (AndroidSettings.canDrawOverlays(this)) {
            // "Display over other apps" lets a foreground service open the card directly.
            startActivity(Intent(this, CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } else {
            Notifier.checkIn(this, snap, "unlock")
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Repo.get(context).session.value != null) PulseService.start(context)
        }
    }
}
