import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }

// Tauri starts this dev server and loads it in the app window (see src-tauri/tauri.conf.json).
// `npm run dev:web` serves the same UI to a normal browser for quick design work.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    // Only the UI is watched: the Rust build in target/ alone is tens of thousands of files.
    watch: { ignored: ['**/src-tauri/**', '**/target/**', '**/crates/**', '**/tools/**', '**/pack/**'] }
  },
  envPrefix: ['VITE_', 'TAURI_ENV_'],
  // WebView2 is Chromium-based, so modern output is fine.
  build: { target: 'chrome120', minify: true }
})
