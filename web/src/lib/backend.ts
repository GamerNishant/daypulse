import { createClient, type RealtimeChannel } from '@supabase/supabase-js'
import { parseRecoveryInput } from './recoveryLink'
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

/**
 * Reset emails send the user to Mind Dump's live reset page when they tap the link (same Supabase
 * account, and DayPulse has no hosted web page yet). The DayPulse apps can also finish the reset
 * themselves from a pasted link, see resetWithLink.
 */
export const RESET_REDIRECT = 'https://gamernishant.github.io/mind-dump/reset-password'

export async function requestPasswordReset(email: string): Promise<void> {
  const { error } = await sb.auth.resetPasswordForEmail(email.trim(), { redirectTo: RESET_REDIRECT })
  if (error) throw error
}

/**
 * Finishes a reset from the pasted email link, the address of the page it opened (even a dead
 * localhost page), or a numeric code. A throwaway client verifies it and sets the new password,
 * then the real client signs in with it, so the app never holds a half-reset session.
 */
export async function resetWithLink(link: string, email: string, password: string): Promise<void> {
  const parsed = parseRecoveryInput(link)
  if (!parsed) throw new Error('Paste the whole link from the reset email (or the address of the page it opened).')
  if (parsed.kind === 'error') throw new Error(parsed.message)
  const tmp = createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false, storageKey: 'daypulse-recovery' },
  })
  const verified =
    parsed.kind === 'session'
      ? await tmp.auth.setSession({ access_token: parsed.accessToken, refresh_token: parsed.refreshToken })
      : parsed.kind === 'code'
        ? await tmp.auth.verifyOtp({ email: email.trim(), token: parsed.code, type: 'recovery' })
        : await tmp.auth.verifyOtp({ token_hash: parsed.tokenHash, type: 'recovery' })
  if (verified.error) {
    if (/expired|invalid|not found/i.test(verified.error.message)) throw new Error('This reset link has expired or was already used. Send a new one.')
    throw verified.error
  }
  const { data, error } = await tmp.auth.updateUser({ password })
  if (error) throw error
  const signIn = await sb.auth.signInWithPassword({ email: data.user.email ?? email.trim(), password })
  if (signIn.error) throw signIn.error
}
