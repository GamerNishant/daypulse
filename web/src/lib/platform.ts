import { inWindow, minutesOf } from './dates'
import type { DeviceKind, Settings } from './types'

/** State the web layer reports to a native shell (Electron) so it can remind at the right time. */
export interface NativeState {
  signedIn: boolean
  hasPlan: boolean
  todayPct: number
  expectedPct: number
  /** ISO time of today's latest check-in on ANY device, or null. */
  lastLogAt: string | null
  reminderMinutes: number
  activeStart: string
  activeEnd: string
  /** One line per goal, for the notification body. */
  goalsText: string
}

export type NativeCommand = 'open-log' | 'checkin' | 'plan'

/** Exposed by the Electron preload script (desktop/preload.cjs). Absent in a browser. */
export interface DesktopBridge {
  platform: 'desktop'
  reportState(s: NativeState): void
  onCommand(cb: (cmd: NativeCommand) => void): void
  hide(): void
  getAutoStart(): Promise<boolean>
  setAutoStart(on: boolean): void
}

declare global {
  interface Window {
    daypulse?: DesktopBridge
  }
}

export const desktop = typeof window !== 'undefined' ? window.daypulse : undefined
export const deviceKind: DeviceKind = desktop ? 'desktop' : 'web'

export function deviceName(): string {
  try {
    const saved = localStorage.getItem('daypulse-device-name')
    if (saved) return saved
  } catch {
    /* ignore */
  }
  if (desktop) return 'Desktop'
  return /Android|iPhone|iPad/i.test(navigator.userAgent) ? 'Phone browser' : 'Browser'
}

export function setDeviceName(name: string) {
  try {
    localStorage.setItem('daypulse-device-name', name.trim())
  } catch {
    /* ignore */
  }
}

/**
 * The reminder rule, shared in spirit by every platform (Electron main.cjs and Android
 * ReminderLogic.kt implement the same thing):
 * inside active hours, remind once `reminderMinutes` have passed since the latest of
 * (today's last check-in on any device, today's active start, the last reminder on this device).
 * A check-in on the phone therefore silences the laptop for the next interval, and vice versa.
 */
export function reminderDue(settings: Pick<Settings, 'reminderMinutes' | 'activeStart' | 'activeEnd'>, lastLogAt: string | null, lastRemindedAt: number, now = new Date()): boolean {
  if (!inWindow(settings.activeStart, settings.activeEnd, now)) return false
  const start = new Date(now)
  start.setHours(0, minutesOf(settings.activeStart), 0, 0)
  const anchor = Math.max(start.getTime(), lastLogAt ? new Date(lastLogAt).getTime() : 0, lastRemindedAt)
  return now.getTime() - anchor >= settings.reminderMinutes * 60_000
}

// ---- browser notifications (web / PWA only; the desktop shell notifies natively) -------------

let swReg: ServiceWorkerRegistration | undefined

export async function registerServiceWorker() {
  if (desktop || !('serviceWorker' in navigator) || location.protocol === 'file:') return
  try {
    swReg = await navigator.serviceWorker.register('./sw.js')
  } catch {
    /* dev server or unsupported */
  }
}

export function notificationsSupported() {
  return !desktop && typeof Notification !== 'undefined'
}

export async function showWebNotification(title: string, body: string) {
  if (!notificationsSupported() || Notification.permission !== 'granted') return
  const opts: NotificationOptions = { body, icon: './icon-192.png', badge: './icon-192.png', tag: 'daypulse-checkin' }
  if (swReg) await swReg.showNotification(title, opts)
  else new Notification(title, opts)
}
