'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const {
  BoundedJournal,
  ProcessSupervisor,
  RunFailure,
  delay,
  waitForCondition,
  waitForExit
} = require('../lib/lifecycle.js')

function harness () {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-lifecycle-test-'))
  const events = []
  const failures = []
  const journal = {
    append: (event, fields) => events.push({ event, ...fields })
  }
  const supervisor = new ProcessSupervisor({
    logBytes: 64 * 1024,
    journal,
    onFailure: (failure) => failures.push(failure)
  })
  const start = (name, script, critical = true) => supervisor.start({
    name,
    command: process.execPath,
    args: ['-e', script],
    cwd: directory,
    env: process.env,
    critical,
    logFile: path.join(directory, `${name}.log`)
  })
  return { directory, events, failures, journal, supervisor, start }
}

async function eventually (predicate) {
  await waitForCondition({
    description: 'test condition',
    timeoutMilliseconds: 2000,
    poll: async () => predicate()
  })
}

test('partial startup is rolled back when a later component cannot start', async () => {
  const run = harness()
  const first = run.start('cognition', 'setInterval(() => {}, 1000)')
  const second = run.supervisor.start({
    name: 'paper',
    command: path.join(run.directory, 'missing-command'),
    cwd: run.directory,
    env: process.env,
    logFile: path.join(run.directory, 'paper.log')
  })
  await eventually(() => run.failures.length > 0)
  await run.supervisor.stopAll(1000)
  assert.equal(await waitForExit(first.child, 1000), true)
  assert.equal(await waitForExit(second.child, 1000), true)
})

test('startup timeout and cancellation are bounded outcomes', async () => {
  await assert.rejects(
    waitForCondition({
      description: 'a condition that stays false',
      timeoutMilliseconds: 30,
      poll: async () => false
    }),
    (failure) => failure instanceof RunFailure && failure.stopReason === 'startupFailed'
  )
  const controller = new AbortController()
  const waiting = delay(5000, controller.signal)
  controller.abort(new RunFailure('cancelled', 'cancelled by test'))
  await assert.rejects(
    waiting,
    (failure) => failure instanceof RunFailure && failure.stopReason === 'cancelled'
  )

  const run = harness()
  const child = run.start('cognition', 'setInterval(() => {}, 1000)')
  const managedController = new AbortController()
  const managedWait = delay(5000, managedController.signal)
  managedController.abort(new RunFailure('cancelled', 'cancelled with a child in flight'))
  await assert.rejects(managedWait, /cancelled with a child/)
  await run.supervisor.stopAll(500)
  assert.equal(await waitForExit(child.child, 1000), true)
})

test('a noncritical body crash is isolated while critical cognition and Paper failures stop the run', async () => {
  {
    const run = harness()
    const body = run.start('body-alpha', 'process.exit(7)', false)
    await waitForExit(body.child, 1000)
    assert.deepEqual(run.failures, [])
    await run.supervisor.stopAll(500)
  }
  for (const component of ['cognition', 'paper']) {
    const run = harness()
    const child = run.start(component, 'process.exit(9)', true)
    await waitForExit(child.child, 1000)
    await eventually(() => run.failures.length === 1)
    assert.equal(
      run.failures[0].stopReason,
      component === 'paper' ? 'paperFailed' : 'cognitionFailed'
    )
    await run.supervisor.stopAll(500)
  }
})

test('a disk failure is surfaced and cleanup still terminates every child', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-journal-test-'))
  const journal = new BoundedJournal(path.join(directory, 'events.jsonl'), 1024)
  const failures = []
  const supervisor = new ProcessSupervisor({
    logBytes: 1024,
    journal,
    onFailure: (failure) => failures.push(failure)
  })
  const child = supervisor.start({
    name: 'cognition',
    command: process.execPath,
    args: ['-e', 'setInterval(() => {}, 1000)'],
    cwd: directory,
    env: process.env,
    logFile: path.join(directory, 'child.log')
  })
  journal.close()
  assert.throws(
    () => journal.append('afterClose'),
    (failure) => failure instanceof RunFailure && failure.stopReason === 'diskFailure'
  )
  await supervisor.stopAll(1000)
  assert.equal(await waitForExit(child.child, 1000), true)
  assert.ok(failures.some((failure) => failure.stopReason === 'diskFailure'))
})

test('cleanup escalates through the process group and leaves no managed child alive', async () => {
  const run = harness()
  const stubborn = run.start(
    'paper',
    "process.on('SIGTERM', () => {}); require('node:child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)']); console.log('ready'); setInterval(() => {}, 1000)"
  )
  await eventually(() =>
    fs.existsSync(path.join(run.directory, 'paper.log')) &&
    fs.readFileSync(path.join(run.directory, 'paper.log'), 'utf8').includes('ready')
  )
  await run.supervisor.stopAll(500)
  assert.equal(await waitForExit(stubborn.child, 1000), true)
  assert.ok(run.events.some((event) => event.event === 'processExited' && event.expected === true))
})

/** Runs [body] with `process.kill` refusing as EPERM, as macOS can, each signal to [child]'s group [refuses] names. */
async function refusingGroupSignals (child, refuses, body) {
  const kill = process.kill
  const sent = []
  process.kill = (pid, signal) => {
    if (pid === -child.pid) {
      sent.push(signal)
      if (refuses(signal, sent.length)) {
        const refused = new Error('kill EPERM')
        refused.code = 'EPERM'
        throw refused
      }
    }
    return kill.call(process, pid, signal)
  }
  try {
    return await body()
  } finally {
    process.kill = kill
  }
}

test('a stop signal the system refuses is journalled with its code and cleanup goes on', async () => {
  // The path rehearsal: an EPERM from the first stop signal was thrown out of cleanup and took the
  // place of the run's own failure.
  const run = harness()
  const first = run.start('cognition', 'setInterval(() => {}, 1000)')
  const second = run.start('paper', 'setInterval(() => {}, 1000)')
  await delay(100)
  // Only the very first signal to cognition's group is refused; the escalation that follows stops it.
  await refusingGroupSignals(first.child, (signal, count) => signal === 'SIGTERM' && count === 1, () =>
    run.supervisor.stopAll(1000))
  assert.equal(await waitForExit(first.child, 1000), true)
  assert.equal(await waitForExit(second.child, 1000), true)
  assert.deepEqual(
    run.events.filter((event) => event.event === 'processGroupSignalFailed'),
    [{ event: 'processGroupSignalFailed', component: 'cognition', signal: 'SIGTERM', code: 'EPERM' }]
  )
  assert.ok(run.events.some((event) => event.event === 'processGroupSignalled' && event.component === 'paper'))
})

test('a group that every stop signal is refused to survives as a cleanup failure naming the refusals', async () => {
  const run = harness()
  const stuck = run.start('cognition', 'setInterval(() => {}, 1000)')
  await delay(100)
  await refusingGroupSignals(stuck.child, (signal) => signal !== 0, async () => {
    await assert.rejects(
      run.supervisor.stopAll(300),
      (failure) => failure instanceof RunFailure && failure.stopReason === 'startupFailed' &&
        failure.message === 'managed process groups survived cleanup: cognition (SIGTERM EPERM, SIGTERM EPERM, SIGKILL EPERM)'
    )
  })
  stuck.child.kill('SIGKILL')
  assert.equal(await waitForExit(stuck.child, 1000), true)
})
