/** A measurable thing you expect to get done today, e.g. "Deep work · 6 hours". */
export interface Goal {
  id: string
  title: string
  /** Expected output. 1 with an empty unit means a yes/no goal. */
  target: number
  unit: string
}

/** The expected output for one calendar day (one row per day, deterministic id). */
export interface Plan {
  day: string
  goals: Goal[]
  /** End-of-day reflection. */
  review?: string
}

/** One goal's *running total* at the moment of a check-in (not an increment). */
export interface LogEntry {
  goalId: string
  value: number
}

export type DeviceKind = 'android' | 'desktop' | 'web'

/** A check-in. The latest log per goal is that goal's current progress. */
export interface Log {
  id: string
  day: string
  /** ISO timestamp of the check-in. */
  at: string
  device: DeviceKind
  deviceName: string
  entries: LogEntry[]
  note?: string
  /** Soft delete, so incremental sync sees removals. */
  deleted?: boolean
}

export interface Settings {
  /** Goals that pre-fill a new day's plan. */
  templateGoals: Goal[]
  /** Minutes between check-in reminders. */
  reminderMinutes: number
  /** "HH:MM" local time; reminders only fire inside this window. */
  activeStart: string
  activeEnd: string
  /** A day counts as "hit" at or above this percentage. */
  hitThreshold: number
}

export const DEFAULT_SETTINGS: Settings = {
  templateGoals: [],
  reminderMinutes: 60,
  activeStart: '08:00',
  activeEnd: '22:00',
  hitThreshold: 80,
}

export const KIND_PLAN = 'pulse_plan'
export const KIND_LOG = 'pulse_log'
export const KIND_SETTINGS = 'pulse_settings'
export const ALL_KINDS = [KIND_PLAN, KIND_LOG, KIND_SETTINGS]
