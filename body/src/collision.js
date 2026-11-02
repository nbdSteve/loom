'use strict'

/**
 * Vanilla's collision tolerance, given to the client's step simulation.
 *
 * Prismarine's physics stops a body against a block only while the body's face is at or
 * outside the block's face, and compares the two exactly. Vanilla compares them with a
 * tolerance of 1e-7 (`VoxelShape.collide`), and the difference is not academic: a body
 * clamped flush against a face at a negative coordinate is placed at, say, x = -2.3, and on
 * the next step its box face is -2.3 + 0.3 = -1.9999999999999998, which is 2e-16 *inside* the
 * block at x = -2. The exact comparison then finds no collision, the client steps into the
 * block, and the server - which applies the tolerance - finds the move collides with
 * something new and puts the player back where it was. That repeats every tick, so a walk or
 * flight that ever comes to rest flush against a block face at a negative coordinate is held
 * in place by the server until its stall bound ends it: the scripted ten-agent rehearsal lost
 * two flights to exactly that, and `test/native-flee-terrain.test.js` replays them on the real
 * ground.
 *
 * So the three offset functions take vanilla's tolerance both ways: a face within it is
 * touching, and a body within it of another box on a cross axis is beside that box rather
 * than over or under it. Nothing else about the simulation changes, and nothing here moves a
 * body or chooses anything; it makes the client's answer the one the server will give.
 * Installed once, on the library's own class, because the pathfinder's simulations and the
 * physics plugin both step through it.
 */

const path = require('node:path')

/** Vanilla's `Shapes.EPSILON`, the tolerance a collision is judged with. */
const COLLISION_EPSILON = 1e-7

const installed = Symbol.for('loom.vanillaCollision')

/** Prismarine's AABB class, resolved where the physics plugin resolves it. */
function physicsAabb () {
  const physics = path.dirname(require.resolve('prismarine-physics', {
    paths: [path.dirname(require.resolve('mineflayer'))]
  }))
  return require(path.join(physics, 'lib', 'aabb'))
}

/**
 * The offset `moving` may travel along one axis before `fixed` stops it, under vanilla's
 * tolerance. `cross` says whether the two overlap on the other two axes.
 */
function clipped (fixedMin, fixedMax, movingMin, movingMax, offset, cross) {
  if (!cross) return offset
  if (offset > 0 && movingMax <= fixedMin + COLLISION_EPSILON) return Math.min(fixedMin - movingMax, offset)
  if (offset < 0 && movingMin >= fixedMax - COLLISION_EPSILON) return Math.max(fixedMax - movingMin, offset)
  return offset
}

/** Whether two ranges overlap by more than the tolerance. */
function overlaps (firstMin, firstMax, secondMin, secondMax) {
  return secondMax > firstMin + COLLISION_EPSILON && secondMin < firstMax - COLLISION_EPSILON
}

/** Gives Prismarine's AABB vanilla's collision tolerance; idempotent. */
function installVanillaCollision (AABB = physicsAabb()) {
  if (AABB.prototype[installed] === true) return AABB
  AABB.prototype.computeOffsetX = function computeOffsetX (other, offsetX) {
    return clipped(this.minX, this.maxX, other.minX, other.maxX, offsetX,
      overlaps(this.minY, this.maxY, other.minY, other.maxY) && overlaps(this.minZ, this.maxZ, other.minZ, other.maxZ))
  }
  AABB.prototype.computeOffsetY = function computeOffsetY (other, offsetY) {
    return clipped(this.minY, this.maxY, other.minY, other.maxY, offsetY,
      overlaps(this.minX, this.maxX, other.minX, other.maxX) && overlaps(this.minZ, this.maxZ, other.minZ, other.maxZ))
  }
  AABB.prototype.computeOffsetZ = function computeOffsetZ (other, offsetZ) {
    return clipped(this.minZ, this.maxZ, other.minZ, other.maxZ, offsetZ,
      overlaps(this.minX, this.maxX, other.minX, other.maxX) && overlaps(this.minY, this.maxY, other.minY, other.maxY))
  }
  Object.defineProperty(AABB.prototype, installed, { value: true })
  return AABB
}

module.exports = {
  COLLISION_EPSILON,
  installVanillaCollision,
  physicsAabb
}
