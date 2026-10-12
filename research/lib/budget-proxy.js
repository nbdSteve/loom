'use strict'

const net = require('node:net')

const MAX_FRAME_BYTES = 256 * 1024

function refusal (message) {
  return `${JSON.stringify({ error: message })}\n`
}

class CognitionBudgetProxy {
  constructor ({ listen, upstream, agents, budget, journal, requestTimeoutMilliseconds = 20_000 }) {
    this.listen = listen
    this.upstream = upstream
    this.agents = new Set(agents)
    this.budget = budget
    this.journal = journal
    this.requestTimeoutMilliseconds = requestTimeoutMilliseconds
    this.total = 0
    this.outstanding = 0
    this.perAgent = new Map()
    this.connections = new Set()
    this.server = net.createServer((socket) => this.accept(socket))
  }

  start () {
    return new Promise((resolve, reject) => {
      const failed = (cause) => reject(cause)
      this.server.once('error', failed)
      this.server.listen(this.listen.port, this.listen.host, () => {
        this.server.removeListener('error', failed)
        this.listen.port = this.server.address().port
        resolve()
      })
    })
  }

  accept (socket) {
    this.connections.add(socket)
    socket.setNoDelay(true)
    let buffered = Buffer.alloc(0)
    let chain = Promise.resolve()
    socket.on('data', (chunk) => {
      buffered = Buffer.concat([buffered, chunk])
      if (buffered.length > MAX_FRAME_BYTES && buffered.indexOf(0x0a) === -1) {
        socket.end(refusal('cognition request exceeded the frame bound'))
        return
      }
      while (true) {
        const newline = buffered.indexOf(0x0a)
        if (newline === -1) break
        const frame = buffered.subarray(0, newline)
        buffered = buffered.subarray(newline + 1)
        chain = chain.then(() => this.route(frame, socket)).catch(() => {
          if (!socket.destroyed) socket.write(refusal('cognition proxy failed safely'))
        })
      }
    })
    socket.on('close', () => this.connections.delete(socket))
    socket.on('error', () => this.connections.delete(socket))
  }

  async route (frame, downstream) {
    let perception
    try {
      perception = JSON.parse(frame.toString('utf8'))
    } catch {
      downstream.write(refusal('cognition request was not valid JSON'))
      return
    }
    const agentId = perception?.agentId
    const requestId = perception?.requestId
    if (!this.agents.has(agentId) || typeof requestId !== 'string') {
      downstream.write(refusal('cognition request did not name a configured agent and request'))
      return
    }
    const agentCalls = this.perAgent.get(agentId) ?? 0
    let reason
    if (this.total >= this.budget.globalCallCeiling) reason = 'globalCallCeiling'
    if (agentCalls >= this.budget.perAgentCallCeiling) reason = 'perAgentCallCeiling'
    if (this.outstanding >= this.budget.globalOutstandingCeiling) reason = 'globalOutstandingCeiling'
    if (reason !== undefined) {
      this.journal.append('cognitionRefused', {
        agentId,
        requestId,
        reason,
        trigger: perception.trigger?.event ?? null,
        importance: perception.importance ?? null
      })
      downstream.write(refusal('cognition request refused by experiment budget'))
      return
    }

    this.total += 1
    this.outstanding += 1
    this.perAgent.set(agentId, agentCalls + 1)
    try {
      const response = await this.forward(frame)
      let parsed
      try {
        parsed = JSON.parse(response.toString('utf8'))
      } catch {
        parsed = null
      }
      this.journal.append('cognitionForwarded', {
        agentId,
        requestId,
        trigger: perception.trigger?.event ?? null,
        importance: perception.importance ?? null,
        outcome: parsed?.error === undefined ? 'decision' : 'upstreamRefusal',
        provenance: parsed?.provenance ?? null
      })
      downstream.write(response)
      downstream.write('\n')
    } catch {
      this.journal.append('cognitionForwarded', {
        agentId,
        requestId,
        trigger: perception.trigger?.event ?? null,
        importance: perception.importance ?? null,
        outcome: 'upstreamUnavailable'
      })
      downstream.write(refusal('cognition upstream was unavailable'))
    } finally {
      this.outstanding -= 1
    }
  }

  forward (frame) {
    return new Promise((resolve, reject) => {
      const socket = net.createConnection(this.upstream)
      let buffered = Buffer.alloc(0)
      let settled = false
      const finish = (callback, value) => {
        if (settled) return
        settled = true
        socket.destroy()
        callback(value)
      }
      socket.setTimeout(this.requestTimeoutMilliseconds)
      socket.once('connect', () => {
        socket.write(frame)
        socket.write('\n')
      })
      socket.on('data', (chunk) => {
        buffered = Buffer.concat([buffered, chunk])
        if (buffered.length > MAX_FRAME_BYTES) {
          finish(reject, new Error('upstream response exceeded the frame bound'))
          return
        }
        const newline = buffered.indexOf(0x0a)
        if (newline !== -1) finish(resolve, buffered.subarray(0, newline))
      })
      socket.once('timeout', () => finish(reject, new Error('upstream response timed out')))
      socket.once('error', (cause) => finish(reject, cause))
      socket.once('close', () => {
        if (!settled) finish(reject, new Error('upstream closed before a response'))
      })
    })
  }

  async close () {
    for (const socket of this.connections) socket.destroy()
    await new Promise((resolve) => {
      if (!this.server.listening) return resolve()
      this.server.close(resolve)
    })
  }
}

module.exports = {
  CognitionBudgetProxy,
  MAX_FRAME_BYTES
}
