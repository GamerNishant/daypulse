# DayPulse: Design

DayPulse helps you **set the output you expect from today**, **check in every hour** with how far you've got, and **compare expected against actual** at the end of each day. It also tracks a weekly and an all-time percentage so you can see whether you're improving.

It runs on three platforms that share one account and one dataset:

| Platform | Folder | What it adds |
|---|---|---|
| **Web / PWA** | [web/](web/) | The full app in any browser. Installable as a PWA. Reminds you while it's open. |
| **Windows desktop** | [desktop/](desktop/) | Electron shell around the same web UI. Lives in the tray, **shows today's targets at Windows login and on every unlock/resume**, and sends hourly reminders while you're at the PC. |
| **Android** | [android/](android/) | Native Kotlin/Compose app. **Shows today's targets when you unlock the phone**, sends hourly reminders while you're using the phone, and keeps an always-on "Today x%" notification. |

---

## 1. Features

- **Plan the day**: a few measurable goals (title, target and unit, e.g. *Deep work · 6 hours*). A target of `1` with no unit is a yes/no goal. One tap reuses your default template or your last plan.
- **Check-ins**: each check-in records the **running total** for every goal at that moment (not increments). Steppers, quick chips (25/50/75%/All done) and an optional note. Every check-in can be undone.
- **Pace**: the "expected by now" percentage spreads your targets evenly across your active hours (default 08:00–22:00). It drives the *Ahead / On pace / Behind* chip and the tick on the progress ring.
- **Unlock / login card**: on desktop login, unlock and resume, and on Android unlock, a card shows today's expected output, your progress so far, and two buttons: **Log progress** or **Not now**.
- **Hourly reminders, only on the device you're using**: see §5.
- **Progress tab**: week % with the change from last week, all-time %, streak and best streak (days at or above the "hit" threshold, 80% by default), days hit this week, daily bars with drill-down (expected vs done per goal), a 12-week trend line and a 20-week heatmap.
- **End-of-day reflection**: a text field that appears near the end of your active hours, or once you reach 100%.
- **Settings (synced)**: reminder interval (30/45/60/90/120 min), active hours, hit threshold and default goals. **Per device**: device name; on Android, how often the unlock card may appear; on desktop, start at login.
- **Offline-first**: every client caches everything locally and queues writes until it's back online.

Days without a plan count as **rest days**. They are excluded from averages and don't break a streak.

---

## 2. Tech stack and why

| Layer | Choice | Why |
|---|---|---|
| Backend | **Supabase** (Postgres + Auth + Realtime), the same project as Mind Dump | Already set up, free tier, RLS, realtime. The same account works in both apps. |
| Storage | The existing generic **`planner_docs`** table (one JSON document per row, `kind` + optional `day`) | **No migration needed.** Its RLS locks rows to the owner, and it's already in the realtime publication. DayPulse only touches kinds starting with `pulse_`. |
| Web | React 19 + TypeScript + Vite, plain CSS tokens, `@supabase/supabase-js` | Small bundle, no UI framework to fight. Hand-drawn SVG charts (no chart library). |
| Desktop | Electron 39 + electron-builder (NSIS) | Reuses the web build exactly. Gives tray, login item, `powerMonitor` unlock/resume events, system idle time and native notifications. Tauri was skipped because there is no Rust toolchain on this machine. |
| Android | Kotlin + Jetpack Compose (Material 3), kotlinx.serialization, `HttpURLConnection` | Background work (foreground service, unlock broadcast, overlay popup) needs native code anyway. Having no Supabase SDK keeps the APK at about 1.4 MB. |

---

## 3. Architecture

```mermaid
flowchart LR
  subgraph Supabase
    PD[(planner_docs<br/>RLS: owner only)]
    RT[[Realtime]]
    AUTH[GoTrue auth]
  end
  subgraph Web["web/ (React)"]
    UI[Components] --> ST[Store<br/>cache + outbox]
    ST --> BE[backend.ts]
  end
  subgraph Desktop["desktop/ (Electron)"]
    MAIN[main.cjs<br/>tray, unlock, reminders] <-->|IPC via preload.cjs| UI
  end
  subgraph Android["android/ (Compose)"]
    AUI[Screens + CheckInActivity] --> REPO[Repo<br/>cache + outbox]
    SVC[PulseService<br/>unlock + reminders] --> REPO
    REPO --> API[Api.kt]
  end
  BE <--> PD
  BE <-- changes --- RT
  API <--> PD
  BE --> AUTH
  API --> AUTH
```

### Module boundaries

**web/src/lib/** (no React except `useStore`)
- `types.ts`: domain types, defaults and the `pulse_*` kind names.
- `dates.ts`: local-calendar helpers (`YYYY-MM-DD`, weeks start on Monday).
- `stats.ts`: all calculations: `progressByGoal`, `dayPercent`, `expectedPercent`, `paceOf`, `summarize` (streaks, ranges, all-time), `weeklyTrend` and `suggestGoals`. Pure and unit-tested.
- `ids.ts`: `stableId()`, a deterministic UUID used for per-day and per-user singletons.
- `backend.ts`: the Supabase client, paged fetch, upsert and the realtime subscription.
- `store.ts`: the local-first `Store` class (see §4).
- `platform.ts`: the desktop bridge type, `reminderDue()` (the shared reminder rule), and browser notifications and service worker registration.

**web/src/components/**: `Today`, `LogSheet`, `PlanEditor`, `CheckIn`, `Insights`, `SettingsView`, `Auth` and `ui.tsx` (Sheet, Ring, Bar, Toast).

**desktop/**: `main.cjs` (window, tray, power events, reminder loop), `preload.cjs` (the `window.daypulse` bridge), `reminder.cjs` (a JS copy of the reminder rule), and `copy-web.cjs` (copies `web/dist` into `desktop/web`).

**android/app/src/main/java/com/nishant/daypulse/**
- `domain/`: `Models.kt` (serializable shapes that mirror `types.ts`) and `Stats.kt` (a line-for-line port of `stats.ts`, `dates.ts`, `ids.ts` and `reminderDue`).
- `data/`: `Api.kt` (GoTrue + PostgREST over HTTP) and `Repo.kt` (a process-wide singleton store, the same design as the web `Store`).
- `service/`: `PulseService` (foreground service: unlock receiver and a one-minute reminder loop), `BootReceiver`, and `Notifier` (channels, status and check-in notifications).
- `ui/`: Compose screens, `CheckInActivity` (the translucent unlock/reminder popup) and the theme (same palette as the web CSS tokens).
- `MainActivity.kt`: navigation, sheets and permission bootstrap.

---

## 4. Data model and sync

All rows live in `public.planner_docs` (`id uuid, user_id, kind, day, data jsonb, created_at, updated_at`).

| kind | day | id | data |
|---|---|---|---|
| `pulse_plan` | `YYYY-MM-DD` | `stableId(user, "pulse_plan", day)` | `{ goals: Goal[], review }` |
| `pulse_log` | `YYYY-MM-DD` | random UUID | `{ at, device, deviceName, entries: [{goalId, value}], note?, deleted? }` |
| `pulse_settings` | `null` | `stableId(user, "pulse_settings", "default")` | `{ templateGoals, reminderMinutes, activeStart, activeEnd, hitThreshold }` |

`Goal = { id, title, target, unit }`.

**Key decisions**
- **Check-ins store running totals, and the latest log per goal wins.** Check-ins from two devices never conflict (each one is its own row). Today's state is a pure function of the logs, and the history shows how the day progressed hour by hour.
- **Deterministic ids** for plans and settings (SHA-1 v5-style UUID, identical in TypeScript and Kotlin; a unit test pins the value). If two devices create today's plan, they upsert the *same* row instead of creating duplicates. Last write wins for plan edits.
- **Soft delete** for logs (`deleted: true`), so incremental sync by `updated_at` also sees removals, and Undo is a single write.
- **Local-first store** (web `Store` and Android `Repo`, same algorithm):
  1. Every row is cached locally (web: localStorage; Android: `files/cache-<uid>.json`), so the app opens instantly and works offline.
  2. A write updates the cache at once and is added to a **persisted outbox**. The outbox is flushed in order with `upsert` and retried on reconnect, focus, every 30 s on the web, and every minute or on resume on Android.
  3. Pulls are **incremental** (`updated_at > syncedAt − 2 min`, paged in blocks of 1000). The overlap catches rows that were committed out of order, and merging is idempotent. A pending local write to a row wins over the server copy until it's flushed.
  4. The web also subscribes to Supabase Realtime (INSERT/UPDATE filtered by `user_id`). Each event triggers a pull.

---

## 5. Reminders and the unlock card

**Rule** (`reminderDue`, implemented in `platform.ts`, `reminder.cjs` and `Stats.kt`): inside active hours, remind once `reminderMinutes` have passed since the **latest** of:
- today's last check-in on **any** device,
- today's active start,
- this device's last reminder.

So a check-in on the phone silences the laptop for the next interval, and vice versa.

**"Only on the device you're using"**
- *Desktop*: reminds only if there was keyboard or mouse input in the last 5 minutes (`powerMonitor.getSystemIdleState(300) === 'active'`). If you're away, the reminder waits until you're back.
- *Android*: reminds only while the screen is on **and** unlocked. Before notifying, it asks the server for today's latest check-in so that laptop check-ins count too.
- *Web*: reminds while the tab or PWA is open and notification permission is granted.

**Unlock / login card**
- *Desktop*: shown at app start (including Windows login, since the app registers itself as a login item on first run), on `unlock-screen` and on `resume`, at most once every 10 minutes, and **never while the lock screen is up**. On wake from sleep the PC is usually still locked, so the card waits for the unlock instead of stealing focus from the Windows sign-in box. The window is briefly set always-on-top so it surfaces. **Not now** hides it back to the tray.
- *Android*: `ACTION_USER_PRESENT` is received by the foreground service (it can't be declared in the manifest). If "Display over other apps" is granted, `CheckInActivity` opens directly. Otherwise a heads-up notification carries the same content. It shows inside active hours (or, when there's no plan yet, any time after 5 AM so the morning unlock prompts a plan), with a per-device cooldown (default 30 min; options: 10 min, 30 min, 60 min or never).

---

## 6. Security

- The Supabase **publishable key** is public by design (it's committed in `web/.env` and `BuildConfig`). All access goes through RLS on `planner_docs`, so a signed-in user can read and write only their own rows. There is no service key anywhere.
- The web build ships a strict **Content-Security-Policy**: scripts from `'self'` only, and network access to `*.supabase.co` only. Electron uses `contextIsolation` and `sandbox`, with a minimal preload bridge. Navigation away from `file://` and new windows are sent to the system browser.
- **Password reset** works on every platform without deep links or a hosted DayPulse page. The reset email's link opens Mind Dump's reset page (same Supabase account). Alternatively, the user pastes the link, or a dead page's address, into DayPulse. A throwaway client (web) or direct GoTrue calls (Android) verify it and set the new password, and only then does the app sign in. So a half-reset session never reaches the app, and the flow doesn't depend on the Supabase redirect allowlist. Email delivery still depends on Supabase's mailer, which reaches only team addresses (~2/hour) until custom SMTP is set up. See Mind Dump's README.
- Android: sessions are stored in app-private SharedPreferences, `allowBackup=false`, and no exported components except the launcher and boot receiver. A revoked refresh token signs you out cleanly.
- Data is small and bounded: the table's own checks cap each document at 32 KB and each user at 50k rows.

---

## 7. Extending it

- **New synced field**: add it to `types.ts` and `Models.kt` (Kotlin `@Serializable` defaults keep old rows readable), then use it in the UI. No SQL is needed.
- **New kind of record**: pick a new `pulse_*` kind, add it to `ALL_KINDS` on both platforms, and derive it in `Store.derive()` / `Repo.derive()`.
- **Change a calculation**: change `stats.ts` **and** `Stats.kt` together, and update both test files (`stats.test.ts`, `StatsTest.kt`).
- **Ideas not built yet**: an Android home-screen widget, weekly email or summary, per-goal weights, and goal categories with per-category trends.
