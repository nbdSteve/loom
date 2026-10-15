'use strict'

/**
 * One-shot player speech: one validated line, one chat packet, no follow-on.
 *
 * Mineflayer's `chat` plugin is deliberately not loaded. It brings chat-pattern listeners,
 * `whisper` (a command path) and `tabComplete`, none of which an operation names. For one
 * line that `isSayText` already admitted - no newline, no leading '/', at most 128 code
 * points and so inside vanilla's 256-unit limit - `bot.chat` reduces to exactly one
 * `bot._client.chat(text)`, which is minecraft-protocol's own chat primitive, so that is
 * what is called. Nothing here listens for chat, so no heard line can cause a reply.
 *
 * The rate window is per process, and one process drives one body, so it is per agent. It
 * is a deterministic sliding window over the injected clock: an accepted line is stamped
 * before the send, so a send that throws still spends its place and cannot be retried
 * around the bound.
 */

const { isSayText } = require('./protocol')

/** The most lines one body may send inside one window. */
const SAY_WINDOW_LIMIT = 2

/** The window those lines are counted over. */
const SAY_WINDOW_MS = 10_000

/** A speech owner for one body, on `now` (milliseconds). */
function createSpeech ({ now }) {
  const stamps = []
  return {
    /**
     * Sends `text` once, or answers `rateLimited` and sends nothing.
     *
     * Throws without sending for text the codec would have refused, so a caller that
     * skipped the decode cannot reach the wire with it.
     */
    say (bot, text) {
      if (!isSayText(text)) throw new Error('say text was not admitted')
      const at = now()
      while (stamps.length > 0 && at - stamps[0] >= SAY_WINDOW_MS) stamps.shift()
      if (stamps.length >= SAY_WINDOW_LIMIT) return 'rateLimited'
      stamps.push(at)
      bot._client.chat(text)
      return 'sent'
    }
  }
}

module.exports = { SAY_WINDOW_LIMIT, SAY_WINDOW_MS, createSpeech }
