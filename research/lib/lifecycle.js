'use strict'

const fs = require('node:fs')
const net = require('node:net')
const path = require('node:path')
const { spawn } = require('node:child_process')

const { canonicalLine } = require('./util.js')

class RunFailure extends Error {
  constructor (stopReason, message, code) {
    super(message)
    this.name = 'RunFailure'
    this.stopReason = stopReason
    if (code !== undefined) this.code = code
  }
}

class BoundedJournal {
  constructor (file, maxBytes, clock = () => new Date()) {
    this.file = file
    this.maxBytes = maxBytes
    this.clock = clock
    this.bytes = 0
    this.closed = false
    fs.mkdirSync(path.dirname(file), { recursive: true })
    this.descriptor = fs.openSync(file, 'wx')
  }

  append (event, fields = {}) {
    if (this.closed) throw new RunFailure('diskFailure', 'harness journal is closed')
    const record = {
      observedAt: this.clock().toISOString(),
      event,
      ...fields
    }
    const line = `${canonicalLine(record)}\n`
    const bytes = Buffer.byteLength(line)
    if (this.bytes + bytes > this.maxBytes) {
      throw new RunFailure('logLimit', `harness journal exceeded ${this.maxBytes} bytes`)
    }
    try {
      fs.writeSync(this.descriptor, line)
      fs.fsyncSync(this.descriptor)
    } catch {
      throw new RunFailure('diskFailure', 'harness journal write failed')
    }
    this.bytes += bytes
    return record
  }

  close () {
    if (this.closed) return
    this.closed = true
    fs.closeSync(this.descriptor)
  }
}

class BoundedLog {
  constructor (file, maxBytes, onFailure) {
    this.file = file
    this.maxBytes = maxBytes
    this.onFailure = onFailure
    this.bytes = 0
    this.failed = false
    fs.mkdirSync(path.dirname(file), { recursive: true })
    this.stream = fs.createWriteStream(file, { flags: 'wx' })
    this.stream.on('error', () => this.fail(new RunFailure('diskFailure', `${path.basename(file)} write failed`)))
  }

  write (chunk) {
    if (this.failed) return
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    if (this.bytes + buffer.length > this.maxBytes) {
      this.fail(new RunFailure('logLimit', `${path.basename(this.file)} exceeded ${this.maxBytes} bytes`))
      return
    }
    this.bytes += buffer.length
    this.stream.write(buffer)
  }

  fail (failure) {
    if (this.failed) return
    this.failed = true
    this.onFailure(failure)
  }

  close () {
    return new Promise((resolve) => {
      if (this.stream.closed) return resolve()
      this.stream.end(resolve)
    })
  }
}

function delay (milliseconds, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason ?? new RunFailure('cancelled', 'run cancelled'))
    const finished = (callback, value) => {
      signal?.removeEventListener('abort', cancelled)
      callback(value)
    }
    const timer = setTimeout(() => finished(resolve), milliseconds)
    const cancelled = () => {
      clearTimeout(timer)
      finished(reject, signal.reason ?? new RunFailure('cancelled', 'run cancelled'))
    }
    signal?.addEventListener('abort', cancelled, { once: true })
  })
}

function waitForExit (child, milliseconds) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve(true)
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.removeListener('exit', exited)
      resolve(false)
    }, milliseconds)
    const exited = () => {
      clearTimeout(timer)
      resolve(true)
    }
    child.once('exit', exited)
  })
}

function signalProcessGroup (child, signal) {
  if (!Number.isInteger(child.pid) || child.pid <= 0) return false
  try {
    if (process.platform === 'win32') {
      if (child.exitCode !== null || child.signalCode !== null) return false
      child.kill(signal)
    } else {
      process.kill(-child.pid, signal)
    }
    return true
  } catch (cause) {
    if (cause.code === 'ESRCH') return false
    throw cause
  }
}

function processGroupIsAlive (child) {
  if (!Number.isInteger(child.pid) || child.pid <= 0) return false
  if (process.platform === 'win32') {
    return child.exitCode === null && child.signalCode === null
  }
  try {
    process.kill(-child.pid, 0)
    return true
  } catch (cause) {
    if (cause.code === 'ESRCH') return false
    if (cause.code === 'EPERM') return true
    throw cause
  }
}

async function waitForProcessGroupExit (child, milliseconds) {
  const deadline = Date.now() + milliseconds
  while (Date.now() < deadline) {
    if (!processGroupIsAlive(child)) return true
    await delay(Math.min(20, Math.max(1, deadline - Date.now())))
  }
  return !processGroupIsAlive(child)
}

class ProcessSupervisor {
  constructor ({ logBytes, journal, onFailure }) {
    this.logBytes = logBytes
    this.journal = journal
    this.onFailure = onFailure
    this.processes = []
    this.stopping = false
  }

  start ({
    name,
    command,
    args = [],
    cwd,
    env,
    critical = true,
    logFile,
    stdin = false
  }) {
    const log = new BoundedLog(logFile, this.logBytes, this.onFailure)
    let child
    try {
      child = spawn(command, args, {
        cwd,
        env,
        detached: process.platform !== 'win32',
        stdio: [stdin ? 'pipe' : 'ignore', 'pipe', 'pipe']
      })
    } catch {
      log.close()
      throw new RunFailure('startupFailed', `${name} could not be spawned`)
    }
    const handle = { name, child, log, critical }
    this.processes.push(handle)
    child.stdout.on('data', (chunk) => log.write(chunk))
    child.stderr.on('data', (chunk) => log.write(chunk))
    child.once('error', () => {
      if (!this.stopping) this.onFailure(new RunFailure('startupFailed', `${name} process error`))
    })
    child.once('spawn', () => {
      try {
        this.journal.append('processStarted', { component: name })
      } catch (cause) {
        this.onFailure(cause)
      }
    })
    child.once('exit', (code, signal) => {
      try {
        this.journal.append('processExited', {
          component: name,
          exitCode: Number.isInteger(code) ? code : null,
          signal: signal ?? null,
          expected: this.stopping
        })
      } catch (cause) {
        this.onFailure(cause)
      }
      if (!this.stopping && critical) {
        const reason = name === 'paper' ? 'paperFailed' : name === 'cognition' ? 'cognitionFailed' : 'startupFailed'
        this.onFailure(new RunFailure(reason, `${name} exited before cleanup`))
      }
    })
    return handle
  }

  async stopAll (shutdownMilliseconds) {
    this.stopping = true
    const reversed = [...this.processes].reverse()
    for (const handle of reversed) {
      if (handle.name === 'paper' && handle.child.stdin?.writable) {
        handle.child.stdin.write('stop\n')
      } else {
        this.signal(handle, 'SIGTERM')
      }
    }
    const killReserve = Math.min(1000, Math.max(50, Math.floor(shutdownMilliseconds / 5)))
    const preKillBudget = Math.max(2, shutdownMilliseconds - killReserve)
    const gracefulBudget = Math.max(1, Math.floor(preKillBudget / 2))
    const terminateBudget = Math.max(1, preKillBudget - gracefulBudget)
    await Promise.all(
      reversed.map((handle) => waitForProcessGroupExit(handle.child, gracefulBudget))
    )
    for (const handle of reversed) {
      if (processGroupIsAlive(handle.child)) this.signal(handle, 'SIGTERM')
    }
    await Promise.all(
      reversed.map((handle) => waitForProcessGroupExit(handle.child, terminateBudget))
    )
    for (const handle of reversed) {
      if (processGroupIsAlive(handle.child)) this.signal(handle, 'SIGKILL')
    }
    const groupsClosed = await Promise.all(
      reversed.map((handle) => waitForProcessGroupExit(handle.child, killReserve))
    )
    await Promise.all(reversed.map((handle) => handle.log.close()))
    const survivors = reversed
      .filter((_handle, index) => !groupsClosed[index])
      .map((handle) => handle.name)
    if (survivors.length > 0) {
      throw new RunFailure('startupFailed', `managed process groups survived cleanup: ${survivors.join(', ')}`)
    }
  }

  signal (handle, signal) {
    if (!signalProcessGroup(handle.child, signal)) return
    try {
      this.journal.append('processGroupSignalled', {
        component: handle.name,
        signal
      })
    } catch (cause) {
      this.onFailure(cause)
    }
  }
}

async function waitForPort ({ host, port, timeoutMilliseconds, signal }) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (signal?.aborted) throw signal.reason ?? new RunFailure('cancelled', 'run cancelled')
    const ready = await new Promise((resolve) => {
      const socket = net.createConnection({ host, port })
      const done = (value) => {
        socket.destroy()
        resolve(value)
      }
      socket.setTimeout(250)
      socket.once('connect', () => done(true))
      socket.once('error', () => done(false))
      socket.once('timeout', () => done(false))
    })
    if (ready) return
    await delay(50, signal)
  }
  throw new RunFailure('startupFailed', `${host}:${port} did not become ready`)
}

async function waitForCondition ({ description, timeoutMilliseconds, poll, signal }) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (signal?.aborted) throw signal.reason ?? new RunFailure('cancelled', 'run cancelled')
    if (await poll()) return
    await delay(100, signal)
  }
  throw new RunFailure('startupFailed', `${description} did not become true`)
}

module.exports = {
  BoundedJournal,
  BoundedLog,
  ProcessSupervisor,
  RunFailure,
  delay,
  processGroupIsAlive,
  signalProcessGroup,
  waitForCondition,
  waitForExit,
  waitForPort
}
