import { useState, type FormEvent } from 'react'
import { errorText, requestPasswordReset, resetWithLink, sb } from '../lib/backend'

type Mode = 'in' | 'up' | 'forgot' | 'finish'

export function Auth() {
  const [mode, setMode] = useState<Mode>('in')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [link, setLink] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')

  function go(next: Mode) {
    setMode(next)
    setError('')
    setInfo('')
    setPassword('')
    setConfirm('')
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (mode === 'finish') {
      if (!link.trim()) return setError('Paste the link from the email first.')
      if (password.length < 6) return setError('Use at least 6 characters.')
      if (password !== confirm) return setError("Passwords don't match.")
    }
    setBusy(true)
    try {
      if (mode === 'forgot') {
        await requestPasswordReset(email)
        go('finish')
        setInfo(`If an account exists for ${email.trim()}, a reset email is on its way.`)
      } else if (mode === 'finish') {
        await resetWithLink(link, email, password)
        // The auth listener in App swaps this screen for the app.
      } else {
        const fn = mode === 'in' ? sb.auth.signInWithPassword({ email, password }) : sb.auth.signUp({ email, password })
        const { data, error } = await fn
        if (error) throw error
        if (mode === 'up' && !data.session) setError('Check your inbox to confirm your email, then sign in.')
      }
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const title = mode === 'forgot' ? 'Reset password' : mode === 'finish' ? 'Choose a new password' : 'DayPulse'
  const subtitle =
    mode === 'forgot'
      ? "We'll email you a reset link."
      : mode === 'finish'
        ? 'Finish the reset with the link from the email.'
        : 'Plan the day. Check in hourly. Watch yourself improve.'

  return (
    <div className="auth">
      <form className="card stack" onSubmit={submit} noValidate={mode === 'finish'}>
        <div className="row">
          <img src="./icon.svg" width={40} height={40} alt="" />
          <div>
            <h1>{title}</h1>
            <div className="small muted">{subtitle}</div>
          </div>
        </div>

        {mode !== 'finish' && (
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
        )}

        {(mode === 'in' || mode === 'up') && (
          <div className="field">
            <label htmlFor="pw">Password</label>
            <input
              id="pw"
              className="input"
              type="password"
              autoComplete={mode === 'in' ? 'current-password' : 'new-password'}
              minLength={6}
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {mode === 'in' && (
              <div style={{ textAlign: 'right' }}>
                <button type="button" className="link small" onClick={() => go('forgot')}>
                  Forgot password?
                </button>
              </div>
            )}
          </div>
        )}

        {mode === 'finish' && (
          <>
            {info && <div className="small">{info}</div>}
            <div className="small muted">
              Tap the link in the email: it opens Mind Dump's reset page, and the new password works here too. Or long-press the
              email's button, copy the link and paste it below. If the link opens a page that doesn't load, paste that page's
              address instead.
            </div>
            <div className="field">
              <label htmlFor="link">Link from the email</label>
              <input id="link" className="input" autoComplete="off" spellCheck={false} value={link} onChange={(e) => setLink(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="npw">New password</label>
              <input id="npw" className="input" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="cpw">Confirm new password</label>
              <input id="cpw" className="input" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            </div>
          </>
        )}

        {error && <div className="err" role="alert">{error}</div>}
        <button className="btn primary block" disabled={busy}>
          {busy
            ? 'One moment…'
            : mode === 'in'
              ? 'Sign in'
              : mode === 'up'
                ? 'Create account'
                : mode === 'forgot'
                  ? 'Send reset email'
                  : 'Set new password'}
        </button>

        {mode === 'in' || mode === 'up' ? (
          <>
            <div className="small muted" style={{ textAlign: 'center' }}>
              {mode === 'in' ? 'New here? ' : 'Have an account? '}
              <button type="button" className="link" onClick={() => go(mode === 'in' ? 'up' : 'in')}>
                {mode === 'in' ? 'Create an account' : 'Sign in'}
              </button>
            </div>
            <div className="small faint" style={{ textAlign: 'center' }}>
              Use the same account on your phone, laptop and the web, and everything stays in sync.
              Your Mind Dump account works here too.
            </div>
          </>
        ) : (
          <div className="small" style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
            <button type="button" className="link" onClick={() => go('in')}>
              Back to sign in
            </button>
            {mode === 'forgot' ? (
              <button type="button" className="link" onClick={() => go('finish')}>
                I already have a reset email
              </button>
            ) : (
              <button type="button" className="link" onClick={() => go('forgot')}>
                Send another email
              </button>
            )}
          </div>
        )}
      </form>
    </div>
  )
}
