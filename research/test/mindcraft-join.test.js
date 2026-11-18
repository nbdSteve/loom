'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { joinRunEvents } = require('../mindcraft/join.js')

function runDirectory (serverLog) {
  const run = fs.mkdtempSync(path.join(os.tmpdir(), 'mindcraft-join-'))
  fs.mkdirSync(path.join(run, 'server', 'logs'), { recursive: true })
  fs.writeFileSync(path.join(run, 'roster.txt'), 'ada_ash\nbram_oak\n')
  fs.writeFileSync(path.join(run, 'started-local-date.txt'), '2026-11-18\n')
  fs.writeFileSync(path.join(run, 'server', 'logs', 'latest.log'), serverLog.join('\n') + '\n')
  return run
}

test('a broadcast said in chat as "(To everyone) ..." is a groupSaid event without its prefix, and other chat is said', () => {
  const run = runDirectory([
    '[10:00:01] [Server thread/INFO]: <ada_ash> (To everyone) meet at the river',
    '[10:00:02] [Server thread/INFO]: [Not Secure] <bram_oak> on my way'
  ])
  const said = joinRunEvents(run).map(({ agent, kind, text }) => ({ agent, kind, text }))
  assert.deepEqual(said, [
    { agent: 'ada_ash', kind: 'groupSaid', text: 'meet at the river' },
    { agent: 'bram_oak', kind: 'said', text: 'on my way' }
  ])
})
