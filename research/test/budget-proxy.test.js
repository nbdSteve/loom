'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const net = require('node:net')

const { CognitionBudgetProxy } = require('../lib/budget-proxy.js')

function startUpstream (delayMilliseconds = 0) {
  const server = net.createServer((socket) => {
    let buffered = ''
    socket.on('data', (chunk) => {
      buffered += chunk.toString('utf8')
      const newline = buffered.indexOf('\n')
      if (newline === -1) return
      const perception = JSON.parse(buffered.slice(0, newline))
      setTimeout(() => {
        socket.end(`${JSON.stringify({
          requestId: perception.requestId,
          agentId: perception.agentId,
          intent: { action: 'idle' },
          reasoning: 'test',
          provenance: {
            tier: perception.importance,
            model: 'scripted-stand-in',
            promptVersion: 'routine-v11'
          }
        })}\n`)
      }, delayMilliseconds)
    })
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server))
  })
}

function startRefusingUpstream (error, failure) {
  const server = net.createServer((socket) => {
    socket.once('data', () => socket.end(`${JSON.stringify({
      error,
      ...(failure === undefined ? {} : { failure })
    })}\n`))
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server))
  })
}

function request (port, agentId, requestId, trigger = 'goalReview', importance = 'ROUTINE') {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let response = ''
    socket.once('connect', () => {
      socket.write(`${JSON.stringify({
        requestId,
        agentId,
        trigger: { event: trigger },
        importance
      })}\n`)
    })
    socket.on('data', (chunk) => {
      response += chunk.toString('utf8')
      const newline = response.indexOf('\n')
      if (newline !== -1) {
        socket.destroy()
        resolve(JSON.parse(response.slice(0, newline)))
      }
    })
    socket.once('error', reject)
  })
}

test('the proxy enforces per-agent and global model call ceilings', async () => {
  const upstream = await startUpstream()
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents: ['alpha', 'beta'],
    budget: {
      perAgentCallCeiling: 1,
      globalCallCeiling: 2,
      globalOutstandingCeiling: 1
    },
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  await proxy.start()
  try {
    assert.equal((await request(proxy.listen.port, 'alpha', 'one')).intent.action, 'idle')
    assert.match((await request(proxy.listen.port, 'alpha', 'two')).error, /experiment budget/)
    assert.equal((await request(proxy.listen.port, 'beta', 'one')).intent.action, 'idle')
    assert.match((await request(proxy.listen.port, 'beta', 'two')).error, /experiment budget/)
    assert.equal(events.filter((event) => event.event === 'cognitionForwarded').length, 2)
    assert.equal(events.filter((event) => event.event === 'cognitionRefused').length, 2)
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('the proxy refuses excess outstanding work instead of queueing it without a bound', async () => {
  const upstream = await startUpstream(100)
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents: ['alpha', 'beta'],
    budget: {
      perAgentCallCeiling: 2,
      globalCallCeiling: 4,
      globalOutstandingCeiling: 1
    },
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  await proxy.start()
  try {
    const first = request(proxy.listen.port, 'alpha', 'one')
    await new Promise((resolve) => setTimeout(resolve, 20))
    const second = await request(proxy.listen.port, 'beta', 'one')
    assert.match(second.error, /experiment budget/)
    assert.equal((await first).intent.action, 'idle')
    assert.equal(events.find((event) => event.event === 'cognitionRefused').reason, 'globalOutstandingCeiling')
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('the primary behavior proxy records one native origin on every cognition request', async () => {
  const upstream = await startUpstream()
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents: ['alpha'],
    budget: {
      perAgentCallCeiling: 1,
      globalCallCeiling: 1,
      globalOutstandingCeiling: 1
    },
    requestOrigin: (trigger) =>
      trigger === 'goalReview'
        ? 'nativeGoalReviewSchedule'
        : 'nativeEventDriver',
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  await proxy.start()
  try {
    assert.equal((await request(proxy.listen.port, 'alpha', 'one')).intent.action, 'idle')
    assert.match((await request(proxy.listen.port, 'alpha', 'two')).error, /experiment budget/)
    assert.deepEqual(
      events.map((event) => event.origin),
      ['nativeGoalReviewSchedule', 'nativeGoalReviewSchedule']
    )
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('the proxy preserves one sanitized cognition classification for the diagnostic owner', async () => {
  const error =
    "cognition failed: the model provider refused Loom's request for " +
    "'fast-model' (HTTP 400, invalid_request_error/invalid_json_schema)"
  const failure = {
    schemaVersion: 'loom-cognition-failure/1',
    backend: 'openai',
    model: 'fast-model',
    httpStatus: 400,
    providerType: 'invalid_request_error',
    providerCode: 'invalid_json_schema',
    responseShape: 'apiErrorEnvelope',
    mapping: 'providerRequest'
  }
  const upstream = await startRefusingUpstream(error, failure)
  const refusals = []
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents: ['alpha'],
    models: {
      ordinary: 'fast-model',
      pivotal: 'deep-model'
    },
    budget: {
      perAgentCallCeiling: 1,
      globalCallCeiling: 1,
      globalOutstandingCeiling: 1
    },
    journal: { append: (event, fields) => events.push({ event, ...fields }) },
    onCognitionFailure: (message, classified) => {
      refusals.push({ message, classified })
    }
  })
  await proxy.start()
  try {
    assert.equal((await request(proxy.listen.port, 'alpha', 'one')).error, error)
    assert.deepEqual(refusals, [{ message: error, classified: failure }])
    const forwarded = events.find((event) => event.event === 'cognitionForwarded')
    // The answer's latency is measured on the proxy's monotonic clock, so only its shape is pinned.
    assert.ok(Number.isSafeInteger(forwarded.latencyMs) && forwarded.latencyMs >= 0)
    delete forwarded.latencyMs
    assert.deepEqual(
      forwarded,
      {
        event: 'cognitionForwarded',
        agentId: 'alpha',
        requestId: 'one',
        trigger: 'goalReview',
        importance: 'ROUTINE',
        outcome: 'cognitionFailure',
        provenance: null,
        outstandingAtAdmission: 1,
        failure
      }
    )
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('a controlled body crash leaves one scheduled cognition call for each peer', async () => {
  const upstream = await startUpstream()
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents: ['alpha', 'beta', 'gamma'],
    budget: {
      perAgentCallCeiling: 3,
      globalCallCeiling: 6,
      globalOutstandingCeiling: 3
    },
    postCrashReserve: {
      crashAgent: 'alpha',
      callsPerPeer: 1
    },
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  await proxy.start()
  try {
    const activeBodies = new Set(['alpha', 'beta', 'gamma'])
    const scheduledCycle = async (cycle) => await Promise.all(
      [...activeBodies].sort().map(async (agent) => [
        agent,
        await request(proxy.listen.port, agent, `${cycle}-${agent}`)
      ])
    )

    const firstCycle = Object.fromEntries(await scheduledCycle('before-1'))
    assert.equal(firstCycle.alpha.intent.action, 'idle')
    assert.equal(firstCycle.beta.intent.action, 'idle')
    assert.equal(firstCycle.gamma.intent.action, 'idle')

    assert.equal((await request(proxy.listen.port, 'alpha', 'before-2-alpha')).intent.action, 'idle')
    assert.match((await request(proxy.listen.port, 'beta', 'before-2-beta')).error, /experiment budget/)
    assert.match((await request(proxy.listen.port, 'gamma', 'before-2-gamma')).error, /experiment budget/)

    activeBodies.delete('alpha')
    proxy.markControlledBodyCrash('alpha')

    const postCrashCycle = {}
    for (const agent of [...activeBodies].sort()) {
      postCrashCycle[agent] = await request(
        proxy.listen.port,
        agent,
        `scheduled-after-crash-${agent}`
      )
      if (agent === 'beta') {
        assert.match(
          (await request(proxy.listen.port, agent, 'extra-before-gamma')).error,
          /experiment budget/
        )
      }
    }
    assert.equal(postCrashCycle.beta.intent.action, 'idle')
    assert.equal(postCrashCycle.gamma.intent.action, 'idle')
    const reserved = events.filter((event) => event.reason === 'postCrashCallReserve')
    assert.equal(reserved.length, 3)
    assert.ok(reserved.every((event) => event.bound === 'postCrashReserve' && event.forwarded === false))
    assert.deepEqual(proxy.snapshot().postCrashReserve.remainingByPeer, {
      beta: 0,
      gamma: 0
    })
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

function typedProxy (upstream, { budget, spend, agents = ['alpha', 'beta'] }) {
  const events = []
  const refusals = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents,
    budget,
    spend,
    requestOrigin: (trigger) =>
      trigger === 'goalReview' ? 'nativeGoalReviewSchedule' : 'nativeEventDriver',
    journal: { append: (event, fields) => events.push({ event, ...fields }) },
    onRefusal: (refusal) => refusals.push(refusal)
  })
  return { proxy, events, refusals }
}

function typed (refusal) {
  const { agentId, bound, reason, unit, ceiling, observed, forwarded, origin, trigger } = refusal
  return { agentId, bound, reason, unit, ceiling, observed, forwarded, origin, trigger }
}

test('a refusal names its exact bound, agent, origin, ceiling, and observed count', async () => {
  const upstream = await startUpstream()
  const { proxy, events, refusals } = typedProxy(upstream, {
    budget: { perAgentCallCeiling: 1, globalCallCeiling: 2, globalOutstandingCeiling: 2 }
  })
  await proxy.start()
  try {
    await request(proxy.listen.port, 'alpha', 'a1')
    await request(proxy.listen.port, 'alpha', 'a2', 'damaged')
    await request(proxy.listen.port, 'beta', 'b1')
    await request(proxy.listen.port, 'beta', 'b2')
    assert.deepEqual(refusals.map(typed), [
      {
        agentId: 'alpha',
        bound: 'perAgentCall',
        reason: 'perAgentCallCeiling',
        unit: 'calls',
        ceiling: 1,
        observed: 1,
        forwarded: false,
        origin: 'nativeEventDriver',
        trigger: 'damaged'
      },
      {
        agentId: 'beta',
        bound: 'globalCall',
        reason: 'globalCallCeiling',
        unit: 'calls',
        ceiling: 2,
        observed: 2,
        forwarded: false,
        origin: 'nativeGoalReviewSchedule',
        trigger: 'goalReview'
      }
    ])
    assert.deepEqual(
      events.filter((event) => event.event === 'cognitionRefused').map(typed),
      refusals.map(typed)
    )
    assert.deepEqual(proxy.snapshot().refusedByBound, { perAgentCall: 1, globalCall: 1 })
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('a native event call arriving with every outstanding slot occupied is a globalOutstanding refusal', async () => {
  const upstream = await startUpstream(150)
  const { proxy, refusals } = typedProxy(upstream, {
    agents: ['alpha', 'beta', 'gamma', 'delta'],
    budget: { perAgentCallCeiling: 4, globalCallCeiling: 12, globalOutstandingCeiling: 3 }
  })
  await proxy.start()
  try {
    const inFlight = ['alpha', 'beta', 'gamma'].map((agent) =>
      request(proxy.listen.port, agent, `${agent}-review`))
    await new Promise((resolve) => setTimeout(resolve, 40))
    const refused = await request(proxy.listen.port, 'delta', 'delta-threat', 'threatSighted')
    assert.match(refused.error, /experiment budget/)
    assert.deepEqual(refusals.map(typed), [{
      agentId: 'delta',
      bound: 'globalOutstanding',
      reason: 'globalOutstandingCeiling',
      unit: 'calls',
      ceiling: 3,
      observed: 3,
      forwarded: false,
      origin: 'nativeEventDriver',
      trigger: 'threatSighted'
    }])
    for (const response of await Promise.all(inFlight)) assert.equal(response.intent.action, 'idle')
    assert.equal(proxy.snapshot().forwardedCalls, 3)
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('the spend bound refuses a call whose worst-case price would cross the ceiling', async () => {
  const upstream = await startUpstream()
  const { proxy, refusals } = typedProxy(upstream, {
    budget: { perAgentCallCeiling: 4, globalCallCeiling: 8, globalOutstandingCeiling: 2 },
    spend: { ceilingNanoUsd: 250, worstCaseCallNanoUsd: 100 }
  })
  await proxy.start()
  try {
    await request(proxy.listen.port, 'alpha', 'a1')
    await request(proxy.listen.port, 'beta', 'b1')
    assert.match((await request(proxy.listen.port, 'alpha', 'a2')).error, /experiment budget/)
    assert.deepEqual(refusals.map(typed), [{
      agentId: 'alpha',
      bound: 'spend',
      reason: 'spendCeiling',
      unit: 'nanoUsd',
      ceiling: 250,
      observed: 200,
      forwarded: false,
      origin: 'nativeGoalReviewSchedule',
      trigger: 'goalReview'
    }])
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

test('a malformed spend bound is refused at construction', () => {
  assert.throws(() => new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: 1 },
    agents: ['alpha'],
    budget: { perAgentCallCeiling: 1, globalCallCeiling: 1, globalOutstandingCeiling: 1 },
    spend: { ceilingNanoUsd: 1.5, worstCaseCallNanoUsd: 1 },
    journal: { append () {} }
  }), /spend bound/)
})

test('the proxy reserves each call at its tier and holds pivotal calls to their own ceiling', async () => {
  // r24 priced every call as pivotal and spent its 150-call ceiling after 149 calls.
  const upstream = await startUpstream()
  const events = []
  const proxy = new CognitionBudgetProxy({
    listen: { host: '127.0.0.1', port: 0 },
    upstream: { host: '127.0.0.1', port: upstream.address().port },
    agents: ['alpha'],
    budget: { perAgentCallCeiling: 10, globalCallCeiling: 10, globalOutstandingCeiling: 1, pivotalCallCeiling: 2 },
    // Four ordinary calls at 10 and two pivotal at 100 fit a ceiling of 240: every one priced as
    // pivotal, only two would.
    spend: { ceilingNanoUsd: 240, worstCaseCallNanoUsd: 100, tier: { ordinary: 10, pivotal: 100 } },
    journal: { append: (event, fields) => events.push({ event, ...fields }) }
  })
  await proxy.start()
  try {
    for (const id of ['r1', 'r2', 'r3']) {
      assert.equal((await request(proxy.listen.port, 'alpha', id)).intent.action, 'idle', id)
    }
    for (const id of ['p1', 'p2']) {
      assert.equal((await request(proxy.listen.port, 'alpha', id, 'damaged', 'PIVOTAL')).intent.action, 'idle', id)
    }
    assert.match((await request(proxy.listen.port, 'alpha', 'p3', 'damaged', 'PIVOTAL')).error, /experiment budget/)
    const refused = events.filter((event) => event.event === 'cognitionRefused')
    assert.deepEqual(refused.map((event) => event.bound), ['pivotalCall'])
    // An ordinary call still fits what the pivotal ceiling left.
    assert.equal((await request(proxy.listen.port, 'alpha', 'r4')).intent.action, 'idle')
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})

// An upstream process that accepts a call and never answers it, run under the harness's own
// supervisor so the stop reaches it exactly as the run's stop reaches cognition.
const SILENT_UPSTREAM = `
const net = require('node:net')
const server = net.createServer((socket) => socket.on('data', () => {}))
server.listen(0, '127.0.0.1', () => process.stdout.write(String(server.address().port) + '\\n'))
`

async function silentUpstreamProcess (directory) {
  const { ProcessSupervisor } = require('../lib/lifecycle.js')
  const harnessEvents = []
  const supervisor = new ProcessSupervisor({
    logBytes: 1 << 16,
    journal: { append: (event, fields) => harnessEvents.push({ event, ...fields }) },
    onFailure: () => {}
  })
  const handle = supervisor.start({
    name: 'cognition',
    command: process.execPath,
    args: ['-e', SILENT_UPSTREAM],
    env: process.env,
    logFile: require('node:path').join(directory, 'upstream.log')
  })
  const port = await new Promise((resolve) => handle.child.stdout.once('data', (chunk) => resolve(Number(String(chunk).trim()))))
  return { supervisor, port, harnessEvents }
}

for (const stopping of [true, false]) {
  test(stopping
    ? 'a call still with the mind when the run stops its processes ends as runStopping, not an upstream fault'
    : 'a call cut off by an upstream that goes away mid-run is still an upstream fault', async () => {
    // r28: the stop signalled cognition while alpha's damage call was still with it, and the call
    // was journalled upstreamUnavailable, which reads as a provider failure.
    const fs = require('node:fs')
    const os = require('node:os')
    const directory = fs.mkdtempSync(require('node:path').join(os.tmpdir(), 'loom-stop-proxy-'))
    const { supervisor, port } = await silentUpstreamProcess(directory)
    const events = []
    const unavailable = []
    const proxy = new CognitionBudgetProxy({
      listen: { host: '127.0.0.1', port: 0 },
      upstream: { host: '127.0.0.1', port },
      agents: ['alpha'],
      budget: { perAgentCallCeiling: 4, globalCallCeiling: 4, globalOutstandingCeiling: 2 },
      journal: { append: (event, fields) => events.push({ event, ...fields }) },
      onUnavailable: (call) => unavailable.push(call)
    })
    await proxy.start()
    try {
      const answer = request(proxy.listen.port, 'alpha', 'zombie', 'damaged', 'PIVOTAL')
      await new Promise((resolve) => setTimeout(resolve, 100))
      assert.equal(proxy.snapshot().outstandingCalls, 1, 'the call is with the mind')
      if (stopping) proxy.beginStopping()
      await supervisor.stopAll(2000)
      const refused = await answer
      const forwarded = events.filter((record) => record.event === 'cognitionForwarded')
      assert.deepEqual(forwarded.map((record) => record.outcome), [stopping ? 'runStopping' : 'upstreamUnavailable'])
      assert.equal(unavailable.length, stopping ? 0 : 1, 'only a mid-run loss reaches the run as a failure')
      assert.equal(typeof refused.error, 'string')
      if (stopping) {
        // Nothing new is admitted once the stop has begun.
        const late = await request(proxy.listen.port, 'alpha', 'late', 'damaged', 'PIVOTAL')
        assert.equal(late.code, 'budgetRefused')
        assert.equal(events.at(-1).event, 'cognitionRefused')
        assert.equal(events.at(-1).bound, 'admissionClosed')
        assert.equal(events.at(-1).reason, 'runStopping')
      }
    } finally {
      await proxy.close()
      fs.rmSync(directory, { recursive: true, force: true })
    }
  })
}
