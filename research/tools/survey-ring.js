#!/usr/bin/env node
'use strict'

// Surveys the ring of initial positions a scene places agents on, from a fresh world.
//
// Starts a disposable loopback Paper server on one seed, joins one spectator through the body
// package's own Mineflayer, and reads the generated terrain around the origin. At each of ten
// even bearings it takes the first radius from 12 to 16 whose column's highest collision block
// that is not leaves is ground rather than a log, with two passable cells above it, and reports
// that slot with what stands under and in it and how many logs lie within sixteen blocks. The
// same seed and server build give the same slots, which is what lets a profile pin them.
//
// Usage: node research/tools/survey-ring.js --seed 10001 \
//   --runtime .scratch/paper/runtime-1.21.1-133-<sha> --jar .scratch/paper/paper-1.21.1-133.jar \
//   [--java /path/to/java21] [--port 25601]
//
// It writes only inside a fresh temporary directory it removes, and touches no network beyond
// loopback. It is a measurement tool, not part of any run.

const { spawn } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const REPO = path.resolve(__dirname, '..', '..')
const mineflayer = require(path.join(REPO, 'body', 'node_modules', 'mineflayer'))
const { Vec3 } = require(path.join(REPO, 'body', 'node_modules', 'vec3'))

const BEARINGS = 10
const RADII = [12, 13, 14, 15, 16]
const LOG_RADIUS = 16
const LOG_RISE = 6
const TOP = 160
const BOTTOM = 40
const SETTLE_MS = 8000

function argument (name, fallback) {
  const index = process.argv.indexOf(`--${name}`)
  if (index === -1) {
    if (fallback === undefined) throw new Error(`--${name} is required`)
    return fallback
  }
  return process.argv[index + 1]
}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

function serverProperties (seed, port) {
  return [
    `level-seed=${seed}`, 'online-mode=false', `server-port=${port}`, 'server-ip=127.0.0.1',
    'view-distance=6', 'simulation-distance=4', 'spawn-monsters=false', 'gamemode=spectator',
    'max-world-size=256', 'spawn-protection=0', 'generate-structures=true'
  ].join('\n') + '\n'
}

/** The ring slot at one bearing, or null when no radius in range stands on ground. */
function slotAt (at, bearing) {
  const passable = (block) => block !== null && block.boundingBox === 'empty' && !/water|lava/.test(block.name)
  const angle = bearing * 2 * Math.PI / BEARINGS
  for (const radius of RADII) {
    const x = Math.floor(radius * Math.cos(angle))
    const z = Math.floor(radius * Math.sin(angle))
    for (let y = TOP; y > BOTTOM; y -= 1) {
      const block = at(x, y, z)
      if (block === null || block.boundingBox !== 'block') continue
      if (/leaves/.test(block.name)) continue
      if (/_log$/.test(block.name)) break
      if (passable(at(x, y + 1, z)) && passable(at(x, y + 2, z))) {
        return { bearing, radius, position: [x + 0.5, y + 1, z + 0.5], ground: block.name, feet: at(x, y + 1, z).name }
      }
      break
    }
  }
  return null
}

/** How many logs lie within [LOG_RADIUS] blocks of `slot`, and the nearest one's distance. */
function logsNear (at, slot) {
  const [x, y, z] = slot.position.map(Math.floor)
  let count = 0
  let nearest = Infinity
  for (let dx = -LOG_RADIUS; dx <= LOG_RADIUS; dx += 1) {
    for (let dz = -LOG_RADIUS; dz <= LOG_RADIUS; dz += 1) {
      for (let dy = -LOG_RISE; dy <= LOG_RISE; dy += 1) {
        const block = at(x + dx, y + dy, z + dz)
        if (block !== null && /_log$/.test(block.name)) {
          count += 1
          nearest = Math.min(nearest, Math.hypot(dx, dy, dz))
        }
      }
    }
  }
  return { logsWithin16: count, nearestLog: Number.isFinite(nearest) ? Number(nearest.toFixed(1)) : null }
}

async function main () {
  const seed = argument('seed')
  const runtime = path.resolve(argument('runtime'))
  const jar = path.resolve(argument('jar'))
  const java = argument('java', 'java')
  const port = Number(argument('port', '25601'))
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-survey-'))
  let paper
  try {
    fs.cpSync(runtime, directory, { recursive: true })
    fs.copyFileSync(jar, path.join(directory, 'paper.jar'))
    fs.writeFileSync(path.join(directory, 'eula.txt'), 'eula=true\n')
    fs.writeFileSync(path.join(directory, 'server.properties'), serverProperties(seed, port))
    paper = spawn(java, ['-Xmx2G', '-jar', 'paper.jar', '--nogui'], { cwd: directory })
    let log = ''
    paper.stdout.on('data', (chunk) => { log += chunk })
    while (!/Done \(/.test(log)) {
      if (paper.exitCode !== null) throw new Error('Paper exited before it was ready')
      await sleep(500)
    }
    const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'surveyor', version: '1.21.1' })
    await new Promise((resolve, reject) => {
      bot.once('spawn', resolve)
      bot.once('error', reject)
    })
    paper.stdin.write('gamemode spectator surveyor\n')
    paper.stdin.write('tp surveyor 0.5 150 0.5\n')
    await sleep(SETTLE_MS)
    const at = (x, y, z) => bot.blockAt(new Vec3(x, y, z)) ?? null
    const slots = []
    for (let bearing = 0; bearing < BEARINGS; bearing += 1) {
      const slot = slotAt(at, bearing)
      slots.push(slot === null ? { bearing, position: null } : { ...slot, ...logsNear(at, slot) })
    }
    process.stdout.write(`${JSON.stringify({ seed, slots }, null, 2)}\n`)
    bot.quit()
  } finally {
    if (paper !== undefined) {
      paper.stdin.write('stop\n')
      await sleep(4000)
      paper.kill('SIGKILL')
    }
    fs.rmSync(directory, { recursive: true, force: true })
  }
}

main().catch((failure) => {
  process.stderr.write(`${failure.stack}\n`)
  process.exitCode = 1
})
