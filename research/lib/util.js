'use strict'

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

function sortedValue (value) {
  if (Array.isArray(value)) return value.map(sortedValue)
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, sortedValue(value[key])])
  )
}

function canonicalJson (value) {
  return `${JSON.stringify(sortedValue(value), null, 2)}\n`
}

function canonicalLine (value) {
  return JSON.stringify(sortedValue(value))
}

function sha256Buffer (buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex')
}

function sha256File (file) {
  const hash = crypto.createHash('sha256')
  const descriptor = fs.openSync(file, 'r')
  try {
    const buffer = Buffer.allocUnsafe(64 * 1024)
    while (true) {
      const count = fs.readSync(descriptor, buffer, 0, buffer.length, null)
      if (count === 0) break
      hash.update(buffer.subarray(0, count))
    }
  } finally {
    fs.closeSync(descriptor)
  }
  return hash.digest('hex')
}

function sourceTreeSha256 (root, relativeFiles) {
  const hash = crypto.createHash('sha256')
  for (const relative of [...relativeFiles].sort()) {
    const file = path.join(root, relative)
    hash.update(relative)
    hash.update('\0')
    hash.update(fs.readFileSync(file))
    hash.update('\0')
  }
  return hash.digest('hex')
}

function commandOutput (command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: options.timeout ?? 30_000
  }).trim()
}

function offlineIdentity (agentId) {
  if (!/^[A-Za-z0-9._:-]{1,64}$/.test(agentId)) {
    throw new Error(`invalid agent id ${JSON.stringify(agentId)}`)
  }
  const suffix = crypto.createHash('sha256').update(agentId).digest('hex').slice(0, 10)
  const username = `loom_${suffix}`
  const digest = crypto.createHash('md5').update(`OfflinePlayer:${username}`).digest()
  digest[6] = (digest[6] & 0x0f) | 0x30
  digest[8] = (digest[8] & 0x3f) | 0x80
  const hex = digest.toString('hex')
  const uuid = [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20)
  ].join('-')
  return { agentId, username, uuid }
}

function resolveRelativeFile (root, relative) {
  if (typeof relative !== 'string' || path.isAbsolute(relative)) {
    throw new Error(`artifact path must be relative: ${JSON.stringify(relative)}`)
  }
  const resolvedRoot = path.resolve(root)
  const resolved = path.resolve(root, relative)
  const prefix = `${resolvedRoot}${path.sep}`
  if (!resolved.startsWith(prefix)) {
    throw new Error(`artifact path escapes its directory: ${relative}`)
  }
  return resolved
}

function readJson (file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function writeJson (file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, canonicalJson(value), { encoding: 'utf8', flag: 'wx' })
}

module.exports = {
  canonicalJson,
  canonicalLine,
  commandOutput,
  offlineIdentity,
  readJson,
  resolveRelativeFile,
  sha256Buffer,
  sha256File,
  sortedValue,
  sourceTreeSha256,
  writeJson
}
