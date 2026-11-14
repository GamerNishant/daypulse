import { describe, expect, it } from 'vitest'
import { reminderDue } from './platform'

const s = { reminderMinutes: 60, activeStart: '08:00', activeEnd: '22:00' }
const at = (h: number, m = 0) => new Date(2026, 10, 14, h, m)

describe('reminderDue', () => {
  it('stays quiet outside active hours', () => {
    expect(reminderDue(s, null, 0, at(7, 30))).toBe(false)
    expect(reminderDue(s, null, 0, at(22, 30))).toBe(false)
  })
  it('first fires one interval after the active window opens', () => {
    expect(reminderDue(s, null, 0, at(8, 59))).toBe(false)
    expect(reminderDue(s, null, 0, at(9, 0))).toBe(true)
  })
  it('a check-in on any device resets the clock', () => {
    expect(reminderDue(s, at(10, 30).toISOString(), 0, at(11, 15))).toBe(false)
    expect(reminderDue(s, at(10, 30).toISOString(), 0, at(11, 30))).toBe(true)
  })
  it('does not repeat until the next interval after reminding', () => {
    expect(reminderDue(s, null, at(9).getTime(), at(9, 40))).toBe(false)
    expect(reminderDue(s, null, at(9).getTime(), at(10, 0))).toBe(true)
  })
})
