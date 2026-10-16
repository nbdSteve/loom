'use strict'

// Research-side half of the capability-completeness audit: a configuration the
// validator accepts, or an experiment mode it pins, must name prompts the current
// mind actually serves, so a run cannot start against a claim it can only fail
// after the fact. The shared classification is contract/fixtures/capability-support.json.

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { validateRunConfig } = require('../lib/artifact')
const { PRIMARY_BEHAVIOR_MODES, SERVED_PROMPTS } = require('../lib/experiment-mode')

const ROOT = path.join(__dirname, '..', '..')
const SUPPORT = JSON.parse(fs.readFileSync(path.join(ROOT, 'contract', 'fixtures', 'capability-support.json'), 'utf8'))
const PROMPT_SOURCE = fs.readFileSync(path.join(ROOT, 'cognition', 'crates', 'cognition', 'src', 'prompt.rs'), 'utf8')
const SERVED = new Set([...PROMPT_SOURCE.matchAll(/pub const (?:ROUTINE|NOTABLE|PIVOTAL)_PROMPT_VERSION: &str = "([a-z0-9-]+)"/g)].map((match) => match[1]))
const CONFIGS = path.join(__dirname, '..', 'config')

test('the served prompt versions are readable and the validator pins exactly them', () => {
  assert.equal(SERVED.size, 3)
  assert.deepEqual(new Set(Object.values(SERVED_PROMPTS)), SERVED)
})

test('every runnable configuration is accepted by the validator and pins served prompts', () => {
  for (const [name, entry] of Object.entries(SUPPORT.researchConfigs)) {
    const config = JSON.parse(fs.readFileSync(path.join(CONFIGS, name), 'utf8'))
    const errors = validateRunConfig(config)
    const unserved = Object.values(config.cognition.prompts).filter((prompt) => !SERVED.has(prompt))
    if (entry.status === 'runnable') {
      assert.deepEqual(errors, [], `${name} is classified runnable`)
      assert.deepEqual(unserved, [], `${name} pins prompts the mind does not serve`)
    } else {
      assert.notDeepEqual(errors, [], `${name} pins ${unserved.join(', ')}, which the mind does not serve, yet the validator accepts it`)
    }
  }
})

test('every experiment mode the validator offers pins prompts the current mind serves', () => {
  for (const [mode, pinned] of Object.entries(PRIMARY_BEHAVIOR_MODES)) {
    for (const prompt of Object.values(pinned.prompts)) {
      assert.ok(SERVED.has(prompt), `mode ${mode} pins ${prompt}, which the mind does not serve`)
    }
  }
})

test('the validator refuses a configuration that disables a feature no production route reads', () => {
  const config = JSON.parse(fs.readFileSync(path.join(CONFIGS, 'first-tribe-routine-v19-agency-pilot-scripted.json'), 'utf8'))
  for (const flag of Object.keys(SUPPORT.featureFlags)) {
    const disabled = { ...config, featureFlags: { ...config.featureFlags, [flag]: false } }
    assert.notDeepEqual(validateRunConfig(disabled), [], `featureFlags.${flag}: false is accepted but changes nothing at runtime`)
  }
})
