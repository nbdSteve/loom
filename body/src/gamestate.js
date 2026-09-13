'use strict'

/**
 * The two things the client library needs from the server's own reports and no loaded
 * plugin provides: the login event, and the world the player is in.
 *
 * Mineflayer keeps both in `game`, which this process must not load, because on a
 * `win_game` game-state change that plugin writes `client_command` at the server -
 * the vanilla client's *respawn*, the automatic respawn `respawn: false` exists to
 * prevent, arriving by another route and reachable by any server that sends the
 * packet. This file therefore registers no handler on `game_state_change` at all,
 * which is a stronger statement than not taking that branch: `bot.game.gameMode` is
 * read once from the login report and never revised, so an operator who changes the
 * body's game mode mid-run leaves it stale. What that field gates is one refusal
 * inside `bot.consume`, and the eat path reads `bot.food` for itself rather than
 * relying on it.
 *
 * So what is here is exactly what that plugin reports and none of what it decides.
 * `bot.game` carries the four fields the loaded plugins actually read - `dimension`,
 * `minY` and `height` for `blocks`, `gameMode` for `inventory` and `physics` - and
 * nothing else, because a field nothing reads is a field that can be silently wrong.
 * Nothing here sends a packet of its own accord.
 *
 * **The clientbound `position` packet is `physics`'s and is deliberately not read
 * here.** It used to be: `physics` could not be loaded while nothing asked the body to
 * walk, so this file recorded the server's own answer and wrote the
 * `teleport_confirm` the protocol demands. A body that can be asked to walk needs the
 * plugin, because a player's movement in Minecraft is client-simulated and there is no
 * "server, please walk me there" packet - so `body/src/movement.js` loads it, under a scope
 * that keeps its stepping and its position reporting to one accepted request, and this file
 * keeps only the two reports above. Registering a second handler would be a fault
 * rather than a duplication: `physics` writes the same `teleport_confirm` under the
 * same `supportFeature('teleportUsesOwnPacket')` guard, so one teleport would be
 * confirmed twice. That handler is the *unscoped* half of the plugin and has to be, since
 * it is what moves `bot.entity.position` off the origin for every perception.
 *
 * **Facing is recorded by that same handler, and it has to be.** 1.21.1's serverbound
 * `use_item` carries the client's rotation and the server applies it, and `inventory`'s
 * `activateItem` fills those two fields from `bot.entity.yaw` and `bot.entity.pitch`.
 * Both default to zero, so a body whose facing nothing recorded would snap to due south
 * and level on every meal - a movement of the body's own choosing, in the one place
 * this process is meant only to consume an item. `physics` records both from the
 * server's own report, so the meal keeps the facing the server last stated.
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
 * Loom's replacement for the Mineflayer plugin above, and the whole of it.
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

module.exports = {
  DEFAULT_MIN_Y,
  DEFAULT_WORLD_HEIGHT,
  GAME_MODE_NAMES,
  followWorldState
}
