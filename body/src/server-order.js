'use strict'

/**
 * One ordered round trip to the server: a statistics request, answered after every packet this
 * client wrote before it, and read by the client after every packet the server sent before its
 * answer.
 *
 * A vanilla server handles a container click and a statistics request (`client_command` action 1)
 * on its main thread in the order they arrive on the connection, and answers the request with its
 * `statistics` (`ServerboundClientCommandPacket` and `ServerboundContainerClickPacket` both go
 * through `PacketUtils.ensureRunningOnSameThread`; nothing else on the server sends that packet).
 * The answer travels the one connection in order, so by the time the client reads it the client
 * has also read every inventory, health and block update the server sent before it. So it serves
 * two waits: a craft's, until the server has handled the clicks Mineflayer predicted the result
 * of, and a sense's, until the client's own state has caught up with what the server sent before
 * the sense was asked for - the path rehearsal's epsilon claimed one log 4 ticks after the server
 * had picked up its second, with its round trip at 694 ms, because the library's packet pipeline
 * trailed the control socket.
 *
 * Action 0 of the same packet is the vanilla client's respawn, which this process never writes.
 * A statistics request reads server state and chooses nothing, so the policy-free invariant holds.
 */

/** The `client_command` action that asks the server for the player's statistics. */
const REQUEST_STATS = 1

/**
 * How long one round trip is waited for: ten vanilla ticks, generous on a loopback server. A
 * server that has not answered by then leaves the caller to go on as it would have without it,
 * and the witness states what the server did.
 */
const SERVER_ORDER_MS = 500

/**
 * Per client: the statistics requests written and the answers counted, the waits still open by
 * ordinal, and the listeners counting while any request is unanswered.
 */
const statisticsOrder = new WeakMap()

/**
 * Resolves true once the server has handled every packet [bot] wrote before this call and the
 * client has read every packet the server sent before answering, or false after [timeoutMs], on
 * a lost connection, or when the client cannot ask. An answer names no request, so answers are
 * matched to requests by their order: a late answer to an earlier wait that timed out never
 * releases a later one (the round 3 offline audit).
 */
function serverHandledEarlierPackets (bot, timeoutMs) {
  const client = bot._client
  if (!(timeoutMs > 0) || typeof client?.write !== 'function' || client.ended === true ||
      typeof client.on !== 'function' || typeof client.removeListener !== 'function') {
    return Promise.resolve(false)
  }
  let order = statisticsOrder.get(client)
  if (order === undefined) {
    order = { asked: 0, answered: 0, waits: new Map(), counted: null, lost: null }
    statisticsOrder.set(client, order)
  }
  return new Promise((resolve) => {
    const ordinal = order.asked + 1
    const end = (handled) => {
      clearTimeout(timer)
      order.waits.delete(ordinal)
      quietWhenAnswered(client, order)
      resolve(handled)
    }
    // Deliberately not unref'd: the timer is all that answers a wait the server never does, and a
    // sense or craft awaits that answer. Unref'd, an event loop with nothing else in it left the
    // promise pending for good (CI's Node 22 cancelled interactions.test.js there). The wait is
    // bounded and every ending clears it, and a lost connection ends every wait at once.
    const timer = setTimeout(() => end(false), timeoutMs)
    order.waits.set(ordinal, end)
    countAnswers(client, order)
    try {
      client.write('client_command', { actionId: REQUEST_STATS })
      order.asked = ordinal
    } catch {
      end(false)
    }
  })
}

/** Counts [client]'s statistics answers in order, each releasing only the wait it answers. */
function countAnswers (client, order) {
  if (order.counted !== null) return
  order.counted = () => {
    // No more answers are counted than were asked for, so an unasked one releases nobody.
    order.answered = Math.min(order.answered + 1, order.asked)
    order.waits.get(order.answered)?.(true)
    quietWhenAnswered(client, order)
  }
  order.lost = () => {
    for (const end of [...order.waits.values()]) end(false)
    statisticsOrder.delete(client)
    quiet(client, order)
  }
  client.on('statistics', order.counted)
  client.on('end', order.lost)
}

/** Stops counting once every request written is answered and no wait is open. */
function quietWhenAnswered (client, order) {
  if (order.waits.size === 0 && order.answered >= order.asked) quiet(client, order)
}

function quiet (client, order) {
  if (order.counted === null) return
  client.removeListener('statistics', order.counted)
  client.removeListener('end', order.lost)
  order.counted = null
  order.lost = null
}

module.exports = {
  REQUEST_STATS,
  SERVER_ORDER_MS,
  serverHandledEarlierPackets
}
