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

function request (port, agentId, requestId) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let response = ''
    socket.once('connect', () => {
      socket.write(`${JSON.stringify({
        requestId,
        agentId,
        trigger: { event: 'goalReview' },
        importance: 'ROUTINE'
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
