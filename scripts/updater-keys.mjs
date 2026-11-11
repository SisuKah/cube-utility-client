// `npm run keys:updater`: creates the key pair that signs launcher updates (separate from
// the pack key). The private key and its password stay in src-tauri/ (ignored by git) and are
// never printed; the public key goes into tauri.conf.json. Installed launchers only accept
// updates signed with the private key, so keep a backup of both files.
import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

const KEY = 'src-tauri/updater.key'
const PASSWORD = 'src-tauri/updater.key.password'
const CONFIG = 'src-tauri/tauri.conf.json'

if (existsSync(KEY) && !process.argv.includes('--force')) {
  console.error(`${KEY} already exists. With --force it's replaced, and launchers already installed can't update anymore.`)
  process.exit(1)
}

const password = randomBytes(24).toString('base64url')
const tauri = 'node_modules/@tauri-apps/cli/tauri.js'
const result = spawnSync(process.execPath, [tauri, 'signer', 'generate', '--ci', '--force', '-w', KEY, '-p', password], {
  stdio: ['ignore', 'ignore', 'inherit']
})
if (result.status !== 0) process.exit(result.status ?? 1)
writeFileSync(PASSWORD, password)

const publicKey = readFileSync(`${KEY}.pub`, 'utf8').trim()
const config = readFileSync(CONFIG, 'utf8')
if (!/"pubkey": "[^"]*"/.test(config)) {
  console.error(`No "pubkey" in ${CONFIG}. Put this public key there: ${publicKey}`)
  process.exit(1)
}
writeFileSync(CONFIG, config.replace(/"pubkey": "[^"]*"/, `"pubkey": "${publicKey}"`))

console.log(`Saved the updater key to ${KEY} and its password to ${PASSWORD} (both ignored by git).`)
console.log(`Put the public key in ${CONFIG}.`)
console.log('GitHub needs both secrets to sign releases:')
console.log(`  gh secret set TAURI_SIGNING_PRIVATE_KEY < ${KEY}`)
console.log(`  gh secret set TAURI_SIGNING_PRIVATE_KEY_PASSWORD < ${PASSWORD}`)
