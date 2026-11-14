import { useSyncExternalStore } from 'react'
import { fetchRows, subscribe, upsertRow, type Row } from './backend'
import { newId, stableId } from './ids'
import {
  DEFAULT_SETTINGS,
  KIND_LOG,
  KIND_PLAN,
  KIND_SETTINGS,
  type DeviceKind,
  type Log,
  type LogEntry,
  type Plan,
  type Settings,
} from './types'

export type SyncStatus = 'synced' | 'syncing' | 'offline'

export interface Snapshot {
  ready: boolean
  plans: Map<string, Plan>
  logs: Log[]
  settings: Settings
  status: SyncStatus
  pending: number
}

type Op = Pick<Row, 'id' | 'kind' | 'day' | 'data'>

/**
 * Local-first sync store for one signed-in user.
 *
 * - Every row is cached in localStorage, so the app opens instantly and works offline.
 * - Writes apply locally at once and go into a persisted outbox that is flushed in order;
 *   failed flushes retry on reconnect / every 30 s.
 * - Remote changes arrive by realtime (any device) + incremental pulls by `updated_at`.
 *   A pending local write for a row always wins over the server copy until it's flushed.
 */
export class Store {
  private rows = new Map<string, Row>()
  private outbox: Op[] = []
  private syncedAt: string | null = null
  private status: SyncStatus = 'syncing'
  private ready = false
  private listeners = new Set<() => void>()
  private snap: Snapshot
  private cleanups: (() => void)[] = []
  private pullTimer: number | undefined
  private flushing = false

  constructor(readonly userId: string) {
    this.load()
    this.snap = this.derive()
  }

  // ---- lifecycle -------------------------------------------------------------------------

  start() {
    void this.pull()
    void this.flush()
    this.cleanups.push(subscribe(this.userId, () => this.schedulePull()))
    const onWake = () => {
      if (document.visibilityState === 'visible') this.schedulePull()
      void this.flush()
    }
    window.addEventListener('focus', onWake)
    window.addEventListener('online', onWake)
    document.addEventListener('visibilitychange', onWake)
    const timer = window.setInterval(() => {
      void this.flush()
      this.schedulePull()
    }, 30_000)
    this.cleanups.push(() => {
      window.removeEventListener('focus', onWake)
      window.removeEventListener('online', onWake)
      document.removeEventListener('visibilitychange', onWake)
      window.clearInterval(timer)
    })
  }

  stop() {
    this.cleanups.forEach((f) => f())
    this.cleanups = []
  }

  // ---- React binding ---------------------------------------------------------------------

  subscribe = (fn: () => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  getSnapshot = () => this.snap

  private emit() {
    this.snap = this.derive()
    this.persist()
    this.listeners.forEach((f) => f())
  }

  private derive(): Snapshot {
    const plans = new Map<string, Plan>()
    const logs: Log[] = []
    let settings = DEFAULT_SETTINGS
    for (const r of this.rows.values()) {
      if (r.kind === KIND_PLAN && r.day) {
        const d = r.data as Partial<Plan>
        plans.set(r.day, { day: r.day, goals: Array.isArray(d.goals) ? d.goals : [], review: d.review })
      } else if (r.kind === KIND_LOG && r.day) {
        const d = r.data as Partial<Log>
        logs.push({
          id: r.id,
          day: r.day,
          at: d.at ?? r.updated_at,
          device: d.device ?? 'web',
          deviceName: d.deviceName ?? '',
          entries: Array.isArray(d.entries) ? d.entries : [],
          note: d.note,
          deleted: d.deleted,
        })
      } else if (r.kind === KIND_SETTINGS) {
        settings = { ...DEFAULT_SETTINGS, ...(r.data as Partial<Settings>) }
      }
    }
    logs.sort((a, b) => a.at.localeCompare(b.at))
    return { ready: this.ready, plans, logs, settings, status: this.status, pending: this.outbox.length }
  }

  // ---- persistence -----------------------------------------------------------------------

  private get cacheKey() {
    return `daypulse-cache-${this.userId}`
  }

  private load() {
    try {
      const raw = localStorage.getItem(this.cacheKey)
      if (!raw) return
      const c = JSON.parse(raw) as { rows: Row[]; outbox: Op[]; syncedAt: string | null }
      c.rows.forEach((r) => this.rows.set(r.id, r))
      this.outbox = c.outbox ?? []
      this.syncedAt = c.syncedAt
      this.ready = true // cached data is good enough to render immediately
    } catch {
      /* corrupt cache: start fresh */
    }
  }

  private persist() {
    try {
      localStorage.setItem(
        this.cacheKey,
        JSON.stringify({ rows: [...this.rows.values()], outbox: this.outbox, syncedAt: this.syncedAt }),
      )
    } catch {
      /* storage full or blocked: the server still has everything */
    }
  }

  // ---- sync ------------------------------------------------------------------------------

  private schedulePull() {
    window.clearTimeout(this.pullTimer)
    this.pullTimer = window.setTimeout(() => void this.pull(), 300)
  }

  async pull() {
    try {
      // Overlap by 2 minutes: rows committed slightly out of order are never missed (merge is idempotent).
      const since = this.syncedAt ? new Date(new Date(this.syncedAt).getTime() - 120_000).toISOString() : null
      const rows = await fetchRows(since)
      const pendingIds = new Set(this.outbox.map((o) => o.id))
      for (const r of rows) {
        if (!pendingIds.has(r.id)) this.rows.set(r.id, r)
        if (!this.syncedAt || r.updated_at > this.syncedAt) this.syncedAt = r.updated_at
      }
      this.ready = true
      this.status = this.outbox.length ? 'syncing' : 'synced'
    } catch {
      this.status = 'offline'
    }
    this.emit()
  }

  private async flush() {
    if (this.flushing || this.outbox.length === 0) return
    this.flushing = true
    this.status = 'syncing'
    this.emit()
    try {
      while (this.outbox.length) {
        const op = this.outbox[0]
        const saved = await upsertRow(op)
        this.outbox.shift()
        // Keep the server's timestamp, unless a newer local edit to the same row is queued.
        if (!this.outbox.some((o) => o.id === op.id)) this.rows.set(saved.id, saved)
      }
      this.status = 'synced'
    } catch {
      this.status = 'offline'
    } finally {
      this.flushing = false
      this.emit()
    }
  }

  private put(op: Op) {
    this.rows.set(op.id, { ...op, updated_at: new Date().toISOString() })
    this.outbox = [...this.outbox.filter((o) => o.id !== op.id || o === this.outbox[0]), op]
    this.emit()
    void this.flush()
  }

  // ---- public writes ---------------------------------------------------------------------

  async savePlan(plan: Plan) {
    const id = await stableId(this.userId, KIND_PLAN, plan.day)
    this.put({ id, kind: KIND_PLAN, day: plan.day, data: { goals: plan.goals, review: plan.review ?? '' } })
  }

  addLog(day: string, entries: LogEntry[], note: string, device: DeviceKind, deviceName: string): Log {
    const log: Log = { id: newId(), day, at: new Date().toISOString(), device, deviceName, entries, note: note || undefined }
    const { id, ...data } = log
    this.put({ id, kind: KIND_LOG, day, data: data as unknown as Record<string, unknown> })
    return log
  }

  deleteLog(log: Log, deleted = true) {
    const { id, ...data } = log
    this.put({ id, kind: KIND_LOG, day: log.day, data: { ...data, deleted } as unknown as Record<string, unknown> })
  }

  async saveSettings(s: Settings) {
    const id = await stableId(this.userId, KIND_SETTINGS, 'default')
    this.put({ id, kind: KIND_SETTINGS, day: null, data: s as unknown as Record<string, unknown> })
  }
}

export function useStore(store: Store): Snapshot {
  return useSyncExternalStore(store.subscribe, store.getSnapshot)
}
