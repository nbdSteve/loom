'use strict'

/**
 * One meal, chosen by the mind and carried out by the client library.
 *
 * Nothing here decides *whether* to eat, *when* to eat or *what* to eat. There is no
 * threshold, no timer, no target selection and no auto-eat plugin: a meal happens
 * because a controller sent an eat request naming one slot of one sense answer, and a
 * body with no such request never eats at all. That is the whole point of the operation
 * being explicit, and it is why this module has no state beyond the bounded memory of
 * which slots a recent sense answer offered.
 *
 * **A slot handle names a position, not a stack, so the position is read again here.**
 * The contents of a slot change while a decision is in flight - the stack can be eaten,
 * moved, swapped or spent - so `slotEmpty` and `slotChanged` are re-read facts rather
 * than inferences from the sense answer that offered the slot. That is the difference
 * between this and an entity handle, whose subject keeps its identity wherever it goes.
 *
 * **Edibility is the game's own answer.** `bot.registry.foodsByName` is minecraft-data's
 * food table for the version the server is running, so a food added or repriced by a
 * version bump is answered correctly with no change here and there is no second food
 * table to fall behind. Nothing in this file names an item.
 *
 * **A full food bar is the library's own refusal, not a bound of this module's.**
 * `bot.consume` throws `Food is full` before it activates anything, under vanilla's own
 * rule - which is not simply `food === 20`, because a golden apple is always consumable
 * and is also a food. A pre-check here would have to restate that exception, which is
 * the food table this file refuses to keep, and would refuse a golden apple vanilla
 * accepts. So the throw is caught and mapped, and a behavioural test pins the message
 * against the real library, which the exact-version lockfile is what makes safe.
 *
 * **`remaining` is waited for rather than read the instant `consume` resolves, and it is
 * the body's latest observation rather than a promised delta.** See [SLOT_SETTLE_MS]: the
 * completion packet and the slot update are two packets of one tick and either may arrive
 * first, so the count is read only once the slot the meal came out of has been restated -
 * but the wait is bounded, and past the bound the count is read anyway.
 *
 * **A meal that happened is reported as `eaten`, whatever the count turned out to be.**
 * Nothing after `bot.consume` resolves may be answered `notReady`, because `notReady` means
 * *ask again* on this wire and there is nothing safe to ask again for once the food is gone:
 * a stack already in the hotbar is equipped by `bot.setQuickBarSlot` alone, so the slot still
 * reads the pre-meal item, `slotChanged` is not reached, and a controller that did as it was
 * told ate a second loaf for one decision. Creative mode reaches the same branch every time,
 * since vanilla consumes nothing there and so restates no slot. The rule is absolute rather
 * than usual, so the two calls past `consume` are guarded ([countLeft]) and the vitals an
 * answer needs are held from before it (`body.js`'s `eat`), leaving no path that could reach
 * for a refusal once the food is gone.
 *
 * **An eat is answered outside the frame that asked for it, so its `state` and its `vitals`
 * are the state and the reading the request was *admitted under*.** Every other answer on
 * this wire is written in the same turn it was asked in, and this one cannot be: bringing a
 * stack to hand and using it are round trips to the server, and the body can die, disconnect
 * or be given a nonsense food level while they are in flight. The frame states `ready`
 * because the body was `ready` when the meal was taken on, which is where the numbers in it
 * come from; a fresher reading is preferred over the admitted one only while the body is
 * still in that state and the three vitals can still be stated together, and the two are
 * never mixed. That is one rule about one field pair, not two coincidences: an answer
 * describes the body whose request was accepted, at the moment it was accepted.
 */

const { MAX_STACK_COUNT } = require('./protocol')

/**
 * How many recent sense answers a body remembers the slots of.
 *
 * A slot handle is scoped to the round trip it belongs to, and this is the bound on how
 * far that scope stretches: a controller may have a sense answer or two in flight while
 * it decides, and anything older than that has been superseded by a scene the body has
 * since reported. Small on purpose - a handle that outlived a run of sense answers would
 * be a name for a slot whose contents the body has already contradicted twice.
 */
const MAX_REMEMBERED_SENSES = 4

/**
 * The message `bot.consume` throws when vanilla will not take another ordinary food.
 *
 * Matched by text because the client library offers nothing else to match on. That is
 * only safe because the dependency is pinned to one exact version with a committed
 * lockfile `npm ci` enforces, and because a behavioural test drives the real library to
 * this message rather than asserting the constant against itself: a version bump that
 * reworded it fails that test instead of quietly turning every full stomach into a fault
 * to go and look at.
 */
const FULL_FOOD_MESSAGE = 'Food is full'

/**
 * How long a finished meal waits for the server's own account of the slot it ate from.
 *
 * `bot.consume` resolves on whichever of three packets the server sends first, and only
 * one of them is the slot update: vanilla completes the item use inside the entity tick
 * and broadcasts the container change at the end of that same tick, so the completion can
 * perfectly well arrive first and leave the library's window still holding the pre-meal
 * count. Read there, `remaining` said 3 for a stack of 3 and 1 rather than 0 for the last
 * loaf - on the one field a run counts consumption from, and on the one path whose whole
 * purpose is telling a mind what it has left.
 *
 * Five game ticks, which is generous for two packets of one tick and still an order of
 * magnitude inside the library's own 2.5 s consume timeout and two orders inside the
 * control channel's idle bound, so a meal can never be what closes a channel.
 *
 * Past it the count is read as it stands and the meal is still reported `eaten`, which is
 * what makes `remaining` a *latest observation* rather than a promised consumption delta.
 * The alternative was tried and is worse than the imprecision: a refusal there described a
 * meal that had already happened, and the only refusal available for it - `notReady` - is
 * the one this wire uses to mean *ask again*, so a controller that obeyed it ate twice for
 * one decision. Whatever this branch reports, exactly one item was consumed, because
 * `bot.consume` is what consumed it.
 */
const SLOT_SETTLE_MS = 250

/**
 * The bounded memory of which slots recent sense answers offered.
 *
 * **An entry is named by the connection as well as by the sense id, because a round trip
 * belongs to both.** Keyed on the id alone the scoping was not in fact structural: the
 * duplicate-id guard it rested on is per control connection, so two of the connections
 * this process allows may each sense under `s1`, and the second answer silently replaced
 * the first. An eat from the first connection then resolved against the second's snapshot
 * and ate a stack the sense it named never offered - reported as `eaten` rather than as
 * the `slotChanged` the slot in fact was. Two connections presenting one id now name two
 * entries, and an id a connection never held is `senseForgotten` to it, which is the
 * outcome a superseded handle already earns: nothing about the wire changes for it.
 *
 * Only what a slot handle needs is kept - per occupied slot, the item that stood in it -
 * because that is the whole of what resolving one costs. The bound is on the whole memory
 * rather than per connection, so no number of peers can make it grow, and the oldest
 * entry goes when it is reached: an eat naming a superseded answer is `senseForgotten`
 * rather than a lookup in something unbounded.
 *
 * A list, oldest first, rather than a keyed map: an entry is named by two things at once,
 * and `MAX_REMEMBERED_SENSES` is what makes walking it to find one free. The order is the
 * list's own rather than a `Map`'s insertion order, so the eviction depends on nothing
 * about whether a key can be written twice.
 */
function senseMemory () {
  let remembered = []
  return {
    remember (connection, senseId, inventory) {
      remembered.push({
        connection,
        senseId,
        slots: new Map(inventory.map((stack) => [stack.slot, stack.what]))
      })
      if (remembered.length > MAX_REMEMBERED_SENSES) remembered.shift()
    },
    recall (connection, senseId) {
      const held = remembered.find((entry) => entry.connection === connection && entry.senseId === senseId)
      return held === undefined ? undefined : held.slots
    },
    /**
     * Drops one slot's offer, once a meal has been eaten against it.
     *
     * A handle names one position of one snapshot, and a meal is what makes that snapshot
     * wrong about it: the count moved, perhaps to nothing. The re-read at apply time cannot
     * see that, because it compares the item's *name* and a half-eaten stack still has the
     * same name - so an eat re-sent against a spent handle ate a second time from a snapshot
     * that had already been contradicted, which is the failure connection-scoping exists to
     * prevent arriving through the count instead of through the identity. Spent, the handle
     * is `slotUnnamed`, which is what that answer's offer of the slot now is.
     *
     * **That gives `slotUnnamed` a second reading, deliberately.** It has always meant "a
     * handle the mind invented rather than named back"; it now also means "a handle this body
     * issued and has already served a meal against". Both are facts about the *handle*, which
     * is the one thing the first check knows about - and it stays the first check, because an
     * unresolvable handle names no slot to look in. Spending after the slot re-read instead
     * would buy a slot-true label (`slotEmpty`, say, once `equip` had moved the stack out)
     * at the cost of the check order this module's own header states is load-bearing.
     *
     * A hotbar stack is the case that needs it: `bot.equip` moves nothing when the stack is
     * already in the held slot, so the named slot still reads the whole pre-meal stack even
     * after the meal, and nothing else in the check order refuses the repeat.
     *
     * Only the slot, never the whole answer: the other slots of that sense are still the
     * body's own account of positions no meal has touched.
     */
    spend (connection, senseId, slot) {
      const held = remembered.find((entry) => entry.connection === connection && entry.senseId === senseId)
      if (held !== undefined) held.slots.delete(slot)
    },
    /**
     * Drops what `connection` was offered, once that connection has gone.
     *
     * Nothing can resolve one of those handles again, since a handle names the connection
     * it was minted on, so keeping them would spend the bound on answers no controller
     * can still eat against.
     */
    forget (connection) {
      remembered = remembered.filter((entry) => entry.connection !== connection)
    }
  }
}

/** Whether `cause` is the client library declining an ordinary food on a full bar. */
function reserveWasFull (cause) {
  return cause !== null && cause !== undefined && cause.message === FULL_FOOD_MESSAGE
}

/**
 * How many of `what` the body still holds, as a count the wire will carry.
 *
 * Counted where the stack ended up rather than in the slot the request named, because
 * bringing a stack to hand *moves* it: the library relocates it to a hotbar slot unless
 * it was already in one, so the named slot holds whatever the move left behind - which
 * is usually nothing. An empty hand, or a hand holding something else, is zero: the last
 * one was eaten.
 *
 * **Total, because it is read only after `consume` resolved.** A reading the wire will not
 * carry is bounded into the field rather than lost: losing it left `eatFromSlot` with no
 * answer for a meal that had already happened, which reached the wire as the `notReady`
 * this module forbids after a meal and re-invited the second loaf. `remaining` is a latest
 * *observation* and this is the last step of taking one - see [SLOT_SETTLE_MS].
 *
 * The two bounds are not the same statement. A count above [MAX_STACK_COUNT] is real - a
 * server can over-stack the held slot with a varint the sense answer would have refused -
 * so it is capped, which is true as far as the field can say it. A count that is not a
 * number at all is the library contradicting itself, and reads as nothing left: an agent
 * told it has less food than it has goes looking for more, where one told it has more than
 * it has stops looking, and only one of those directions is safe for a survival run.
 */
function stillHeld (bot, what) {
  const held = bot.heldItem
  if (held === null || held === undefined || held.name !== what) return 0
  const count = held.count
  if (!Number.isFinite(count)) return 0
  return Math.min(Math.max(Math.trunc(count), 0), MAX_STACK_COUNT)
}

/** Which window slot the body is holding, or undefined when the library cannot say. */
function heldSlot (bot) {
  const start = bot.QUICK_BAR_START
  const selected = bot.quickBarSlot
  if (!Number.isInteger(start) || !Number.isInteger(selected)) return undefined
  return start + selected
}

/** Whether the hand still holds exactly the stack `before` describes. */
function stillStanding (bot, before) {
  const held = bot.heldItem
  return held !== null && held !== undefined &&
    held.name === before.name && held.count === before.count
}

/**
 * Waits, at most [SLOT_SETTLE_MS], for the server's own account of the slot a meal came
 * out of.
 *
 * Returns at once when the count has already moved, which is the ordinary case: the slot
 * update is itself one of the three packets `bot.consume` finishes on, so a server that
 * sent it first has already been read and nothing is waited for. Otherwise the slot's own
 * update is listened for until the bound runs out, and every update is re-checked against
 * the stack in hand rather than trusted - a window re-sync that restates the same count is
 * not the meal being accounted for.
 *
 * It answers nothing, deliberately. Whether the wait ended in an update or at the deadline
 * changes no answer - the count is read afterwards either way - so a verdict here would be
 * a verdict nothing may act on, and acting on one is what once turned a finished meal into
 * a refusal.
 *
 * The timer is deliberately not unref'd: an eat is a request that must be answered, and a
 * bounded wait a stopping process may sit out for a quarter of a second is cheaper than a
 * controller left holding a request id.
 */
function slotSettles (bot, before) {
  if (!stillStanding(bot, before)) return Promise.resolve()
  const window = bot.inventory
  const slot = heldSlot(bot)
  if (slot === undefined || window === null || window === undefined) {
    return Promise.resolve()
  }
  const restated = `updateSlot:${slot}`
  return new Promise((resolve) => {
    const timer = setTimeout(finish, SLOT_SETTLE_MS)
    function finish () {
      clearTimeout(timer)
      window.off(restated, updated)
      resolve()
    }
    function updated () {
      if (!stillStanding(bot, before)) finish()
    }
    window.on(restated, updated)
  })
}

/**
 * What the meal left behind: the bounded wait, and then the reading.
 *
 * Nothing past a resolved `bot.consume` may throw out of [eatFromSlot], because a throw
 * there reaches `settleMeal`'s own catch and is answered `notReady` - a refusal for a meal
 * that happened, and the one answer this module forbids after one. Both calls are the client
 * library's own and neither is this module's to vouch for, so the pair is guarded rather than
 * trusted, and a throw reads as nothing left for the reason [stillHeld] gives about a count
 * it cannot make sense of: under-reporting food sends an agent looking for more, and
 * over-reporting it stops one looking.
 */
async function countLeft (bot, brought, what) {
  try {
    await slotSettles(bot, brought)
    return stillHeld(bot, what)
  } catch {
    return 0
  }
}

/**
 * Eats the stack one eat request named, and answers what became of it.
 *
 * The order of the checks is load-bearing rather than tidy. The handle first, because an
 * unresolvable one names no slot to look in. Then what the sense answer said stood there,
 * because a slot it never offered is a handle the mind invented rather than named back.
 * Then the slot itself, twice - empty, then holding something else - so a stack eaten or
 * moved in between reads as the fact it is. Then edibility, so an agent that reached for
 * a pickaxe finds out that is what it did. Every one of those is settled before anything
 * is brought to hand, which is what keeps a refusal from costing an agent its food.
 *
 * What follows is the library's, and exactly two calls of it: `equip` to bring the stack
 * to hand and `consume` to use it. Neither is retried. `consume` is what consumes exactly
 * one item, by the game's own rule for using an item rather than by arithmetic here, and
 * it is also what refuses a full food bar. Anything else that goes wrong across those two
 * calls is the residual `slotInaccessible`, which is the one outcome that cannot promise
 * the food is still there.
 *
 * Answers undefined when the body cannot state an answer at all, and `body.js` refuses
 * that as `notReady`. That is the same rule `sensing.js` obeys - a body states what it
 * read or says it cannot - and it is why a missing inventory window or a missing food
 * table is not an outcome: both would otherwise read as facts about the agent, an empty
 * slot and an inedible stack, when what happened is that the library had nothing to
 * answer from. **Every one of those is settled *before* the meal**, which is what makes
 * `notReady` honest there: nothing has been consumed, so asking again costs nothing.
 *
 * So `eaten` is exactly "`consume` resolved", with no path between them: past that call
 * every branch answers `eaten`, and the only thing the bounded wait and [stillHeld]
 * change is how fresh and how bounded the count in it is. That equivalence is what
 * `body.js` spends the slot handle on, so a path added here that answered anything else
 * after a resolved `consume` would hand a spent meal's handle back and invite a second one.
 *
 * A `slotUnnamed` therefore has two readings, and the first check cannot tell them apart:
 * a handle the mind invented, and a handle this body issued and has already served a meal
 * against. Both are answers about the *handle* rather than about the slot, which is why
 * they share an outcome - see `senseMemory.spend`.
 */
async function eatFromSlot (bot, { offered, slot }) {
  if (offered === undefined) return { outcome: 'senseForgotten' }
  const named = offered.get(slot)
  if (named === undefined) return { outcome: 'slotUnnamed' }
  const slots = bot.inventory?.slots
  if (!Array.isArray(slots)) return undefined
  const standing = slots[slot]
  if (standing === null || standing === undefined) return { outcome: 'slotEmpty' }
  if (standing.name !== named) return { outcome: 'slotChanged' }
  const foods = bot.registry?.foodsByName
  if (foods === null || foods === undefined) return undefined
  if (foods[named] === undefined) return { outcome: 'notEdible' }
  let brought
  try {
    await bot.equip(standing, 'hand')
    brought = bot.heldItem
    if (brought === null || brought === undefined || brought.name !== named) {
      return { outcome: 'slotInaccessible' }
    }
    await bot.consume()
  } catch (cause) {
    return { outcome: reserveWasFull(cause) ? 'reserveFull' : 'slotInaccessible' }
  }
  return { outcome: 'eaten', what: named, remaining: await countLeft(bot, brought, named) }
}

module.exports = {
  FULL_FOOD_MESSAGE,
  MAX_REMEMBERED_SENSES,
  SLOT_SETTLE_MS,
  eatFromSlot,
  senseMemory
}
