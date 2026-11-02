package dev.loom.body.player

import dev.loom.body.ThreatApproach
import dev.loom.body.blocksApart

/** Records how far each creature [scene] offers stands, so a later flight can tell one closing in. */
internal fun ThreatApproach.recordScene(scene: PlayerScene) =
    scene.entityIndex.forEach { (subject, index) ->
        val seen = scene.raw.report.entities[index].position
        record(subject, scene.raw.capturedTick, blocksApart(scene.raw.report.position, seen))
    }
