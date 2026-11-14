import { useState } from 'react'
import { fmtDay } from '../lib/dates'
import { newId } from '../lib/ids'
import type { Goal } from '../lib/types'
import { Sheet } from './ui'

const UNITS = ['hours', 'pages', 'questions', 'tasks', 'chapters', 'km', 'reps']

const blank = (): Goal => ({ id: newId(), title: '', target: 1, unit: 'hours' })

/** Edits a list of goals: today's plan, or the default template (from Settings). */
export function PlanEditor({
  day,
  initial,
  isTemplate = false,
  onSave,
  onClose,
}: {
  day?: string
  initial: Goal[]
  isTemplate?: boolean
  onSave: (goals: Goal[], alsoTemplate: boolean) => void
  onClose: () => void
}) {
  const [goals, setGoals] = useState<Goal[]>(() => (initial.length ? initial.map((g) => ({ ...g })) : [blank()]))
  const [alsoTemplate, setAlsoTemplate] = useState(false)
  const update = (id: string, patch: Partial<Goal>) => setGoals((gs) => gs.map((g) => (g.id === id ? { ...g, ...patch } : g)))
  const valid = goals.filter((g) => g.title.trim() && g.target > 0)

  return (
    <Sheet
      title={isTemplate ? 'Default daily goals' : 'Plan your day'}
      subtitle={isTemplate ? 'These pre-fill each new day. You can still change any day.' : day ? `Expected output for ${fmtDay(day)}` : undefined}
      onClose={onClose}
      footer={
        <>
          <button className="btn ghost" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn primary"
            style={{ flex: 1 }}
            disabled={valid.length === 0}
            onClick={() => onSave(valid.map((g) => ({ ...g, title: g.title.trim(), unit: g.unit.trim() })), alsoTemplate)}
          >
            Save {valid.length} goal{valid.length === 1 ? '' : 's'}
          </button>
        </>
      }
    >
      <div className="small muted">
        Make each goal measurable. For a simple yes/no task, set target <strong>1</strong> and clear the unit.
      </div>
      <div className="plan-row small faint" aria-hidden>
        <span>Goal</span>
        <span>Target</span>
        <span>Unit</span>
        <span />
      </div>
      {goals.map((g, i) => (
        <div className="plan-row" key={g.id}>
          <input
            className="input"
            placeholder={i === 0 ? 'e.g. Deep work' : 'Goal'}
            aria-label={`Goal ${i + 1} name`}
            value={g.title}
            maxLength={60}
            onChange={(e) => update(g.id, { title: e.target.value })}
          />
          <input
            className="input num"
            inputMode="decimal"
            aria-label={`Goal ${i + 1} target`}
            value={Number.isFinite(g.target) ? String(g.target) : ''}
            onChange={(e) => update(g.id, { target: parseFloat(e.target.value) || 0 })}
          />
          <input className="input" list="units" aria-label={`Goal ${i + 1} unit`} value={g.unit} maxLength={16} onChange={(e) => update(g.id, { unit: e.target.value })} />
          <button className="icon-btn" aria-label={`Remove goal ${i + 1}`} onClick={() => setGoals((gs) => gs.filter((x) => x.id !== g.id))}>
            ×
          </button>
        </div>
      ))}
      <datalist id="units">
        {UNITS.map((u) => (
          <option key={u} value={u} />
        ))}
      </datalist>
      <button className="btn" onClick={() => setGoals((gs) => [...gs, blank()])} disabled={goals.length >= 12}>
        + Add goal
      </button>
      {!isTemplate && (
        <label className="row small" style={{ gap: 8 }}>
          <input type="checkbox" checked={alsoTemplate} onChange={(e) => setAlsoTemplate(e.target.checked)} />
          Also use these as my default for new days
        </label>
      )}
    </Sheet>
  )
}
