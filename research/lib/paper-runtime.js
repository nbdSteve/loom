'use strict'

const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawn } = require('node:child_process')

const { RunFailure, delay } = require('./lifecycle.js')

// What Paper writes on its first start for a given jar: the Mojang server jar it downloads,
// its libraries, the patched server and the remapping cache. Seeding these into each
// disposable run directory keeps startup off the network and off a full remap.
const RUNTIME_ENTRIES = Object.freeze(['cache', 'libraries', 'versions', path.join('plugins', '.paper-remapped')])
const MANIFEST = 'runtime-manifest.json'
const BOOTSTRAP_TIMEOUT_MS = 300_000

// The host must be quiet before a run starts: Paper's tick rate and the bodies' path search
// budgets are wall-clock bounded, so a contended host changes behavior, not only speed.
const MAX_LOAD_PER_CPU = 0.5
const LOAD_SETTLE_MS = 180_000

function sha256 (file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

function filesUnder (root) {
  const files = []
  const walk = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (entry.isFile()) files.push(full)
    }
  }
  walk(root)
  return files.sort()
}

function manifestOf (root) {
  const files = {}
  for (const entry of RUNTIME_ENTRIES) {
    const base = path.join(root, entry)
    if (!fs.existsSync(base)) continue
    for (const file of filesUnder(base)) files[path.relative(root, file)] = sha256(file)
  }
  return files
}

/** Starts a bare loopback Paper once in a temporary directory and keeps what it wrote. */
async function bootstrapRuntime (paperFile, java, port, target) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-paper-bootstrap-'))
  try {
    fs.copyFileSync(paperFile, path.join(work, 'paper.jar'))
    fs.writeFileSync(path.join(work, 'eula.txt'), 'eula=true\n')
    fs.writeFileSync(
      path.join(work, 'server.properties'),
      `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenable-query=false\nenable-rcon=false\n`
    )
    const log = path.join(work, 'bootstrap.log')
    const child = spawn(java, ['-Xmx1024M', '-jar', 'paper.jar', '--nogui'], {
      cwd: work,
      stdio: ['pipe', fs.openSync(log, 'w'), 'ignore'],
      env: { PATH: process.env.PATH, HOME: process.env.HOME, JAVA_HOME: path.dirname(path.dirname(java)) }
    })
    const exited = new Promise((resolve) => child.once('exit', resolve))
    const deadline = Date.now() + BOOTSTRAP_TIMEOUT_MS
    let ready = false
    while (Date.now() < deadline && child.exitCode === null) {
      if (fs.readFileSync(log, 'utf8').includes('Done (')) {
        ready = true
        break
      }
      await delay(500)
    }
    if (child.exitCode === null) child.stdin.write('stop\n')
    const stopped = await Promise.race([exited.then(() => true), delay(60_000).then(() => false)])
    if (!stopped) child.kill('SIGKILL')
    if (!ready) throw new RunFailure('startupFailed', 'the Paper runtime cache could not be bootstrapped')
    const staging = `${target}.partial-${process.pid}`
    fs.rmSync(staging, { recursive: true, force: true })
    for (const entry of RUNTIME_ENTRIES) {
      const from = path.join(work, entry)
      if (fs.existsSync(from)) fs.cpSync(from, path.join(staging, entry), { recursive: true })
    }
    fs.writeFileSync(path.join(staging, MANIFEST), `${JSON.stringify(manifestOf(staging), null, 2)}\n`)
    fs.rmSync(target, { recursive: true, force: true })
    fs.renameSync(staging, target)
  } finally {
    fs.rmSync(work, { recursive: true, force: true })
  }
}

/** Verifies a runtime cache file by file against its own manifest. */
function verifiedRuntime (root) {
  const manifestFile = path.join(root, MANIFEST)
  if (!fs.existsSync(manifestFile)) return false
  let manifest
  try {
    manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
  } catch {
    return false
  }
  const actual = manifestOf(root)
  const expected = Object.keys(manifest).sort()
  return expected.length > 0 &&
    expected.join('\0') === Object.keys(actual).sort().join('\0') &&
    expected.every((file) => manifest[file] === actual[file])
}

/**
 * Seeds `runDirectory` with the verified runtime cache for this exact Paper jar, building and
 * verifying the cache first if it is missing or does not match its manifest.
 */
async function seedPaperRuntime ({ repositoryRoot, paperFile, config, java, runDirectory }) {
  const root = path.join(
    repositoryRoot,
    '.scratch',
    'paper',
    `runtime-${config.paper.minecraftVersion}-${config.paper.build}-${config.paper.sha256.slice(0, 12)}`
  )
  if (!verifiedRuntime(root)) {
    await bootstrapRuntime(paperFile, java, config.network.minecraftPort, root)
    if (!verifiedRuntime(root)) {
      throw new RunFailure('startupFailed', 'the Paper runtime cache did not verify after bootstrap')
    }
  }
  for (const entry of RUNTIME_ENTRIES) {
    const from = path.join(root, entry)
    if (fs.existsSync(from)) fs.cpSync(from, path.join(runDirectory, entry), { recursive: true })
  }
  return root
}

/**
 * Waits, at most `settleMs`, for the one-minute load average to fall to the fixed per-CPU
 * threshold, and refuses to start a run on a host that stays busier than that.
 */
async function awaitQuietHost ({
  settleMs = LOAD_SETTLE_MS,
  loadavg = os.loadavg,
  cpus = () => os.cpus().length,
  wait = delay
} = {}) {
  const limit = MAX_LOAD_PER_CPU * cpus()
  const deadline = Date.now() + settleMs
  while (true) {
    const load = loadavg()[0]
    if (load <= limit) return { load, limit }
    if (Date.now() >= deadline) {
      throw new RunFailure(
        'startupFailed',
        `host load ${load.toFixed(2)} is above the ${limit.toFixed(2)} start threshold (${MAX_LOAD_PER_CPU} per CPU)`
      )
    }
    await wait(5_000)
  }
}

// The poll loop runs every 250 ms; a much longer gap means the host itself was suspended.
const MAX_POLL_GAP_MS = 10_000

/** Tracks the wall-clock gap between polls and names a host suspension when one occurs. */
class SuspensionWatch {
  constructor ({ now = Date.now, maxGapMs = MAX_POLL_GAP_MS } = {}) {
    this.now = now
    this.maxGapMs = maxGapMs
    this.last = now()
  }

  check () {
    const at = this.now()
    const gap = at - this.last
    this.last = at
    if (gap > this.maxGapMs) {
      return new RunFailure(
        'startupFailed',
        `the host was suspended for ${Math.round(gap / 1000)} s during the run; its clients and timers cannot be trusted after it`,
        'hostSuspended'
      )
    }
    return null
  }

  /**
   * The suspension a long gap since the last check shows, else `cause`: a failure that ends a poll
   * interval spanning a suspension is the suspension's (rehearsal 20: a clamshell sleep's wake
   * aborted the run as a player leaving, through the loop's own wait, before its check ran).
   */
  explain (cause) {
    return this.check() ?? cause
  }
}

module.exports = {
  MAX_LOAD_PER_CPU,
  MAX_POLL_GAP_MS,
  SuspensionWatch,
  RUNTIME_ENTRIES,
  awaitQuietHost,
  seedPaperRuntime,
  verifiedRuntime
}
