// Copies the built web app (../web/dist) into ./web so Electron loads it from disk (works offline).
const fs = require('fs')
const path = require('path')
const src = path.join(__dirname, '../web/dist')
const dst = path.join(__dirname, 'web')
fs.rmSync(dst, { recursive: true, force: true })
fs.cpSync(src, dst, { recursive: true })
console.log('copied web build ->', dst)
