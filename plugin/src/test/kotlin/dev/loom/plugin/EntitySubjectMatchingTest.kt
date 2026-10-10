package dev.loom.plugin

import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedItem
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

    @Test
    fun `dropped items match exact stack and nearest distinct entity`() {
        val near = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val far = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val wrongCount = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val sensed =
            listOf(
                SensedItem("apple", 2, listOf(1.1, 64.0, 0.0), true),
                SensedItem("minecraft:apple", 2, listOf(3.9, 64.0, 0.0), true),
            )
        val candidates =
            listOf(
                ServerItemSubject(far, "minecraft:apple", 2, listOf(4.0, 64.0, 0.0)),
                ServerItemSubject(wrongCount, "minecraft:apple", 1, listOf(1.0, 64.0, 0.0)),
                ServerItemSubject(near, "minecraft:apple", 2, listOf(1.0, 64.0, 0.0)),
            )

        assertEquals(listOf(near, far), matchServerItems(sensed, candidates))
    }

    @Test
    fun `one server item cannot authorize two observations`() {
        val subject = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val sensed =
            listOf(
                SensedItem("minecraft:dirt", 1, listOf(0.0, 64.0, 0.0), true),
                SensedItem("minecraft:dirt", 1, listOf(0.1, 64.0, 0.0), true),
            )

        assertEquals(
            listOf(subject, null),
            matchServerItems(
                sensed,
                listOf(
                    ServerItemSubject(
                        subject,
                        "minecraft:dirt",
                        1,
                        listOf(0.0, 64.0, 0.0),
                    ),
                ),
            ),
        )
    }
}
