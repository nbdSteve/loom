#!/usr/bin/env node
'use strict'

// Keeps every Mindcraft agent of one run in the world, and the observer in
// spectator mode. Mindcraft itself is left as is; this supervises it from the
// outside, from what the server reports.
//
// Mindcraft restarts an agent only when its process exits, and not at all if
// it exits within 10 s of its last restart. An agent whose event loop blocks
// (r 20261104T213950Z: frozen inside collectBlocks, timed out by the server,
// never restarted) never exits, so it never came back. The watchdog follows
// Paper's log instead: an agent off the server for longer than the grace
// period is missing, whatever the reason. A missing agent's process, if any,
// is killed (SIGKILL: a blocked event loop handles no other signal, and for the
// same reason a Node diagnostic report cannot be asked of it); if Mindcraft does
// not start it again by itself, the MindServer is asked to. Mindcraft restarts it from its saved memory, and if
// that memory shows no running goal loop the agent is told to resume its goal.
//
// Two more rules (society run 20261104T223720Z):
// - A death is final. The world is hardcore, so a dead agent would come back as
//   a spectator and keep "acting"; on its death message the agent is stopped
//   for good (SIGINT, which Mindcraft does not restart), listed in
//   dead-agents.txt (which run.sh leaves out of a Mindcraft relaunch), and
//   stopped again if it ever rejoins.
// - An agent in the world whose goal loop has stopped (Mindcraft stops it after
//   three answers with no command) and that has made no model call for
//   IDLE_SECONDS is told to set its goal again, so it does not sit idle.
//
//   node watchdog.js --run <dir> --mindcraft <dir> --mindserver-port <n>
//     --roster a,b,c --console <fifo> --observer <name> --grace-seconds <n>

const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const POLL_MS = 2000
const START_CHECK_MS = 8000
const RESUME_AFTER_JOIN_MS = 15000
// Before an agent's first join it is still starting (ten agents load their
// examples through one proxy at once), so it gets at least this long.
const STARTUP_GRACE_MS = 180000
const IDLE_MS = 90000
const STOP_CHECK_MS = 10000
const SELF_PROMPT_PAUSED = 2
// Vanilla death messages all start with the player's name and one of these.
const DEATH = /^(was |drowned|died|blew up|fell |hit the ground|burned|went up in flames|went off with a bang|tried to swim|suffocated|starved|walked into|froze|experienced kinetic|withered|discovered the floor|left the confines|didn't want to live)/
const RESTART_GOAL_MESSAGE = 'Your goal loop has stopped and you are idle. Choose your long-term goal again and set it now with !goal, then keep working toward it.'
const RESUME_MESSAGE = 'You were disconnected and have reconnected. Your memory above has your long-term goal. If your goal is not running, set it again with !goal and continue working toward it.'

function parseArgs (argv) {
  const args = {}
  for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, '')] = argv[i + 1]
  return args
}

function agentPids (mindcraft, name, port) {
  const script = path.join(mindcraft, 'src', 'process', 'init_agent.js')
  const lines = execFileSync('ps', ['-axo', 'pid=,command='], { encoding: 'utf8' }).split('\n')
  return lines
    .filter((line) => line.includes(`${script} ${name} `) && line.includes(`-p ${port}`))
    .map((line) => Number(line.trim().split(/\s+/)[0]))
}

function savedGoalState (run, name) {
  try {
    const memory = JSON.parse(fs.readFileSync(path.join(run, 'bots', name, 'memory.json'), 'utf8'))
    return memory.self_prompt ? memory.self_prompting_state : 0
  } catch {
    return 0
  }
}

function savedGoalRunning (run, name) {
  return savedGoalState(run, name) === 1
}

function main () {
  const args = parseArgs(process.argv.slice(2))
  const run = args.run
  const roster = args.roster.split(',')
  const graceMs = Number(args['grace-seconds']) * 1000
  const logPath = path.join(run, 'server', 'logs', 'latest.log')
  const recordPath = path.join(run, 'watchdog.jsonl')
  const record = (entry) => fs.appendFileSync(recordPath, JSON.stringify({ t: new Date().toISOString(), ...entry }) + '\n')
  const toConsole = (command) => fs.appendFileSync(args.console, command + '\n')

  const { io } = require(path.join(args.mindcraft, 'node_modules', 'socket.io-client'))
  const socket = io(`http://localhost:${args['mindserver-port']}`, { reconnection: true })

  const started = Date.now()
  const agents = Object.fromEntries(roster.map((name) => [name, {
    inGame: false,
    missingSince: started,
    lastAction: 0,
    revived: false,
    everJoined: false,
    dead: false,
    lastCall: started,
    lastNudge: 0
  }]))
  const deadPath = path.join(run, 'dead-agents.txt')
  let offset = 0
  let partial = ''
  let callsOffset = 0

  function stopForGood (name, reason) {
    const pids = agentPids(args.mindcraft, name, args['mindserver-port'])
    socket.emit('stop-agent', name)
    record({ kind: 'stoppedForGood', agent: name, reason, pids })
    setTimeout(() => {
      for (const pid of agentPids(args.mindcraft, name, args['mindserver-port'])) {
        try { process.kill(pid, 'SIGINT'); record({ kind: 'interrupted', agent: name, pid }) } catch {}
      }
    }, STOP_CHECK_MS)
  }

  function readCalls () {
    const callsPath = path.join(run, 'proxy-calls.jsonl')
    let size
    try { size = fs.statSync(callsPath).size } catch { return }
    if (size <= callsOffset) return
    const fd = fs.openSync(callsPath, 'r')
    const buffer = Buffer.alloc(size - callsOffset)
    fs.readSync(fd, buffer, 0, buffer.length, callsOffset)
    fs.closeSync(fd)
    const text = buffer.toString('utf8')
    const end = text.lastIndexOf('\n') + 1
    callsOffset += Buffer.byteLength(text.slice(0, end))
    for (const line of text.slice(0, end).split('\n').filter(Boolean)) {
      try {
        const call = JSON.parse(line)
        if (call.endpoint === 'chat/completions' && agents[call.agent]) agents[call.agent].lastCall = Date.parse(call.t)
      } catch {}
    }
  }

  function readLog () {
    let size
    try { size = fs.statSync(logPath).size } catch { return }
    if (size < offset) offset = 0
    if (size === offset) return
    const fd = fs.openSync(logPath, 'r')
    const buffer = Buffer.alloc(size - offset)
    fs.readSync(fd, buffer, 0, buffer.length, offset)
    fs.closeSync(fd)
    offset = size
    const lines = (partial + buffer.toString('utf8')).split('\n')
    partial = lines.pop()
    for (const line of lines) {
      const death = /\[Server thread\/INFO\]: ([A-Za-z0-9_]{3,16}) (.+)$/.exec(line)
      if (death !== null && agents[death[1]] && !agents[death[1]].dead && DEATH.test(death[2])) {
        const name = death[1]
        agents[name].dead = true
        fs.appendFileSync(deadPath, name + '\n')
        record({ kind: 'died', agent: name, message: death[2] })
        stopForGood(name, 'died')
        continue
      }
      const m = /\]: ([A-Za-z0-9_]{3,16}) (joined|left) the game$/.exec(line)
      if (m === null) continue
      const [, name, change] = m
      if (name === args.observer && change === 'joined') {
        toConsole(`gamemode spectator ${name}`)
        record({ kind: 'observerSpectator', name })
      }
      const agent = agents[name]
      if (agent === undefined) continue
      agent.inGame = change === 'joined'
      if (agent.inGame) agent.everJoined = true
      agent.missingSince = agent.inGame ? null : Date.now()
      record({ kind: change, agent: name })
      if (agent.inGame && agent.dead) {
        stopForGood(name, 'rejoinedAfterDeath')
        continue
      }
      if (agent.inGame && agent.revived) {
        agent.revived = false
        setTimeout(() => {
          if (savedGoalRunning(run, name)) return record({ kind: 'goalResumedFromMemory', agent: name })
          socket.emit('send-message', name, { from: 'system', message: RESUME_MESSAGE })
          record({ kind: 'toldToResumeGoal', agent: name })
        }, RESUME_AFTER_JOIN_MS)
      }
    }
  }

  function revive (name) {
    const agent = agents[name]
    agent.lastAction = Date.now()
    agent.revived = true
    const pids = agentPids(args.mindcraft, name, args['mindserver-port'])
    record({ kind: 'missing', agent: name, missingSeconds: Math.round((Date.now() - agent.missingSince) / 1000), pids })
    for (const pid of pids) {
      try { process.kill(pid, 'SIGKILL'); record({ kind: 'killed', agent: name, pid }) } catch {}
    }
    setTimeout(() => {
      if (agents[name].inGame || agentPids(args.mindcraft, name, args['mindserver-port']).length > 0) {
        return record({ kind: 'restartedByMindcraft', agent: name })
      }
      socket.emit('start-agent', name)
      record({ kind: 'startRequested', agent: name })
    }, START_CHECK_MS)
  }

  setInterval(() => {
    readLog()
    readCalls()
    const now = Date.now()
    for (const [name, agent] of Object.entries(agents)) {
      if (agent.dead) continue
      if (agent.inGame) {
        const state = savedGoalState(run, name)
        if (state !== 1 && state !== SELF_PROMPT_PAUSED && now - agent.lastCall >= IDLE_MS && now - agent.lastNudge >= IDLE_MS) {
          agent.lastNudge = now
          socket.emit('send-message', name, { from: 'system', message: RESTART_GOAL_MESSAGE })
          record({ kind: 'goalLoopRestarted', agent: name, idleSeconds: Math.round((now - agent.lastCall) / 1000) })
        }
        continue
      }
      if (agent.missingSince === null) continue
      const allowed = agent.everJoined ? graceMs : Math.max(graceMs, STARTUP_GRACE_MS)
      if (now - agent.missingSince >= allowed && now - agent.lastAction >= graceMs) revive(name)
    }
  }, POLL_MS)

  const stop = () => { socket.close(); process.exit(0) }
  process.on('SIGINT', stop)
  process.on('SIGTERM', stop)
  record({ kind: 'watching', roster, graceSeconds: graceMs / 1000, observer: args.observer })
}

main()
