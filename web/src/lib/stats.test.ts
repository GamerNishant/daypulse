import { describe, expect, it } from 'vitest'
import { addDays, weekStart } from './dates'
import { uuidFromHash } from './ids'
import { dayPercent, expectedPercent, paceOf, progressByGoal, summarize, weeklyTrend } from './stats'
import { DEFAULT_SETTINGS, type Log, type Plan } from './types'

const g = (id: string, target: number) => ({ id, title: id, target, unit: '' })
const log = (day: string, at: string, entries: [string, number][], deleted = false): Log => ({
  id: at,
  day,
  at: `${day}T${at}:00.000Z`,
  device: 'web',
  deviceName: 'test',
  entries: entries.map(([goalId, value]) => ({ goalId, value })),
  deleted,
})

describe('progress', () => {
  it('uses the latest log per goal, ignoring deleted logs', () => {
    const logs = [
      log('2026-11-10', '09:00', [['a', 1]]),
      log('2026-11-10', '11:00', [['a', 3], ['b', 1]]),
      log('2026-11-10', '12:00', [['a', 9]], true),
    ]
    const p = progressByGoal(logs)
    expect(p.get('a')).toBe(3)
    expect(p.get('b')).toBe(1)
  })

  it('averages capped goal fractions', () => {
    const plan: Plan = { day: '2026-11-10', goals: [g('a', 4), g('b', 1)] }
    expect(dayPercent(plan, [log('2026-11-10', '10:00', [['a', 2]])])).toBe(25)
    expect(dayPercent(plan, [log('2026-11-10', '10:00', [['a', 8], ['b', 1]])])).toBe(100)
    expect(dayPercent(undefined, [])).toBeNull()
    expect(dayPercent({ day: 'x', goals: [] }, [])).toBeNull()
  })
})

describe('pace', () => {
  it('spreads expectation linearly over active hours', () => {
    const s = { ...DEFAULT_SETTINGS, activeStart: '08:00', activeEnd: '20:00' }
    expect(expectedPercent(s, new Date(2026, 10, 10, 7, 0))).toBe(0)
    expect(expectedPercent(s, new Date(2026, 10, 10, 14, 0))).toBe(50)
    expect(expectedPercent(s, new Date(2026, 10, 10, 23, 0))).toBe(100)
    expect(paceOf(40, 50)).toBe('behind')
    expect(paceOf(48, 50)).toBe('on-pace')
    expect(paceOf(70, 50)).toBe('ahead')
  })
})

describe('summary', () => {
  it('computes week %, streaks (rest days skip) and trend', () => {
    const today = '2026-11-14' // Saturday
    const plans = new Map<string, Plan>()
    const logs: Log[] = []
    const set = (day: string, pct: number) => {
      plans.set(day, { day, goals: [g('a', 100)] })
      logs.push(log(day, '10:00', [['a', pct]]))
    }
    set('2026-11-10', 90)
    // 11th: rest day (no plan)
    set('2026-11-12', 85)
    set('2026-11-13', 100)
    set('2026-11-14', 20) // today, in progress
    set('2026-11-03', 50) // last week
    const s = summarize(plans, logs, DEFAULT_SETTINGS, today)
    expect(s.streak).toBe(3)
    expect(s.bestStreak).toBe(3)
    expect(s.rangePct(weekStart(today), today)).toBe(Math.round((90 + 85 + 100 + 20) / 4))
    const trend = weeklyTrend(s, 2, today)
    expect(trend[0]).toEqual({ start: addDays(weekStart(today), -7), pct: 50 })
  })
})

describe('ids', () => {
  it('formats a v5-style uuid', () => {
    expect(uuidFromHash(new Uint8Array(20).fill(0xff))).toBe('ffffffff-ffff-5fff-bfff-ffffffffffff')
  })
})
