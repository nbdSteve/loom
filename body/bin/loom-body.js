#!/usr/bin/env node
'use strict'

/**
 * The body process's entry point: read the configuration, start one body, and stop
 * it once, whatever ends it.
 *
 * Configuration is environment only, and every value is explicit rather than
 * discovered. There is no credential to configure: the body connects in offline mode
 * to a loopback server, which is the whole of why this file has nothing to read a
 * secret out of.
 *
 *   LOOM_BODY_AGENT_ID        required. 1-64 of [A-Za-z0-9._:-]
 *   LOOM_BODY_USERNAME        required. 3-16 of [A-Za-z0-9_]
 *   LOOM_BODY_MC_HOST         default 127.0.0.1. A loopback literal, never a name
 *   LOOM_BODY_MC_PORT         default 25565
 *   LOOM_BODY_MC_VERSION      optional. Negotiated by Mineflayer when unset or blank
 *   LOOM_BODY_CONTROL_HOST    default 127.0.0.1. A loopback literal, never a name
 *   LOOM_BODY_CONTROL_PORT    required
 *
 * A host is refused unless it is a loopback *literal*, and `localhost` is refused
 * along with every other name: a name is answered by a resolver, and what a resolver
 * answers is not this process's to know. So the address this process binds and the
 * address it connects to are both decided here rather than anywhere in the system
 * that could be made to answer differently later.
 *
 * Everything this file writes is bounded: a lifecycle state from the protocol's own
 * closed set, the name of one refused configuration field, or one closed startup
 * code. Neither a value, a path, a host name nor a transport error's own text is
 * ever printed, because a body process's output is a log somebody keeps.
 *
 * What it deliberately does *not* do is decide anything. A disconnected body is left
 * disconnected rather than reconnected, since when and whether to come back is the
 * controller's call and a reconnect loop here would be gameplay policy in the one
 * place that must hold none. The exits it does own are process lifecycle rather than
 * gameplay: a `failed` body has no connection left to control, a signal is the host
 * asking this process to go away, and a body that has begun stopping - by whichever
 * of those routes, or over the control channel - is a process with nothing left to
 * do. All of them end at `leave`, which is the only place this process exits from.
 */

const fs = require('node:fs')

/** The file descriptor every line here is written to, synchronously. See `say`. */
const STANDARD_ERROR = 2

/**
 * The four exit statuses, which are the whole of what a supervisor reads.
 *
 * They separate the three things a supervisor would do differently about them: 0 is
 * this process being asked to go, and needs nothing; `EXIT_UNCONFIGURED` and
 * `EXIT_CONTROL` will both happen again identically until something outside this
 * process changes, and a supervisor that restarts on them loops; `EXIT_FAILED` is a
 * Minecraft connection that went away, which a restart may well fix.
 *
 * A bind failure is its own status rather than folded into `EXIT_UNCONFIGURED`,
 * because a refused configuration is this process saying no before it touched
 * anything, while a refused bind is the host saying no to an address that is
 * perfectly well spelled - most often because a body is already running on it.
 */
const EXIT_FAILED = 1
const EXIT_UNCONFIGURED = 2
const EXIT_CONTROL = 3

/** Default Minecraft server port, and the one a disposable Paper server uses. */
const DEFAULT_MINECRAFT_PORT = 25565

/** Loopback, spelled as an address rather than as a name a resolver answers for. */
const DEFAULT_LOOPBACK_HOST = '127.0.0.1'

/** The fixed code for a failure with no safe, recognised code of its own. */
const UNAVAILABLE = 'unavailable'

/**
 * The control-channel startup codes that may be written.
 *
 * An allowlist rather than a check for something to strip, because the alternative
 * is printing whatever `code` an error happens to carry - and a `code` is a string
 * from a dependency, which is exactly the sort of value that turns out to carry a
 * path the day something below `net` starts setting it. These three are the ones an
 * operator can act on: no permission for the port, something already on it, and an
 * address that is not on this host.
 */
const CONTROL_CHANNEL_FAILURE_CODES = Object.freeze([
  'EACCES',
  'EADDRINUSE',
  'EADDRNOTAVAIL'
])

/**
 * Reads a port from the environment.
 *
 * A malformed value becomes `NaN` rather than a throw of its own, so that the one
 * place that refuses a configuration is `createBody` and the line names the field
 * rather than repeating what was in the variable.
 */
function portFrom (value, fallback) {
  if (value === undefined) return fallback
  return /^\d+$/.test(value) ? Number(value) : Number.NaN
}

/**
 * Says one bounded line, synchronously.
 *
 * `process.stderr.write` queues into libuv when standard error is a pipe, which is
 * what it is whenever anything is collecting this process's output - so a line
 * written that way can still be in flight when the process exits below, and the last
 * thing a stopping body said would be the thing that got lost. A closed descriptor
 * throws rather than reporting, and there is nowhere left to report it to.
 */
function say (line) {
  try {
    fs.writeSync(STANDARD_ERROR, `${line}\n`)
  } catch {}
}

function controlChannelFailureCode (failure) {
  return CONTROL_CHANNEL_FAILURE_CODES.includes(failure?.code)
    ? failure.code
    : UNAVAILABLE
}

const { ConfigurationRefusal, createBody, silenceDependencyDebug } = require('../src/body')

/**
 * Cleared here as well as in `createBody`, and this is the earlier of the two that
 * matters: `debug` decides whether a logger writes when the logger is *created*,
 * which is require time, so an inherited `DEBUG` reaching any dependency's top level
 * has already won. Nothing above this line loads anything but `node:fs` and Loom's
 * own module, neither of which has `debug` anywhere in its tree.
 *
 * `createBody` clears it again rather than trusting this, because it is an API that
 * anything may call and its own guarantee cannot rest on its caller.
 *
 * What this cannot reach is `NODE_DEBUG`, which is Node's own rather than a
 * dependency's: Node writes its core debug output - module resolution, with the
 * absolute path of every file it looks for - while bootstrapping *this file*, before
 * a line of it has run. No Node process can suppress its own, and re-executing does
 * not help, because the parent has already written its share to the same descriptor.
 * It is cleared regardless, which is what keeps it out of anything created after this
 * point and out of any child process; whoever launches a body must not set it.
 */
silenceDependencyDebug(process.env)

let body
let leaving = false

/**
 * One bounded line per transition, and the one place a state is printed.
 *
 * Both terminal states end the process, and `stopping` is the one that matters most
 * for how long that takes: it is the state a control-channel shutdown reaches, and
 * left to the event loop it waited on whatever handle happened to be outstanding.
 * A shutdown sent after the Minecraft connection had already dropped took the
 * control channel's whole idle timeout - 30 s of a mute process still holding the
 * username it logged in with, on a channel already refusing connections.
 */
function report (state) {
  say(`loom body ${body.agentId}: ${state}`)
  if (state === 'failed') leave(EXIT_FAILED)
  if (state === 'stopping') leave(0)
}

/**
 * Stops once and exits, however this process came to be ending.
 *
 * The exit is explicit rather than left to the event loop draining, because a
 * Mineflayer client leaves timers and a socket of its own behind - a body stopped
 * before it ever logged in kept this process alive indefinitely with nothing to show
 * for it. A stopped body must not be able to outlive its own shutdown, so the
 * process ends once `stop` has closed everything Loom owns. Everything this file
 * says is written synchronously, so nothing is lost to exiting here.
 *
 * The first status wins, and the guard is what makes that true: stopping reports
 * `stopping`, which comes back here asking to exit 0, and a failed body's own 1 must
 * not be overwritten by the shutdown it just began. `stop` hands back the same
 * promise either way, so nothing is closed twice.
 */
function leave (status) {
  if (leaving) return
  leaving = true
  if (body === undefined) {
    process.exit(status)
    return
  }
  let stopped
  try {
    stopped = body.stop()
  } catch {
    process.exit(status)
    return
  }
  Promise.resolve(stopped).then(
    () => process.exit(status),
    () => process.exit(status)
  )
}

/**
 * Contains anything this process did not expect, and ends deterministically.
 *
 * A Mineflayer callback that throws, or a promise of its own that rejects, arrives
 * here rather than as Node's default report - which prints the whole exception,
 * message and stack and all, and a client's exception carries the paths of the
 * dependency it came from. So the failure is stated as one closed code, and the exit
 * status is the same one a lost connection uses, because that is what an exception
 * inside the client amounts to.
 */
function failUnexpectedly () {
  if (leaving) return
  say(`the loom body failed: ${UNAVAILABLE}`)
  leave(EXIT_FAILED)
}

process.on('uncaughtException', failUnexpectedly)
process.on('unhandledRejection', failUnexpectedly)

function main () {
  const env = process.env
  try {
    body = createBody({
      agentId: env.LOOM_BODY_AGENT_ID,
      username: env.LOOM_BODY_USERNAME,
      minecraft: {
        host: env.LOOM_BODY_MC_HOST ?? DEFAULT_LOOPBACK_HOST,
        port: portFrom(env.LOOM_BODY_MC_PORT, DEFAULT_MINECRAFT_PORT),
        version: env.LOOM_BODY_MC_VERSION
      },
      control: {
        host: env.LOOM_BODY_CONTROL_HOST ?? DEFAULT_LOOPBACK_HOST,
        port: portFrom(env.LOOM_BODY_CONTROL_PORT, Number.NaN)
      },
      onState: (state) => report(state)
    })
  } catch (failure) {
    // The refusal's own message is one field name and nothing else, which is why it
    // is the one dependency message this file passes on.
    if (failure instanceof ConfigurationRefusal) {
      say(failure.message)
      leave(EXIT_UNCONFIGURED)
    } else {
      say(`the loom body could not start: ${UNAVAILABLE}`)
      leave(EXIT_FAILED)
    }
    return
  }

  // A signal is the host asking this process to go away, which is a shutdown by
  // another route: the same one bounded teardown, and the same status as a shutdown
  // asked for over the control channel.
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => leave(0))
  }

  // Two starts rather than one, because the two failures are not the same fact and a
  // supervisor acts differently on them: a channel that will not bind is this host
  // refusing an address, and a client that will not start is this process's own
  // dependency failing.
  body.startControl().then(
    () => {
      try {
        body.startClient()
      } catch {
        say(`the loom body could not start its Minecraft client: ${UNAVAILABLE}`)
        leave(EXIT_FAILED)
        return
      }
      // The state a body starts in, said once, because `onState` fires on a
      // *transition* and the first state is not one. Without this the process is
      // silent from launch until it logs in - and a server that never answers the
      // handshake looks exactly like a process that never started.
      report(body.state())
    },
    (failure) => {
      say(`the loom body could not open its control channel: ${controlChannelFailureCode(failure)}`)
      leave(EXIT_CONTROL)
    }
  )
}

main()
