#!/usr/bin/env node
'use strict'

const fs = require('node:fs')
const path = require('node:path')

const USAGE = 'usage: node join.js <run-directory>'
const BROADCAST_CHAT_PREFIX = '(To everyone) '

function serverEvents (run, roster) {
  const logPath = path.join(run, 'server', 'logs', 'latest.log')
  if (!fs.existsSync(logPath)) return []
  const date = fs.readFileSync(path.join(run, 'started-local-date.txt'), 'utf8').trim()
  let day = new Date(`${date}T00:00:00`).getTime()
  let last = -1
  const events = []
  for (const line of fs.readFileSync(logPath, 'utf8').split('\n')) {
    const m = /^\[(\d\d):(\d\d):(\d\d)\] \[[^\]]+\]: (.*)$/.exec(line)
    if (m === null) continue
    const seconds = (Number(m[1]) * 3600) + (Number(m[2]) * 60) + Number(m[3])
    if (seconds < last) day += 86_400_000
    last = seconds
    const t = new Date(day + (seconds * 1000)).toISOString()
    const text = m[4]
    const chat = /^(?:\[Not Secure\] )?<([A-Za-z0-9_]{3,16})> (.*)$/.exec(text)
    if (chat !== null) {
      const text = chat[2].trim()
      const broadcast = text.startsWith(BROADCAST_CHAT_PREFIX)
      events.push({
        t,
        agent: chat[1],
        kind: broadcast ? 'groupSaid' : 'said',
        text: broadcast ? text.slice(BROADCAST_CHAT_PREFIX.length) : text,
        source: 'paper'
      })
      continue
    }
    const agent = roster.find((name) => text.startsWith(`${name} `))
    if (agent === undefined) continue
    const rest = text.slice(agent.length + 1)
    if (rest === 'joined the game') events.push({ t, agent, kind: 'joined', source: 'paper' })
    else if (rest.startsWith('left the game')) events.push({ t, agent, kind: 'left', source: 'paper' })
    else if (!/^(issued server command|lost connection|\[|logged in|moved |has made)/.test(rest)) {
      events.push({ t, agent, kind: 'died', text, source: 'paper' })
    }
  }
  return events
}

function decisionEvents (run, roster) {
  const events = []
  for (const agent of roster) {
    const dir = path.join(run, 'bots', agent, 'logs')
    if (!fs.existsSync(dir)) continue
    for (const file of fs.readdirSync(dir).sort()) {
      const m = /^(.+)_(\d{4}-\d\d-\d\dT\d\d)-(\d\d)-(\d\d)-(\d{3})Z\.txt$/.exec(file)
      if (m === null) continue
      const text = fs.readFileSync(path.join(dir, file), 'utf8')
      const at = text.lastIndexOf('\nResponse:\n')
      events.push({
        t: `${m[2]}:${m[3]}:${m[4]}.${m[5]}Z`,
        agent,
        kind: m[1] === 'conversation' ? 'decided' : m[1],
        text: at === -1 ? '' : text.slice(at + 11).trim(),
        source: `bots/${agent}/logs/${file}`
      })
    }
  }
  return events
}

function costEvents (run) {
  const logPath = path.join(run, 'proxy-calls.jsonl')
  if (!fs.existsSync(logPath)) return []
  return fs.readFileSync(logPath, 'utf8').split('\n').filter(Boolean).map((line) => {
    const call = JSON.parse(line)
    return { t: call.t, agent: call.agent, kind: 'call', ...call, source: 'proxy' }
  })
}

function watchdogEvents (run) {
  const logPath = path.join(run, 'watchdog.jsonl')
  if (!fs.existsSync(logPath)) return []
  return fs.readFileSync(logPath, 'utf8').split('\n').filter(Boolean).map((line) => {
    const entry = JSON.parse(line)
    return { ...entry, kind: `watchdog:${entry.kind}`, source: 'watchdog' }
  })
}

function joinRunEvents (run) {
  const roster = fs.readFileSync(path.join(run, 'roster.txt'), 'utf8').split('\n').filter(Boolean)
  return [...serverEvents(run, roster), ...decisionEvents(run, roster), ...costEvents(run), ...watchdogEvents(run)]
    .sort((a, b) => a.t.localeCompare(b.t))
}

if (require.main === module) {
  const run = process.argv[2]
  if (run === undefined) {
    console.error(USAGE)
    process.exit(2)
  }
  const events = joinRunEvents(run)
  fs.writeFileSync(path.join(run, 'events.jsonl'), events.map((e) => JSON.stringify(e)).join('\n') + '\n')
  console.log(`${events.length} events in ${path.join(run, 'events.jsonl')}`)
}

module.exports = { joinRunEvents }
