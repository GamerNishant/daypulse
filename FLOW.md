# DayPulse: Code flow

How the code runs, step by step, on each platform. Read [DESIGN.md](DESIGN.md) first for the overall shape.

---

## 1. Web / PWA

### Startup
1. [web/index.html](web/index.html) loads `/src/main.tsx`. Production builds also get the CSP meta tag, which a plugin in [vite.config.ts](web/vite.config.ts) injects.
2. [main.tsx](web/src/main.tsx) calls `registerServiceWorker()` ([platform.ts](web/src/lib/platform.ts)). It is skipped inside Electron and on `file://`. Then it renders `<App/>`.
3. `App` ([App.tsx](web/src/App.tsx)) reads the Supabase session (`sb.auth.getSession`) and subscribes to auth changes.
   - No session: renders `<Auth/>` ([Auth.tsx](web/src/components/Auth.tsx)), which calls `signInWithPassword` or `signUp`. The auth listener then re-renders.
   - Session: renders `<Signed userId=…/>` (keyed by user id, so switching accounts resets all state).
4. `Signed` creates `new Store(userId)` ([store.ts](web/src/lib/store.ts)):
   - The constructor runs `load()`, which reads `localStorage['daypulse-cache-<uid>']` (rows, outbox, syncedAt). If a cache exists, `ready = true` and the UI renders immediately.
   - `start()` runs `pull()` and `flush()`, opens the realtime subscription (`subscribe()` in [backend.ts](web/src/lib/backend.ts)), and sets listeners for focus, online and visibility, plus a 30 s interval.
5. `useStore(store)` (`useSyncExternalStore`) gives the components the derived `Snapshot { ready, plans, logs, settings, status, pending }`.
6. URL flags: `?log=1` opens the check-in sheet and `?checkin=1` opens the unlock card. The service worker uses the first one when a notification is clicked.

### User actions
| Action | Path |
|---|---|
| Plan today | `Today` empty state → **Reuse template/last plan** calls `store.savePlan()` directly, or **Plan today** opens `PlanEditor` → `onSave` → `store.savePlan()` (and `saveSettings()` if "also use as default" is ticked). |
| Log progress | **Log progress** button → `LogSheet` (values start at `progressByGoal(todayLogs)`) → `onSave` builds a full snapshot of every goal's total → `store.addLog()` → a toast with **Undo** (`deleteLog`). |
| Remove a check-in | `Today` timeline × → `store.deleteLog(log)` (soft delete) → toast with Undo (`deleteLog(log, false)`). |
| Reflection | The `Reflection` textarea saves on blur → `store.savePlan({...plan, review})`. |
| Change settings | `SettingsView` → `store.saveSettings()`. The device name goes to `localStorage['daypulse-device-name']`. |
| Browse progress | `Insights` → `summarize()` / `weeklyTrend()` from [stats.ts](web/src/lib/stats.ts). Tapping a day bar shows `DayDetail`. |

### Write path (all writes)
`savePlan / addLog / deleteLog / saveSettings` → `Store.put(op)`:
1. Puts the row into the local `rows` map with a provisional `updated_at`.
2. Replaces any queued op for the same id (except one currently in flight) and appends `op` to the outbox.
3. `emit()` re-derives the snapshot, persists to localStorage and notifies React.
4. `flush()` sends the outbox in order with `upsertRow()` (`planner_docs.upsert`). On success the op is removed and the row is replaced with the server copy. On failure, status becomes `offline` and the op is retried by the 30 s timer or the `online` and `focus` events.

### Read path
Realtime event, focus, visibility, online, or the 30 s timer → `schedulePull()` (300 ms debounce) → `pull()` → `fetchRows(syncedAt − 2 min)` (paged) → each row is merged unless it has a pending op → `syncedAt` advances → `emit()`.

### Reminders (browser only)
A 60 s interval in `Signed` runs `reminderDue(settings, lastLogAtToday, localStorage['daypulse-last-reminded'])`. When due, it stores the time and calls `showWebNotification()` (via the service worker registration). Clicking the notification sends the `open-log` message to the open tab, or opens `./?log=1`.

### Shutdown and errors
- Sign out: `sb.auth.signOut()` → `App` shows `<Auth/>`, and `Signed` unmounts (`store.stop()` removes listeners and the realtime channel). The cache stays in localStorage for the next sign-in. If writes are still pending, the user confirms first.
- Network errors never throw into the UI. They set `status = 'offline'` (the red dot in the header), and the outbox keeps the data.
- Auth errors are mapped to readable text by `errorText()`.

---

## 2. Windows desktop (Electron)

### Startup
1. `npm start` / `npm run dist` in [desktop/](desktop/) first builds the web app and copies it to `desktop/web` (`copy-web.cjs`).
2. [main.cjs](desktop/main.cjs):
   - `requestSingleInstanceLock()`: a second launch shows the check-in card in the existing instance.
   - `whenReady` sets the AppUserModelID (needed for notifications). On the **first run of the packaged app** it enables start-at-login (`--autostart`).
   - `createWindow()` creates a hidden BrowserWindow with `preload.cjs`, `contextIsolation`, `sandbox` and `backgroundThrottling: false` (so sync and state reports keep running in the tray), then loads `web/index.html`.
   - It creates the tray icon and menu (status line, Log progress…, Plan today…, Open, Start at login, Quit).
   - `ready-to-show` → `showCheckin('startup')`.
   - `powerMonitor` `unlock-screen` / `resume` → `showCheckin()` (10 min cooldown).
   - `setInterval(tickReminder, 30 s)`.
3. The renderer is the web app. `window.daypulse` comes from [preload.cjs](desktop/preload.cjs), so `platform.ts` sees `desktop` and `deviceKind = 'desktop'`. The web reminder loop and service worker are disabled.

### Main ↔ renderer
- Renderer → main: `state` (on every snapshot change, `Signed` sends `{signedIn, hasPlan, todayPct, expectedPct, lastLogAt, reminderMinutes, activeStart, activeEnd, goalsText}`), `ready` (sent when `onCommand` is registered, which flushes any queued command), `hide`, and `autostart:get/set`.
- Main → renderer: `command` = `checkin` | `open-log` | `plan`. Commands sent before the renderer is ready are queued in `pendingCommand`.
- `showWindow(cmd)` restores, shows and focuses the window, makes it always-on-top for 1.5 s, then sends `cmd`. The renderer opens the `CheckIn`, `LogSheet` or `PlanEditor` sheet.
- In the check-in card, **Not now** calls `desktop.hide()`. Saving a check-in hides the window after 1.2 s.

### Reminder tick
`tickReminder()` → returns if signed out → `reminderDue(state, lastRemindedAt)` ([reminder.cjs](desktop/reminder.cjs)) → returns unless `getSystemIdleState(300) === 'active'` → records `lastRemindedAt` → shows a native `Notification`. Clicking it calls `showWindow('open-log')`, or `'plan'` if there is no plan.

### Exit
Closing the window only hides it. **Quit** (tray) or `before-quit` sets `quitting = true`, and then the window really closes. `window-all-closed` is ignored, so the app keeps running in the tray.

---

## 3. Android

### Entry points
| Entry | When |
|---|---|
| `MainActivity` (launcher) | The user opens the app, or taps the status notification (extras `open=plan` to plan). |
| `CheckInActivity` | The unlock card (started by `PulseService`), or a notification action **Log progress** (`mode=log`). |
| `PulseService` | Started by `MainActivity` after sign-in and on every resume, by `BootReceiver` after boot or app update, and restarted by the system (`START_STICKY`). |
| `BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`. Starts the service if signed in. |

### Startup (app open)
1. `MainActivity.onCreate` → `Repo.get(ctx)` (singleton). Its `init` loads the session from SharedPreferences `daypulse/session` and the cache from `files/cache-<uid>.json`, then emits the first `Snapshot`.
2. `AppRoot` collects `repo.session` and `repo.snapshot`.
   - No session: `LoginScreen` → `repo.signIn(email, pw, create)` → `Api.signIn`/`Api.signUp` → `saveSession` → `loadCache` → `refresh()`.
   - Session: `LaunchedEffect(userId)` starts `PulseService` and asks for `POST_NOTIFICATIONS` once.
3. `onResume`: `repo.refreshAsync()` (flush, then pull), start the service, and clear any pending reminder notification.
4. An `open` intent extra (`log` or `plan`) opens the matching bottom sheet once `snap.ready` is true.

### User actions
Same as the web, using the Compose screens in `ui/` (`TodayScreen`, `ProgressScreen`, `SettingsScreen`, with `LogForm` and `PlanForm` in `Forms.kt`). Writes go through `Repo.savePlan / addLog / deleteLog / saveSettings` → `put(op)` → cache + outbox → `flush()` (`Api.upsert`, `Prefer: resolution=merge-duplicates`). The `SetupCard` on Today links to the notification, overlay ("Display over other apps") and battery-optimisation settings.

### Sync internals ([Repo.kt](android/app/src/main/java/com/nishant/daypulse/data/Repo.kt))
- `authed {}` gets a token from `token()`, which refreshes under `authLock` when the token expires in under 60 s. On a 401 it forces one refresh and retries. If the refresh fails with a 4xx, the session is cleared and the user returns to sign-in.
- `pull()` and `flush()` share `syncLock`. The rows, outbox and syncedAt state is guarded by `synchronized(rows)`.
- `latestLogAtAnyDevice(day)` is a cheap server query (`Api.latestLogAt`) used by the reminder check.

### Background service ([PulseService.kt](android/app/src/main/java/com/nishant/daypulse/service/PulseService.kt))
1. `onCreate`: creates the notification channels (`status` low, `checkin` high) and calls `startForeground` with the status notification (type `specialUse`). It registers a runtime receiver for `ACTION_USER_PRESENT`, collects the snapshot and session to update the status notification, and runs `refresh()` followed by a loop of `tick()` every 60 s.
2. `tick()`: refreshes the status notification (the expected % changes with the clock). If signed in **and** `PowerManager.isInteractive && !keyguard.isKeyguardLocked`, it checks `reminderDue` with local data, then with `latestLogAtAnyDevice`. If it's still due, it stores `lastRemindedAt` and calls `Notifier.checkIn(…, "reminder")`.
3. `onUnlock()`: checks the cooldown (`unlockCooldownMin`, a per-device pref) and the active-hours rule. It then stores `lastUnlockCard`, triggers a refresh, and either starts `CheckInActivity` (when the overlay permission is granted) or posts a heads-up `Notifier.checkIn(…, "unlock")`.

### Check-in popup ([CheckInActivity.kt](android/app/src/main/java/com/nishant/daypulse/ui/CheckInActivity.kt))
A translucent bottom card. With no plan it offers **Plan today**, which opens `MainActivity` with `open=plan`. With a plan it shows the ring, pace and goals, plus **Log progress**, which switches to the in-place `LogForm`, saves through `repo.addLog`, shows a toast and closes. **Not now** and tapping outside close it. It's excluded from recents and uses its own task affinity, so it never drags the main app forward.

### Shutdown and errors
- Sign out (Settings): `PulseService.stop()`, then `repo.signOut()` (the server logout is best-effort) and the in-memory state is cleared. The cache file stays on disk.
- Offline: `IOException` sets the status to `OFFLINE` (red dot), and the outbox survives restarts in the cache file.
- If the service is killed, `START_STICKY` and the next app resume or boot restart it. For reliability, the user is prompted to exempt the app from battery optimisation.

---

## 4. Build, test and release

| Task | Command |
|---|---|
| Web dev server | `cd web && npm run dev` (port 5174) |
| Web tests and build | `cd web && npm test && npm run build` |
| Desktop dev | `cd desktop && npm start` (from a VS Code terminal, run `unset ELECTRON_RUN_AS_NODE` first) |
| Windows installer | `cd desktop && npm run dist` → `desktop/dist/DayPulse-Setup-<ver>.exe` |
| Android tests | `cd android && JAVA_HOME=~/.jdks/jdk-21.0.13+9 ./gradlew :app:testDebugUnitTest` |
| Android release APK | `./gradlew :app:assembleRelease`, signed with `android/keystore/` (git-ignored; **keep it**, or installed apps can't be updated) |
| Icons | `PW_PATH=<path to playwright> node scripts/icons.cjs` renders `web/public/icon.svg` to the PWA and Electron PNGs |
| Web deploy | Push to `main` → `.github/workflows/pages.yml` builds `web/` and publishes it to GitHub Pages |
