// Run by release-launcher.yml after `npm run build`: publishes the installer, its update
// signature and latest.json as the launcher-v<version> release. Installed launchers read
// releases/latest/download/latest.json, so this release is marked as the latest one (pack
// releases never are).
import { execFileSync } from 'node:child_process'
import { copyFileSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'

function fail(message) {
  console.error(message)
  process.exit(1)
}

const repo = process.env.GITHUB_REPOSITORY ?? fail('GITHUB_REPOSITORY is missing')
const version = JSON.parse(readFileSync('package.json', 'utf8')).version
const tag = `launcher-v${version}`
if (process.env.GITHUB_REF_NAME && process.env.GITHUB_REF_NAME !== tag) {
  fail(`The tag ${process.env.GITHUB_REF_NAME} doesn't match package.json (${version}).`)
}

const bundle = 'target/release/bundle/nsis'
const setup = readdirSync(bundle).find((name) => name.endsWith('-setup.exe') && name.includes(`_${version}_`))
if (!setup) fail(`No installer for ${version} in ${bundle}.`)

// A name GitHub keeps as-is (it would turn the spaces into dots).
const asset = `CubeUtilityClient_${version}_x64-setup.exe`
mkdirSync('release', { recursive: true })
copyFileSync(`${bundle}/${setup}`, `release/${asset}`)
copyFileSync(`${bundle}/${setup}.sig`, `release/${asset}.sig`)

const latest = {
  version,
  notes: `Cube Utility Client ${version}`,
  pub_date: new Date().toISOString(),
  platforms: {
    'windows-x86_64': {
      signature: readFileSync(`release/${asset}.sig`, 'utf8').trim(),
      url: `https://github.com/${repo}/releases/download/${tag}/${asset}`
    }
  }
}
writeFileSync('release/latest.json', JSON.stringify(latest, null, 2) + '\n')

const notes = [
  `Download **${asset}** and run it. It installs for your Windows account only, without administrator rights.`,
  'Installed launchers update themselves, so you only do this once.',
  '',
  'Windows may say it protected your PC, because the installer isn\'t code-signed yet: choose "More info", then "Run anyway".'
].join('\n')
execFileSync(
  'gh',
  ['release', 'create', tag, `release/${asset}`, `release/${asset}.sig`, 'release/latest.json', '--title', `Cube Utility Client ${version}`, '--notes', notes, '--latest'],
  { stdio: 'inherit' }
)
