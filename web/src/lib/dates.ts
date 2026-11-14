const pad = (n: number) => String(n).padStart(2, '0')

/** Local calendar day as YYYY-MM-DD. */
export function dayOf(d: Date = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** Parse YYYY-MM-DD as local noon (noon avoids DST edge cases). */
export function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d, 12)
}

export function addDays(day: string, n: number): string {
  const d = parseDay(day)
  d.setDate(d.getDate() + n)
  return dayOf(d)
}

/** Monday of the week containing `day`. */
export function weekStart(day: string): string {
  const dow = (parseDay(day).getDay() + 6) % 7 // Mon=0
  return addDays(day, -dow)
}

export function weekDays(start: string): string[] {
  return Array.from({ length: 7 }, (_, i) => addDays(start, i))
}

/** Minutes since local midnight for "HH:MM". */
export function minutesOf(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number)
  return (h || 0) * 60 + (m || 0)
}

export function minutesNow(d: Date = new Date()): number {
  return d.getHours() * 60 + d.getMinutes()
}

export function fmtDay(day: string, opts: Intl.DateTimeFormatOptions = { weekday: 'long', day: 'numeric', month: 'long' }) {
  return parseDay(day).toLocaleDateString(undefined, opts)
}

export function fmtTime(iso: string) {
  return new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

export function relTime(iso: string, now = Date.now()): string {
  const m = Math.round((now - new Date(iso).getTime()) / 60000)
  if (m < 1) return 'just now'
  if (m < 60) return `${m} min ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} h ${m % 60 ? `${m % 60} min ` : ''}ago`
  return `${Math.floor(h / 24)} d ago`
}

/** True when the local clock is inside the [start, end) "HH:MM" window. */
export function inWindow(start: string, end: string, d: Date = new Date()): boolean {
  const t = minutesNow(d)
  return t >= minutesOf(start) && t < minutesOf(end)
}
