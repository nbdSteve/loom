#!/usr/bin/env node
'use strict'

const fs = require('node:fs')
const path = require('node:path')

const { validateArtifact, validateProspectiveManifest } = require('../lib/artifact.js')
const { runExperiment, sourceIdentity } = require('../lib/harness.js')
const { RunFailure } = require('../lib/lifecycle.js')
const { readJson } = require('../lib/util.js')

function usage () {
  console.error(`Usage:
  loom-tribe validate <artifact-directory>
  loom-tribe preflight --config <file>
  loom-tribe run --config <file> [--artifact-root <directory>] [--paper-jar <file>] [--skip-build]`)
}

function parsedOptions (args) {
  const options = {}
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--skip-build') {
      options.skipBuild = true
      continue
    }
    if (!argument.startsWith('--') || index + 1 >= args.length) {
      throw new Error(`unexpected argument ${argument}`)
    }
    options[argument.slice(2)] = args[index + 1]
    index += 1
  }
  return options
}

async function main () {
  const [command, ...args] = process.argv.slice(2)
  const repositoryRoot = path.resolve(__dirname, '..', '..')
  const invocationDirectory = path.resolve(process.env.INIT_CWD ?? process.cwd())
  const argumentPath = (value) => path.resolve(invocationDirectory, value)
  if (command === 'validate') {
    if (args.length !== 1) throw new Error('validate requires one artifact directory')
    const result = validateArtifact(argumentPath(args[0]))
    if (!result.ok) throw new Error(result.errors.join('\n'))
    console.log(`valid loom experiment artifact (manifest generation ${result.version})`)
    return
  }
  const options = parsedOptions(args)
  if (typeof options.config !== 'string') throw new Error(`${command ?? 'command'} requires --config`)
  const configFile = argumentPath(options.config)
  if (!fs.existsSync(configFile)) throw new Error(`configuration does not exist: ${configFile}`)
  if (command === 'preflight') {
    const config = readJson(configFile)
    validateProspectiveManifest(config, sourceIdentity(repositoryRoot))
    console.log(`valid prospective manifest for ${config.experimentId}`)
    return
  }
  if (command !== 'run') {
    usage()
    process.exitCode = 2
    return
  }

  const controller = new AbortController()
  const cancel = (signal) => {
    controller.abort(new RunFailure('cancelled', `received ${signal}`))
  }
  const cancelInterrupt = () => cancel('SIGINT')
  const cancelTermination = () => cancel('SIGTERM')
  process.once('SIGINT', cancelInterrupt)
  process.once('SIGTERM', cancelTermination)
  try {
    const result = await runExperiment({
      repositoryRoot,
      configFile,
      artifactRoot:
        options['artifact-root'] === undefined
          ? path.join(repositoryRoot, 'research', 'experiments')
          : argumentPath(options['artifact-root']),
      paperJar:
        options['paper-jar'] === undefined
          ? undefined
          : argumentPath(options['paper-jar']),
      skipBuild: options.skipBuild === true,
      signal: controller.signal
    })
    console.log(`published ${result.manifest.experimentId}`)
  } finally {
    process.removeListener('SIGINT', cancelInterrupt)
    process.removeListener('SIGTERM', cancelTermination)
  }
}

main().catch((cause) => {
  console.error(cause instanceof Error ? cause.message : String(cause))
  process.exitCode = 1
})
