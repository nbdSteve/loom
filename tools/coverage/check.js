#!/usr/bin/env node
'use strict'

// Reads each runtime's machine-readable coverage report, prints a summary, and fails
// when any total or seam falls below its floor in floors.json. No dependencies.

const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '..', '..')
const FLOORS = JSON.parse(fs.readFileSync(path.join(__dirname, 'floors.json'), 'utf8'))

/** One counter: covered of total. */
function add (into, metric, covered, total) {
  const slot = into[metric] ?? (into[metric] = { covered: 0, total: 0 })
  slot.covered += covered
  slot.total += total
}

/** JaCoCo XML: `{ total, files }` keyed by `dev/loom/<pkg>/<File>.kt`. */
function readJacoco (file) {
  const xml = fs.readFileSync(file, 'utf8')
  const names = { LINE: 'lines', BRANCH: 'branches', METHOD: 'methods' }
  const counters = (text, into) => {
    for (const m of text.matchAll(/<counter type="(\w+)" missed="(\d+)" covered="(\d+)"\/>/g)) {
      const metric = names[m[1]]
      if (metric) add(into, metric, Number(m[3]), Number(m[2]) + Number(m[3]))
    }
  }
  const files = {}
  for (const pkg of xml.matchAll(/<package name="([^"]+)">([\s\S]*?)<\/package>/g)) {
    for (const source of pkg[2].matchAll(/<sourcefile name="([^"]+)">([\s\S]*?)<\/sourcefile>/g)) {
      counters(source[2], files[`${pkg[1]}/${source[1]}`] = {})
    }
  }
  const total = {}
  counters(xml.replace(/<package [\s\S]*?<\/package>/g, ''), total)
  return { total, files }
}

/** `cargo llvm-cov report --json --summary-only`. Branches need nightly rustc, so are omitted. */
function readLlvmCov (file) {
  const data = JSON.parse(fs.readFileSync(file, 'utf8')).data[0]
  const pick = (summary) => {
    const out = {}
    for (const metric of ['lines', 'functions', 'regions']) {
      add(out, metric, summary[metric].covered, summary[metric].count)
    }
    return out
  }
  const files = {}
  for (const entry of data.files) files[path.relative(ROOT, entry.filename)] = pick(entry.summary)
  return { total: pick(data.totals), files }
}

/** c8 / istanbul `coverage-summary.json`. */
function readIstanbul (file) {
  const data = JSON.parse(fs.readFileSync(file, 'utf8'))
  const pick = (summary) => {
    const out = {}
    for (const metric of ['lines', 'branches', 'functions']) {
      add(out, metric, summary[metric].covered, summary[metric].total)
    }
    return out
  }
  const files = {}
  for (const [name, summary] of Object.entries(data)) {
    if (name !== 'total') files[path.relative(ROOT, name)] = pick(summary)
  }
  return { total: pick(data.total), files }
}

const READERS = { jacoco: readJacoco, 'llvm-cov': readLlvmCov, istanbul: readIstanbul }

const percent = ({ covered, total }) => (total === 0 ? 0 : (100 * covered) / total)

function show (label, counts, floors, failures) {
  const cells = Object.keys(floors).map((metric) => {
    const count = counts[metric]
    if (count === undefined || count.total === 0) {
      failures.push(`${label}: no ${metric} measured`)
      return `${metric} -`
    }
    const value = percent(count)
    const ok = value >= floors[metric]
    if (!ok) failures.push(`${label}: ${metric} ${value.toFixed(2)}% < floor ${floors[metric]}%`)
    return `${metric} ${value.toFixed(2)}% (${count.covered}/${count.total}, floor ${floors[metric]})${ok ? '' : ' FAIL'}`
  })
  console.log(`  ${label}: ${cells.join('  ')}`)
}

function check (name, failures) {
  const runtime = FLOORS.runtimes[name]
  if (runtime === undefined) throw new Error(`unknown runtime ${name}`)
  const report = path.join(ROOT, runtime.report)
  if (!fs.existsSync(report)) {
    failures.push(`${name}: report missing at ${runtime.report}`)
    return
  }
  const { total, files } = READERS[runtime.format](report)
  console.log(`${name} (${runtime.report})`)
  show('total', total, runtime.total, failures)
  for (const [seam, spec] of Object.entries(runtime.seams ?? {})) {
    const counts = {}
    const matched = Object.keys(files).filter((file) => spec.paths.some((prefix) => file.startsWith(prefix)))
    if (matched.length === 0) failures.push(`${name}/${seam}: no file matches ${spec.paths.join(', ')}`)
    for (const file of matched) {
      for (const [metric, count] of Object.entries(files[file])) add(counts, metric, count.covered, count.total)
    }
    show(seam, counts, spec.floors, failures)
  }
}

const requested = process.argv.slice(2)
const names = requested.length > 0 ? requested : Object.keys(FLOORS.runtimes)
const failures = []
for (const name of names) check(name, failures)
if (failures.length > 0) {
  console.error(`\ncoverage below floor:\n  ${failures.join('\n  ')}`)
  process.exit(1)
}
console.log('\ncoverage floors hold')
