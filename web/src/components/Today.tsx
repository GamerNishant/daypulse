import { useEffect, useState } from 'react'
import { fmtDay, fmtTime, minutesNow, minutesOf, relTime } from '../lib/dates'
import { dayPercent, expectedPercent, fmtValue, goalFraction, isCheck, paceOf, progressByGoal, suggestGoals } from '../lib/stats'
import type { Snapshot, Store } from '../lib/store'
import type { Log, Plan } from '../lib/types'
import { Bar, Ring } from './ui'

const PACE_TEXT = { ahead: 'Ahead of pace', 'on-pace': 'On pace', behind: 'Behind pace', 'not-started': 'Day not started' }

export function Today({
  snap,
  store,
  today,
  onLog,
  onPlan,
  toast,
}: {
  snap: Snapshot
  store: Store
  today: string
  onLog: () => void
  onPlan: () => void
  toast: (text: string, action?: string, onAction?: () => void) => void
}) {
  const [, tick] = useState(0)
  useEffect(() => {
    const t = window.setInterval(() => tick((n) => n + 1), 60_000) // keep "x min ago" / pace fresh
    return () => window.clearInterval(t)
  }, [])

  const plan = snap.plans.get(today)
  const logs = snap.logs.filter((l) => l.day === today && !l.deleted)
  const pct = dayPercent(plan, logs) ?? 0
  const expected = expectedPercent(snap.settings)
  const pace = paceOf(pct, expected)
  const done = progressByGoal(logs)
  const last = logs[logs.length - 1]
  const suggestion = suggestGoals(snap.plans, snap.settings, today)

  if (!plan || plan.goals.length === 0) {
    return (
      <div className="stack">
        <Header today={today} />
        <div className="card empty stack" style={{ alignItems: 'center' }}>
          <div className="big" aria-hidden>🎯</div>
          <h2>What output do you expect from today?</h2>
          <p className="muted" style={{ margin: 0, maxWidth: 420 }}>
            Set a few measurable targets, like “Deep work · 6 hours” or “Questions · 40”. DayPulse will check in with you every{' '}
            {snap.settings.reminderMinutes} minutes and compare at the end of the day.
          </p>
          <div className="row" style={{ flexWrap: 'wrap', justifyContent: 'center' }}>
            {suggestion.length > 0 && (
              <button
                className="btn primary"
                onClick={() => {
                  void store.savePlan({ day: today, goals: suggestion.map((g) => ({ ...g })) })
                  toast(`Planned ${suggestion.length} goals for today`)
                }}
              >
                Reuse {snap.settings.templateGoals.length ? 'my template' : 'last plan'} ({suggestion.length})
              </button>
            )}
            <button className={`btn ${suggestion.length ? '' : 'primary'}`} onClick={onPlan}>
              {suggestion.length ? 'Customise' : 'Plan today'}
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="stack">
      <Header today={today} />

      <section className="card hero" aria-label="Today's progress">
        <Ring pct={pct} expected={expected} />
        <div className="stack" style={{ gap: 10 }}>
          <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
            <span className={`pill ${pace}`}>{PACE_TEXT[pace]}</span>
            <span className="small muted num">Expected by now: {expected}%</span>
          </div>
          <div className="small muted">
            {last ? (
              <>
                Last check-in {relTime(last.at)} on {last.deviceName || last.device}
              </>
            ) : (
              'No check-ins yet today'
            )}
          </div>
          <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
            <button className="btn primary" onClick={onLog}>
              Log progress
            </button>
            <button className="btn ghost" onClick={onPlan}>
              Edit plan
            </button>
          </div>
        </div>
      </section>

      <section className="card" aria-label="Expected output">
        <div className="row spread" style={{ marginBottom: 8 }}>
          <h2>Expected output</h2>
          <span className="small faint num">
            {plan.goals.filter((g) => goalFraction(g, done.get(g.id)) >= 1).length}/{plan.goals.length} complete
          </span>
        </div>
        <GoalList plan={plan} done={done} />
      </section>

      <section className="card" aria-label="Check-ins">
        <h2 style={{ marginBottom: 6 }}>Check-ins</h2>
        {logs.length === 0 ? (
          <p className="small muted" style={{ margin: 0 }}>
            Each check-in records where you are right now. Your first one shows up here.
          </p>
        ) : (
          <Timeline
            logs={[...logs].reverse()}
            plan={plan}
            onDelete={(l) => {
              store.deleteLog(l)
              toast('Check-in removed', 'Undo', () => store.deleteLog(l, false))
            }}
          />
        )}
      </section>

      {(minutesNow() >= minutesOf(snap.settings.activeEnd) - 90 || pct >= 100 || plan.review) && (
        <Reflection plan={plan} onSave={(review) => void store.savePlan({ ...plan, review })} />
      )}
    </div>
  )
}

function Header({ today }: { today: string }) {
  return (
    <div>
      <div className="eyebrow">Today</div>
      <h1>{fmtDay(today)}</h1>
    </div>
  )
}

export function GoalList({ plan, done }: { plan: Plan; done: Map<string, number> }) {
  return (
    <div>
      {plan.goals.map((g) => {
        const v = done.get(g.id) ?? 0
        const f = goalFraction(g, v)
        return (
          <div className="goal" key={g.id}>
            <div className="goal-title">{g.title}</div>
            <div className="goal-val num">
              {isCheck(g) ? (
                f >= 1 ? <span className="check">✓ Done</span> : 'Not yet'
              ) : (
                <>
                  {fmtValue(v)} / {fmtValue(g.target)} {g.unit}
                </>
              )}
            </div>
            <Bar value={f} />
          </div>
        )
      })}
    </div>
  )
}

function Timeline({ logs, plan, onDelete }: { logs: Log[]; plan: Plan; onDelete: (l: Log) => void }) {
  const goals = new Map(plan.goals.map((g) => [g.id, g]))
  return (
    <ul className="timeline">
      {logs.map((l) => (
        <li key={l.id}>
          <time dateTime={l.at}>{fmtTime(l.at)}</time>
          <div className="small">
            <div>
              {l.entries
                .filter((e) => goals.has(e.goalId))
                .map((e) => {
                  const g = goals.get(e.goalId)!
                  return isCheck(g) ? `${g.title} ${e.value >= 1 ? '✓' : '–'}` : `${g.title} ${fmtValue(e.value)}/${fmtValue(g.target)}`
                })
                .join(' · ')}
            </div>
            {l.note && <div className="muted" style={{ marginTop: 2 }}>“{l.note}”</div>}
            <span className="device-tag">{l.deviceName || l.device}</span>
          </div>
          <button className="icon-btn" style={{ width: 32, height: 32, fontSize: 15 }} onClick={() => onDelete(l)} aria-label={`Remove check-in at ${fmtTime(l.at)}`}>
            ×
          </button>
        </li>
      ))}
    </ul>
  )
}

function Reflection({ plan, onSave }: { plan: Plan; onSave: (s: string) => void }) {
  const [text, setText] = useState(plan.review ?? '')
  useEffect(() => setText(plan.review ?? ''), [plan.review])
  return (
    <section className="card stack" style={{ gap: 10 }}>
      <h2>End-of-day reflection</h2>
      <label className="sr-only" htmlFor="review">
        How did today go?
      </label>
      <textarea
        id="review"
        className="input"
        placeholder="What went well? What got in the way? One thing to change tomorrow?"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => text !== (plan.review ?? '') && onSave(text)}
      />
      <div className="small faint">Saved when you click away.</div>
    </section>
  )
}
