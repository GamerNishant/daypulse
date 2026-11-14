// Same rule as web/src/lib/platform.ts reminderDue() and Android ReminderLogic.kt:
// inside active hours, remind once `reminderMinutes` have passed since the latest of
// (today's last check-in on ANY device, today's active start, this device's last reminder).

function minutesOf(hhmm) {
  const [h, m] = String(hhmm).split(':').map(Number)
  return (h || 0) * 60 + (m || 0)
}

function inWindow(start, end, now) {
  const t = now.getHours() * 60 + now.getMinutes()
  return t >= minutesOf(start) && t < minutesOf(end)
}

function reminderDue(state, lastRemindedAt, now = new Date()) {
  if (!inWindow(state.activeStart, state.activeEnd, now)) return false
  const start = new Date(now)
  start.setHours(0, minutesOf(state.activeStart), 0, 0)
  const anchor = Math.max(start.getTime(), state.lastLogAt ? new Date(state.lastLogAt).getTime() : 0, lastRemindedAt)
  return now.getTime() - anchor >= state.reminderMinutes * 60000
}

module.exports = { reminderDue, inWindow }
