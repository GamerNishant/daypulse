import { useEffect, useRef, type ReactNode } from 'react'

/** Modal sheet: bottom sheet on phones, centred dialog on wider screens. Esc / scrim click closes. */
export function Sheet({
  title,
  subtitle,
  onClose,
  children,
  footer,
  className = '',
}: {
  title: ReactNode
  subtitle?: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  className?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null
    const first = ref.current?.querySelector<HTMLElement>('input, textarea, button.primary')
    first?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      prev?.focus?.()
    }
  }, [onClose])
  return (
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`sheet ${className}`} role="dialog" aria-modal="true" aria-label={typeof title === 'string' ? title : undefined} ref={ref}>
        <div className="sheet-head">
          <div style={{ flex: 1 }}>
            <h2>{title}</h2>
            {subtitle && <div className="small muted">{subtitle}</div>}
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        <div className="sheet-body">{children}</div>
        {footer && <div className="sheet-foot">{footer}</div>}
      </div>
    </div>
  )
}

/** Progress ring with an optional "expected by now" tick. */
export function Ring({ pct, expected, size = 132, stroke = 12 }: { pct: number; expected?: number; size?: number; stroke?: number }) {
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const tick = expected !== undefined ? (expected / 100) * 2 * Math.PI - Math.PI / 2 : null
  const done = pct >= 100
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`${pct}% done`}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--track)" strokeWidth={stroke} />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke={done ? 'var(--good)' : 'var(--accent)'}
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={`${(Math.min(pct, 100) / 100) * c} ${c}`}
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
        style={{ transition: 'stroke-dasharray 0.5s ease' }}
      />
      {tick !== null && expected! > 0 && expected! < 100 && (
        <line
          x1={size / 2 + (r - stroke / 2 - 3) * Math.cos(tick)}
          y1={size / 2 + (r - stroke / 2 - 3) * Math.sin(tick)}
          x2={size / 2 + (r + stroke / 2 + 3) * Math.cos(tick)}
          y2={size / 2 + (r + stroke / 2 + 3) * Math.sin(tick)}
          stroke="var(--text)"
          strokeWidth={2.5}
          strokeLinecap="round"
          opacity={0.55}
        >
          <title>Expected by now: {expected}%</title>
        </line>
      )}
      <text x="50%" y="50%" dominantBaseline="central" textAnchor="middle" className="ring-label num" fill="var(--text)">
        {pct}%
      </text>
    </svg>
  )
}

export function Bar({ value }: { value: number }) {
  return (
    <div className={`bar ${value >= 1 ? 'done' : ''}`}>
      <span style={{ width: `${Math.round(value * 100)}%` }} />
    </div>
  )
}

export function Toast({ text, action, onAction, onDone }: { text: string; action?: string; onAction?: () => void; onDone: () => void }) {
  useEffect(() => {
    const t = window.setTimeout(onDone, 5000)
    return () => window.clearTimeout(t)
  }, [text, onDone])
  return (
    <div className="toast" role="status">
      <span>{text}</span>
      {action && (
        <button
          onClick={() => {
            onAction?.()
            onDone()
          }}
        >
          {action}
        </button>
      )}
    </div>
  )
}
