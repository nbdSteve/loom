'use strict'

/**
 * The server's world border, as the server told this client, and the cells a body may stand
 * in inside it.
 *
 * The border is a wall the server enforces and the client library does not model: Mineflayer
 * keeps no border state and Prismarine's physics collides with blocks alone, so the
 * pathfinder searched across the border as if it were open ground. A route over it is walked
 * into a wall nobody can see: the server puts the player back on every step, the body jumps in
 * place, and the stall bound ends the request. In the v29 plains rehearsal theta's flights from
 * a skeleton stalled at z -63.7 against the 128-block border three times, and iota's flight
 * from a zombie stalled at x -63.7 while the zombie closed to 1.6 blocks and killed it.
 *
 * So the four border packets are read here and nothing else: the centre, the diameter, and a
 * diameter that is changing. While it changes the smaller of the two ends is the border, so a
 * shrinking border is never searched as the larger one. This decides nowhere a body goes; it
 * states where the server will not let one stand, which is the ground's answer rather than
 * Loom's.
 */

/** Half a player's width: the body's box reaches this far either side of where it stands. */
const PLAYER_HALF_WIDTH = 0.3

/** The search cost of a cell outside the border: past every move the library will take. */
const BORDER_EXCLUDED = Number.POSITIVE_INFINITY

const borders = new WeakMap()

/** Starts reading `bot`'s border packets, once per bot, and answers its border reader. */
function trackWorldBorder (bot) {
  const known = borders.get(bot)
  if (known !== undefined) return known
  const client = bot._client
  // A bot with no protocol client is told no border, and states none.
  if (typeof client?.on !== 'function') return () => undefined
  const state = { x: undefined, z: undefined, diameter: undefined }
  const finite = (value) => typeof value === 'number' && Number.isFinite(value)
  const sized = (from, to) => {
    if (finite(from) && finite(to) && from > 0 && to > 0) state.diameter = Math.min(from, to)
  }
  client.on('initialize_world_border', (packet) => {
    if (finite(packet.x) && finite(packet.z)) {
      state.x = packet.x
      state.z = packet.z
    }
    sized(packet.oldDiameter, packet.newDiameter)
  })
  client.on('world_border_center', (packet) => {
    if (finite(packet.x) && finite(packet.z)) {
      state.x = packet.x
      state.z = packet.z
    }
  })
  client.on('world_border_size', (packet) => sized(packet.diameter, packet.diameter))
  client.on('world_border_lerp_size', (packet) => sized(packet.oldDiameter, packet.newDiameter))
  const reader = () => state.x === undefined || state.diameter === undefined
    ? undefined
    : { x: state.x, z: state.z, diameter: state.diameter }
  borders.set(bot, reader)
  return reader
}

/** The border `bot`'s server last stated, or undefined while it has stated none. */
function worldBorderOf (bot) {
  return borders.get(bot)?.()
}

/** Whether a body standing in block column (`x`, `z`) lies wholly inside `border`. */
function columnInside (border, x, z) {
  const half = border.diameter / 2
  const inside = (cell, centre) =>
    cell + 0.5 - PLAYER_HALF_WIDTH >= centre - half && cell + 0.5 + PLAYER_HALF_WIDTH <= centre + half
  return inside(x, border.x) && inside(z, border.z)
}

/**
 * Whether the block in column (`x`, `z`) lies inside `border` as vanilla's own interaction check
 * reads it (`WorldBorder.isWithinBounds`): any part of the block inside the wall. The server
 * refuses every dig of, and placement against, a block outside it and puts the block back, so a
 * dig the client library predicted waited out its whole deadline for a break that never came
 * (r36 zeta's short grass at x -65, a block past the 128-block border, `digLibraryFailure`).
 */
function blockInside (border, x, z) {
  const half = border.diameter / 2
  const inside = (cell, centre) => cell + 1 > centre - half && cell < centre + half
  return inside(x, border.x) && inside(z, border.z)
}

/**
 * The step exclusion a route is searched under: every cell of a column outside `border` costs
 * [BORDER_EXCLUDED], so no move enters one. The pathfinder prices each cell a move passes
 * through with it, and drops a move whose cost passes a hundred.
 */
function borderExclusion (border) {
  return (block) => {
    const position = block?.position
    if (position === undefined || position === null) return 0
    return columnInside(border, position.x, position.z) ? 0 : BORDER_EXCLUDED
  }
}

module.exports = {
  BORDER_EXCLUDED,
  PLAYER_HALF_WIDTH,
  blockInside,
  borderExclusion,
  columnInside,
  trackWorldBorder,
  worldBorderOf
}
