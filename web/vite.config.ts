/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

// Strict CSP for production builds only (the dev server injects inline scripts for HMR).
// The only remote origin the app talks to is Supabase (REST + realtime websocket).
const csp: Plugin = {
  name: 'daypulse-csp',
  apply: 'build',
  transformIndexHtml: (html) =>
    html.replace(
      '<meta charset="UTF-8" />',
      `<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' https://*.supabase.co wss://*.supabase.co; worker-src 'self'; manifest-src 'self'; base-uri 'self'; form-action 'none'" />`,
    ),
}

// Relative base: the same build works on GitHub Pages (/daypulse/) and inside Electron (file://).
export default defineConfig({
  base: './',
  plugins: [react(), csp],
  server: { port: 5174 },
  test: { include: ['src/**/*.test.ts'] },
})
