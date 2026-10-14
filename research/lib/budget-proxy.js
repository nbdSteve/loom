'use strict'

const net = require('node:net')

const { validatedCognitionFailure } = require('./cognition-failure.js')
const { ADMISSION_CLOSED_EVENT, PROXY_REQUEST_TIMEOUT_MILLIS } = require('./controlled-stop.js')

const MAX_FRAME_BYTES = 256 * 1024
const REFUSAL_BOUNDS = Object.freeze([
  'admissionClosed',
  'globalOutstanding',
  'globalCall',
  'perAgentCall',
  'postCrashReserve',
  'spend'
])

function refusal (message) {
  return `${JSON.stringify({ error: message })}\n`
}

class CognitionBudgetProxy {
  constructor ({
    listen,
    upstream,
    agents,
    budget,
    models,
    journal,
    postCrashReserve,
    requestOrigin = () => undefined,
    onCognitionFailure = () => {},
    onRefusal = () => {},
    onUnavailable = () => {},
    spend,
    requestTimeoutMilliseconds = PROXY_REQUEST_TIMEOUT_MILLIS
  }) {
    this.listen = listen
    this.upstream = upstream
    this.agents = new Set(agents)
    this.budget = budget
    this.models = models
    this.journal = journal
    this.postCrashReserve = this.checkedPostCrashReserve(postCrashReserve)
    this.requestOrigin = requestOrigin
    this.postCrashReserveReleased = this.postCrashReserve === null
    this.postCrashCallsRemaining = new Map(
      this.postCrashReserve?.peers.map((agent) => [
        agent,
        this.postCrashReserve.callsPerPeer
      ]) ?? []
    )
    this.onCognitionFailure = onCognitionFailure
    this.onRefusal = onRefusal
    this.onUnavailable = onUnavailable
    this.requestTimeoutMilliseconds = requestTimeoutMilliseconds
    this.spend = this.checkedSpend(spend)
    this.total = 0
    this.admissionClosed = null
    this.refusedByBound = {}
    this.outstanding = 0
    this.perAgent = new Map()
    this.connections = new Set()
    this.server = net.createServer((socket) => this.accept(socket))
  }

  checkedPostCrashReserve (configured) {
    if (configured === undefined) return null
    const crashAgent = configured?.crashAgent
    const callsPerPeer = configured?.callsPerPeer
    if (!this.agents.has(crashAgent)) {
      throw new Error('post-crash reserve must name a configured crash agent')
    }
    if (
      !Number.isInteger(callsPerPeer) ||
      callsPerPeer < 1 ||
      callsPerPeer >= this.budget.perAgentCallCeiling
    ) {
      throw new Error('post-crash calls per peer must fit below the per-agent call ceiling')
    }
    const peers = [...this.agents].filter((agent) => agent !== crashAgent).sort()
    if (peers.length * callsPerPeer >= this.budget.globalCallCeiling) {
      throw new Error('post-crash peer reserve must fit below the global call ceiling')
    }
    return { crashAgent, callsPerPeer, peers }
  }

  // Spend is reserved at the worst configured call price, so the bound holds before
  // any provider usage is known.
  checkedSpend (configured) {
    if (configured === undefined) return null
    const { ceilingNanoUsd, worstCaseCallNanoUsd } = configured
    if (
      !Number.isSafeInteger(ceilingNanoUsd) || ceilingNanoUsd < 0 ||
      !Number.isSafeInteger(worstCaseCallNanoUsd) || worstCaseCallNanoUsd < 0
    ) {
      throw new Error('spend bound must be non-negative integer nano-USD')
    }
    return { ceilingNanoUsd, worstCaseCallNanoUsd }
  }

  // Returns the post-crash threshold this call would cross, or null.
  reserveBlocks (agentId, agentCalls) {
    const reserve = this.postCrashReserve
    if (reserve === null) return null
    if (!this.postCrashReserveReleased) {
      const perAgent = this.budget.perAgentCallCeiling - reserve.callsPerPeer
      if (reserve.peers.includes(agentId) && agentCalls >= perAgent) {
        return { ceiling: perAgent, observed: agentCalls }
      }
      const global =
        this.budget.globalCallCeiling - reserve.peers.length * reserve.callsPerPeer
      return this.total >= global ? { ceiling: global, observed: this.total } : null
    }
    if ((this.postCrashCallsRemaining.get(agentId) ?? 0) > 0) return null
    const remaining = [...this.postCrashCallsRemaining.values()]
      .reduce((sum, calls) => sum + calls, 0)
    const global = this.budget.globalCallCeiling - remaining
    return this.total >= global ? { ceiling: global, observed: this.total } : null
  }

  // The first bound this call would cross, in a fixed order, with its exact numbers.
  crossedBound (agentId, agentCalls) {
    const calls = (bound, reason, ceiling, observed) =>
      ({ bound, reason, unit: 'calls', ceiling, observed })
    if (this.admissionClosed !== null) {
      return calls('admissionClosed', this.admissionClosed.reason, 0, 0)
    }
    if (this.outstanding >= this.budget.globalOutstandingCeiling) {
      return calls(
        'globalOutstanding',
        'globalOutstandingCeiling',
        this.budget.globalOutstandingCeiling,
        this.outstanding
      )
    }
    if (this.total >= this.budget.globalCallCeiling) {
      return calls('globalCall', 'globalCallCeiling', this.budget.globalCallCeiling, this.total)
    }
    if (agentCalls >= this.budget.perAgentCallCeiling) {
      return calls('perAgentCall', 'perAgentCallCeiling', this.budget.perAgentCallCeiling, agentCalls)
    }
    const reserve = this.reserveBlocks(agentId, agentCalls)
    if (reserve !== null) {
      return calls('postCrashReserve', 'postCrashCallReserve', reserve.ceiling, reserve.observed)
    }
    if (this.spend !== null) {
      const reserved = this.total * this.spend.worstCaseCallNanoUsd
      if (reserved + this.spend.worstCaseCallNanoUsd > this.spend.ceilingNanoUsd) {
        return {
          bound: 'spend',
          reason: 'spendCeiling',
          unit: 'nanoUsd',
          ceiling: this.spend.ceilingNanoUsd,
          observed: reserved
        }
      }
    }
    return null
  }

  consumePostCrashReserve (agentId) {
    if (!this.postCrashReserveReleased) return
    const remaining = this.postCrashCallsRemaining.get(agentId) ?? 0
    if (remaining > 0) this.postCrashCallsRemaining.set(agentId, remaining - 1)
  }

  markControlledBodyCrash (agentId) {
    const reserve = this.postCrashReserve
    if (reserve === null) return
    if (agentId !== reserve.crashAgent) {
      throw new Error('controlled body crash did not match the reserved crash agent')
    }
    if (this.postCrashReserveReleased) return
    this.postCrashReserveReleased = true
    this.journal.append('postCrashCallReserveReleased', {
      agentId,
      callsPerPeer: reserve.callsPerPeer,
      peers: reserve.peers
    })
  }

  // Refuses every later request. Journalled synchronously so evidence order separates
  // work admitted before the stop from work refused after it.
  closeAdmission (stop, event = ADMISSION_CLOSED_EVENT) {
    if (this.admissionClosed !== null) return false
    this.admissionClosed = { reason: 'admissionClosed' }
    this.journal.append(event, {
      ...stop,
      outstandingCalls: this.outstanding,
      forwardedCalls: this.total
    })
    return true
  }

  snapshot () {
    const reserve = this.postCrashReserve
    return {
      forwardedCalls: this.total,
      outstandingCalls: this.outstanding,
      admissionClosed: this.admissionClosed !== null,
      refusedByBound: { ...this.refusedByBound },
      callsByAgent: Object.fromEntries(
        [...this.agents]
          .sort()
          .map((agent) => [agent, this.perAgent.get(agent) ?? 0])
      ),
      postCrashReserve: reserve === null
        ? null
        : {
            crashAgent: reserve.crashAgent,
            callsPerPeer: reserve.callsPerPeer,
            peers: reserve.peers,
            released: this.postCrashReserveReleased,
            remainingByPeer: Object.fromEntries(
              reserve.peers.map((agent) => [
                agent,
                this.postCrashCallsRemaining.get(agent) ?? 0
              ])
            )
          }
    }
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
    const origin = this.requestOrigin(perception.trigger?.event)
    const originField = origin === undefined ? {} : { origin }
    const crossed = this.crossedBound(agentId, agentCalls)
    if (crossed !== null) {
      this.refusedByBound[crossed.bound] = (this.refusedByBound[crossed.bound] ?? 0) + 1
      const refused = {
        agentId,
        requestId,
        ...crossed,
        forwarded: false,
        trigger: perception.trigger?.event ?? null,
        importance: perception.importance ?? null,
        ...originField
      }
      this.journal.append('cognitionRefused', refused)
      this.onRefusal(refused)
      downstream.write(refusal('cognition request refused by experiment budget'))
      return
    }

    this.total += 1
    this.outstanding += 1
    const outstandingAtAdmission = this.outstanding
    this.perAgent.set(agentId, agentCalls + 1)
    this.consumePostCrashReserve(agentId)
    try {
      const response = await this.forward(frame)
      let parsed
      try {
        parsed = JSON.parse(response.toString('utf8'))
      } catch {
        parsed = null
      }
      const cognitionFailed = typeof parsed?.error === 'string'
      const failure = cognitionFailed
        ? validatedCognitionFailure(parsed.failure, {
          models: this.models,
          importance: perception.importance
        })
        : null
      this.journal.append('cognitionForwarded', {
        agentId,
        requestId,
        trigger: perception.trigger?.event ?? null,
        importance: perception.importance ?? null,
        outcome: cognitionFailed ? 'cognitionFailure' : 'decision',
        provenance: parsed?.provenance ?? null,
        outstandingAtAdmission,
        ...originField,
        ...(failure === null ? {} : { failure })
      })
      if (cognitionFailed) {
        this.onCognitionFailure(parsed.error, failure, {
          agentId,
          requestId,
          trigger: perception.trigger?.event ?? null,
          importance: perception.importance ?? null,
          ...originField
        })
      }
      downstream.write(response)
      downstream.write('\n')
    } catch {
      this.journal.append('cognitionForwarded', {
        agentId,
        requestId,
        trigger: perception.trigger?.event ?? null,
        importance: perception.importance ?? null,
        outcome: 'upstreamUnavailable',
        outstandingAtAdmission,
        ...originField
      })
      this.onUnavailable({ agentId, requestId })
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
  MAX_FRAME_BYTES,
  REFUSAL_BOUNDS
}
