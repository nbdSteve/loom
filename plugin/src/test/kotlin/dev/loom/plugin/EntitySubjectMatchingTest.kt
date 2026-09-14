package dev.loom.plugin

import dev.loom.bodycontrol.SensedEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class EntitySubjectMatchingTest {
    @Test
    fun `same-kind observations take the nearest distinct server subjects`() {
        val near = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val far = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val subjects =
            listOf(
                ServerEntitySubject(far, "minecraft:zombie", listOf(4.0, 64.0, 0.0)),
                ServerEntitySubject(near, "minecraft:zombie", listOf(1.0, 64.0, 0.0)),
            )
        val sensed =
            listOf(
                SensedEntity("zombie", listOf(1.2, 64.0, 0.0), true),
                SensedEntity("minecraft:zombie", listOf(3.8, 64.0, 0.0), true),
            )

        assertEquals(listOf(near, far), matchServerSubjects(sensed, subjects))
    }

    @Test
    fun `an uncorroborated observation stays request-local`() {
        val subject = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val sensed =
            listOf(
                SensedEntity("minecraft:zombie", listOf(0.0, 64.0, 0.0), true),
                SensedEntity("minecraft:zombie", listOf(0.0, 64.0, 0.0), true),
            )

        assertEquals(
            listOf(subject, null),
            matchServerSubjects(
                sensed,
                listOf(
                    ServerEntitySubject(
                        subject,
                        "minecraft:zombie",
                        listOf(0.5, 64.0, 0.0),
                    ),
                ),
            ),
        )
    }
}
