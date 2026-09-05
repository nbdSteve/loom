'use strict'

const conversions = require('mineflayer/lib/conversions')

/**
 * The three things the client library needs from the server's own reports, as one
 * plugin of Loom's own: the login event, the world the player is in, and where the
 * server says the player is standing and facing.
 *
 * Mineflayer keeps all three in plugins this process must not load, and each of those
 * plugins writes packets back at the server that a policy-free body may not send:
 *
 * - `game` is where `login`, `bot.game` and the dimension codec are. It cannot be
 *   loaded, because on a `win_game` game-state change it writes `client_command` at
 *   the server, which is the vanilla client's *respawn* - the automatic respawn
 *   `respawn: false` exists to prevent, arriving by another route and reachable by
 *   any server that sends the packet. This file therefore registers no handler on
 *   `game_state_change` at all, which is a stronger statement than not taking that
 *   branch: `bot.game.gameMode` is read once from the login report and never
 *   revised, so an operator who changes the body's game mode mid-run leaves it
 *   stale. What that field gates is one refusal inside `bot.consume`, and the eat
 *   path reads `bot.food` for itself rather than relying on it.
 * - `physics` owns the *clientbound* `position` packet, and with it the
 *   `teleport_confirm` reply the protocol requires. It cannot be loaded, because it
 *   also runs a movement interval and writes autonomous position updates. Without
 *   the handler below `bot.entity.position` never leaves `(0,0,0)`, so every
 *   perception this process reports would be taken at the world origin.
 *
 * So what is here is exactly what those two plugins report and none of what they
 * decide. `bot.game` carries the four fields the loaded plugins actually read -
 * `dimension`, `minY` and `height` for `blocks`, `gameMode` for `inventory` - and
 * nothing else, because a field nothing reads is a field that can be silently wrong.
 * The position handler records the server's own answer and writes one reply the
 * protocol demands, in the same category as the `keep_alive` minecraft-protocol's own
 * client answers below any plugin. Neither half sends a packet of its own accord.
 *
 * **Facing is recorded because an eat would otherwise turn the body.** 1.21.1's
 * serverbound `use_item` carries the client's rotation and the server applies it, and
 * `inventory`'s `activateItem` fills those two fields from `bot.entity.yaw` and
 * `bot.entity.pitch`. Both default to zero, so a process that never recorded the
 * server's own answer would snap the body to facing due south and level on every
 * meal - a movement of the body's own choosing, in the one place this process is
 * meant only to consume an item. The conversion is Mineflayer's own
 * (`mineflayer/lib/conversions`) rather than trigonometry restated here, so the value
 * that plugin converts back is exactly the value the server sent.
 *
 * **The world shape is read for 1.19 and above.** That is every version with
 * `dimensionDataInCodec`, which is where a dimension's `minY` and `height` come from,
 * and it covers the whole range Loom runs against. Below it the two fall back to the
 * pre-1.18 geometry the constants below state, exactly as Mineflayer's own plugin does
 * before it reaches its NBT branch - so a body pointed at an older server would build
 * chunks to the wrong height. Mirroring that branch means reading NBT, and this
 * process does not take a dependency for a version it does not target.
 */

/**
 * The world shape assumed before the codec answers, and Mineflayer's own defaults.
 *
 * Stated as the vanilla geometry of 1.16 and 1.17 rather than as a neutral zero,
 * because `blocks` builds every chunk column against them: a zero height would drop
 * every block the server sent instead of merely misplacing it.
 */
const DEFAULT_MIN_Y = 0
const DEFAULT_WORLD_HEIGHT = 256

/**
 * Loom's replacement for the two Mineflayer plugins above, and the whole of it.
 *
 * The login event is emitted from here rather than from a plugin of its own so that
 * the ordering is a property of this function instead of of an object's key order:
 * `bot.game` is populated from the same packet, and a lifecycle event that arrived
 * before the world it describes would be a state this process could not answer for.
 */
function followWorldState (bot) {
  bot.game = {}

  // 1.20.2+ sends the registries in the configuration phase, before login, and the
  // dimension registry is what turns the login packet's dimension index into a name
  // and that name into a height. A registry this reader has no handler for is
  // ignored by the library rather than refused here.
  bot._client.on('registry_data', (packet) => {
    bot.registry.loadDimensionCodec(packet.codec || packet)
  })

  bot._client.on('login', (packet) => {
    readWorld(bot, packet.worldState ?? packet)
    bot.emit('login')
  })

  // A respawn packet is how a dimension change arrives as well as how a death is
  // answered, and this process never asks for either: it is read for the world it
  // names and nothing is written back. `blocks` keeps its own chunk storage in step
  // from the same packet, against the shape this sets.
  bot._client.on('respawn', (packet) => {
    readWorld(bot, packet.worldState ?? packet)
  })

  bot._client.on('position', (packet) => {
    recordPosition(bot, packet)
  })
}

/**
 * Reads the world out of a login or respawn report.
 *
 * The branches are Mineflayer's own, in its own order: 1.20.5 and above index a
 * registry that arrived earlier, 1.21 sends the name outright, and 1.19 sends the
 * dimension *type* under `worldType` on a login and under `dimension` on a respawn -
 * never the level name, which a proxy or a modded server may spell differently from
 * the type the codec is keyed by.
 */
function readWorld (bot, spawn) {
  bot.game.gameMode = bot.supportFeature('spawnRespawnWorldDataField')
    ? spawn.gamemode
    : gameModeNameOf(spawn.gamemode ?? spawn.gameMode)
  bot.game.dimension = dimensionNameOf(bot, spawn)
  bot.game.minY = DEFAULT_MIN_Y
  bot.game.height = DEFAULT_WORLD_HEIGHT
  if (bot.supportFeature('dimensionDataInCodec')) {
    const shape = bot.registry.dimensionsByName[bot.game.dimension]
    if (shape !== undefined) {
      bot.game.minY = shape.minY
      bot.game.height = shape.height
    }
  }
}

/** Every game mode, by the number the wire spells it as before 1.20.5. */
const GAME_MODE_NAMES = Object.freeze(['survival', 'creative', 'adventure', 'spectator'])

/**
 * The game mode a pre-1.20.5 report names.
 *
 * Only the lower two bits are the mode - the third carries hardcore, which nothing
 * here reads - and a number outside the set is read as survival, which is
 * Mineflayer's own answer for it.
 */
function gameModeNameOf (bits) {
  if (typeof bits !== 'number' || bits < 0) return GAME_MODE_NAMES[0]
  return GAME_MODE_NAMES[bits & 0b11]
}

/** The dimension a login or respawn report names, or undefined if it names none. */
function dimensionNameOf (bot, spawn) {
  if (bot.supportFeature('segmentedRegistryCodecData')) {
    if (typeof spawn.dimension === 'number') {
      return withoutNamespace(bot.registry.dimensionsArray?.[spawn.dimension]?.name)
    }
    if (typeof spawn.dimension === 'string') return withoutNamespace(spawn.dimension)
    return undefined
  }
  const named = spawn.worldType ?? spawn.dimension ?? spawn.worldName
  return typeof named === 'string' ? withoutNamespace(named) : undefined
}

/** A resource name with the vanilla namespace dropped, the way the codec is keyed. */
function withoutNamespace (name) {
  return typeof name === 'string' ? name.replace('minecraft:', '') : undefined
}

/**
 * Records where the server says the player is and which way it is facing, and
 * confirms the teleport.
 *
 * Each field is absolute unless its flag is set, in which case it is a delta on the
 * value already held - so a reader that took every field as absolute would place the
 * body at the origin plus a step on the first relative update. 1.20.5 and above send
 * the flags as an object of booleans and older versions as a bitmask, and both are
 * read here for the same reason the branches above are.
 *
 * Facing arrives in degrees, measured the way the protocol measures them, and is kept
 * in the radians `bot.entity` is documented to carry - so a relative update is summed
 * in the protocol's own units, before the conversion, rather than mixing the two.
 *
 * Velocity and hitbox height are not recorded. They are read by no plugin this process
 * loads, and a field kept up to date for no reader is a field that can be quietly
 * wrong.
 *
 * The confirmation is the protocol's own reply rather than a movement of this body's
 * choosing: an unconfirmed teleport leaves the server ignoring the client, and this
 * process never writes a position packet at all. The absent `bot.entity` is guarded
 * rather than assumed, because a throw inside a packet handler is an uncaught
 * exception that would take the whole process down outside its bounded output path.
 */
function recordPosition (bot, packet) {
  if (bot.entity === undefined) return
  const at = bot.entity.position
  const relative = relativeFieldsOf(packet.flags)
  at.set(
    relative.x ? at.x + packet.x : packet.x,
    relative.y ? at.y + packet.y : packet.y,
    relative.z ? at.z + packet.z : packet.z
  )
  const heldYaw = relative.yaw ? conversions.toNotchianYaw(bot.entity.yaw) : 0
  const heldPitch = relative.pitch ? conversions.toNotchianPitch(bot.entity.pitch) : 0
  bot.entity.yaw = conversions.fromNotchianYaw(heldYaw + packet.yaw)
  bot.entity.pitch = conversions.fromNotchianPitch(heldPitch + packet.pitch)
  if (bot.supportFeature('teleportUsesOwnPacket')) {
    bot._client.write('teleport_confirm', { teleportId: packet.teleportId })
  }
}

/** Which fields of a position update are deltas, from either spelling of the flags. */
function relativeFieldsOf (flags) {
  if (typeof flags === 'object' && flags !== null) {
    return {
      x: Boolean(flags.x),
      y: Boolean(flags.y),
      z: Boolean(flags.z),
      yaw: Boolean(flags.yaw),
      pitch: Boolean(flags.pitch)
    }
  }
  const bits = typeof flags === 'number' ? flags : 0
  return {
    x: Boolean(bits & 0x01),
    y: Boolean(bits & 0x02),
    z: Boolean(bits & 0x04),
    yaw: Boolean(bits & 0x08),
    pitch: Boolean(bits & 0x10)
  }
}

module.exports = {
  DEFAULT_MIN_Y,
  DEFAULT_WORLD_HEIGHT,
  GAME_MODE_NAMES,
  followWorldState
}
