import { useState, type FormEvent } from 'react'
import { errorText, sb } from '../lib/backend'

export function Auth() {
  const [mode, setMode] = useState<'in' | 'up'>('in')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    const fn = mode === 'in' ? sb.auth.signInWithPassword({ email, password }) : sb.auth.signUp({ email, password })
    const { data, error } = await fn
    setBusy(false)
    if (error) return setError(errorText(error))
    if (mode === 'up' && !data.session) setError('Check your inbox to confirm your email, then sign in.')
  }

  return (
    <div className="auth">
      <form className="card stack" onSubmit={submit}>
        <div className="row">
          <img src="./icon.svg" width={40} height={40} alt="" />
          <div>
            <h1>DayPulse</h1>
            <div className="small muted">Plan the day. Check in hourly. Watch yourself improve.</div>
          </div>
        </div>
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
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
        </div>
        {error && <div className="err" role="alert">{error}</div>}
        <button className="btn primary block" disabled={busy}>
          {busy ? 'One moment…' : mode === 'in' ? 'Sign in' : 'Create account'}
        </button>
        <div className="small muted" style={{ textAlign: 'center' }}>
          {mode === 'in' ? 'New here? ' : 'Have an account? '}
          <button type="button" className="link" onClick={() => setMode(mode === 'in' ? 'up' : 'in')}>
            {mode === 'in' ? 'Create an account' : 'Sign in'}
          </button>
        </div>
        <div className="small faint" style={{ textAlign: 'center' }}>
          Use the same account on your phone, laptop and the web, and everything stays in sync.
          Your Mind Dump account works here too.
        </div>
      </form>
    </div>
  )
}
