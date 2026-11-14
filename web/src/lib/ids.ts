/** Random UUID v4 for new rows (logs, goals). */
export function newId(): string {
  return crypto.randomUUID()
}

/**
 * Deterministic UUID (v5-style, SHA-1) for rows that must be unique per user + key,
 * e.g. one plan per day. Every device computes the same id, so two devices creating
 * today's plan upsert the same row instead of making duplicates.
 * The Android app implements the exact same function (Ids.kt).
 */
export async function stableId(userId: string, kind: string, key: string): Promise<string> {
  const data = new TextEncoder().encode(`${userId}:${kind}:${key}`)
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-1', data))
  return uuidFromHash(bytes)
}

export function uuidFromHash(hash: Uint8Array): string {
  const b = hash.slice(0, 16)
  b[6] = (b[6] & 0x0f) | 0x50
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
