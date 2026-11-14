// DayPulse desktop shell.
// - Lives in the system tray; the window hides instead of closing.
// - Shows the check-in card when the app starts (incl. at Windows login) and when the screen is
//   unlocked / the PC resumes from sleep.
// - Fires a check-in reminder every N minutes while you're actually at the computer, skipping it
//   when any of your devices checked in recently (the web layer reports that via IPC).
const { app, BrowserWindow, Tray, Menu, Notification, ipcMain, nativeImage, powerMonitor, shell } = require('electron')
const path = require('path')
const fs = require('fs')
const { reminderDue } = require('./reminder.cjs')

const APP_ID = 'com.nishant.daypulse'
const CHECKIN_COOLDOWN_MS = 10 * 60 * 1000 // don't re-show the unlock card more often than this
const IDLE_LIMIT_S = 5 * 60 // "at the computer" = input within the last 5 minutes

let win = null
let tray = null
let quitting = false
let rendererReady = false
let pendingCommand = null
let lastCheckinShown = 0
let lastRemindedAt = 0
// True while Windows shows the lock screen. Surfacing or focusing a window then steals focus from
// the lock screen's sign-in box, leaving a blank screen until you press Enter a few times.
let locked = false
let state = { signedIn: false, hasPlan: false, todayPct: 0, expectedPct: 0, lastLogAt: null, reminderMinutes: 60, activeStart: '08:00', activeEnd: '22:00', goalsText: '' }

// ---- small persisted prefs --------------------------------------------------------------------

const prefsFile = () => path.join(app.getPath('userData'), 'prefs.json')
function readPrefs() {
  try {
    return JSON.parse(fs.readFileSync(prefsFile(), 'utf8'))
  } catch {
    return {}
  }
}
function writePrefs(p) {
  try {
    fs.writeFileSync(prefsFile(), JSON.stringify({ ...readPrefs(), ...p }))
  } catch {
    /* ignore */
  }
}

function setAutoStart(on) {
  app.setLoginItemSettings({ openAtLogin: on, args: ['--autostart'] })
}

// ---- window -----------------------------------------------------------------------------------

function createWindow() {
  win = new BrowserWindow({
    width: 980,
    height: 780,
    minWidth: 380,
    minHeight: 520,
    show: false,
    title: 'DayPulse',
    backgroundColor: '#0f1115',
    icon: path.join(__dirname, 'assets', 'tray.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      sandbox: true,
      // Keep realtime sync + state reports alive while hidden in the tray.
      backgroundThrottling: false,
    },
  })
  win.loadFile(path.join(__dirname, 'web', 'index.html'))
  win.on('close', (e) => {
    if (!quitting) {
      e.preventDefault()
      win.hide()
    }
  })
  // External links (e.g. downloads) open in the browser, never inside the app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file://')) {
      e.preventDefault()
      shell.openExternal(url)
    }
  })
}

function send(cmd) {
  if (rendererReady) win.webContents.send('command', cmd)
  else pendingCommand = cmd
}

function showWindow(cmd) {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  // Briefly on top so it actually surfaces over whatever you were doing, then behave normally.
  win.setAlwaysOnTop(true)
  win.focus()
  setTimeout(() => win && win.setAlwaysOnTop(false), 1500)
  if (cmd) send(cmd)
}

function isLocked() {
  if (locked) return true
  try {
    return powerMonitor.getSystemIdleState(1) === 'locked'
  } catch {
    return false
  }
}

function showCheckin(reason) {
  if (isLocked()) return // the unlock-screen event will show it once you're at the desktop
  const now = Date.now()
  if (now - lastCheckinShown < CHECKIN_COOLDOWN_MS) return
  lastCheckinShown = now
  console.log('[daypulse] check-in:', reason)
  showWindow('checkin')
}

// ---- tray -------------------------------------------------------------------------------------

function trayTooltip() {
  if (!state.signedIn) return 'DayPulse: sign in to start'
  if (!state.hasPlan) return 'DayPulse: no plan for today yet'
  return `DayPulse: today ${state.todayPct}% (expected ${state.expectedPct}% by now)`
}

function buildTrayMenu() {
  const auto = app.getLoginItemSettings().openAtLogin
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: trayTooltip().replace('DayPulse: ', ''), enabled: false },
      { type: 'separator' },
      { label: 'Log progress…', click: () => showWindow('open-log') },
      { label: 'Plan today…', click: () => showWindow('plan') },
      { label: 'Open DayPulse', click: () => showWindow() },
      { type: 'separator' },
      { label: 'Start at login', type: 'checkbox', checked: auto, click: (i) => { setAutoStart(i.checked); buildTrayMenu() } },
      { label: 'Quit', click: () => { quitting = true; app.quit() } },
    ]),
  )
  tray.setToolTip(trayTooltip())
}

// ---- reminders --------------------------------------------------------------------------------

function userIsPresent() {
  const idle = powerMonitor.getSystemIdleState(IDLE_LIMIT_S)
  return idle === 'active'
}

function tickReminder() {
  if (!state.signedIn) return
  if (!reminderDue(state, lastRemindedAt)) return
  // "Especially on the device that's on": only remind while you're actually using this PC.
  // If you're away, the reminder waits; when you come back it fires (or the unlock card shows).
  if (!userIsPresent()) return
  lastRemindedAt = Date.now()
  const title = state.hasPlan ? `Check in · today ${state.todayPct}%` : 'Plan your day'
  const body = state.hasPlan
    ? `Expected by now: ${state.expectedPct}%. Click to log progress.\n${state.goalsText}`
    : "You haven't set today's expected output yet."
  const n = new Notification({ title, body, icon: path.join(__dirname, 'assets', 'tray.png'), silent: false })
  n.on('click', () => showWindow(state.hasPlan ? 'open-log' : 'plan'))
  n.show()
}

// ---- IPC --------------------------------------------------------------------------------------

ipcMain.on('state', (_e, s) => {
  state = { ...state, ...s }
  if (tray) buildTrayMenu()
})
ipcMain.on('ready', () => {
  rendererReady = true
  if (pendingCommand) {
    win.webContents.send('command', pendingCommand)
    pendingCommand = null
  }
})
ipcMain.on('hide', () => win && win.hide())
ipcMain.handle('autostart:get', () => app.getLoginItemSettings().openAtLogin)
ipcMain.on('autostart:set', (_e, on) => {
  setAutoStart(on)
  if (tray) buildTrayMenu()
})

// ---- lifecycle --------------------------------------------------------------------------------

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => showWindow('checkin'))

  app.whenReady().then(() => {
    app.setAppUserModelId(APP_ID)
    // First run: start at login by default — that's the point of the desktop app.
    const prefs = readPrefs()
    if (!prefs.initialised) {
      if (app.isPackaged) setAutoStart(true)
      writePrefs({ initialised: true })
    }

    createWindow()
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'assets', 'tray.png')))
    tray.on('click', () => showWindow())
    buildTrayMenu()

    // At launch (manual or Windows login): show today's targets.
    win.once('ready-to-show', () => showCheckin('startup'))

    // Never touch the window while the lock screen is up. 'resume' fires before you've signed in,
    // so wait and re-check; when the PC locks on sleep, 'unlock-screen' shows the card instead.
    powerMonitor.on('lock-screen', () => { locked = true })
    powerMonitor.on('suspend', () => { locked = true })
    powerMonitor.on('unlock-screen', () => {
      locked = false
      setTimeout(() => showCheckin('unlock'), 1500)
    })
    powerMonitor.on('resume', () => {
      // No lock-screen event arrives on wake if the PC doesn't lock; trust the OS state after a pause.
      setTimeout(() => {
        locked = powerMonitor.getSystemIdleState(1) === 'locked'
        showCheckin('resume')
      }, 4000)
    })

    setInterval(tickReminder, 30 * 1000)
  })

  app.on('before-quit', () => {
    quitting = true
  })
  app.on('window-all-closed', (e) => e.preventDefault())
}
