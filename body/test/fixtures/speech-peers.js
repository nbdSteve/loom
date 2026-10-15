'use strict'

/**
 * Real body processes for the JVM's offline speech acceptance test. Not a suite.
 *
 * Starts one real body per `agentId=username` argument, each driving real Mineflayer over
 * the fake protocol client with minecraft-protocol's own chat module, on its own loopback
 * control port. Prints one `{"ready":[...]}` line, then one `{"chat":...}` line per
 * `chat_message` packet a client writes - the fake server's view of the wire. Stops every
 * body when stdin closes.
 */

const installClientChat = require('minecraft-protocol/src/client/chat')

const { clockReported, embodied, ground, positionPacket, settled, spawned } = require('../harness')

function emit (line) {
  process.stdout.write(`${JSON.stringify(line)}\n`)
}

async function main () {
  const peers = process.argv.slice(2).map((pair) => {
    const [agentId, username] = pair.split('=')
    return { agentId, username }
  })
  const started = []
  for (const { agentId, username } of peers) {
    const peer = await embodied({ agentId, username })
    installClientChat(peer.client, {})
    peer.client.chat = peer.client._signedChat
    const write = peer.client.write
    peer.client.write = (name, parameters) => {
      if (name === 'chat_message') emit({ chat: { agentId, username, text: parameters.message } })
      return write(name, parameters)
    }
    // A sensable body: spawned, clocked, placed on ground, all agents side by side.
    spawned(peer.client)
    clockReported(peer.client, { timeOfDay: 6000, day: 1 })
    peer.client.emit('position', positionPacket({ x: 0.5 + started.length, y: 64, z: 0.5, yaw: 0, pitch: 0, teleportId: 1 }))
    ground(peer.bot, { level: 63 })
    started.push({ agentId, username, port: peer.port, body: peer.body })
  }
  await settled()
  emit({ ready: started.map(({ agentId, username, port }) => ({ agentId, username, port })) })
  process.stdin.resume()
  process.stdin.on('end', async () => {
    for (const { body } of started) await body.stop()
    process.exit(0)
  })
}

main().catch((failure) => {
  process.stderr.write(`${failure.stack}\n`)
  process.exit(1)
})
