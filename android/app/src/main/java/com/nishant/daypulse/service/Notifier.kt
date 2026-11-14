package com.nishant.daypulse.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nishant.daypulse.MainActivity
import com.nishant.daypulse.R
import com.nishant.daypulse.data.Snapshot
import com.nishant.daypulse.domain.dayPercent
import com.nishant.daypulse.domain.expectedPercent
import com.nishant.daypulse.domain.fmtValue
import com.nishant.daypulse.domain.progressByGoal
import com.nishant.daypulse.domain.today
import com.nishant.daypulse.ui.CheckInActivity

object Notifier {
    const val CH_STATUS = "status"
    const val CH_CHECKIN = "checkin"
    const val ID_STATUS = 1
    const val ID_REMINDER = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Today's progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always-on summary of today's progress. Keeps reminders running."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECKIN, "Check-in reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hourly prompts to log progress, and today's targets when you unlock."
            },
        )
    }

    private fun openApp(ctx: Context, open: String?, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (open != null) putExtra("open", open)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Quick check-in popup (log without opening the full app). */
    fun quickLog(ctx: Context, req: Int = 20): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, CheckInActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(CheckInActivity.EXTRA_MODE, CheckInActivity.MODE_LOG)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    data class Summary(val title: String, val body: String, val hasPlan: Boolean, val pct: Int, val expected: Int)

    fun summary(snap: Snapshot, signedIn: Boolean): Summary {
        if (!signedIn) return Summary("DayPulse", "Sign in to start tracking your day", false, 0, 0)
        val day = today()
        val plan = snap.plans[day]
        val expected = expectedPercent(snap.settings)
        if (plan == null || plan.goals.isEmpty()) return Summary("No plan for today yet", "Tap to set today's expected output", false, 0, expected)
        val logs = snap.logsFor(day)
        val pct = dayPercent(plan, logs) ?: 0
        val done = progressByGoal(logs)
        val body = plan.goals.joinToString("\n") { g ->
            val v = done[g.id] ?: 0.0
            if (g.isCheck) "${if (v >= 1) "✓" else "○"} ${g.title}" else "${g.title}: ${fmtValue(v)}/${fmtValue(g.target)} ${g.unit}".trim()
        }
        return Summary("Today $pct% · expected $expected% by now", body, true, pct, expected)
    }

    fun status(ctx: Context, snap: Snapshot, signedIn: Boolean): Notification {
        val s = summary(snap, signedIn)
        return NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.title)
            .setContentText(s.body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.body))
            .setProgress(100, s.pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx, null, 10))
            .apply {
                if (s.hasPlan) addAction(0, "Log progress", quickLog(ctx, 11))
                else if (signedIn) addAction(0, "Plan today", openApp(ctx, "plan", 12))
            }
            .build()
    }

    fun canNotify(ctx: Context) =
        android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Hourly reminder, or (when the app can't draw over other apps) the unlock card as a heads-up. */
    fun checkIn(ctx: Context, snap: Snapshot, reason: String) {
        if (!canNotify(ctx)) return
        val s = summary(snap, true)
        val title = when {
            !s.hasPlan -> "Plan your day"
            reason == "unlock" -> "Today's expected output · ${s.pct}% done"
            else -> "Time to check in · today ${s.pct}%"
        }
        val text = if (s.hasPlan) "Expected by now: ${s.expected}%. Tap to log progress." else "You haven't set today's expected output yet."
        val n = NotificationCompat.Builder(ctx, CH_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (s.hasPlan) "$text\n${s.body}" else text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(50 * 60 * 1000L)
            .setContentIntent(if (s.hasPlan) quickLog(ctx, 21) else openApp(ctx, "plan", 22))
            .apply { if (s.hasPlan) addAction(0, "Log progress", quickLog(ctx, 23)) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_REMINDER, n)
    }

    fun clearReminder(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_REMINDER)
}
