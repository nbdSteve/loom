'use strict'

/**
 * One craft application judged only once the server's result has reached the inventory.
 *
 * r19 theta body:00000310 and epsilon 352 crafted two applications of planks on a real Paper
 * server: Mineflayer's craft resolved before the server's restated slots arrived, so the
 * second application saw a log consumed and no planks gained, and ended libraryFailure.
 * `settledApplication` reads the inventory again until it shows the recipe's delta, within a
 * bound, and the offline window responder in `native-craft-terminal.test.js` covers the whole
 * craft; this file pins the wait against a bot whose inventory reconciles late.
 */

const assert = require('node:assert/strict')
const { test } = require('node:test')

const H = require('./harness')
const { inventoryState, settledApplication } = require('../src/crafting')

const LOG = H.registry.itemsByName.oak_log.id
const PLANKS = H.registry.itemsByName.oak_planks.id

function bot (stacks) {
  const slots = new Array(46).fill(null)
  for (const [slot, id, count] of stacks) slots[slot] = new H.Item(id, count, null)
  return { inventory: { slots } }
}

const planksFromLog = new Map([
  [LOG, { count: -1, metadata: new Set([null]) }],
  [PLANKS, { count: 4, metadata: new Set([null]) }]
])

test('a result that trails the resolved craft is waited for, then matched', async () => {
  const body = bot([[20, LOG, 2]])
  const before = inventoryState(body)
  // The log is consumed at once; the planks arrive 60 ms later, as the server restates them.
  body.inventory.slots[20] = new H.Item(LOG, 1, null)
  setTimeout(() => { body.inventory.slots[10] = new H.Item(PLANKS, 4, null) }, 60)
  const after = await settledApplication(body, before, planksFromLog, { pollMs: 10 })
  assert.ok(after !== undefined, 'the late result is matched')
  assert.equal(body.inventory.slots[10].count, 4)
})

test('a delta never matched within the bound is a failure, and a stopped wait ends at once', async () => {
  const body = bot([[20, LOG, 2]])
  const before = inventoryState(body)
  body.inventory.slots[20] = new H.Item(LOG, 1, null)
  const started = Date.now()
  assert.equal(await settledApplication(body, before, planksFromLog, { settleMs: 80, pollMs: 10 }), undefined)
  assert.ok(Date.now() - started >= 70, 'it waited out its bound')
  const quick = Date.now()
  assert.equal(await settledApplication(body, before, planksFromLog, { stopped: () => true }), undefined)
  assert.ok(Date.now() - quick < 50, 'a stopped request does not wait')
})
