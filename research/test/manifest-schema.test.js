'use strict'

// The semantic suite for the manifest schema policy.
//
// Every case here runs the real validator against the real committed evidence.
// Nothing asserts on the text of a schema file as a stand-in for what the
// validator does with it, because the thing worth pinning is whether a manifest
// is accepted or refused - not whether a file happens to contain a word.
//
// The manifests are swept off the filesystem rather than listed here, so an
// experiment committed later is covered on the day it lands.

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const {
  SCHEMA_DIR,
  compileSchema,
  currentSchemaVersion,
  publishedVersions,
  schemaFor,
  validateManifest,
  validateManifestFile,
  versionOfTag,
  versionTag
} = require('../schema/manifest-schema.js')

const EXPERIMENTS_DIR = path.join(__dirname, '..', 'experiments')

/**
 * What differs between each adjacent pair of published generations, declared
 * here rather than inferred.
 *
 * A bump is owed whenever the new generation would refuse something the old one
 * accepted, and that does **not** always make the two refuse each other's
 * committed evidence: a bump taken for a narrowed constraint or a dropped
 * required field leaves evidence already committed valid under the new schema by
 * design, and that evidence may never be rewritten to make it otherwise. So
 * each step names the shared-core paths that changed and carries at least one
 * *witness* - a manifest the two generations disagree about, with which of the
 * two accepts it - instead of the suite assuming every generation refuses every
 * other generation's manifests.
 *
 * A generation added later has to be declared here, which the coverage case
 * below fails by name until it is.
 *
 * @type {{ older: number, newer: number, paths: string[], witnesses: { acceptedBy: number, from: (manifestOf: (version: number) => object) => object }[] }[]}
 */
const ADJACENT_GENERATIONS = [
  {
    older: 1,
    newer: 2,
    // Four shared-core shapes changed in one step, and any one of them alone
    // would have required the bump: `scannedFor` became an array, `outcome`
    // became prose, `scientificUsability.scope` became an array of claims, and
    // `clientPresence` moved out of `environment` to the root. All four are type
    // or path changes, so here each generation does refuse the other's manifest.
    paths: [
      '/rawEvidence/credentialScan/scannedFor',
      '/outcome',
      '/scientificUsability/scope',
      'clientPresence'
    ],
    witnesses: [
      { acceptedBy: 1, from: (manifestOf) => manifestOf(1) },
      { acceptedBy: 2, from: (manifestOf) => manifestOf(2) }
    ]
  }
]

/** The declared step between two adjacent generations, or undefined. */
function declaredStep (older, newer) {
  return ADJACENT_GENERATIONS.find((step) => step.older === older && step.newer === newer)
}

/**
 * Every committed manifest, as a parsed object beside the path it came from.
 *
 * @returns {{ experimentId: string, file: string, manifest: object }[]}
 */
function committedManifests () {
  return fs
    .readdirSync(EXPERIMENTS_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => ({
      experimentId: entry.name,
      file: path.join(EXPERIMENTS_DIR, entry.name, 'manifest.json')
    }))
    .filter((entry) => fs.existsSync(entry.file))
    .map((entry) => ({ ...entry, manifest: JSON.parse(fs.readFileSync(entry.file, 'utf8')) }))
}

/** A manifest a case may reshape without touching the committed file. */
function copyOf (manifest) {
  return structuredClone(manifest)
}

/** The root keys of a manifest that the shared core does not name. */
function measurementSectionsOf (manifest, version) {
  const core = new Set(Object.keys(schemaFor(version).properties))
  return Object.keys(manifest).filter((key) => !core.has(key))
}

/** The types a leaf can declare that a union would widen away from. */
const SCALAR_TYPES = new Set(['string', 'number', 'integer', 'boolean'])

/**
 * A local `$ref` resolved against the schema it appears in, or the node itself.
 *
 * A generation is self-contained, so every `$ref` in one points inside it - and
 * a shape factored into `$defs` is still shared core, so a walk that stopped at
 * the reference would not see what it says.
 *
 * @param {object} node
 * @param {object} root
 * @returns {object}
 */
function refResolved (node, root) {
  if (typeof node.$ref !== 'string' || !node.$ref.startsWith('#/')) return node
  let target = root
  for (const step of node.$ref.slice(2).split('/')) {
    if (target === null || typeof target !== 'object') return node
    target = target[step]
  }
  return target === null || typeof target !== 'object' ? node : target
}

/**
 * Every property a generation names, in document order, as the path to it
 * beside its own resolved subschema and the `properties` object holding it.
 *
 * The one derivation the cases below share, so no case names a nested path of
 * its own. Any one shared-core field is exactly what a later generation is
 * expected to relocate or rename, and a case that only needs *a* field of some
 * shape should then fail by name rather than throw on a path that is no longer
 * there. Recursion carries the branch it came down so a `$defs` shape that
 * refers to itself terminates.
 *
 * @param {object} schema
 * @param {object} [root]
 * @param {string[]} [at]
 * @param {Set<object>} [seen]
 * @returns {Generator<{ path: string[], schema: object, properties: object, key: string }>}
 */
function * nodesOf (schema, root = schema, at = [], seen = new Set()) {
  const here = refResolved(schema, root)
  if (seen.has(here)) return
  const branch = new Set(seen)
  branch.add(here)
  const properties = here.properties
  if (properties === null || typeof properties !== 'object') return
  for (const [key, value] of Object.entries(properties)) {
    if (value === null || typeof value !== 'object') continue
    const path = [...at, key]
    yield { path, schema: refResolved(value, root), properties, key }
    yield * nodesOf(value, root, path, branch)
  }
}

/**
 * The first leaf a schema names that declares a single scalar type.
 *
 * A `const`-pinned leaf is skipped: `schemaVersion` is what makes a generation
 * name itself.
 *
 * @param {object} schema
 * @returns {{ properties: object, key: string } | null}
 */
function scalarLeafOf (schema) {
  for (const node of nodesOf(schema)) {
    if (SCALAR_TYPES.has(node.schema.type) && node.schema.const === undefined) return node
  }
  return null
}

/** The object a path's last step lives in, or null if the manifest has no such object. */
function containerAt (manifest, path) {
  let node = manifest
  for (const step of path.slice(0, -1)) {
    if (node === null || typeof node !== 'object') return null
    node = node[step]
  }
  return node === null || typeof node !== 'object' ? null : node
}

/** Whether a manifest carries the field a path names. */
function carries (manifest, path) {
  const container = containerAt(manifest, path)
  return container !== null && path[path.length - 1] in container
}

/** Something a schema pinning `pinned` refuses, whatever type it pinned. */
function aValueOtherThan (pinned) {
  if (typeof pinned === 'boolean') return !pinned
  if (typeof pinned === 'number') return pinned + 1
  return `${pinned} and something else`
}

test('every committed manifest is valid under the generation it declares', () => {
  const manifests = committedManifests()
  assert.ok(
    manifests.length >= 2,
    'expected the two committed experiments at least; the sweep found none, so the rest of this suite would pass vacuously'
  )
  for (const { experimentId, manifest } of manifests) {
    const result = validateManifest(manifest)
    assert.deepEqual(result.errors, [], `${experimentId} is invalid under ${manifest.schemaVersion}`)
    assert.equal(result.ok, true, experimentId)
    assert.equal(result.version, versionOfTag(manifest.schemaVersion), experimentId)
  }
})

test('a committed manifest is read from its own file, not only from memory', () => {
  for (const { experimentId, file } of committedManifests()) {
    const result = validateManifestFile(file)
    assert.deepEqual(result.errors, [], experimentId)
    assert.equal(result.ok, true, experimentId)
  }
})

test('the version a new manifest gets is derived and is published', () => {
  const current = currentSchemaVersion()
  const published = publishedVersions()
  assert.ok(published.length > 0, `no schema in ${SCHEMA_DIR}`)
  assert.equal(current, Math.max(...published), 'the current version must be the newest published one')

  // Deterministic in the sense the policy means it: the selector's answer is a
  // tag the validator resolves, so anything writing a manifest from it produces
  // a manifest that can be checked rather than one naming a missing generation.
  assert.equal(versionOfTag(versionTag(current)), current)
  assert.ok(published.includes(current))
})

test('no committed manifest declares a generation newer than the derived current one', () => {
  const current = currentSchemaVersion()
  for (const { experimentId, manifest } of committedManifests()) {
    const declared = versionOfTag(manifest.schemaVersion)
    assert.notEqual(declared, null, `${experimentId} declares ${manifest.schemaVersion}`)
    assert.ok(
      declared <= current,
      `${experimentId} declares generation ${declared}, ahead of the newest schema (${current})`
    )
    assert.ok(publishedVersions().includes(declared), `${experimentId} declares an unpublished generation`)
  }
})

test('the current version is stable across calls and is read from the schema directory', () => {
  assert.equal(currentSchemaVersion(), currentSchemaVersion())
  const names = fs.readdirSync(SCHEMA_DIR)
  for (const version of publishedVersions()) {
    assert.ok(
      names.includes(`loom-experiment-manifest-${version}.schema.json`),
      `generation ${version} was reported published with no schema file`
    )
  }
})

test('a generation with no schema is refused by name rather than read as the newest', () => {
  const [{ manifest }] = committedManifests()
  const absent = currentSchemaVersion() + 97
  const retagged = { ...copyOf(manifest), schemaVersion: versionTag(absent) }

  const result = validateManifest(retagged)
  assert.equal(result.ok, false)
  assert.equal(result.version, absent)
  assert.equal(result.errors.length, 1)
  assert.match(result.errors[0], new RegExp(`loom-experiment-manifest/${absent}`))
})

test('a schemaVersion that is not a generation tag is refused rather than guessed at', () => {
  const [{ manifest }] = committedManifests()
  const current = currentSchemaVersion()
  const notTags = [
    undefined,
    null,
    '',
    String(current),
    `loom-experiment-manifest/v${current}`,
    'loom-experiment-manifest/0',
    `loom-experiment-manifest/0${current}`,
    `loom-experiment-manifest/${current} `,
    `loom-experiment-manifest/${current}.0`,
    `LOOM-EXPERIMENT-MANIFEST/${current}`,
    current
  ]
  for (const declared of notTags) {
    assert.equal(versionOfTag(declared), null, `${JSON.stringify(declared)} must not read as a generation`)
    const result = validateManifest({ ...copyOf(manifest), schemaVersion: declared })
    assert.equal(result.ok, false, `${JSON.stringify(declared)} was accepted`)
    assert.equal(result.version, null)
  }
})

test('something that is not a manifest object is refused without throwing', () => {
  for (const value of [null, undefined, 42, 'a manifest', [], [{ schemaVersion: versionTag(1) }]]) {
    const result = validateManifest(value)
    assert.equal(result.ok, false, `${JSON.stringify(value) ?? 'undefined'} was accepted`)
  }
  const missing = validateManifestFile(path.join(EXPERIMENTS_DIR, 'no-such-experiment', 'manifest.json'))
  assert.equal(missing.ok, false)
  assert.equal(missing.errors.length, 1)
})

test('the scannedFor shape is what separates generation 1 from generation 2', () => {
  // The case the policy exists to resolve: rawEvidence.credentialScan.scannedFor
  // went from one string to an array of patterns. Each generation must hold its
  // own shape, so neither manifest can be read as the other generation, and
  // swapping that one field alone is enough to be refused.
  const published = publishedVersions()
  if (!published.includes(1) || !published.includes(2)) return

  const byVersion = new Map(
    committedManifests().map((entry) => [versionOfTag(entry.manifest.schemaVersion), entry])
  )
  const one = byVersion.get(1)
  const two = byVersion.get(2)
  assert.ok(one, 'no committed manifest declares generation 1')
  assert.ok(two, 'no committed manifest declares generation 2')

  assert.equal(typeof one.manifest.rawEvidence.credentialScan.scannedFor, 'string')
  assert.ok(Array.isArray(two.manifest.rawEvidence.credentialScan.scannedFor))

  const arrayInGeneration1 = copyOf(one.manifest)
  arrayInGeneration1.rawEvidence.credentialScan.scannedFor = ['sk-', 'Bearer ']
  const refusedArray = validateManifest(arrayInGeneration1)
  assert.equal(refusedArray.ok, false, 'generation 1 accepted an array scannedFor')
  assert.ok(refusedArray.errors.some((error) => error.includes('/rawEvidence/credentialScan/scannedFor')))

  const stringInGeneration2 = copyOf(two.manifest)
  stringInGeneration2.rawEvidence.credentialScan.scannedFor = 'sk- and Bearer'
  const refusedString = validateManifest(stringInGeneration2)
  assert.equal(refusedString.ok, false, 'generation 2 accepted a string scannedFor')
  assert.ok(refusedString.errors.some((error) => error.includes('/rawEvidence/credentialScan/scannedFor')))

  const emptyList = copyOf(two.manifest)
  emptyList.rawEvidence.credentialScan.scannedFor = []
  assert.equal(validateManifest(emptyList).ok, false, 'a scan that names no pattern is not a scan')
})

test('every adjacent pair of published generations declares what differs between them', () => {
  const published = publishedVersions()
  for (let i = 1; i < published.length; i += 1) {
    const older = published[i - 1]
    const newer = published[i]
    const step = declaredStep(older, newer)
    assert.ok(
      step,
      `generation ${newer} was published without declaring what changed since ${older}; add the step to ADJACENT_GENERATIONS`
    )
    assert.ok(step.paths.length > 0, `the step ${older} -> ${newer} declares no shared-core path`)
    assert.ok(step.witnesses.length > 0, `the step ${older} -> ${newer} carries no witness, so it proves nothing`)
    for (const witness of step.witnesses) {
      assert.ok(
        witness.acceptedBy === older || witness.acceptedBy === newer,
        `the step ${older} -> ${newer} has a witness accepted by generation ${witness.acceptedBy}, which is neither of them`
      )
    }
  }
})

test('each declared step makes the two generations disagree about a witness', () => {
  // Narrowed to the differences a step declares. Blanket mutual refusal - every
  // manifest refused by every other generation - is stronger than the policy: a
  // bump taken for a narrowed constraint or a dropped required field leaves
  // evidence committed under the older generation valid under the newer one, so
  // the blanket form would have to be weakened the day such a bump lands.
  const byVersion = new Map(
    committedManifests().map((entry) => [versionOfTag(entry.manifest.schemaVersion), entry])
  )
  const published = publishedVersions()

  for (const step of ADJACENT_GENERATIONS) {
    if (!published.includes(step.older) || !published.includes(step.newer)) continue
    const manifestOf = (version) => {
      const entry = byVersion.get(version)
      assert.ok(entry, `no committed manifest declares generation ${version}`)
      return copyOf(entry.manifest)
    }

    for (const witness of step.witnesses) {
      const refusedBy = witness.acceptedBy === step.older ? step.newer : step.older
      const manifest = witness.from(manifestOf)

      const accepted = validateManifest({ ...manifest, schemaVersion: versionTag(witness.acceptedBy) })
      assert.deepEqual(
        accepted.errors,
        [],
        `generation ${witness.acceptedBy} refused its own witness, so the witness proves nothing about ${refusedBy}`
      )

      const refused = validateManifest({ ...manifest, schemaVersion: versionTag(refusedBy) })
      assert.equal(
        refused.ok,
        false,
        `generation ${refusedBy} accepted generation ${witness.acceptedBy}'s witness: the two generations have collapsed into one`
      )
      assert.ok(
        step.paths.some((declared) => refused.errors.some((error) => error.includes(declared))),
        `generation ${refusedBy} refused the witness without naming any difference the step declares:\n${refused.errors.join('\n')}`
      )
    }
  }
})

test('the four shared-core changes that required generation 2 are each still refused by generation 1', () => {
  const published = publishedVersions()
  if (!published.includes(1) || !published.includes(2)) return

  const step = declaredStep(1, 2)
  assert.ok(step, 'the step 1 -> 2 is no longer declared')

  const two = committedManifests().find((entry) => versionOfTag(entry.manifest.schemaVersion) === 2)
  assert.ok(two, 'no committed manifest declares generation 2')

  const asGeneration1 = { ...copyOf(two.manifest), schemaVersion: versionTag(1) }
  const errors = validateManifest(asGeneration1).errors.join('\n')
  for (const drift of step.paths) {
    assert.ok(errors.includes(drift), `generation 1 did not object to ${drift}:\n${errors}`)
  }
})

test('every published generation compiles, not only the one a manifest declares', () => {
  // A generation with no committed evidence under it is handed to Ajv by no
  // other case, since every other route into the compiler goes through a
  // manifest that declares a generation. An in-place loosening edit that trips
  // Ajv strict - a keyword typo, or `nullable: true` - would then leave the
  // suite green while that generation could not be compiled at all, which is to
  // say the evidence committed under it could no longer be read by its one
  // reader.
  const published = publishedVersions()
  assert.ok(published.length > 0, `no schema in ${SCHEMA_DIR}`)
  for (const version of published) {
    assert.doesNotThrow(() => compileSchema(schemaFor(version)), `generation ${version} does not compile`)
  }
})

test('a generation widened to accept two shapes of one field does not compile', () => {
  // The tempting way round a type change, and the collapse the whole policy
  // exists to prevent: a union looks like a loosening, and it would make one
  // generation accept both shapes of a field that changed, so a reader would
  // have to check the type before reading it. Ajv `strictTypes` refuses it, and
  // this pins that against the options `manifest-schema.js` actually compiles
  // with rather than against an Ajv default nothing exercises.
  const current = currentSchemaVersion()
  const leafOf = (schema) => {
    const leaf = scalarLeafOf(schema)
    assert.ok(leaf, `generation ${current} declares no scalar leaf, so this case has nothing to widen`)
    return leaf
  }

  const widened = schemaFor(current)
  const widenedLeaf = leafOf(widened)
  const widenedType = widenedLeaf.properties[widenedLeaf.key].type
  widenedLeaf.properties[widenedLeaf.key] = { type: [widenedType, 'array'] }
  assert.throws(() => compileSchema(widened), /strictTypes/)

  // The one union Ajv does allow is a single type paired with `null`, which is
  // not this collapse: a reader still reads one shape, or nothing at all.
  const nullable = schemaFor(current)
  const nullableLeaf = leafOf(nullable)
  const nullableType = nullableLeaf.properties[nullableLeaf.key].type
  nullableLeaf.properties[nullableLeaf.key] = { type: [nullableType, 'null'] }
  assert.doesNotThrow(() => compileSchema(nullable))
})

test('an experiment-specific measurement section is schema-neutral', () => {
  const manifests = committedManifests()

  // Added, and dropped along with it: every committed manifest takes a section
  // no generation has ever named, and every one of them still validates with all
  // of its own sections gone. Both hold whatever a manifest happens to carry,
  // because a manifest carrying nothing but the shared core is legitimate - the
  // policy's same-generation case 1 is an experiment that drops a section - so
  // nothing here may require a non-core key of committed evidence that can never
  // be rewritten to supply one.
  for (const { experimentId, manifest } of manifests) {
    const version = versionOfTag(manifest.schemaVersion)

    const added = copyOf(manifest)
    added.somethingThisRunInvented = { paths: 3, note: 'a measurement no schema knows about' }
    assert.equal(validateManifest(added).ok, true, `${experimentId} with a fabricated section`)

    const stripped = copyOf(manifest)
    for (const section of measurementSectionsOf(manifest, version)) delete stripped[section]
    assert.deepEqual(validateManifest(stripped).errors, [], `${experimentId} with no measurement sections`)
  }

  // Reshaped needs a section that actually exists, so the witness is selected
  // rather than demanded of all of them, and its absence fails by name.
  const witness = manifests.find(
    ({ manifest }) => measurementSectionsOf(manifest, versionOfTag(manifest.schemaVersion)).length > 0
  )
  assert.ok(
    witness,
    'no committed manifest carries a section outside the shared core, so the reshaped case has nothing to reshape'
  )

  const sections = measurementSectionsOf(witness.manifest, versionOfTag(witness.manifest.schemaVersion))
  for (const section of sections) {
    const reshaped = copyOf(witness.manifest)
    reshaped[section] = ['reshaped', 42, { nested: true }]
    assert.equal(
      validateManifest(reshaped).ok,
      true,
      `${witness.experimentId}: reshaping ${section} was refused, so it is not schema-neutral`
    )
  }
})

test('a missing shared-core field is refused, every one of them', () => {
  for (const { experimentId, manifest } of committedManifests()) {
    const version = versionOfTag(manifest.schemaVersion)
    const required = schemaFor(version).required
    assert.ok(required.length > 0, `generation ${version} requires nothing`)
    for (const field of required) {
      const without = copyOf(manifest)
      delete without[field]
      const result = validateManifest(without)
      assert.equal(result.ok, false, `${experimentId} was accepted without ${field}`)
      if (field !== 'schemaVersion') {
        assert.ok(
          result.errors.some((error) => error.includes(field)),
          `${experimentId}: nothing in the refusal named the missing ${field}`
        )
      }
    }
  }
})

test('a narrowed shared-core value is refused rather than coerced', () => {
  for (const { experimentId, manifest } of committedManifests()) {
    const drifts = [
      ['sourceCommit', 'not-a-commit'],
      ['experimentId', 'Not A Slug'],
      ['knownWarnings', {}],
      ['integrityChecks', {}]
    ]
    for (const [field, value] of drifts) {
      const drifted = copyOf(manifest)
      drifted[field] = value
      const result = validateManifest(drifted)
      assert.equal(result.ok, false, `${experimentId} accepted ${field} as ${JSON.stringify(value)}`)
      assert.ok(result.errors.some((error) => error.includes(field)), `${experimentId}: ${field}`)
    }

    const version = versionOfTag(manifest.schemaVersion)
    const schema = schemaFor(version)

    let pinnedLeaves = 0
    for (const leaf of nodesOf(schema)) {
      if (leaf.path.length < 2 || leaf.schema.const === undefined) continue
      if (!carries(manifest, leaf.path)) continue
      const flipped = copyOf(manifest)
      containerAt(flipped, leaf.path)[leaf.key] = aValueOtherThan(leaf.schema.const)
      assert.equal(
        validateManifest(flipped).ok,
        false,
        `${experimentId} accepted ${leaf.path.join('/')} away from the value generation ${version} pins it to`
      )
      pinnedLeaves += 1
    }
    assert.ok(
      pinnedLeaves > 0,
      `generation ${version} pins no nested value, so nothing here proves a pinned value cannot drift`
    )

    let requirements = 0
    for (const node of nodesOf(schema)) {
      if (!Array.isArray(node.schema.required)) continue
      for (const field of node.schema.required) {
        const path = [...node.path, field]
        if (!carries(manifest, path)) continue
        const without = copyOf(manifest)
        delete containerAt(without, path)[field]
        assert.equal(
          validateManifest(without).ok,
          false,
          `${experimentId} accepted a manifest with no ${path.join('/')}`
        )
        requirements += 1
      }
    }
    assert.ok(
      requirements > 0,
      `generation ${version} requires nothing of a nested object, so nothing here proves a nested requirement`
    )
  }
})

test('every published generation is self-contained and names itself', () => {
  // Not a stand-in for validator behaviour: it is the structural rule the whole
  // policy rests on. A generation that $ref'd a shared file could have its
  // meaning changed by an edit somewhere else, after evidence was committed
  // under it.
  for (const version of publishedVersions()) {
    const schema = schemaFor(version)
    assert.equal(schema.properties.schemaVersion.const, versionTag(version), `generation ${version} names itself`)

    const external = []
    const forbidden = []
    const walk = (node, at) => {
      if (Array.isArray(node)) {
        node.forEach((item, index) => walk(item, `${at}/${index}`))
        return
      }
      if (node === null || typeof node !== 'object') return
      for (const [key, value] of Object.entries(node)) {
        if (key === '$ref' && typeof value === 'string' && !value.startsWith('#')) {
          external.push(`${at}/$ref -> ${value}`)
        }
        // Both keywords close an object completely in draft 2020-12, which both
        // generations declare, so neither may appear: `unevaluatedProperties`
        // closes one against a key no committed instance happens to carry, which
        // is exactly the case the other keyword's absence would not catch.
        if ((key === 'additionalProperties' || key === 'unevaluatedProperties') && value === false) {
          forbidden.push(`${at}/${key}`)
        }
        walk(value, `${at}/${key}`)
      }
    }
    walk(schema, '')

    assert.deepEqual(external, [], `generation ${version} refers outside its own file`)
    assert.deepEqual(
      forbidden,
      [],
      `generation ${version} closes an object, which would stop experiment-specific sections being schema-neutral`
    )
  }
})
