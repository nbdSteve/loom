'use strict'

const fs = require('node:fs')

const READ_CHUNK_BYTES = 1024 * 1024

// Reads only the bytes appended since the last poll and yields complete JSON lines, so
// a long run's poll loop costs what was written rather than the whole log each time.
class JsonLinesTail {
  constructor (file) {
    this.file = file
    this.offset = 0
    this.pending = Buffer.alloc(0)
    this.maxTick = 0
  }

  poll () {
    let descriptor
    try {
      descriptor = fs.openSync(this.file, 'r')
    } catch (cause) {
      if (cause.code === 'ENOENT') return []
      throw cause
    }
    const records = []
    try {
      const buffer = Buffer.alloc(READ_CHUNK_BYTES)
      while (true) {
        const read = fs.readSync(descriptor, buffer, 0, buffer.length, this.offset)
        if (read === 0) break
        this.offset += read
        // Split on bytes, never on decoded text, so a multi-byte character can straddle reads.
        const bytes = Buffer.concat([this.pending, buffer.subarray(0, read)])
        const lastNewline = bytes.lastIndexOf(0x0a)
        if (lastNewline === -1) {
          this.pending = bytes
          continue
        }
        this.pending = Buffer.from(bytes.subarray(lastNewline + 1))
        for (const line of bytes.toString('utf8', 0, lastNewline).split('\n')) {
          if (line.length === 0) continue
          const record = JSON.parse(line)
          if (Number.isFinite(record.tick) && record.tick > this.maxTick) this.maxTick = record.tick
          records.push(record)
        }
      }
    } finally {
      fs.closeSync(descriptor)
    }
    return records
  }
}

function maximumOf (values, empty = 0) {
  let result
  for (const value of values) {
    if (result === undefined || value > result) result = value
  }
  return result ?? empty
}

function minimumOf (values, empty = 0) {
  let result
  for (const value of values) {
    if (result === undefined || value < result) result = value
  }
  return result ?? empty
}

module.exports = {
  JsonLinesTail,
  maximumOf,
  minimumOf
}
