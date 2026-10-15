'use strict'

/**
 * One-shot player speech, end to end from a control frame to a real chat packet.
 *
 * The bot is real Mineflayer over the fake protocol client, with minecraft-protocol's own
 * chat module installed on that client, so `sent` is proven by the `chat_message` packet it
 * writes rather than by a stub having been called.
 */

const assert = require('node:assert/strict')
const { afterEach, test } = require('node:test')

const installClientChat = require('minecraft-protocol/src/client/chat')

const {
  disconnected,
  embodied,
  handshaken,
  request,
  settled,
  spawned
} = require('./harness')
const { MAX_SAY_BYTES, MAX_SAY_CHARS, isSayText } = require('../src/protocol')
const { SAY_WINDOW_LIMIT, SAY_WINDOW_MS, createSpeech } = require('../src/speaking')

afterEach(disconnected)

/** A spawned body whose client really writes chat packets, on a clock the test owns. */
async function speakingBody () {
  const clock = { ms: 1_000 }
  const started = await embodied({ now: () => clock.ms })
  installClientChat(started.client, {})
  started.client.chat = started.client._signedChat
  spawned(started.client)
  await settled()
  const peer = await handshaken(started.port)
  const chats = () => started.client.writes.filter(({ name }) => name.startsWith('chat'))
  return { ...started, clock, peer, chats }
}

test('one accepted say writes exactly one chat packet carrying the exact text', async () => {
  const { body, peer, chats } = await speakingBody()
  const text = 'anyone near the river? I have spare bread'
  const answer = await peer.answer(request('say', 's1', { text }))
  assert.deepEqual(answer, {
    op: 'say', protocol: answer.protocol, requestId: 's1', agentId: answer.agentId, state: 'ready', outcome: 'sent'
  })
  assert.equal(chats().length, 1)
  assert.equal(chats()[0].name, 'chat_message')
  assert.equal(chats()[0].parameters.message, text)
  await body.stop()
})

test('the rate window refuses past its limit, sends nothing, and reopens on the clock', async () => {
  const { body, peer, chats, clock } = await speakingBody()
  for (let i = 0; i < SAY_WINDOW_LIMIT; i++) {
    assert.equal((await peer.answer(request('say', `ok${i}`, { text: `line ${i}` }))).outcome, 'sent')
  }
  assert.equal((await peer.answer(request('say', 'over', { text: 'one more' }))).outcome, 'rateLimited')
  assert.equal(chats().length, SAY_WINDOW_LIMIT)
  clock.ms += SAY_WINDOW_MS - 1
  assert.equal((await peer.answer(request('say', 'still', { text: 'not yet' }))).outcome, 'rateLimited')
  clock.ms += 1
  assert.equal((await peer.answer(request('say', 'again', { text: 'now' }))).outcome, 'sent')
  assert.equal(chats().length, SAY_WINDOW_LIMIT + 1)
  await body.stop()
})

test('every unsafe line is refused as invalidField before any chat is written', async () => {
  const { body, port, chats } = await speakingBody()
  const unsafe = [
    '', ' ', '   ', 'a'.repeat(MAX_SAY_CHARS + 1), '\u{1F600}'.repeat(65), 'hi\u0007there',
    'hi\nthere', 'hi\tthere', '\u00a7chi', '\u001b[31mred', 'abc\u202edef', 'abc\u2066def',
    'ab\u200bcd', '\ufeffhi', ' hi', 'hi ', 'hi  there', 'hi\u00a0there', '/kill @a',
    'a\u2028b', 'hi\ud800there', 'x\u0085y'
  ]
  // A fresh peer per line, so the per-connection refusal budget is not what is measured.
  let n = 0
  for (const text of [...unsafe, 7]) {
    const peer = await handshaken(port)
    const answer = await peer.answer(request('say', `bad${n++}`, { text }))
    assert.equal(answer.error, 'invalidField', JSON.stringify(text))
    peer.stop()
  }
  assert.equal(chats().length, 0)
  await body.stop()
})

test('the multi-byte boundary is the byte bound, and the character bound is code points', () => {
  assert.equal(isSayText('\u{1F600}'.repeat(MAX_SAY_BYTES / 4)), true)
  assert.equal(isSayText('\u{1F600}'.repeat(MAX_SAY_BYTES / 4 + 1)), false)
  assert.equal(isSayText('a'.repeat(MAX_SAY_CHARS)), true)
  assert.equal(isSayText('a'.repeat(MAX_SAY_CHARS + 1)), false)
  assert.equal(isSayText('héllo wörld 你好'), true)
})

test('a duplicate request id is refused and sends nothing more', async () => {
  const { body, peer, chats } = await speakingBody()
  assert.equal((await peer.answer(request('say', 'dup', { text: 'first' }))).outcome, 'sent')
  assert.equal((await peer.answer(request('say', 'dup', { text: 'second' }))).error, 'duplicateRequestId')
  assert.equal(chats().length, 1)
  await body.stop()
})

test('a dead body refuses speech as notReady and writes no chat', async () => {
  const { body, peer, client, chats } = await speakingBody()
  client.emit('update_health', { health: 0, food: 20, foodSaturation: 5 })
  await settled()
  assert.equal(body.state(), 'dead')
  assert.equal((await peer.answer(request('say', 'dead', { text: 'hello' }))).error, 'notReady')
  assert.equal(chats().length, 0)
  await body.stop()
})

test('a disconnected body refuses speech as notReady and writes no chat', async () => {
  const { body, peer, client, chats } = await speakingBody()
  client.emit('end', 'gone')
  await settled()
  assert.equal(body.state(), 'disconnected')
  assert.equal((await peer.answer(request('say', 'gone', { text: 'hello' }))).error, 'notReady')
  assert.equal(chats().length, 0)
  await body.stop()
})

test('heard chat starts nothing: no reply, no packet, no frame', async () => {
  const { body, peer, client } = await speakingBody()
  const before = client.writes.length
  client.emit('system_chat', { content: JSON.stringify({ text: '<other> hello loom_test' }), isActionBar: false })
  client.emit('systemChat', { formattedMessage: JSON.stringify({ text: '<other> hello loom_test' }), positionId: 1 })
  await settled(100)
  assert.equal(client.writes.length, before)
  // The control channel is still answering only what it is asked.
  assert.equal((await peer.answer(request('status', 'after'))).op, 'status')
  await body.stop()
})

test('the speech owner refuses unadmitted text without touching the client', () => {
  let calls = 0
  const bot = { _client: { chat: () => { calls += 1 } } }
  const speech = createSpeech({ now: () => 0 })
  assert.throws(() => speech.say(bot, '/op me'))
  assert.throws(() => speech.say(bot, ''))
  assert.equal(calls, 0)
  assert.equal(speech.say(bot, 'hi'), 'sent')
  assert.equal(calls, 1)
})
