'use strict'

/**
 * The cross-layer affordance gate: every block and dropped item a sense answer offers must
 * be a handle its own action can use, at once, in the scene that offered it.
 *
 * Each case builds a real voxel scene under a real bot, senses it over the control socket
 * the way the plugin does, and then names every offered block to `dig` and `place` and every
 * offered item to `collect`, one at a time, from the same standing position. The fake server
 * never answers a dig, a placement or a pickup, so nothing the gate does changes the scene: a
 * dig or placement that began ends `timedOut`, and a collection whose route was accepted ends
 * `timedOut` or `cancelled` and the body is put back where it stood. Any other ending - an
 * unseen target, one out of reach, a refused route, a library failure - is a handle the body
 * should never have offered, and fails the gate.
 *
 * The plugin offers the model exactly these lists: under 48 per kind no JVM cut happens, so
 * a body offer and a model-visible ref are one-to-one (`PlayerSceneSelectionIntegrationTest`).
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')
const { Vec3 } = require('vec3')

const {
  Chunk,
  Item,
  OVERWORLD,
  QUICK_BAR_START,
  clockReported,
  disconnected,
  embodied,
  handshaken,
  positionPacket,
  promptly,
  registry,
  request,
  settled,
  spawned,
  spotted,
  stack,
  stocked
} = require('./harness')
const { MAX_REMEMBERED_SENSES } = require('../src/senses')

afterEach(disconnected)

/** The radius and per-kind limit the plugin asks every sense for. */
const SENSE = Object.freeze({ radius: 24, limit: 48 })

/** Short enough that no dig or placement can finish, so the scene stays as it was sensed. */
const PROBE_MS = 25

/** Long enough for any route search to answer inside the body's own search budget. */
const COLLECT_PROBE_MS = 900

const FED = Object.freeze({ health: 20, food: 20, foodSaturation: 5 })
const EYE_HEIGHT = 1.62

/** Endings that prove an action began against the handle it was given. */
const BEGAN = new Set(['timedOut', 'dug', 'placed', 'collected', 'cancelled'])

/** Faces a placement may name, with the neighbour each would fill. */
const FACES = Object.freeze({
  up: [0, 1, 0],
  down: [0, -1, 0],
  north: [0, 0, -1],
  south: [0, 0, 1],
  west: [-1, 0, 0],
  east: [1, 0, 0]
})

/**
 * A voxel scene over the three-by-three chunk columns around the origin.
 *
 * `solid` answers whether a block position holds the scene's filler, and `blocks` places
 * named blocks over it. Everything else is air, so a hole in `solid` is a cavity or a pit.
 */
function voxels (bot, { solid, blocks = [], filler = 'dirt', top = 'grass_block', topLevel }) {
  const fill = registry.blocksByName[filler].defaultState
  const surface = registry.blocksByName[top].defaultState
  const named = new Map(blocks.map(([x, y, z, name]) => [`${x},${y},${z}`, name]))
  for (let cx = -1; cx <= 1; cx += 1) {
    for (let cz = -1; cz <= 1; cz += 1) {
      const column = new Chunk(OVERWORLD)
      for (let lx = 0; lx < 16; lx += 1) {
        for (let lz = 0; lz < 16; lz += 1) {
          const x = cx * 16 + lx
          const z = cz * 16 + lz
          for (let y = topLevel - 8; y <= topLevel + 8; y += 1) {
            const name = named.get(`${x},${y},${z}`)
            if (name !== undefined) {
              column.setBlockStateId(new Vec3(lx, y, lz), registry.blocksByName[name].defaultState)
            } else if (solid(x, y, z)) {
              column.setBlockStateId(new Vec3(lx, y, lz), y === topLevel && !solid(x, y + 1, z) ? surface : fill)
            }
          }
        }
      }
      bot.world.setColumn(cx, cz, column)
    }
  }
}

/** Ground that is solid up to `topLevel`, with `holes` carved out of it. */
function groundWith (topLevel, holes = []) {
  const carved = new Set(holes.map(([x, y, z]) => `${x},${y},${z}`))
  return (x, y, z) => y <= topLevel && !carved.has(`${x},${y},${z}`)
}

/** Every block position in an inclusive box, for carving. */
function box ([x0, y0, z0], [x1, y1, z1]) {
  const cells = []
  for (let x = x0; x <= x1; x += 1) {
    for (let y = y0; y <= y1; y += 1) {
      for (let z = z0; z <= z1; z += 1) cells.push([x, y, z])
    }
  }
  return cells
}

/** One dropped stack where a server put it. */
function droppedAt (client, { id, what, x, y, z }) {
  spotted(client, { id, what: 'item', x, y, z })
  client.emit('entity_metadata', {
    entityId: id,
    metadata: [{ key: 8, type: 'item_stack', value: Item.toNotch(stack(what, 1)) }]
  })
}

/** A body standing at `feet` in a built scene, with placeable dirt in hand, and one control peer. */
async function sceneBody ({ feet, build, items = [] }) {
  const { body, bot, client, port } = await embodied()
  spawned(client, FED)
  clockReported(client, { timeOfDay: 6_000, day: 0 })
  client.emit('position', positionPacket({ x: feet[0], y: feet[1], z: feet[2], yaw: 0, pitch: 0, teleportId: 1 }))
  build(bot)
  stocked(client, [[QUICK_BAR_START, 'dirt', 16]])
  for (const item of items) droppedAt(client, item)
  await settled(200)
  const peer = await handshaken(port)
  let teleports = 1
  const standBack = async () => {
    teleports += 1
    client.emit('position', positionPacket({ x: feet[0], y: feet[1], z: feet[2], yaw: 0, pitch: 0, teleportId: teleports }))
    await settled(60)
  }
  return { body, bot, client, peer, port, standBack }
}

/** One sense answer, as the plugin asks for it. */
async function sensed (peer, requestId) {
  const answer = await promptly(peer.answer(request('sense', requestId, SENSE)), `sense ${requestId}`)
  assert.equal(answer.op, 'sense', JSON.stringify(answer))
  return answer
}

/** The faces of `position` whose neighbour is air in the live world, turned toward `eyes`. */
function openFaces (bot, position, eyes) {
  return Object.entries(FACES).filter(([, [dx, dy, dz]]) => {
    const neighbour = bot.blockAt(new Vec3(position[0] + dx, position[1] + dy, position[2] + dz))
    if (neighbour === null || neighbour.name !== 'air') return false
    const centre = [position[0] + 0.5 + dx * 0.5, position[1] + 0.5 + dy * 0.5, position[2] + 0.5 + dz * 0.5]
    return (centre[0] - eyes[0]) * dx + (centre[1] - eyes[1]) * dy + (centre[2] - eyes[2]) * dz < 0
  }).map(([face]) => face)
}

/**
 * Spends one action on a fresh control connection with a fresh sense, as the plugin does:
 * one perception, one action. The scene is unchanged, so the fresh answer offers exactly
 * the handles the enumerated one did, at the same ordinals.
 */
async function actOnce ({ port }, enumerated, op, fields, label) {
  const peer = await handshaken(port)
  try {
    const fresh = await sensed(peer, 'a1')
    assert.deepEqual(fresh.blocks, enumerated.blocks, `${label}: the unchanged scene must offer the same blocks`)
    assert.deepEqual(fresh.items, enumerated.items, `${label}: the unchanged scene must offer the same items`)
    const answer = await promptly(
      peer.answer(request(op, 'a2', { senseId: fresh.requestId, ...fields })),
      label,
      op === 'collect' ? COLLECT_PROBE_MS + 5_000 : 5_000
    )
    return answer.outcome ?? answer.error
  } finally {
    peer.stop()
  }
}

/**
 * Names every offered block to dig and place, and every offered item to collect, and
 * returns each handle whose action did not begin.
 */
async function probeEveryOffer (context, answer, feet) {
  const { bot, standBack } = context
  const failures = []
  const eyes = [feet[0], feet[1] + EYE_HEIGHT, feet[2]]
  const held = answer.inventory.find((entry) => entry.slot === QUICK_BAR_START)
  for (const [index, block] of answer.blocks.entries()) {
    if (block.visible !== true) failures.push(`block ${block.position} offered unseen`)
    const dug = await actOnce(context, answer, 'dig', { block: index, timeoutMs: PROBE_MS }, `dig ${index}`)
    if (!BEGAN.has(dug)) failures.push(`dig ${block.what} ${block.position}: ${dug}`)
    const endings = []
    let placedOnce = false
    for (const face of openFaces(bot, block.position, eyes)) {
      const placed = await actOnce(context, answer, 'place', {
        block: index, slot: held.slot, face, timeoutMs: PROBE_MS
      }, `place ${index} ${face}`)
      endings.push(`${face}:${placed}`)
      if (BEGAN.has(placed)) {
        placedOnce = true
        break
      }
    }
    if (!placedOnce) failures.push(`place on ${block.what} ${block.position}: ${endings.join(',') || 'no open face'}`)
  }
  for (const [index, item] of answer.items.entries()) {
    if (item.visible !== true) failures.push(`item ${item.position} offered unseen`)
    const collected = await actOnce(context, answer, 'collect', {
      item: index, approach: true, timeoutMs: COLLECT_PROBE_MS, sprint: false
    }, `collect ${index}`)
    if (!BEGAN.has(collected)) failures.push(`collect ${item.what} ${item.position}: ${collected}`)
    await standBack()
  }
  return failures
}

/** Runs the whole gate over one scene and returns the answer it probed. */
async function gate (scene) {
  const context = await sceneBody(scene)
  const answer = await sensed(context.peer, 's1')
  const failures = await probeEveryOffer(context, answer, scene.feet)
  context.peer.stop()
  await context.body.stop()
  assert.deepEqual(failures, [], 'every offered handle must be usable by its own action')
  return answer
}

const listed = (answer) => new Set(answer.blocks.map((block) => block.position.join(',')))

test('open ground: every offered block and item is usable, including floor out of a centre ray', async () => {
  const answer = await gate({
    feet: [8.5, 64, 8.5],
    build: (bot) => voxels(bot, { topLevel: 63, solid: groundWith(63) }),
    items: [
      { id: 900, what: 'wheat_seeds', x: 11.5, y: 64, z: 8.5 },
      { id: 901, what: 'apple', x: 8.5, y: 64, z: 14.5 }
    ]
  })
  assert.ok(listed(answer).has('11,63,8'), 'floor three blocks out is in sight through its top face')
  assert.equal(answer.items.length, 2)
})

test('occluded: nothing behind a wall is offered, and what is offered is usable', async () => {
  const wall = box([10, 64, 5], [10, 66, 12]).map(([x, y, z]) => [x, y, z, 'stone'])
  const answer = await gate({
    feet: [8.5, 64, 8.5],
    build: (bot) => voxels(bot, { topLevel: 63, solid: groundWith(63), blocks: wall }),
    items: [
      { id: 900, what: 'wheat_seeds', x: 12.5, y: 64, z: 8.5 },
      { id: 901, what: 'apple', x: 7.5, y: 64, z: 8.5 }
    ]
  })
  assert.deepEqual(answer.items.map((item) => item.position), [[7.5, 64, 8.5]])
  assert.equal(listed(answer).has('11,63,8'), false, 'floor behind the wall is not offered')
})

test('r3 regression: seeds in a sealed cavity three blocks below the surface are not offered', async () => {
  // tribe-alpha's first r3 sense: feet at [10.5, 68, 3.5] and wheat seeds 5.68 blocks south,
  // three blocks down, which the body could only answer noRoute or searchOverBudget for.
  const cavity = box([10, 65, 8], [10, 65, 9])
  const answer = await gate({
    feet: [10.5, 68, 3.5],
    build: (bot) => voxels(bot, { topLevel: 67, solid: groundWith(67, cavity) }),
    items: [{ id: 900, what: 'wheat_seeds', x: 10.5, y: 65.0095, z: 9.18 }]
  })
  assert.deepEqual(answer.items, [])
  assert.equal([...listed(answer)].some((position) => Number(position.split(',')[1]) < 67), false)
})

test('r5 regression: the dirt under the grass is not offered and nearby grass is diggable', async () => {
  // tribe-alpha's r5 dig named dirt "just below the grass", which the body refused as unseen.
  const answer = await gate({
    feet: [10.5, 68, 3.5],
    build: (bot) => voxels(bot, { topLevel: 67, solid: groundWith(67) })
  })
  assert.equal(listed(answer).has('10,66,3'), false)
  assert.ok(listed(answer).has('13,67,3'))
  assert.ok(answer.blocks.every((block) => block.what === 'grass_block'))
})

test('vertical: a ledge, a pillar and a shallow pit offer only usable faces and items', async () => {
  const pit = box([12, 61, 7], [14, 63, 9])
  const ledge = box([5, 64, 6], [6, 65, 10]).map(([x, y, z]) => [x, y, z, 'stone'])
  const pillar = box([8, 64, 12], [8, 67, 12]).map(([x, y, z]) => [x, y, z, 'stone'])
  const answer = await gate({
    feet: [8.5, 64, 8.5],
    build: (bot) => voxels(bot, { topLevel: 63, solid: groundWith(63, pit), blocks: [...ledge, ...pillar] }),
    items: [{ id: 900, what: 'apple', x: 13.5, y: 61, z: 8.5 }]
  })
  assert.ok(listed(answer).has('8,66,12'), 'the side of a pillar above the eyes is in sight')
})

test('native-event burst: superseded senses, despawns and changed blocks are typed, never unseen', async () => {
  const feet = [8.5, 64, 8.5]
  const context = await sceneBody({
    feet,
    build: (bot) => voxels(bot, { topLevel: 63, solid: groundWith(63) }),
    items: [
      { id: 900, what: 'wheat_seeds', x: 10.5, y: 64, z: 8.5 },
      { id: 901, what: 'apple', x: 8.5, y: 64, z: 11.5 }
    ]
  })
  const { client, peer } = context
  // A burst of native events senses faster than any one of them is acted on.
  const answers = []
  for (let burst = 0; burst < MAX_REMEMBERED_SENSES + 2; burst += 1) {
    answers.push(await sensed(peer, `b${burst}`))
  }
  const oldest = answers[0]
  const newest = answers.at(-1)
  const forgotten = await promptly(peer.answer(request('dig', 'x0', {
    senseId: oldest.requestId, block: 0, timeoutMs: PROBE_MS
  })), 'the superseded dig')
  assert.equal(forgotten.outcome, 'senseForgotten')
  // Every handle the newest sense offered is still usable in the unchanged scene.
  assert.deepEqual(await probeEveryOffer(context, newest, feet), [])

  // Faults between the sense and the action: an item despawns and two blocks change.
  const seeds = newest.items.findIndex((item) => item.what === 'wheat_seeds')
  client.emit('entity_destroy', { entityIds: [900] })
  const target = newest.blocks.findIndex((block) => block.position.join(',') === '9,63,8')
  const other = newest.blocks.findIndex((block) => block.position.join(',') === '8,63,9')
  client.emit('block_change', { location: { x: 9, y: 63, z: 8 }, type: 0 })
  client.emit('block_change', { location: { x: 8, y: 63, z: 9 }, type: registry.blocksByName.stone.defaultState })
  await settled()
  const gone = await promptly(peer.answer(request('collect', 'x1', {
    senseId: newest.requestId, item: seeds, approach: true, timeoutMs: COLLECT_PROBE_MS, sprint: false
  })), 'the despawned collect')
  assert.equal(gone.outcome, 'itemGone')
  const removed = await promptly(peer.answer(request('dig', 'x2', {
    senseId: newest.requestId, block: target, timeoutMs: PROBE_MS
  })), 'the removed dig')
  assert.equal(removed.outcome, 'blockGone')
  const changed = await promptly(peer.answer(request('dig', 'x3', {
    senseId: newest.requestId, block: other, timeoutMs: PROBE_MS
  })), 'the changed dig')
  assert.equal(changed.outcome, 'blockChanged')
  // A fresh sense after the faults offers only what is now there, all of it usable.
  const after = await sensed(peer, 'b-after')
  assert.equal(after.items.some((item) => item.what === 'wheat_seeds'), false)
  assert.deepEqual(await probeEveryOffer(context, after, feet), [])
  peer.stop()
  await context.body.stop()
})
