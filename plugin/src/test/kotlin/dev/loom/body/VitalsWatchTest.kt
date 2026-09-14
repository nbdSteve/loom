package dev.loom.body

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val FULL_FOOD = AgentVitals(energy = 20.0, capacity = 20.0)
private val UNHURT = AgentVitals(energy = 20.0, capacity = 20.0)

class VitalsWatchTest {
    private val agent = AgentId("agent-ada")
    private val records = mutableListOf<BodyEventRecord>()
    private var tick = 0L
    private val watch =
        VitalsWatch(BodyEventJournal(BodyEventLog { records.add(it) }, currentTick = { tick }))

    @Test
    fun `food and health changes keep their own event names and carry both readings`() {
        watch.noted(agent, FULL_FOOD, UNHURT)
        val food = AgentVitals(energy = 18.0, capacity = 20.0)
        val health = AgentVitals(energy = 16.0, capacity = 20.0)

        watch.recordChanges(agent, food, health)

        assertEquals(listOf(BodyEvent.ENERGY_CHANGED, BodyEvent.HEALTH_CHANGED), records.map { it.event })
        records.forEach { record ->
            assertEquals(food, record.vitals)
            assertEquals(health, record.health)
        }
        assertEquals("food 18 of 20", records.first().detail)
        assertEquals("health 16 of 20", records.last().detail)
    }

    @Test
    fun `a health change does not wait for food to cross its step`() {
        watch.noted(agent, FULL_FOOD, UNHURT)
        tick = 1L

        watch.recordChanges(
            agent,
            AgentVitals(energy = 19.5, capacity = 20.0),
            AgentVitals(energy = 19.0, capacity = 20.0),
        )

        assertEquals(1, records.size)
        assertEquals(BodyEvent.HEALTH_CHANGED, records.single().event)
        assertEquals(1L, records.single().tick)
    }

    @Test
    fun `noting food alone leaves the health watch unchanged`() {
        watch.noted(agent, FULL_FOOD, UNHURT)
        val food = AgentVitals(energy = 19.0, capacity = 20.0)
        watch.noted(agent, food)

        watch.recordChanges(agent, food, AgentVitals(energy = 12.0, capacity = 20.0))

        assertEquals(listOf(BodyEvent.HEALTH_CHANGED), records.map { it.event })
        assertEquals(food, records.single().vitals)
    }

    @Test
    fun `forgetting drops both readings`() {
        watch.noted(agent, FULL_FOOD, UNHURT)

        watch.forget(agent)
        watch.recordChanges(agent, FULL_FOOD, UNHURT)

        assertEquals(listOf(BodyEvent.ENERGY_CHANGED, BodyEvent.HEALTH_CHANGED), records.map { it.event })
    }
}
