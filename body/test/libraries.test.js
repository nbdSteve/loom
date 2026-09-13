'use strict'

/**
 * The pinned libraries, proven against the versions actually installed.
 *
 * Loom does not implement A*, collision, stepping, jumping, gravity or a movement
 * packet: `mineflayer-pathfinder` searches and Prismarine's physics simulates. What
 * that buys in code it costs in trust, and this file is where the cost is paid. Two
 * claims, and neither is expressible anywhere else.
 *
 * The first is the *version*. A search library's behaviour is its defaults plus its
 * node evaluator, and both change between releases with no wire to notice it on - so
 * the two direct dependencies are declared exactly rather than as a range, and every
 * transitive library the body's movement and perception are drawn through is stated
 * here at the version the lockfile resolved. A test reading `package.json` alone would
 * pass while `node_modules` held something else; these read what is installed.
 *
 * The second is the *policy's coverage*. `movementPolicyFor` sets every field a
 * `Movements` has, on purpose, because the library's own defaults are permissive: it
 * will dig through a wall, pillar up, parkour a gap, sprint, and drop four blocks
 * unless told otherwise. So the fields are named against a real `Movements` of the
 * pinned version in both directions - a field the library added is a field this policy
 * silently accepts the default of, and a field this policy names that the library
 * dropped is a policy talking to itself. Then each permissive default is shown to *be*
 * permissive before it is shown to be off, because a case that only asserted the
 * second half would pass just as well against a library that had already turned it off
 * for its own reasons, and would say nothing at all about the acceptance criterion this
 * file exists for.
 *
 * A `Movements` needs only `bot.registry` in this version (`lib/movements.js`, first
 * line of the constructor), which is what lets every case here run with no bot, no
 * server, no network and no credential - against the same real registry the harness
 * builds for every other suite rather than a stand-in for one.
 */

const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const { isDeepStrictEqual } = require('node:util')

const { Movements } = require('mineflayer-pathfinder')

const { applyMovementPolicy, MAX_DROP_BLOCKS, movementPolicyFor } = require('../src/movement')
const { registry } = require('./harness')

/** This package's own root, which the two files below are read relative to. */
const PACKAGE = path.join(__dirname, '..')

/**
 * Every library the body's movement and perception are drawn through, and its version.
 *
 * Written out rather than derived, because a derived table would agree with whatever is
 * installed and prove nothing. What each one is for is why it is here: `mineflayer` and
 * `mineflayer-pathfinder` are the two direct dependencies; `minecraft-data` and
 * `prismarine-registry` are the vocabulary every block, item and entity name is read
 * from; `prismarine-physics` is the step simulation a walk is made of; `prismarine-block`
 * and `prismarine-entity` are what a sense answer describes; and the rest are the world,
 * window, item and chat models those two are built on.
 */
const PINNED = Object.freeze({
  mineflayer: '4.38.0',
  'mineflayer-pathfinder': '2.4.5',
  'minecraft-data': '3.114.0',
  'prismarine-biome': '1.4.0',
  'prismarine-block': '1.23.0',
  'prismarine-chat': '1.13.0',
  'prismarine-chunk': '1.41.0',
  'prismarine-entity': '2.6.0',
  'prismarine-item': '1.18.0',
  'prismarine-nbt': '2.8.0',
  'prismarine-physics': '1.11.1',
  'prismarine-registry': '1.12.0',
  'prismarine-windows': '2.10.0',
  'prismarine-world': '3.7.0',
  vec3: '0.1.10'
})

/**
 * The abilities the pinned library turns on for itself, and what each one would do.
 *
 * Every value here is the library's own default, taken from a fresh `Movements` in the
 * case below rather than trusted from this table - so if a release changes one, the
 * assertion that it *was* permissive fails and this file has to be read again.
 */
const PERMISSIVE_DEFAULTS = Object.freeze({
  // Would mine a way through rather than walk round it.
  canDig: true,
  // Would place blocks under itself to climb.
  allow1by1towers: true,
  // Would jump gaps a walking body cannot cross.
  allowParkour: true,
  // Would run, which costs hunger the request never agreed to spend.
  allowSprinting: true,
  // Would read the entities near a node, which is a scan per node.
  allowEntityDetection: true,
  // Would fall four blocks, which costs health.
  maxDropDown: 4,
  // Would fall any distance at all into water.
  infiniteLiquidDropdownDistance: true
})

/** The version of a package as it is installed, read off disk rather than required. */
function installedVersion (name) {
  // `readFileSync` rather than `require`, because a package whose `exports` map does not
  // list `./package.json` throws there - `vec3` is one, so the reading has to be a file
  // read to cover the whole table.
  const manifest = path.join(PACKAGE, 'node_modules', ...name.split('/'), 'package.json')
  return JSON.parse(readFileSync(manifest, 'utf8')).version
}

/** A `Movements` of the pinned version, built against the real registry, with no bot. */
function movements () {
  return new Movements({ registry })
}

test('every movement library is installed at the version this body is pinned to', () => {
  const installed = {}
  for (const name of Object.keys(PINNED)) installed[name] = installedVersion(name)
  assert.deepEqual(installed, PINNED)
})

test('the physics simulation carries a vec3 of its own, and that one is pinned too', () => {
  // The only nested duplicate in the whole set, and it is stated rather than left for a
  // top-level reading to hide: `prismarine-physics` asks for `^0.2.0` while `mineflayer`
  // and `mineflayer-pathfinder` both ask for `^0.1.7`, so two versions are installed and
  // the simulation runs against the one npm nested under it. A table that named only the
  // top-level `vec3` would pin the copy the step simulation does not use.
  assert.equal(installedVersion('prismarine-physics/node_modules/vec3'), '0.2.0')
  assert.notEqual(installedVersion('vec3'), installedVersion('prismarine-physics/node_modules/vec3'))
})

test('the two movement dependencies are declared exactly rather than as a range', () => {
  const { dependencies } = JSON.parse(readFileSync(path.join(PACKAGE, 'package.json'), 'utf8'))
  // A caret would let a minor release change the search's own defaults and its node
  // evaluator between one `npm ci` and the next, with nothing on either wire to notice
  // it: the schema does not describe a route and a report does not carry a library
  // version. Exact is what makes the coverage proof below mean anything tomorrow.
  assert.deepEqual(dependencies, {
    mineflayer: PINNED.mineflayer,
    'mineflayer-pathfinder': PINNED['mineflayer-pathfinder']
  })
})

test('the policy names every movement field the pinned library has, and no others', () => {
  const library = movements()
  const stated = movementPolicyFor(library, { sprint: false })
  // `bot` is the library's own handle on the bot and not a policy field; everything else
  // a `Movements` carries is something the search reads.
  const fields = Object.keys(library).filter((field) => field !== 'bot').sort()
  assert.deepEqual(Object.keys(stated).sort(), fields)
  // Both directions matter and the equality above states both, so this is only the
  // reason: a field present in the library and absent here is a permissive default this
  // process accepted without deciding to, and a field present here and absent there is
  // a setting nothing reads.
  assert.ok(fields.length > 0)
})

test('the pinned library turns these abilities on for itself', () => {
  const library = movements()
  // Not a restatement of the table: this is what makes the next case a proof rather than
  // a tautology. If a release ships with `canDig` already false, this fails and the
  // policy's own claim to be turning it off has to be re-read.
  for (const [ability, permissive] of Object.entries(PERMISSIVE_DEFAULTS)) {
    assert.equal(library[ability], permissive, ability)
  }
})

test('no ability the pinned library offers by default survives the policy', () => {
  const library = movements()
  applyMovementPolicy(library, { sprint: false })
  assert.equal(library.canDig, false)
  assert.equal(library.allow1by1towers, false)
  assert.equal(library.allowParkour, false)
  assert.equal(library.allowSprinting, false)
  assert.equal(library.allowEntityDetection, false)
  assert.equal(library.canOpenDoors, false)
  assert.equal(library.allowFreeMotion, false)
  // A drop is bounded rather than forbidden, because a body that may not step down at all
  // cannot leave a slope, and the bound is lower than the library's four.
  assert.equal(library.maxDropDown, MAX_DROP_BLOCKS)
  assert.ok(MAX_DROP_BLOCKS < PERMISSIVE_DEFAULTS.maxDropDown)
  assert.equal(library.infiniteLiquidDropdownDistance, false)
  // Scaffolding is emptied as well as switched off, which is strictly stronger: a tower
  // needs a block to place, and there is no block a placement could reach for.
  assert.deepEqual(library.scafoldingBlocks, [])
  // No area is excluded, because nothing here decides where a body may not go - a bound
  // is a bound on the request, not a region Loom drew.
  assert.deepEqual(library.exclusionAreasStep, [])
  assert.deepEqual(library.exclusionAreasBreak, [])
  assert.deepEqual(library.exclusionAreasPlace, [])
  assert.deepEqual(library.entityIntersections, {})
})

test('water is a wall, and the library own avoided blocks are kept beside it', () => {
  const library = movements()
  const avoided = new Set(library.blocksToAvoid)
  const water = registry.blocksByName.water.id
  // The library does not avoid water: it prices it and swims. This body has no swim
  // mechanism at all, so water goes in the set - which is what "no swimming" means here,
  // since `Movements` has no swim setting to turn off.
  assert.equal(avoided.has(water), false)
  applyMovementPolicy(library, { sprint: false })
  assert.equal(library.blocksToAvoid.has(water), true)
  // And the library's own three are still avoided: fire, cobweb and lava are hazards
  // whatever Loom thinks, so the set is added to rather than replaced.
  for (const kept of avoided) assert.equal(library.blocksToAvoid.has(kept), true)
  assert.equal(library.blocksToAvoid.size, avoided.size + 1)
})

test('sprinting is the one ability the request itself decides', () => {
  const running = movements()
  applyMovementPolicy(running, { sprint: true })
  assert.equal(running.allowSprinting, true)
  // And it is the only thing the flag reaches: a request that agreed to spend hunger did
  // not thereby agree to dig, pillar or parkour, so every other ability is off in both
  // policies. Read off the two results rather than named, so an ability added later is
  // covered the day the policy names it.
  const library = movements()
  const walking = movementPolicyFor(library, { sprint: false })
  const sprinting = movementPolicyFor(library, { sprint: true })
  // Compared by value rather than by reference: a policy builds its own set for the
  // blocks to avoid and its own empty list for the scaffolding on every call, so the
  // two results are equal without being the same objects.
  const differing = Object.keys(sprinting).filter(
    (field) => !isDeepStrictEqual(sprinting[field], walking[field])
  )
  assert.deepEqual(differing, ['allowSprinting'])
})

test('the registry vocabularies stay the library own', () => {
  const library = movements()
  const before = {}
  for (const vocabulary of [
    'blocksCantBreak', 'liquids', 'gravityBlocks', 'climbables', 'emptyBlocks',
    'replaceables', 'fences', 'carpets', 'openable', 'interactableBlocks',
    'entitiesToAvoid', 'passableEntities'
  ]) {
    before[vocabulary] = library[vocabulary]
    // Present and non-trivial, or the assertion below would hold for the wrong reason.
    // `entitiesToAvoid` is the library's own empty set and is checked for presence only.
    assert.ok(library[vocabulary] instanceof Set, vocabulary)
  }
  applyMovementPolicy(library, { sprint: false })
  for (const [vocabulary, kept] of Object.entries(before)) {
    // The same object, not an equal one. These are what the library derived from the
    // registry for this version, and a set of Loom's own would be either inert - the
    // abilities that read most of them are off - or wrong, because `emptyBlocks` and
    // `fences` decide where a body may stand.
    assert.equal(library[vocabulary], kept, vocabulary)
  }
})
