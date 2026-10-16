#!/usr/bin/env node
'use strict'

/**
 * A real-Paper check of the walk the routine-v14 mind asks for: onto one sensed block.
 *
 * Starts the disposable Paper server in `<server-dir>` (loopback, offline, provider-free),
 * drives the real body process over a real control socket from the s2-ring slot tribe-alpha
 * stood on, and walks onto sensed blocks exactly as Loom's JVM resolves a `moveTo` ref: to the
 * cell directly above the block one `sense` answer reported. It walks once onto each block it
 * can see open air above and once onto one block with a block above it, and prints each
 * body report beside the position Paper itself states afterwards.
 *
 *   node body/scripts/paper-walk-onto.js <server-dir> <java> [port]
 *
 * The server directory needs `paper.jar`, `eula.txt` and a `server.properties` naming the
 * seed, `online-mode=false` and `server-ip=127.0.0.1`. Nothing here reaches a network.
 */

const path = require('node:path')
const { spawn } = require('node:child_process')

const H = require('../test/harness')
const { createBody } = require('../src/body')

const SLOT = [12.5, 105, 0.5]
const USERNAME = 'walkprobe'

async function main () {
  const [serverDir, java, portText = '25571'] = process.argv.slice(2)
  const port = Number(portText)
  const server = spawn(java, ['-Xmx1024M', '-jar', 'paper.jar', '--nogui'], {
    cwd: path.resolve(serverDir),
    stdio: ['pipe', 'pipe', 'ignore']
  })
  let log = ''
  // Paper colours its console; the escapes are dropped so a reading is plain text.
  const colour = new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, 'g')
  server.stdout.on('data', (chunk) => { log += String(chunk).replace(colour, '') })
  const console = (command) => server.stdin.write(`${command}\n`)
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
  const until = async (ready, ms, what) => {
    const end = Date.now() + ms
    while (!ready()) {
      if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
      await wait(200)
    }
  }
  /** Paper's own statement of where the player stands. */
  const serverPosition = async () => {
    const before = log.length
    console(`data get entity ${USERNAME} Pos`)
    await until(() => /has the following entity data: \[[^\]]*\]/.test(log.slice(before)), 10_000, 'Pos')
    const match = log.slice(before).match(/has the following entity data: \[([^\]]*)\]/)
    return match[1].split(',').map((part) => Number(part.trim().replace(/d$/, '')))
  }
  try {
    await until(() => log.includes('Done ('), 240_000, 'Paper')
    const body = createBody({
      agentId: H.AGENT,
      username: USERNAME,
      minecraft: { host: '127.0.0.1', port, version: '1.21.1' },
      control: { host: '127.0.0.1', port: await H.freePort() }
    })
    const { control } = await body.start()
    await until(() => log.includes(`${USERNAME} joined the game`), 60_000, 'join')
    const peer = await H.handshaken(control.port)
    const atSlot = async () => {
      console(`tp ${USERNAME} ${SLOT.join(' ')}`)
      await wait(3000)
    }
    await atSlot()
    let sequence = 0
    const sensed = async () => {
      sequence += 1
      return peer.answer(H.request('sense', `s${sequence}`, { radius: 4, limit: 48 }))
    }
    const first = await sensed()
    const occupied = new Set(first.blocks.map((block) => block.position.join(',')))
    const above = (block, dy) => [block.position[0], block.position[1] + dy, block.position[2]].join(',')
    const open = first.blocks.filter((block) => !occupied.has(above(block, 1)) && !occupied.has(above(block, 2)) &&
      block.what !== 'dead_bush')
    const covered = first.blocks.find((block) => occupied.has(above(block, 1)) && block.what !== 'dead_bush')
    const chosen = [...open.slice(0, 4).map((block) => ({ block, kind: 'openAbove' })), { block: covered, kind: 'covered' }]
    const results = []
    for (const { block, kind } of chosen) {
      await atSlot()
      const answer = await sensed()
      const index = answer.blocks.findIndex((entry) => entry.position.join(',') === block.position.join(','))
      if (index < 0) continue
      const target = answer.blocks[index].position
      const destination = [target[0], target[1] + 1, target[2]]
      sequence += 1
      const report = await peer.answer(H.request('moveTo', `m${sequence}`, {
        destination, tolerance: 0, timeoutMs: 20_000, sprint: false
      }))
      await wait(1000)
      results.push({ kind, block: target, what: block.what, destination, outcome: report.outcome, reported: report.position, paper: await serverPosition() })
    }
    process.stdout.write(`${JSON.stringify(results, null, 1)}\n`)
    peer.stop()
    await body.stop()
  } finally {
    console('stop')
  }
}

main().catch((failure) => {
  process.stderr.write(`${failure.stack}\n`)
  process.exitCode = 1
})
