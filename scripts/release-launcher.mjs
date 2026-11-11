// `npm run release:launcher -- patch|minor|major|<version>`: bumps the launcher's version,
// commits it, tags launcher-v<version> and pushes. GitHub Actions (release-launcher.yml) then
// builds the signed installer and publishes it with latest.json, and installed launchers
// update themselves. Passing the current version just tags it (the first release).
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

function fail(message) {
  console.error(message)
  process.exit(1)
}

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim()
const npm = (...args) => execFileSync(process.execPath, [process.env.npm_execpath, ...args], { encoding: 'utf8' }).trim()
const version = () => JSON.parse(readFileSync('package.json', 'utf8')).version

const bump = process.argv[2]
if (!bump) fail('usage: npm run release:launcher -- patch|minor|major|<version>')
if (!process.env.npm_execpath) fail('Run this through npm: npm run release:launcher -- patch')
if (git('status', '--porcelain')) fail('Commit or stash your changes first.')
const branch = git('rev-parse', '--abbrev-ref', 'HEAD')
if (branch !== 'main') fail(`Release from main (this is ${branch}).`)

if (bump !== version()) {
  // Updates package.json and package-lock.json; tauri.conf.json reads the version from package.json.
  npm('version', bump, '--no-git-tag-version')
  git('add', 'package.json', 'package-lock.json')
  git('commit', '-m', `Launcher ${version()}`)
}
const tag = `launcher-v${version()}`
if (git('tag', '--list', tag)) fail(`${tag} already exists.`)
git('tag', '-a', tag, '-m', `Cube Utility Client ${version()}`)
git('push', 'origin', branch)
git('push', 'origin', tag)
console.log(`Pushed ${tag}. GitHub Actions builds and publishes it (about 15 minutes); launchers pick it up within 30 minutes.`)
