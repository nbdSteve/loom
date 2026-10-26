'use strict'

// Repository-wide capability-completeness audit.
//
// Every surface that advertises a capability - the contract, the model prompt, the
// strict provider schema, the JVM actuation, the body-control wire in both codecs,
// the Node handlers, the server witness, the research evidence, the plugin commands
// and the operator docs - is read from its own source and compared with the others.
// The single exemption list is `contract/fixtures/capability-support.json`: a
// variant may be dormant only there, and a dormant variant must then be hidden from
// every model-facing surface. Nothing in this file names a capability by hand.

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const protocol = require('../src/protocol')

const ROOT = path.join(__dirname, '..', '..')
const read = (...parts) => fs.readFileSync(path.join(ROOT, ...parts), 'utf8')
const json = (...parts) => JSON.parse(read(...parts))
const sorted = (values) => [...new Set(values)].sort()

const SUPPORT = json('contract', 'fixtures', 'capability-support.json')
const LOOM_SCHEMA = json('contract', 'loom.schema.json')
const BODY_SCHEMA = json('contract', 'body-control.schema.json')

const KOTLIN_MAIN = path.join(ROOT, 'plugin', 'src', 'main', 'kotlin', 'dev', 'loom')
const kotlin = (...parts) => fs.readFileSync(path.join(KOTLIN_MAIN, ...parts), 'utf8')
const RUST_SRC = path.join(ROOT, 'cognition', 'crates')
const rust = (...parts) => fs.readFileSync(path.join(RUST_SRC, ...parts), 'utf8')

function walk (directory, extension) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) return walk(full, extension)
    return entry.name.endsWith(extension) ? [full] : []
  })
}

// The text of one brace-delimited block opened by the first line matching `opener`.
function blockAfter (source, opener) {
  const start = source.search(opener)
  assert.notEqual(start, -1, `no block matches ${opener}`)
  const open = source.indexOf('{', start)
  let depth = 0
  for (let at = open; at < source.length; at++) {
    if (source[at] === '{') depth++
    else if (source[at] === '}' && --depth === 0) return source.slice(open + 1, at)
  }
  throw new Error(`unterminated block for ${opener}`)
}

// Only the block's own top-level members: nested field renames sit deeper.
const topLevel = (block, pattern) =>
  [...block.matchAll(new RegExp(`^ {4}${pattern}`, 'gm'))].map((match) => match[1])

const schemaTags = (union, tag) => LOOM_SCHEMA.$defs[union].oneOf.map((variant) => variant.properties[tag].const)
const rustTags = (name) => topLevel(blockAfter(rust('contract', 'src', 'lib.rs'), new RegExp(`pub enum ${name} \\{`)), '#\\[serde\\(rename = "([A-Za-z]+)"')
const kotlinTags = (name) => topLevel(blockAfter(kotlin('contract', 'Contract.kt'), new RegExp(`sealed interface ${name}\\b`)), '@SerialName\\("([A-Za-z]+)"\\)')
const classified = (kind, status) => Object.entries(SUPPORT[kind]).filter(([, entry]) => entry.status === status).map(([name]) => name)
const kotlinName = (tag) => tag[0].toUpperCase() + tag.slice(1)

const INTENTS = sorted(schemaTags('Intent', 'action'))
const TRIGGERS = sorted(schemaTags('Trigger', 'event'))
const SUPPORTED_INTENTS = sorted(classified('intents', 'supported'))
const DORMANT_INTENTS = sorted(classified('intents', 'dormant'))

test('every contract intent and trigger is the same set in schema, Rust, Kotlin, fixtures and the classification', () => {
  assert.deepEqual(sorted(rustTags('Intent')), INTENTS, 'Rust Intent variants')
  assert.deepEqual(sorted(kotlinTags('Intent')), INTENTS, 'Kotlin Intent variants')
  assert.deepEqual(sorted(Object.keys(SUPPORT.intents)), INTENTS, 'classified intents')
  assert.deepEqual(sorted(rustTags('Trigger')), TRIGGERS, 'Rust Trigger variants')
  assert.deepEqual(sorted(kotlinTags('Trigger')), TRIGGERS, 'Kotlin Trigger variants')
  assert.deepEqual(sorted(Object.keys(SUPPORT.triggers)), TRIGGERS, 'classified triggers')
  for (const entry of [...Object.values(SUPPORT.intents), ...Object.values(SUPPORT.triggers)]) {
    assert.ok(['supported', 'dormant'].includes(entry.status), `unknown status ${entry.status}`)
    if (entry.status === 'dormant') assert.ok(entry.reason, 'a dormant variant must state why')
  }
  const fixtures = path.join(ROOT, 'contract', 'fixtures', 'valid')
  const actions = fs.readdirSync(fixtures).filter((name) => name.startsWith('decision-'))
    .map((name) => JSON.parse(fs.readFileSync(path.join(fixtures, name), 'utf8')).intent.action)
  assert.deepEqual(sorted(actions), INTENTS, 'every intent has a valid decision fixture')
  const events = fs.readdirSync(fixtures).filter((name) => name.startsWith('perception-'))
    .map((name) => JSON.parse(fs.readFileSync(path.join(fixtures, name), 'utf8')).trigger.event)
  assert.deepEqual(sorted(events), TRIGGERS, 'every trigger has a valid perception fixture')
})

// The action names the agent brief lists, in its own `- name:` bullet form.
function promptActions () {
  const source = rust('cognition', 'src', 'prompt.rs')
  const brief = source.slice(source.indexOf('const AGENT_BRIEF'))
  const list = brief.slice(brief.indexOf('actions:'), brief.indexOf('\n\n', brief.indexOf('actions:')))
  return [...list.matchAll(/^- ([A-Za-z]+):/gm)].map((match) => match[1])
}

// The intent tags the strict provider schema offers, read from its builder.
function strictSchemaActions () {
  const source = rust('cognition', 'src', 'openai', 'wire.rs')
  const builder = blockAfter(source, /fn build_decision_schema\([^)]*\) -> Value \{/)
  const named = (block) => [...block.matchAll(/intent_variant\(\s*"([A-Za-z]+)"/g)].map((match) => match[1])
  const direct = named(builder)
  const helpers = [...builder.matchAll(/\b([a-z_]+_variant)\(\)/g)].map((match) => match[1])
  const indirect = helpers.flatMap((helper) =>
    [...blockAfter(source, new RegExp(`fn ${helper}\\(\\) -> Value \\{`)).matchAll(/"action"[^]*?"(?:const|enum)"\s*:\s*\[?\s*"([A-Za-z]+)"/g)].map((match) => match[1]))
  // The variant helpers the builder extends its list with, each taking what was offered.
  const extended = [...builder.matchAll(/\b([a-z_]+_variants)\(offered\)/g)].map((match) => match[1])
    .flatMap((helper) => named(blockAfter(source, new RegExp(`fn ${helper}\\(`))))
  return [...direct, ...indirect, ...extended]
}

test('the prompt advertises exactly the supported intents and states their count', () => {
  const advertised = sorted(promptActions())
  assert.deepEqual(advertised, SUPPORTED_INTENTS, 'prompt action list must equal the supported intents')
  for (const dormant of DORMANT_INTENTS) {
    assert.ok(!advertised.includes(dormant), `the prompt advertises dormant intent ${dormant}`)
  }
  const words = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen']
  const source = rust('cognition', 'src', 'prompt.rs')
  const stated = source.match(/accepts exactly ([a-z]+) actions/)
  assert.ok(stated, 'the brief states how many actions it accepts')
  assert.equal(stated[1], words[promptActions().length], 'the stated action count matches the list')
})

test('the strict provider schema can emit exactly the supported intents', () => {
  const emitted = strictSchemaActions()
  assert.ok(emitted.length >= SUPPORTED_INTENTS.length - 3, `could not read the strict schema builder: ${emitted}`)
  const wire = rust('cognition', 'src', 'openai', 'wire.rs')
  const all = sorted(INTENTS.filter((tag) => new RegExp(`intent_variant\\("${tag}"|"${tag}",\\s*$`, 'm').test(wire.slice(0, wire.indexOf('#[cfg(test)]')))))
  assert.deepEqual(all, SUPPORTED_INTENTS, 'the strict schema must offer exactly the supported intents')
})

test('every supported intent has a JVM actuation branch that reaches the body, and only dormant ones refuse unconditionally', () => {
  const source = kotlin('body', 'player', 'PlayerActuation.kt')
  const prepare = blockAfter(source, /fun prepare\(\s*runtime: PlayerRuntime,\s*intent: Intent,/)
  const branches = new Map([...prepare.matchAll(/is Intent\.([A-Za-z]+) ->([^\n]*(?:\n(?! {12}is Intent)[^\n]*)*)/g)]
    .map((match) => [match[1], match[2]]))
  assert.deepEqual(sorted([...branches.keys()]), sorted(INTENTS.map(kotlinName)), 'one branch per intent')
  for (const [name, body] of branches) {
    const tag = name[0].toLowerCase() + name.slice(1)
    const refuses = /IntentOutcome\.Unsupported\(/.test(body)
    if (SUPPORT.intents[tag].status === 'supported') {
      assert.ok(!refuses, `supported intent ${tag} ends in Unsupported`)
    } else {
      assert.ok(refuses, `dormant intent ${tag} is classified dormant but its branch does something`)
    }
  }
  for (const file of walk(KOTLIN_MAIN, '.kt')) {
    const text = fs.readFileSync(file, 'utf8')
    for (const match of text.matchAll(/IntentOutcome\.Unsupported\("([A-Za-z]+)"\)/g)) {
      assert.equal(SUPPORT.intents[match[1]]?.status, 'dormant', `${path.relative(ROOT, file)} refuses ${match[1]} unconditionally`)
    }
  }
})

test('every supported trigger has a production producer and a dormant one has none', () => {
  const producers = new Set()
  for (const file of walk(KOTLIN_MAIN, '.kt')) {
    if (file.includes(`${path.sep}contract${path.sep}`)) continue
    const text = fs.readFileSync(file, 'utf8')
    for (const match of text.matchAll(/Trigger\.([A-Za-z]+)(?:\(|\b(?!\s*->))/g)) {
      if (!new RegExp(`is Trigger\\.${match[1]}\\b`).test(text.slice(Math.max(0, match.index - 3), match.index + match[0].length))) producers.add(match[1])
    }
  }
  for (const [tag, entry] of Object.entries(SUPPORT.triggers)) {
    const produced = producers.has(kotlinName(tag))
    assert.equal(produced, entry.status === 'supported', `trigger ${tag} is ${entry.status} but production ${produced ? 'constructs' : 'never constructs'} it`)
  }
})

const OUTCOME_ENUMS = Object.keys(BODY_SCHEMA.$defs).filter((name) => name.endsWith('Outcome'))
const OPERATIONS = BODY_SCHEMA.$defs.ControlOperation.enum
const BODY_CONTROL_SOURCES = () => ['BodyControl.kt', 'BodyControlSpeech.kt'].map((file) => kotlin('bodycontrol', file)).join('\n')
const kotlinEnum = (name) => topLevel(blockAfter(BODY_CONTROL_SOURCES(), new RegExp(`enum class ${name} \\{`)), '@(?:kotlinx\\.serialization\\.)?SerialName\\("([A-Za-z]+)"\\)')
const constant = (name) => name.replace(/Outcome$/, '').replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase() + '_OUTCOMES'

test('every body-control operation is the same set in schema, Node codec, JVM codec, fixtures, Node dispatch, JVM request path and classification', () => {
  assert.deepEqual(sorted(protocol.OPERATIONS ?? []), sorted(OPERATIONS), 'Node codec operations')
  const kotlinOps = topLevel(blockAfter(kotlin('bodycontrol', 'BodyControl.kt'), /sealed interface BodyRequest \{/), '@SerialName\\("([A-Za-z]+)"\\)')
  assert.deepEqual(sorted(kotlinOps), sorted(OPERATIONS), 'JVM codec operations')
  assert.deepEqual(sorted(Object.keys(SUPPORT.bodyOperations)), sorted(OPERATIONS), 'classified operations')
  const fixtures = path.join(ROOT, 'contract', 'fixtures', 'body-control', 'valid')
  const frames = fs.readdirSync(fixtures).map((name) => JSON.parse(fs.readFileSync(path.join(fixtures, name), 'utf8')))
  const requested = frames.filter((frame) => frame.op && !('state' in frame)).map((frame) => frame.op)
  const answered = frames.filter((frame) => 'state' in frame && frame.op).map((frame) => frame.op)
  assert.deepEqual(sorted(requested), sorted(OPERATIONS), 'every operation has a request fixture')
  assert.deepEqual(sorted(answered), sorted(OPERATIONS), 'every operation has a response fixture')
  const dispatch = read('body', 'src', 'body.js')
  for (const op of OPERATIONS) {
    if (op === 'status') continue // the dispatch's fall-through answer, guarded by the codec's operation set
    assert.match(dispatch, new RegExp(`op === '${op}'|'${op}'(?:, '[a-z]+')*\\]\\.includes\\(op\\)|\\[[^\\]]*'${op}'[^\\]]*\\]\\.includes\\(op\\)`), `body.js dispatches ${op}`)
  }
  const jvm = walk(KOTLIN_MAIN, '.kt').filter((file) => !file.includes(`${path.sep}bodycontrol${path.sep}`)).map((file) => fs.readFileSync(file, 'utf8')).join('\n')
  for (const op of OPERATIONS) {
    assert.match(jvm, new RegExp(`BodyRequest\\.${kotlinName(op)}\\(`), `the JVM production path constructs ${op}`)
  }
})

test('every closed outcome is the same set in schema, Node codec and JVM codec, has a fixture and a Node producer', () => {
  const fixtures = path.join(ROOT, 'contract', 'fixtures', 'body-control', 'valid')
  const frames = fs.readdirSync(fixtures).map((name) => JSON.parse(fs.readFileSync(path.join(fixtures, name), 'utf8')))
  const handlers = fs.readdirSync(path.join(ROOT, 'body', 'src')).filter((name) => name !== 'protocol.js')
    .map((name) => read('body', 'src', name)).join('\n')
  const opsOf = (enumName) => {
    const refs = Object.entries(BODY_SCHEMA.$defs.BodyResponse.oneOf ?? [])
    const ops = []
    for (const [, variant] of refs) {
      const text = JSON.stringify(variant)
      if (text.includes(`"#/$defs/${enumName}"`)) ops.push(variant.properties?.op?.const)
    }
    return ops.filter(Boolean)
  }
  for (const name of OUTCOME_ENUMS) {
    const values = BODY_SCHEMA.$defs[name].enum
    assert.deepEqual(sorted(protocol[constant(name)] ?? []), sorted(values), `Node ${constant(name)}`)
    assert.deepEqual(sorted(kotlinEnum(name)), sorted(values), `JVM ${name}`)
    const ops = opsOf(name)
    assert.ok(ops.length > 0, `no response variant uses ${name}`)
    const seen = new Set(frames.filter((frame) => ops.includes(frame.op)).map((frame) => frame.outcome))
    for (const value of values) {
      assert.ok(seen.has(value), `${name}.${value} has no valid response fixture for ${ops.join('/')}`)
      assert.match(handlers, new RegExp(`'${value}'`), `${name}.${value} is never produced by a Node handler`)
    }
  }
})

test('every action reaching the world has server evidence, and every interaction has an auditor branch', () => {
  const auditor = kotlin('plugin', 'BukkitInteractionAuditor.kt')
  const state = kotlin('plugin', 'BukkitPlayerServerState.kt')
  const production = walk(KOTLIN_MAIN, '.kt').map((file) => fs.readFileSync(file, 'utf8')).join('\n')
  for (const [tag, entry] of Object.entries(SUPPORT.intents)) {
    if (entry.status !== 'supported') continue
    assert.ok(OPERATIONS.includes(entry.bodyOperation), `${tag} maps to unknown operation ${entry.bodyOperation}`)
    const name = kotlinName(entry.bodyOperation)
    if (entry.serverEvidence === 'interaction') {
      assert.match(auditor, new RegExp(`is ServerInteractionClaim\\.${name} -> audit${name}\\(`), `${tag} has no auditor branch`)
      assert.match(production, new RegExp(`ServerInteractionClaim\\.${name}\\(`), `${tag} never builds a server claim`)
    } else if (entry.serverEvidence === 'movement') {
      assert.match(state, /claimAudit\.sawMovement\(/, `${tag} movement is not cross-checked`)
    } else if (entry.serverEvidence === 'sense') {
      assert.match(state, /claimAudit\.sawClaim\(/, `${tag} sense is not cross-checked`)
    } else if (entry.serverEvidence === 'speech') {
      assert.match(production, /class SpeechWitness\b/, `${tag} has no speech witness`)
      assert.match(production, /SpeechClaim\(/, `${tag} never builds a speech claim`)
    } else {
      assert.equal(entry.serverEvidence, 'none', `${tag} has unknown evidence kind`)
    }
  }
})

test('research evidence never reports a dormant intent as an applied capability', () => {
  const evidence = read('research', 'lib', 'evidence.js')
  for (const match of evidence.matchAll(/appliedByIntent\.([A-Za-z]+)/g)) {
    assert.equal(SUPPORT.intents[match[1]]?.status, 'supported', `evidence.js counts applied ${match[1]} as a capability`)
  }
})

test('every documented command is a declared and handled command, and every handled command is declared', () => {
  // A subcommand may be hyphenated, as `end-exposure` is.
  const descriptor = read('plugin', 'src', 'main', 'resources', 'plugin.yml')
  const declared = new Map()
  for (const match of descriptor.matchAll(/^ {6}\/(loom[a-z]+)(?: ([a-z]+(?:-[a-z]+)*))?/gm)) {
    if (!declared.has(match[1])) declared.set(match[1], new Set())
    if (match[2]) declared.get(match[1]).add(match[2])
  }
  const handled = {
    loombody: kotlin('plugin', 'BodyCommand.kt'),
    loomwatch: kotlin('plugin', 'WatchCommand.kt')
  }
  assert.deepEqual(sorted([...declared.keys()]), sorted(Object.keys(handled)), 'declared commands')
  for (const [command, source] of Object.entries(handled)) {
    const constants = new Map([...source.matchAll(/const val ([A-Z_]+)(?:: String)? = "([a-z]+(?:-[a-z]+)*)"/g)].map((match) => [match[1], match[2]]))
    const branches = [...source.matchAll(/^\s+(?:args\.first(?:OrNull)?\(\) == )?(?:"([a-z]+(?:-[a-z]+)*)"|([A-Z_]+)) ->/gm)].map((match) => match[1] ?? constants.get(match[2])).filter(Boolean)
    for (const sub of declared.get(command)) assert.ok(branches.includes(sub), `${command} ${sub} is declared but unhandled`)
    for (const branch of branches) assert.ok(declared.get(command).has(branch), `${command} ${branch} is handled but undeclared`)
  }
  const docs = ['README.md', 'DEMO.md', 'AGENTS.md', 'body/README.md', 'cognition/README.md', 'research/README.md', 'research/HARNESS.md']
  for (const doc of docs) {
    for (const match of read(doc).matchAll(/\/(loom[a-z]+)\b((?: [a-z]+(?:-[a-z]+)*)(?:\|[a-z]+(?:-[a-z]+)*)*)?/g)) {
      assert.ok(declared.has(match[1]), `${doc} documents unknown command /${match[1]}`)
      if (!match[2]) continue
      for (const sub of match[2].trim().split('|')) {
        if (/^(?:off)$/.test(sub) || declared.get(match[1]).has(sub)) continue
        // A following word that is prose rather than a subcommand is not a claim.
        if (match[1] === 'loombody') assert.fail(`${doc} documents /${match[1]} ${sub}, which plugin.yml does not declare`)
      }
    }
  }
})

test('every feature flag a research config enables maps to a supported production route', () => {
  const configs = path.join(ROOT, 'research', 'config')
  for (const name of fs.readdirSync(configs)) {
    const config = JSON.parse(fs.readFileSync(path.join(configs, name), 'utf8'))
    // A historical configuration records the flags it ran under; only runnable ones must
    // claim today's whole set.
    if (SUPPORT.researchConfigs[name]?.status === 'runnable') {
      assert.deepEqual(sorted(Object.keys(config.featureFlags)), sorted(Object.keys(SUPPORT.featureFlags)), `${name} feature flags`)
    }
    for (const [flag, enabled] of Object.entries(config.featureFlags)) {
      assert.equal(enabled, true, `${name} disables ${flag}, but no production route reads the flag, so it would be a no-op claim`)
    }
  }
  const covered = new Set(Object.values(SUPPORT.featureFlags).flat())
  for (const op of OPERATIONS.filter((op) => SUPPORT.bodyOperations[op] === 'action' && op !== 'halt')) {
    assert.ok(covered.has(op), `operation ${op} has no feature flag claim`)
    assert.ok(Object.values(SUPPORT.intents).some((entry) => entry.status === 'supported' && entry.bodyOperation === op), `flagged operation ${op} is reached by no supported intent`)
  }
})

test('every runnable research config pins prompts the current mind serves, and docs do not run a historical one', () => {
  const served = new Set([...rust('cognition', 'src', 'prompt.rs').matchAll(/pub const (?:ROUTINE|NOTABLE|PIVOTAL)_PROMPT_VERSION: &str = "([a-z0-9-]+)"/g)].map((match) => match[1]))
  assert.equal(served.size, 3, 'read the three served prompt versions')
  const configs = path.join(ROOT, 'research', 'config')
  assert.deepEqual(sorted(fs.readdirSync(configs)), sorted(Object.keys(SUPPORT.researchConfigs)), 'classified research configs')
  for (const [name, entry] of Object.entries(SUPPORT.researchConfigs)) {
    const prompts = Object.values(json('research', 'config', name).cognition.prompts)
    if (entry.status === 'runnable') {
      for (const prompt of prompts) assert.ok(served.has(prompt), `${name} pins ${prompt}, which the mind does not serve`)
    } else {
      assert.equal(entry.status, 'historical')
      assert.ok(prompts.some((prompt) => !served.has(prompt)), `${name} is classified historical but the mind still serves its prompts`)
      for (const doc of ['README.md', 'DEMO.md', 'research/README.md', 'research/HARNESS.md']) {
        assert.doesNotMatch(read(doc), new RegExp(`--config research/config/${name.replace(/[.]/g, '\\.')}`), `${doc} tells an operator to run historical config ${name}`)
      }
    }
  }
})

test('the documented body-control generation and operation count match the codec', () => {
  const words = { thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16 }
  for (const doc of ['AGENTS.md', 'body/README.md']) {
    const text = read(doc)
    const stated = text.match(/([A-Z][a-z]+) operations at protocol generation ([0-9]+)/)
    if (!stated) continue
    assert.equal(words[stated[1].toLowerCase()], OPERATIONS.length, `${doc} states ${stated[1]} operations`)
    assert.equal(Number(stated[2]), protocol.PROTOCOL_VERSION, `${doc} states generation ${stated[2]}`)
  }
})
