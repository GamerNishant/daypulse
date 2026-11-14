import { createClient, type RealtimeChannel } from '@supabase/supabase-js'
import { ALL_KINDS } from './types'

const url = import.meta.env.VITE_SUPABASE_URL as string
const key = import.meta.env.VITE_SUPABASE_ANON_KEY as string

/**
 * One Supabase client per app instance. Its own storage key keeps the DayPulse session
 * separate from Mind Dump's if both ever run on the same origin.
 */
export const sb = createClient(url, key, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false, storageKey: 'daypulse-auth' },
})

/** A raw row of the shared `planner_docs` table (only DayPulse kinds are touched). */
export interface Row {
  id: string
  kind: string
  day: string | null
  data: Record<string, unknown>
  updated_at: string
}

const COLS = 'id,kind,day,data,updated_at'

/** Rows changed after `since` (or all rows), paged past PostgREST's 1000-row cap. */
export async function fetchRows(since: string | null): Promise<Row[]> {
  const PAGE = 1000
  const out: Row[] = []
  for (let from = 0; ; from += PAGE) {
    let q = sb.from('planner_docs').select(COLS).in('kind', ALL_KINDS).order('updated_at').order('id').range(from, from + PAGE - 1)
    if (since) q = q.gt('updated_at', since)
    const { data, error } = await q
    if (error) throw error
    out.push(...(data as Row[]))
    if (!data || data.length < PAGE) return out
  }
}

export async function upsertRow(row: Pick<Row, 'id' | 'kind' | 'day' | 'data'>): Promise<Row> {
  const { data, error } = await sb.from('planner_docs').upsert(row).select(COLS).single()
  if (error) throw error
  return data as Row
}

/** Calls `onChange` whenever any of this user's planner rows change on another device. */
export function subscribe(userId: string, onChange: () => void): () => void {
  const filter = `user_id=eq.${userId}`
  const channel: RealtimeChannel = sb
    .channel(`daypulse-${userId}`)
    .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'planner_docs', filter }, onChange)
    .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'planner_docs', filter }, onChange)
    .subscribe()
  return () => void sb.removeChannel(channel)
}

/** Human message for a Supabase/network error. */
export function errorText(e: unknown): string {
  const msg = (e as { message?: string })?.message ?? String(e)
  if (/fetch|network|Failed to fetch|load failed/i.test(msg)) return 'You appear to be offline.'
  if (/invalid login credentials/i.test(msg)) return 'Wrong email or password.'
  if (/already registered|already exists/i.test(msg)) return 'An account with this email already exists. Try signing in.'
  if (/rate limit|429/i.test(msg)) return 'Too many attempts. Wait a minute and try again.'
  return msg
}
