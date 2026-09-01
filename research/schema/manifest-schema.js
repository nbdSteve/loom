'use strict'

// The one place a research experiment manifest is validated, and the one place a
// generation is chosen.
//
// A manifest declares the generation of the shared core it is written in, and
// this module reads that declaration and validates the manifest against exactly
// that generation's frozen schema. Nothing here converts, upgrades or normalises
// a manifest: committed evidence is append-only, so an older generation is read
// as what it is rather than made to look like the newest one.
//
// `currentSchemaVersion` is what anything writing a *new* manifest must ask -
// a template, a generator, or an operator by hand. It is derived from the schema
// files present in this directory rather than written down, so adding
// `loom-experiment-manifest-3.schema.json` moves it and no second place has to
// be remembered.
//
// The policy this enforces - which shared-core changes need a new generation and
// which do not - is stated once, in README.md beside the schemas.

const fs = require('node:fs')
const path = require('node:path')
const Ajv = require('ajv/dist/2020')

/** The directory this module and the versioned schemas share. */
const SCHEMA_DIR = __dirname

/** `loom-experiment-manifest-<n>.schema.json`, the only file name that is a generation. */
const SCHEMA_FILE_PATTERN = /^loom-experiment-manifest-([1-9][0-9]*)\.schema\.json$/

/** The prefix of the tag a manifest declares in `schemaVersion`. */
const VERSION_TAG_PREFIX = 'loom-experiment-manifest/'

/**
 * The tag a manifest declares for a generation number.
 *
 * @param {number} version
 * @returns {string}
 */
function versionTag (version) {
  return `${VERSION_TAG_PREFIX}${version}`
}

/**
 * The generation a `schemaVersion` tag names, or null if the value is not a tag
 * at all. Deliberately strict: a leading zero, a `v`, whitespace, or a bare
 * number is refused rather than guessed at, because guessing is how a manifest
 * gets read against a generation it never claimed.
 *
 * @param {unknown} tag
 * @returns {number | null}
 */
function versionOfTag (tag) {
  if (typeof tag !== 'string' || !tag.startsWith(VERSION_TAG_PREFIX)) return null
  const digits = tag.slice(VERSION_TAG_PREFIX.length)
  if (!/^[1-9][0-9]*$/.test(digits)) return null
  return Number(digits)
}

/**
 * Every generation that has a schema in this directory, ascending. Read from the
 * filesystem on each call so a test cannot pass against a stale list.
 *
 * @returns {number[]}
 */
function publishedVersions () {
  return fs
    .readdirSync(SCHEMA_DIR)
    .map((name) => SCHEMA_FILE_PATTERN.exec(name))
    .filter((match) => match !== null)
    .map((match) => Number(match[1]))
    .sort((a, b) => a - b)
}

/**
 * The generation a new manifest must declare: the highest one published. This is
 * the deterministic answer to "which version do I write", and the only one.
 *
 * @returns {number}
 */
function currentSchemaVersion () {
  const versions = publishedVersions()
  if (versions.length === 0) {
    throw new Error(`no manifest schema found in ${SCHEMA_DIR}`)
  }
  return versions[versions.length - 1]
}

/**
 * One generation's schema, as JSON.
 *
 * @param {number} version
 * @returns {object}
 */
function schemaFor (version) {
  const file = path.join(SCHEMA_DIR, `loom-experiment-manifest-${version}.schema.json`)
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

const compiled = new Map()

/**
 * Compile one schema under the options every generation is compiled with.
 *
 * `allErrors` because a manifest is read by a person fixing it: reporting the
 * first failure only would hide the rest of a shape that has drifted. `strict`
 * because a schema mistake must be an error rather than a silently ignored
 * keyword - and it earns its keep on `strictTypes`, which refuses a type union
 * unless it is a single type paired with `null`, so a generation cannot be
 * loosened into accepting two shapes for one field.
 *
 * An Ajv instance **per call**, never one shared: a schema Ajv refuses to
 * compile has already had its `$id` registered by the time the error is thrown,
 * so on a shared instance every later call answers "id already exists" and
 * hides the real reason. One registry per generation also means no generation's
 * `$id` can shadow another's, which is the self-containment rule in the
 * validator as well as in the schemas.
 *
 * Exported so the strictness itself is pinnable by a case that compiles a
 * deliberately union-typed schema, rather than resting on an Ajv default
 * nothing exercises.
 *
 * @param {object} schema
 * @returns {import('ajv').ValidateFunction}
 */
function compileSchema (schema) {
  return new Ajv({ allErrors: true, strict: true }).compile(schema)
}

/**
 * One generation's validator, compiled once.
 *
 * @param {number} version
 * @returns {import('ajv').ValidateFunction}
 */
function validatorFor (version) {
  const cached = compiled.get(version)
  if (cached !== undefined) return cached
  const validate = compileSchema(schemaFor(version))
  compiled.set(version, validate)
  return validate
}

/**
 * Validate one parsed manifest against the generation it declares.
 *
 * A manifest that declares no generation, or one with no schema, is refused
 * rather than validated against the newest: silently reading it as the current
 * generation is how evidence written for an older shape would be reported as
 * conforming to a shape it was never checked against.
 *
 * @param {unknown} manifest
 * @returns {{ ok: boolean, version: number | null, errors: string[] }}
 */
function validateManifest (manifest) {
  if (manifest === null || typeof manifest !== 'object' || Array.isArray(manifest)) {
    return { ok: false, version: null, errors: ['manifest is not a JSON object'] }
  }
  const declared = manifest.schemaVersion
  const version = versionOfTag(declared)
  if (version === null) {
    return {
      ok: false,
      version: null,
      errors: [`schemaVersion ${JSON.stringify(declared) ?? 'absent'} is not a ${VERSION_TAG_PREFIX}<n> tag`]
    }
  }
  if (!publishedVersions().includes(version)) {
    return {
      ok: false,
      version,
      errors: [`schemaVersion ${versionTag(version)} has no schema in ${SCHEMA_DIR}`]
    }
  }
  const validate = validatorFor(version)
  if (validate(manifest)) return { ok: true, version, errors: [] }
  const errors = (validate.errors ?? []).map(
    (error) => `${error.instancePath === '' ? '/' : error.instancePath} ${error.message}`
  )
  return { ok: false, version, errors }
}

/**
 * Validate the manifest at a path. Unreadable or unparseable is a failure with a
 * reason rather than a thrown error, so a caller checking several manifests
 * reports on all of them.
 *
 * @param {string} file
 * @returns {{ ok: boolean, version: number | null, errors: string[] }}
 */
function validateManifestFile (file) {
  let parsed
  try {
    parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (cause) {
    return { ok: false, version: null, errors: [`${file}: ${cause.message}`] }
  }
  return validateManifest(parsed)
}

module.exports = {
  SCHEMA_DIR,
  VERSION_TAG_PREFIX,
  compileSchema,
  currentSchemaVersion,
  publishedVersions,
  schemaFor,
  validateManifest,
  validateManifestFile,
  versionOfTag,
  versionTag
}
