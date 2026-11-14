import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { Auth } from './components/Auth'
import { CheckIn } from './components/CheckIn'
import { Insights } from './components/Insights'
import { LogSheet } from './components/LogSheet'
import { PlanEditor } from './components/PlanEditor'
import { SettingsView } from './components/SettingsView'
import { Today } from './components/Today'
import { Toast } from './components/ui'
import { sb } from './lib/backend'
import { dayOf } from './lib/dates'
import { desktop, deviceKind, deviceName, reminderDue, showWebNotification, type NativeCommand } from './lib/platform'
import { dayPercent, expectedPercent, fmtValue, isCheck, progressByGoal, suggestGoals } from './lib/stats'
import { Store, useStore } from './lib/store'

export function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined)

  useEffect(() => {
    sb.auth.getSession().then(({ data }) => setSession(data.session))
    const { data } = sb.auth.onAuthStateChange((_e, s) => setSession(s))
    return () => data.subscription.unsubscribe()
  }, [])

  useEffect(() => {
    if (session === null) desktop?.reportState({ signedIn: false, hasPlan: false, todayPct: 0, expectedPct: 0, lastLogAt: null, reminderMinutes: 60, activeStart: '08:00', activeEnd: '22:00', goalsText: '' })
  }, [session])

  if (session === undefined) return null
  if (!session) return <Auth />
  return <Signed key={session.user.id} userId={session.user.id} email={session.user.email ?? ''} />
}

type Tab = 'today' | 'progress' | 'settings'
type SheetKind = 'log' | 'plan' | 'template' | 'checkin' | null

function Signed({ userId, email }: { userId: string; email: string }) {
  const store = useMemo(() => new Store(userId), [userId])
  useEffect(() => {
    store.start()
    return () => store.stop()
  }, [store])
  const snap = useStore(store)

  const [today, setToday] = useState(dayOf())
  const [tab, setTab] = useState<Tab>('today')
  const [sheet, setSheet] = useState<SheetKind>(() => {
    const p = new URLSearchParams(location.search)
    return p.has('log') ? 'log' : p.has('checkin') ? 'checkin' : null
  })
  const [toast, setToast] = useState<{ text: string; action?: string; onAction?: () => void } | null>(null)
  const notify = useCallback((text: string, action?: string, onAction?: () => void) => setToast({ text, action, onAction }), [])

  // Midnight rollover.
  useEffect(() => {
    const t = window.setInterval(() => setToday(dayOf()), 30_000)
    return () => window.clearInterval(t)
  }, [])

  const plan = snap.plans.get(today)
  const todayLogs = useMemo(() => snap.logs.filter((l) => l.day === today && !l.deleted), [snap.logs, today])
  const lastLogAt = todayLogs.length ? todayLogs[todayLogs.length - 1].at : null

  // Commands from the desktop shell (tray, notification click, unlock) and the service worker.
  const openLog = useCallback(() => {
    setTab('today')
    setSheet(store.getSnapshot().plans.get(dayOf())?.goals.length ? 'log' : 'plan')
  }, [store])
  useEffect(() => {
    desktop?.onCommand((cmd: NativeCommand) => {
      if (cmd === 'open-log') openLog()
      else if (cmd === 'plan') setSheet('plan')
      else if (cmd === 'checkin') setSheet((s) => (s === 'log' || s === 'plan' ? s : 'checkin'))
    })
    const onMsg = (e: MessageEvent) => e.data?.type === 'open-log' && openLog()
    navigator.serviceWorker?.addEventListener('message', onMsg)
    return () => navigator.serviceWorker?.removeEventListener('message', onMsg)
  }, [openLog])

  // Keep the desktop shell informed so it can remind at the right time.
  useEffect(() => {
    if (!desktop || !snap.ready) return
    const done = progressByGoal(todayLogs)
    desktop.reportState({
      signedIn: true,
      hasPlan: !!plan?.goals.length,
      todayPct: dayPercent(plan, todayLogs) ?? 0,
      expectedPct: expectedPercent(snap.settings),
      lastLogAt,
      reminderMinutes: snap.settings.reminderMinutes,
      activeStart: snap.settings.activeStart,
      activeEnd: snap.settings.activeEnd,
      goalsText: (plan?.goals ?? [])
        .map((g) => (isCheck(g) ? `${(done.get(g.id) ?? 0) >= 1 ? '✓' : '○'} ${g.title}` : `${g.title}: ${fmtValue(done.get(g.id) ?? 0)}/${fmtValue(g.target)} ${g.unit}`))
        .join('\n'),
    })
  }, [snap, plan, todayLogs, lastLogAt])

  // Browser / PWA reminders while the app is open (the desktop shell does its own).
  const latest = useRef({ snap, plan, todayLogs, lastLogAt })
  latest.current = { snap, plan, todayLogs, lastLogAt }
  useEffect(() => {
    if (desktop) return
    const t = window.setInterval(() => {
      const { snap, plan, todayLogs, lastLogAt } = latest.current
      const last = Number(localStorage.getItem('daypulse-last-reminded') || 0)
      if (!reminderDue(snap.settings, lastLogAt, last)) return
      localStorage.setItem('daypulse-last-reminded', String(Date.now()))
      const pct = dayPercent(plan, todayLogs)
      void showWebNotification(
        'Time to check in',
        pct === null ? "You haven't planned today yet." : `Today ${pct}% · expected ${expectedPercent(snap.settings)}% by now. Tap to log progress.`,
      )
    }, 60_000)
    return () => window.clearInterval(t)
  }, [])

  const close = useCallback(() => setSheet(null), [])
  const dismissCheckin = useCallback(() => {
    setSheet(null)
    desktop?.hide()
  }, [])

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <img src="./icon.svg" alt="" />
          <span>DayPulse</span>
        </div>
        <nav className="tabs" aria-label="Sections">
          {(['today', 'progress', 'settings'] as Tab[]).map((t) => (
            <button key={t} className="tab" aria-current={tab === t ? 'page' : undefined} onClick={() => setTab(t)}>
              {t === 'today' ? 'Today' : t === 'progress' ? 'Progress' : 'Settings'}
            </button>
          ))}
        </nav>
        <div className={`sync ${snap.status}`} title={snap.pending ? `${snap.pending} change(s) waiting to sync` : undefined}>
          <span className="dot" />
          <span className="label">{snap.status === 'synced' ? 'Synced' : snap.status === 'syncing' ? 'Syncing' : 'Offline'}</span>
        </div>
      </header>

      <main>
        {!snap.ready ? (
          <p className="muted">Loading your days…</p>
        ) : tab === 'today' ? (
          <Today snap={snap} store={store} today={today} onLog={() => setSheet('log')} onPlan={() => setSheet('plan')} toast={notify} />
        ) : tab === 'progress' ? (
          <Insights snap={snap} today={today} />
        ) : (
          <SettingsView snap={snap} store={store} email={email} onEditTemplate={() => setSheet('template')} />
        )}
      </main>

      {snap.ready && sheet === 'checkin' && (
        <CheckIn snap={snap} today={today} onLog={() => setSheet(plan?.goals.length ? 'log' : 'plan')} onPlan={() => setSheet('plan')} onDismiss={dismissCheckin} />
      )}
      {sheet === 'log' && plan && plan.goals.length > 0 && (
        <LogSheet
          plan={plan}
          logs={todayLogs}
          onClose={close}
          onSave={(values, note) => {
            const current = progressByGoal(todayLogs)
            // Record every goal's total, so each check-in is a full snapshot of the day.
            const entries = plan.goals.map((g) => ({ goalId: g.id, value: values.get(g.id) ?? current.get(g.id) ?? 0 }))
            const log = store.addLog(today, entries, note, deviceKind, deviceName())
            setSheet(null)
            notify('Check-in saved', 'Undo', () => store.deleteLog(log))
            if (desktop) window.setTimeout(() => desktop!.hide(), 1200)
          }}
        />
      )}
      {sheet === 'plan' && (
        <PlanEditor
          day={today}
          initial={plan?.goals.length ? plan.goals : suggestGoals(snap.plans, snap.settings, today)}
          onClose={close}
          onSave={(goals, alsoTemplate) => {
            void store.savePlan({ day: today, goals, review: plan?.review })
            if (alsoTemplate) void store.saveSettings({ ...snap.settings, templateGoals: goals })
            setSheet(null)
            notify('Plan saved')
          }}
        />
      )}
      {sheet === 'template' && (
        <PlanEditor
          isTemplate
          initial={snap.settings.templateGoals}
          onClose={close}
          onSave={(goals) => {
            void store.saveSettings({ ...snap.settings, templateGoals: goals })
            setSheet(null)
            notify('Default goals saved')
          }}
        />
      )}
      {toast && <Toast {...toast} onDone={() => setToast(null)} />}
    </div>
  )
}
