import { useMemo, useState } from 'react'
import { dayPercent, fmtValue, goalFraction, isCheck, progressByGoal } from '../lib/stats'
import type { Goal, Log, Plan } from '../lib/types'
import { Bar, Sheet } from './ui'

/** Sensible +/- step for a unit: 0.5 for hours, 1 otherwise, 5 for big counts. */
function stepFor(g: Goal) {
  if (/^h(ou)?rs?$|^hours?$/i.test(g.unit.trim())) return 0.5
  if (g.target >= 100) return 5
  return 1
}

/**
 * Check-in: set each goal's running total for today. Starts at the current value, so an
 * unchanged goal costs nothing. Values are totals, not increments.
 */
export function LogSheet({
  plan,
  logs,
  onSave,
  onClose,
}: {
  plan: Plan
  logs: Log[]
  onSave: (values: Map<string, number>, note: string) => void
  onClose: () => void
}) {
  const current = useMemo(() => progressByGoal(logs), [logs])
  const [values, setValues] = useState(() => new Map(plan.goals.map((g) => [g.id, current.get(g.id) ?? 0])))
  const [note, setNote] = useState('')
  const set = (id: string, v: number) => setValues((m) => new Map(m).set(id, Math.max(0, Math.round(v * 100) / 100)))

  const before = dayPercent(plan, logs) ?? 0
  const after = Math.round((plan.goals.reduce((s, g) => s + goalFraction(g, values.get(g.id)), 0) / plan.goals.length) * 100)
  const changed = plan.goals.some((g) => (values.get(g.id) ?? 0) !== (current.get(g.id) ?? 0))

  return (
    <Sheet
      title="Log progress"
      subtitle="Where are you right now? Enter totals for today so far."
      onClose={onClose}
      footer={
        <>
          <div className="small muted num" style={{ flex: 1, alignSelf: 'center' }}>
            Today {before}% → <strong style={{ color: 'var(--text)' }}>{after}%</strong>
          </div>
          <button className="btn primary" onClick={() => onSave(values, note.trim())} disabled={!changed && !note.trim()}>
            Save check-in
          </button>
        </>
      }
    >
      {plan.goals.map((g) => {
        const v = values.get(g.id) ?? 0
        const step = stepFor(g)
        return (
          <div className="log-goal" key={g.id}>
            <div className="row spread">
              <strong>{g.title}</strong>
              <span className="small muted num">
                {isCheck(g) ? '' : `target ${fmtValue(g.target)} ${g.unit}`}
              </span>
            </div>
            {isCheck(g) ? (
              <div className="toggle" role="group" aria-label={g.title}>
                <button className="chip" aria-pressed={v < 1} onClick={() => set(g.id, 0)}>
                  Not yet
                </button>
                <button className="chip" aria-pressed={v >= 1} onClick={() => set(g.id, 1)}>
                  ✓ Done
                </button>
              </div>
            ) : (
              <>
                <div className="stepper">
                  <button className="icon-btn" onClick={() => set(g.id, v - step)} aria-label={`Decrease ${g.title}`}>
                    −
                  </button>
                  <input
                    className="input num"
                    inputMode="decimal"
                    aria-label={`${g.title} so far`}
                    value={fmtValue(v)}
                    onChange={(e) => {
                      const n = parseFloat(e.target.value)
                      set(g.id, Number.isFinite(n) ? n : 0)
                    }}
                    onFocus={(e) => e.target.select()}
                  />
                  <button className="icon-btn" onClick={() => set(g.id, v + step)} aria-label={`Increase ${g.title}`}>
                    +
                  </button>
                  <span className="small muted">{g.unit}</span>
                </div>
                <div className="chips">
                  {[0.25, 0.5, 0.75, 1].map((f) => {
                    const target = Math.round(g.target * f * 100) / 100
                    return (
                      <button key={f} className="chip" aria-pressed={v === target} onClick={() => set(g.id, target)}>
                        {f === 1 ? 'All done' : `${f * 100}%`}
                      </button>
                    )
                  })}
                </div>
                <Bar value={goalFraction(g, v)} />
              </>
            )}
          </div>
        )
      })}
      <div className="field">
        <label htmlFor="note">Note (optional)</label>
        <input id="note" className="input" placeholder="What are you working on / what's blocking you?" value={note} onChange={(e) => setNote(e.target.value)} />
      </div>
    </Sheet>
  )
}
