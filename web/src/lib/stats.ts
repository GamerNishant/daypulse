import { addDays, dayOf, minutesNow, minutesOf, weekDays, weekStart } from './dates'
import type { Goal, Log, Plan, Settings } from './types'

/** Current running total per goal: the value from the latest (by time) log that mentions it. */
export function progressByGoal(logs: Log[]): Map<string, number> {
  const latest = new Map<string, { at: string; value: number }>()
  for (const log of logs) {
    if (log.deleted) continue
    for (const e of log.entries) {
      const prev = latest.get(e.goalId)
      if (!prev || log.at >= prev.at) latest.set(e.goalId, { at: log.at, value: e.value })
    }
  }
  return new Map([...latest].map(([k, v]) => [k, v.value]))
}

export function goalFraction(goal: Goal, done: number | undefined): number {
  if (!goal.target || goal.target <= 0) return 0
  return Math.max(0, Math.min(1, (done ?? 0) / goal.target))
}

/** Day completion 0–100, or null for a day with no plan (a rest day, not counted). */
export function dayPercent(plan: Plan | undefined, logs: Log[]): number | null {
  if (!plan || plan.goals.length === 0) return null
  const done = progressByGoal(logs)
  const sum = plan.goals.reduce((s, g) => s + goalFraction(g, done.get(g.id)), 0)
  return Math.round((sum / plan.goals.length) * 100)
}

/** Where you "should" be by now if the work were spread evenly across your active hours. */
export function expectedPercent(settings: Settings, now = new Date()): number {
  const start = minutesOf(settings.activeStart)
  const end = minutesOf(settings.activeEnd)
  if (end <= start) return 0
  const t = (minutesNow(now) - start) / (end - start)
  return Math.round(Math.max(0, Math.min(1, t)) * 100)
}

export type Pace = 'ahead' | 'on-pace' | 'behind' | 'not-started'

export function paceOf(actual: number, expected: number): Pace {
  if (expected === 0 && actual === 0) return 'not-started'
  if (actual >= expected + 10) return 'ahead'
  if (actual >= expected - 5) return 'on-pace'
  return 'behind'
}

export function groupLogsByDay(logs: Log[]): Map<string, Log[]> {
  const m = new Map<string, Log[]>()
  for (const l of logs) {
    if (l.deleted) continue
    const arr = m.get(l.day)
    if (arr) arr.push(l)
    else m.set(l.day, [l])
  }
  return m
}

export interface Summary {
  /** Per-day percent (only days that had a plan, up to today). */
  days: Map<string, number>
  dayPct: (day: string) => number | null
  /** Average of planned days in [from, to] (inclusive), or null. */
  rangePct: (from: string, to: string) => number | null
  allTimePct: number | null
  streak: number
  bestStreak: number
}

export function summarize(plans: Map<string, Plan>, logs: Log[], settings: Settings, today = dayOf()): Summary {
  const byDay = groupLogsByDay(logs)
  const days = new Map<string, number>()
  for (const [day, plan] of plans) {
    if (day > today) continue
    const pct = dayPercent(plan, byDay.get(day) ?? [])
    if (pct !== null) days.set(day, pct)
  }
  const avg = (vals: number[]) => (vals.length ? Math.round(vals.reduce((a, b) => a + b, 0) / vals.length) : null)
  const rangePct = (from: string, to: string) => avg([...days].filter(([d]) => d >= from && d <= to).map(([, v]) => v))

  // Streak: consecutive planned days at/above the threshold. Rest days (no plan) don't break it;
  // today only counts once it's hit, so an in-progress today never resets the streak.
  const hit = (d: string) => (days.get(d) ?? -1) >= settings.hitThreshold
  const sorted = [...days.keys()].sort()
  let streak = 0
  for (let i = sorted.length - 1; i >= 0; i--) {
    const d = sorted[i]
    if (d === today && !hit(d)) continue
    if (hit(d)) streak++
    else break
  }
  let bestStreak = 0
  let run = 0
  for (const d of sorted) {
    run = hit(d) ? run + 1 : 0
    bestStreak = Math.max(bestStreak, run)
  }

  return {
    days,
    dayPct: (d) => days.get(d) ?? null,
    rangePct,
    allTimePct: avg([...days.values()]),
    streak,
    bestStreak,
  }
}

export interface WeekStat {
  start: string
  pct: number | null
}

/** The last `n` weeks (oldest first), ending with the week containing `today`. */
export function weeklyTrend(summary: Summary, n: number, today = dayOf()): WeekStat[] {
  const thisWeek = weekStart(today)
  return Array.from({ length: n }, (_, i) => {
    const start = addDays(thisWeek, -7 * (n - 1 - i))
    const days = weekDays(start)
    return { start, pct: summary.rangePct(days[0], days[6]) }
  })
}

/** Pre-fill for a new day: the template if set, otherwise the most recent plan's goals. */
export function suggestGoals(plans: Map<string, Plan>, settings: Settings, day: string): Goal[] {
  if (settings.templateGoals.length) return settings.templateGoals
  const prev = [...plans.keys()].filter((d) => d < day && plans.get(d)!.goals.length).sort().pop()
  return prev ? plans.get(prev)!.goals : []
}

export function fmtValue(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

export function isCheck(g: Goal): boolean {
  return g.target === 1 && !g.unit
}

export function goalLabel(g: Goal): string {
  if (isCheck(g)) return 'yes / no'
  return `${fmtValue(g.target)}${g.unit ? ` ${g.unit}` : ''}`
}
