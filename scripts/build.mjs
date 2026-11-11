// `npm run build`: the release build and installer, with the update files signed. In GitHub
// Actions the updater key comes from secrets; on this PC from src-tauri/updater.key. Without
// a key it still builds, just without the files the self-updater needs.
import { existsSync, readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

const KEY = 'src-tauri/updater.key'
const PASSWORD = 'src-tauri/updater.key.password'

const env = { ...process.env }
const args = ['node_modules/@tauri-apps/cli/tauri.js', 'build', ...process.argv.slice(2)]
if (!env.TAURI_SIGNING_PRIVATE_KEY && existsSync(KEY)) {
  env.TAURI_SIGNING_PRIVATE_KEY = readFileSync(KEY, 'utf8').trim()
  if (existsSync(PASSWORD)) env.TAURI_SIGNING_PRIVATE_KEY_PASSWORD = readFileSync(PASSWORD, 'utf8').trim()
}
if (!env.TAURI_SIGNING_PRIVATE_KEY) {
  console.warn(`No updater key (${KEY} or TAURI_SIGNING_PRIVATE_KEY): building without update files.`)
  args.push('--config', JSON.stringify({ bundle: { createUpdaterArtifacts: false } }))
}

const result = spawnSync(process.execPath, args, { stdio: 'inherit', env })
process.exit(result.status ?? 1)
