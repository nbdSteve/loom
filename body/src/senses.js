'use strict'

/**
 * The bounded memory of what recent sense answers offered.
 *
 * A sense answer hands a controller two kinds of handle at once - a slot of the body's
 * inventory window, and an index into the scene it reported - and this is the whole of
 * what makes either mean something later. Nothing here decides anything: it remembers
 * what was offered, answers what a handle names, and forgets.
 *
 * **Both kinds are remembered together, which is the invariant rather than tidiness.**
 * One answer carries slot handles and entity handles, so a memory that kept one and not
 * the other would resolve a flight and refuse a meal off the same answer - or the reverse.
 * They are therefore one entry with one lifetime and one eviction, and [senseMemory] is
 * the only place either is written.
 *
 * **An entry is named by the connection as well as by the sense id, because a round trip
 * belongs to both.** Keyed on the id alone the scoping was not in fact structural: the
 * duplicate-id guard it rests on is per control connection, so two of the connections
 * this process allows may each sense under `s1`, and the second answer silently replaced
 * the first. An eat from the first connection then resolved against the second's snapshot
 * and ate a stack the sense it named never offered - reported as `eaten` rather than as
 * the `slotChanged` the slot in fact was, and a flee would have run from a creature the
 * sense it named never showed. Two connections presenting one id now name two entries,
 * and an id a connection never held is forgotten to it, which is the outcome a superseded
 * handle already earns: nothing about the wire changes for it.
 *
 * **A slot handle is spent by the meal it buys and an entity handle is not**, because the
 * two name different kinds of thing. A slot is a *position* whose contents a meal changes,
 * so serving one meal against it makes the answer wrong about it - see [spend]. A creature
 * keeps its identity wherever it goes and however often it is run from, so a second flight
 * from one sighting is a legitimate request rather than a repeat of a spent one.
 */

/**
 * How many recent sense answers a body remembers what it offered of.
 *
 * A handle is scoped to the round trip it belongs to, and this is the bound on how far
 * that scope stretches: a controller may have a sense answer or two in flight while it
 * decides, and anything older than that has been superseded by a scene the body has since
 * reported. Small on purpose - a handle that outlived a run of sense answers would be a
 * name for a slot whose contents the body has already contradicted twice, or for a place
 * in a list of a scene that has moved.
 */
const MAX_REMEMBERED_SENSES = 4

/**
 * The bounded memory itself.
 *
 * Only what resolving a handle costs is kept: per occupied slot, the item that stood in
 * it, and per reported entity, the client library's id and exact object identity. The id
 * alone is not an identity because Mineflayer may reuse it after an entity leaves. The
 * bound is on the whole memory rather than per connection, so no number of peers can make
 * it grow, and the oldest entry goes when it is reached: a request naming a superseded
 * answer is refused rather than answered from a lookup in something unbounded.
 *
 * A list, oldest first, rather than a keyed map: an entry is named by two things at once,
 * and [MAX_REMEMBERED_SENSES] is what makes walking it to find one free. The order is the
 * list's own rather than a `Map`'s insertion order, so the eviction depends on nothing
 * about whether a key can be written twice.
 */
function senseMemory () {
  let remembered = []
  function held (connection, senseId) {
    return remembered.find((entry) => entry.connection === connection && entry.senseId === senseId)
  }
  return {
    remember (connection, senseId, { inventory, entityOffers }) {
      remembered.push({
        connection,
        senseId,
        slots: new Map(inventory.map((stack) => [stack.slot, stack.what])),
        // Copied rather than held, because the caller's array is built per answer and this
        // memory outlives the frame it came from. Each offer keeps the exact object the sense
        // described, because an id reused later must not become the same handle.
        entityOffers: entityOffers.map(({ id, entity }) => ({ id, entity, dead: false }))
      })
      if (remembered.length > MAX_REMEMBERED_SENSES) remembered.shift()
    },
    /**
     * What one sense answer still offers, or undefined for an answer this connection was
     * never given or that has since been evicted.
     *
     * Both kinds come back together for the reason the module header gives, and a caller
     * reads the one its own operation is about.
     */
    recall (connection, senseId) {
      const entry = held(connection, senseId)
      if (entry === undefined) return undefined
      return { slots: entry.slots, entityOffers: entry.entityOffers }
    },
    /**
     * Marks every remembered handle for this exact entity as dead.
     *
     * Mineflayer emits `entityDead` before it necessarily removes the object from
     * `bot.entities`, and leaves `isValid` unchanged in that interval. Remembering the event
     * beside the exact object closes the gap between a sense and a later flee: an id still in
     * the table, or already reused by another object, cannot make a dead creature live again.
     */
    sawEntityDead (entity) {
      for (const entry of remembered) {
        for (const offered of entry.entityOffers) {
          if (offered.entity === entity) offered.dead = true
        }
      }
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
     * at the cost of the check order `eating.js`'s own header states is load-bearing.
     *
     * A hotbar stack is the case that needs it: `bot.equip` moves nothing when the stack is
     * already in the held slot, so the named slot still reads the whole pre-meal stack even
     * after the meal, and nothing else in the check order refuses the repeat.
     *
     * Only the slot, never the whole answer: the other slots of that sense are still the
     * body's own account of positions no meal has touched, and its entity handles are
     * untouched by a meal in any case.
     */
    spend (connection, senseId, slot) {
      const entry = held(connection, senseId)
      if (entry !== undefined) entry.slots.delete(slot)
    },
    /**
     * Drops what `connection` was offered, once that connection has gone.
     *
     * Nothing can resolve one of those handles again, since a handle names the connection
     * it was minted on, so keeping them would spend the bound on answers no controller
     * can still act against.
     */
    forget (connection) {
      remembered = remembered.filter((entry) => entry.connection !== connection)
    }
  }
}

module.exports = {
  MAX_REMEMBERED_SENSES,
  senseMemory
}
