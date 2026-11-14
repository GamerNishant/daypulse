import { useEffect, useState } from 'react'
import { sb } from '../lib/backend'
import { desktop, deviceName, notificationsSupported, setDeviceName } from '../lib/platform'
import { goalLabel } from '../lib/stats'
import type { Snapshot, Store } from '../lib/store'
import type { Settings } from '../lib/types'

export const RELEASES_URL = 'https://github.com/GamerNishant/daypulse/releases/latest'

export function SettingsView({ snap, store, email, onEditTemplate }: { snap: Snapshot; store: Store; email: string; onEditTemplate: () => void }) {
  const s = snap.settings
  const save = (patch: Partial<Settings>) => void store.saveSettings({ ...s, ...patch })
  const [name, setName] = useState(deviceName())
  const [perm, setPerm] = useState(notificationsSupported() ? Notification.permission : 'denied')
  const [autoStart, setAutoStart] = useState<boolean | null>(null)

  useEffect(() => {
    desktop?.getAutoStart().then(setAutoStart)
  }, [])

  return (
    <div className="stack">
      <div>
        <div className="eyebrow">Settings</div>
        <h1>Make it fit your day</h1>
      </div>

      <section className="card stack">
        <h2>Check-in reminders</h2>
        <div className="settings-group">
          <div className="field">
            <label htmlFor="every">Remind me every</label>
            <select id="every" className="input" value={s.reminderMinutes} onChange={(e) => save({ reminderMinutes: Number(e.target.value) })}>
              {[30, 45, 60, 90, 120].map((m) => (
                <option key={m} value={m}>
                  {m < 60 ? `${m} minutes` : m === 60 ? '1 hour' : `${m / 60} hours`}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="hit">A day counts as “hit” at</label>
            <select id="hit" className="input" value={s.hitThreshold} onChange={(e) => save({ hitThreshold: Number(e.target.value) })}>
              {[60, 70, 80, 90, 100].map((v) => (
                <option key={v} value={v}>
                  {v}%
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="start">Active from</label>
            <input id="start" type="time" className="input" value={s.activeStart} onChange={(e) => e.target.value && save({ activeStart: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="end">Active until</label>
            <input id="end" type="time" className="input" value={s.activeEnd} onChange={(e) => e.target.value && save({ activeEnd: e.target.value })} />
          </div>
        </div>
        <p className="small muted" style={{ margin: 0 }}>
          Reminders only fire on a device you're using, only inside your active hours, and never within{' '}
          {s.reminderMinutes} minutes of a check-in on <em>any</em> of your devices. The “expected by now” pace line spreads your
          targets evenly across these hours. These settings sync to all your devices.
        </p>
        {notificationsSupported() && perm !== 'granted' && (
          <div className="row spread" style={{ flexWrap: 'wrap' }}>
            <span className="small">
              {perm === 'denied' ? 'Notifications are blocked for this site in your browser settings.' : 'Allow notifications so this browser can remind you while DayPulse is open.'}
            </span>
            {perm === 'default' && (
              <button className="btn" onClick={() => Notification.requestPermission().then(setPerm)}>
                Allow notifications
              </button>
            )}
          </div>
        )}
        {desktop && autoStart !== null && (
          <label className="row small" style={{ gap: 8 }}>
            <input
              type="checkbox"
              checked={autoStart}
              onChange={(e) => {
                desktop!.setAutoStart(e.target.checked)
                setAutoStart(e.target.checked)
              }}
            />
            Start DayPulse when I sign in to Windows (shows today's targets at login and unlock)
          </label>
        )}
      </section>

      <section className="card stack">
        <div className="row spread">
          <h2>Default daily goals</h2>
          <button className="btn" onClick={onEditTemplate}>
            Edit
          </button>
        </div>
        {s.templateGoals.length ? (
          <ul className="small" style={{ margin: 0, paddingLeft: 18 }}>
            {s.templateGoals.map((g) => (
              <li key={g.id}>
                {g.title} · <span className="muted">{goalLabel(g)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="small muted" style={{ margin: 0 }}>
            None yet. New days start from your most recent plan instead.
          </p>
        )}
      </section>

      <section className="card stack">
        <h2>This device</h2>
        <div className="field">
          <label htmlFor="dev">Device name (shown on check-ins)</label>
          <input id="dev" className="input" value={name} maxLength={24} onChange={(e) => setName(e.target.value)} onBlur={() => name.trim() && setDeviceName(name)} />
        </div>
      </section>

      {!desktop && (
        <section className="card stack">
          <h2>Get the apps</h2>
          <p className="small muted" style={{ margin: 0 }}>
            The Android and Windows apps show today's targets as soon as you unlock or log on, and remind you every hour even
            when this tab is closed.
          </p>
          <a className="btn" href={RELEASES_URL} target="_blank" rel="noreferrer">
            Download Android APK / Windows installer
          </a>
        </section>
      )}

      <section className="card row spread" style={{ flexWrap: 'wrap' }}>
        <div className="small">
          Signed in as <strong>{email}</strong>
          {snap.pending > 0 && <div className="faint">{snap.pending} change(s) waiting to sync</div>}
        </div>
        <button
          className="btn danger"
          onClick={() => {
            if (snap.pending > 0 && !confirm('Some changes have not synced yet and will be lost. Sign out anyway?')) return
            void sb.auth.signOut()
          }}
        >
          Sign out
        </button>
      </section>
    </div>
  )
}
