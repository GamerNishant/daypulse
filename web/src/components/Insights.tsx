import { useMemo, useState } from 'react'
import { addDays, fmtDay, parseDay, weekDays, weekStart } from '../lib/dates'
import { fmtValue, goalFraction, isCheck, progressByGoal, summarize, weeklyTrend } from '../lib/stats'
import type { Snapshot } from '../lib/store'

const SHORT: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' }

export function Insights({ snap, today }: { snap: Snapshot; today: string }) {
  const summary = useMemo(() => summarize(snap.plans, snap.logs, snap.settings, today), [snap, today])
  const [weekOf, setWeekOf] = useState(weekStart(today))
  const [selected, setSelected] = useState<string | null>(null)
  const days = weekDays(weekOf)
  const weekPct = summary.rangePct(days[0], days[6])
  const prevDays = weekDays(addDays(weekOf, -7))
  const prevPct = summary.rangePct(prevDays[0], prevDays[6])
  const delta = weekPct !== null && prevPct !== null ? weekPct - prevPct : null
  const isThisWeek = weekOf === weekStart(today)
  const trend = weeklyTrend(summary, 12, today)
  const threshold = snap.settings.hitThreshold
  const hitDays = days.filter((d) => (summary.dayPct(d) ?? -1) >= threshold).length

  const detailDay = selected && days.includes(selected) ? selected : null

  return (
    <div className="stack">
      <div className="row spread">
        <div>
          <div className="eyebrow">Progress</div>
          <h1>{isThisWeek ? 'This week' : `Week of ${parseDay(weekOf).toLocaleDateString(undefined, SHORT)}`}</h1>
        </div>
        <div className="row" style={{ gap: 6 }}>
          <button className="icon-btn" aria-label="Previous week" onClick={() => setWeekOf(addDays(weekOf, -7))}>‹</button>
          <button className="icon-btn" aria-label="Next week" disabled={isThisWeek} onClick={() => setWeekOf(addDays(weekOf, 7))}>›</button>
        </div>
      </div>

      <div className="tiles">
        <div className="card tile">
          <div className="k">Week</div>
          <div className="v num">{weekPct ?? '–'}{weekPct !== null && '%'}</div>
          {delta !== null && (
            <div className={`small delta num ${delta >= 0 ? 'up' : 'down'}`}>
              {delta >= 0 ? '▲' : '▼'} {Math.abs(delta)}% vs last week
            </div>
          )}
        </div>
        <div className="card tile">
          <div className="k">All time</div>
          <div className="v num">{summary.allTimePct ?? '–'}{summary.allTimePct !== null && '%'}</div>
          <div className="small faint num">{summary.days.size} planned days</div>
        </div>
        <div className="card tile">
          <div className="k">Streak</div>
          <div className="v num">{summary.streak}</div>
          <div className="small faint num">best {summary.bestStreak} · days ≥{threshold}%</div>
        </div>
        <div className="card tile">
          <div className="k">Days hit</div>
          <div className="v num">{hitDays}/{days.filter((d) => summary.dayPct(d) !== null).length}</div>
          <div className="small faint">this week</div>
        </div>
      </div>

      <section className="card">
        <div className="row spread" style={{ marginBottom: 12 }}>
          <h2>Daily completion</h2>
          <span className="small faint">Tap a day for details</span>
        </div>
        <div className="week-bars">
          {days.map((d) => {
            const p = summary.dayPct(d)
            return (
              <button
                key={d}
                className={`wb ${d === today ? 'today' : ''} ${d === detailDay ? 'sel' : ''}`}
                onClick={() => setSelected(d === detailDay ? null : d)}
                aria-label={`${fmtDay(d)}: ${p === null ? 'no plan' : `${p}%`}`}
                disabled={d > today}
              >
                <span className="pct num">{p === null ? '' : `${p}%`}</span>
                <span className="col">
                  <span className={`fill ${p !== null && p >= threshold ? 'hit' : ''}`} style={{ height: `${p ?? 0}%` }} />
                </span>
                <span className="lbl">{parseDay(d).toLocaleDateString(undefined, { weekday: 'narrow' })}</span>
              </button>
            )
          })}
        </div>
        {detailDay && <DayDetail snap={snap} day={detailDay} />}
      </section>

      <section className="card">
        <h2 style={{ marginBottom: 12 }}>12-week trend</h2>
        <TrendChart points={trend.map((t) => t.pct)} labels={trend.map((t) => parseDay(t.start).toLocaleDateString(undefined, SHORT))} />
      </section>

      <section className="card">
        <div className="row spread" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
          <h2>Last 20 weeks</h2>
          <div className="legend">
            less
            {[0, 25, 50, 75, 100].map((v) => (
              <span key={v} className="c" style={{ background: heatColor(v) }} />
            ))}
            more
          </div>
        </div>
        <Heatmap pctOf={summary.dayPct} today={today} />
      </section>
    </div>
  )
}

function DayDetail({ snap, day }: { snap: Snapshot; day: string }) {
  const plan = snap.plans.get(day)
  const logs = snap.logs.filter((l) => l.day === day && !l.deleted)
  if (!plan) return <p className="small muted" style={{ margin: '14px 0 0' }}>{fmtDay(day)}: no plan (rest day).</p>
  const done = progressByGoal(logs)
  return (
    <div style={{ marginTop: 16, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
      <div className="row spread">
        <strong>{fmtDay(day)}</strong>
        <span className="small faint">{logs.length} check-in{logs.length === 1 ? '' : 's'}</span>
      </div>
      <table className="small" style={{ width: '100%', borderCollapse: 'collapse', marginTop: 8 }}>
        <thead>
          <tr className="faint" style={{ textAlign: 'left' }}>
            <th style={{ fontWeight: 550 }}>Goal</th>
            <th style={{ fontWeight: 550 }}>Expected</th>
            <th style={{ fontWeight: 550 }}>Done</th>
            <th style={{ fontWeight: 550, textAlign: 'right' }}>%</th>
          </tr>
        </thead>
        <tbody>
          {plan.goals.map((g) => {
            const v = done.get(g.id) ?? 0
            return (
              <tr key={g.id} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ padding: '6px 0' }}>{g.title}</td>
                <td className="num">{isCheck(g) ? 'yes' : `${fmtValue(g.target)} ${g.unit}`}</td>
                <td className="num">{isCheck(g) ? (v >= 1 ? 'yes' : 'no') : `${fmtValue(v)} ${g.unit}`}</td>
                <td className="num" style={{ textAlign: 'right' }}>{Math.round(goalFraction(g, v) * 100)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {plan.review && <p className="small muted" style={{ marginBottom: 0 }}>“{plan.review}”</p>}
    </div>
  )
}

function TrendChart({ points, labels }: { points: (number | null)[]; labels: string[] }) {
  const W = 600
  const H = 160
  const pad = { l: 30, r: 10, t: 10, b: 24 }
  const x = (i: number) => pad.l + (i / (points.length - 1)) * (W - pad.l - pad.r)
  const y = (v: number) => pad.t + (1 - v / 100) * (H - pad.t - pad.b)
  const segs: string[] = []
  let cur = ''
  points.forEach((p, i) => {
    if (p === null) {
      if (cur) segs.push(cur)
      cur = ''
    } else cur += `${cur ? 'L' : 'M'}${x(i)},${y(p)}`
  })
  if (cur) segs.push(cur)
  if (points.every((p) => p === null)) return <p className="small muted" style={{ margin: 0 }}>Your weekly trend appears after your first planned day.</p>
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Weekly completion trend">
      {[0, 50, 100].map((v) => (
        <g key={v}>
          <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} stroke="var(--border)" strokeDasharray={v === 0 ? '' : '3 4'} />
          <text x={pad.l - 6} y={y(v)} fontSize="11" fill="var(--faint)" textAnchor="end" dominantBaseline="central">{v}</text>
        </g>
      ))}
      {segs.map((d, i) => (
        <path key={i} d={d} fill="none" stroke="var(--accent)" strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" />
      ))}
      {points.map((p, i) =>
        p === null ? null : (
          <circle key={i} cx={x(i)} cy={y(p)} r={i === points.length - 1 ? 5 : 3.5} fill={i === points.length - 1 ? 'var(--accent)' : 'var(--surface)'} stroke="var(--accent)" strokeWidth={2}>
            <title>Week of {labels[i]}: {p}%</title>
          </circle>
        ),
      )}
      {labels.map((l, i) => (i % 3 === 0 || i === labels.length - 1 ? (
        <text key={i} x={x(i)} y={H - 6} fontSize="11" fill="var(--faint)" textAnchor="middle">{i === labels.length - 1 ? 'now' : l}</text>
      ) : null))}
    </svg>
  )
}

function heatColor(p: number | null) {
  if (p === null) return 'var(--track)'
  return `color-mix(in srgb, var(--accent) ${Math.round(15 + (p / 100) * 85)}%, var(--track))`
}

function Heatmap({ pctOf, today }: { pctOf: (d: string) => number | null; today: string }) {
  const start = addDays(weekStart(today), -7 * 19)
  const cells = Array.from({ length: 20 * 7 }, (_, i) => addDays(start, i))
  return (
    <div className="heat" role="img" aria-label="Daily completion heatmap, last 20 weeks">
      {cells.map((d) => {
        const p = d > today ? null : pctOf(d)
        return <span key={d} className="c" style={{ background: heatColor(p), opacity: d > today ? 0.3 : 1 }} title={`${fmtDay(d, SHORT)}: ${p === null ? 'no plan' : `${p}%`}`} />
      })}
    </div>
  )
}
