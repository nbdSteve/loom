'use strict'

const fs = require('node:fs')

// Paper's own game time, asked on its console and read back from its log. Body records are
// event-driven and can be thousands of ticks apart in a quiet world, so they cannot say
// when a tick ceiling has been reached; the server's clock can.
const GAME_TIME = /The time is (\d+)\s*$/
const QUERY_INTERVAL_MS = 2_000

class PaperClock {
  constructor ({ write, logFile, now = Date.now }) {
    this.write = write
    this.logFile = logFile
    this.now = now
    this.offset = 0
    this.pending = ''
    this.latest = null
    this.queriedAt = -Infinity
  }

  /** Asks for the game time at most every QUERY_INTERVAL_MS, then reads any answer. */
  poll () {
    if (this.now() - this.queriedAt >= QUERY_INTERVAL_MS) {
      this.queriedAt = this.now()
      this.write('time query gametime')
    }
    let text
    try {
      const descriptor = fs.openSync(this.logFile, 'r')
      try {
        const size = fs.fstatSync(descriptor).size
        if (size <= this.offset) return this.latest
        const buffer = Buffer.alloc(size - this.offset)
        fs.readSync(descriptor, buffer, 0, buffer.length, this.offset)
        this.offset = size
        text = this.pending + buffer.toString('utf8')
      } finally {
        fs.closeSync(descriptor)
      }
    } catch {
      return this.latest
    }
    const lines = text.split('\n')
    this.pending = lines.pop()
    for (const line of lines) {
      const match = GAME_TIME.exec(line)
      if (match !== null) this.latest = Number(match[1])
    }
    return this.latest
  }
}

module.exports = {
  PaperClock,
  QUERY_INTERVAL_MS
}
