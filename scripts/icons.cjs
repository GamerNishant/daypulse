// Renders public/icon.svg to the PNG sizes the PWA, Electron and docs need.
// Usage: node scripts/icons.cjs  (uses Playwright's Chromium from MindDump's node_modules)
const path = require('path')
const fs = require('fs')
const { chromium } = require(process.env.PW_PATH || 'playwright')
;(async () => {
  const svg = fs.readFileSync(path.join(__dirname, '../web/public/icon.svg'), 'utf8')
  const browser = await chromium.launch()
  const page = await browser.newPage()
  const out = [
    ['../web/public/icon-192.png', 192],
    ['../web/public/icon-512.png', 512],
    ['../desktop/build/icon.png', 512],
    ['../desktop/assets/tray.png', 32],
  ]
  for (const [file, size] of out) {
    await page.setViewportSize({ width: size, height: size })
    await page.setContent(`<style>html,body{margin:0;background:transparent}</style>${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}`)
    const target = path.join(__dirname, file)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    await page.screenshot({ path: target, omitBackground: true })
    console.log('wrote', file)
  }
  await browser.close()
})()
