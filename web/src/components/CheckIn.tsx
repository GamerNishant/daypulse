import { relTime } from '../lib/dates'
import { dayPercent, expectedPercent, paceOf, progressByGoal } from '../lib/stats'
import type { Snapshot } from '../lib/store'
import { GoalList } from './Today'
import { Ring, Sheet } from './ui'

/**
 * The card shown when you unlock / log on / start the computer: today's expected output and
 * one obvious choice — log now, or not now.
 */
export function CheckIn({
  snap,
  today,
  onLog,
  onPlan,
  onDismiss,
}: {
  snap: Snapshot
  today: string
  onLog: () => void
  onPlan: () => void
  onDismiss: () => void
}) {
  const plan = snap.plans.get(today)
  const logs = snap.logs.filter((l) => l.day === today && !l.deleted)
  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'

  if (!plan || plan.goals.length === 0) {
    return (
      <Sheet title={greeting} subtitle="You haven't set today's expected output yet." onClose={onDismiss} className="checkin"
        footer={
          <>
            <button className="btn ghost" onClick={onDismiss}>Not now</button>
            <button className="btn primary" style={{ flex: 1 }} onClick={onPlan}>Plan today</button>
          </>
        }
      >
        <p className="muted" style={{ margin: 0 }}>Takes 30 seconds. A clear target makes the hourly check-ins meaningful.</p>
      </Sheet>
    )
  }

  const pct = dayPercent(plan, logs) ?? 0
  const expected = expectedPercent(snap.settings)
  const pace = paceOf(pct, expected)
  const last = logs[logs.length - 1]

  return (
    <Sheet
      title={greeting}
      subtitle={last ? `Last check-in ${relTime(last.at)} · ${last.deviceName || last.device}` : 'No check-ins yet today'}
      onClose={onDismiss}
      className="checkin"
      footer={
        <>
          <button className="btn ghost" onClick={onDismiss}>Not now</button>
          <button className="btn primary" style={{ flex: 1 }} onClick={onLog}>Log progress</button>
        </>
      }
    >
      <div className="row" style={{ gap: 16 }}>
        <Ring pct={pct} expected={expected} size={96} stroke={9} />
        <div className="small">
          <div className={`pill ${pace}`} style={{ marginBottom: 6 }}>
            {pace === 'behind' ? `Behind by ${expected - pct}%` : pace === 'ahead' ? 'Ahead of pace' : pace === 'on-pace' ? 'On pace' : 'Fresh start'}
          </div>
          <div className="muted num">Expected by now: {expected}%</div>
        </div>
      </div>
      <div>
        <div className="eyebrow" style={{ marginBottom: 4 }}>Today's expected output</div>
        <GoalList plan={plan} done={progressByGoal(logs)} />
      </div>
    </Sheet>
  )
}
