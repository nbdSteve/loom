package dev.loom.body

import dev.loom.contract.LoomJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PerceptionAuditTest {
    @Test
    fun `empty per-kind lists remain complete evidence`() {
        val audit = auditOf(emptyKind(), emptyKind(), emptyKind())

        assertEquals(audit, LoomJson.decodeFromString(LoomJson.encodeToString(audit)))
    }

    @Test
    fun `a full selected scene keeps all three category counts separate`() {
        val audit =
            auditOf(
                kind(raw = 48, selected = 48),
                kind(raw = 48, selected = 48),
                kind(raw = 48, selected = 48),
            )

        assertEquals(144, selectedTotal(audit))
        assertEquals(audit, LoomJson.decodeFromString(LoomJson.encodeToString(audit)))
    }

    @Test
    fun `body omissions and JVM omissions are not collapsed together`() {
        val audit =
            auditOf(
                kind(raw = 48, bodyOmitted = 7, selected = 1),
                kind(raw = 48, bodyOmitted = 40, selected = 46),
                kind(raw = 48, bodyOmitted = 3, selected = 1),
            )

        assertEquals(7, audit.entities.bodyReportedOmittedCount)
        assertEquals(47, audit.entities.jvmCutOmittedCount)
        assertEquals(40, audit.blocks.bodyReportedOmittedCount)
        assertEquals(2, audit.blocks.jvmCutOmittedCount)
        assertEquals(3, audit.droppedItems.bodyReportedOmittedCount)
        assertEquals(47, audit.droppedItems.jvmCutOmittedCount)
    }

    @Test
    fun `every non-empty scene category keeps a selected representative`() {
        assertFailsWith<IllegalArgumentException> {
            auditOf(kind(raw = 1, selected = 0), emptyKind(), emptyKind())
        }
        assertFailsWith<IllegalArgumentException> {
            auditOf(emptyKind(), kind(raw = 1, selected = 0), emptyKind())
        }
        assertFailsWith<IllegalArgumentException> {
            auditOf(emptyKind(), emptyKind(), kind(raw = 1, selected = 0))
        }
    }

    @Test
    fun `incomplete or inconsistent audit fixtures are refused`() {
        val incomplete =
            """{"worldClock":{"timeOfDay":0,"day":0,"isDay":true},""" +
                """"entities":{"rawBodyListCount":0,"bodyReportedOmittedCount":0,""" +
                """"jvmSelectedCount":0,"jvmCutOmittedCount":0},"blocks":{""" +
                """"rawBodyListCount":0,"bodyReportedOmittedCount":0,"jvmSelectedCount":0,""" +
                """"jvmCutOmittedCount":0}}"""
        val inconsistent =
            """{"rawBodyListCount":3,"bodyReportedOmittedCount":0,""" +
                """"jvmSelectedCount":2,"jvmCutOmittedCount":0}"""

        assertFailsWith<Exception> {
            LoomJson.decodeFromString<PerceptionAudit>(incomplete)
        }
        assertFailsWith<IllegalArgumentException> {
            LoomJson.decodeFromString<PerceptionKindAudit>(inconsistent)
        }
        assertFailsWith<IllegalArgumentException> {
            kind(raw = 3, bodyOmitted = 1, selected = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            kind(raw = 49, selected = 49)
        }
    }

    private fun auditOf(
        entities: PerceptionKindAudit,
        blocks: PerceptionKindAudit,
        droppedItems: PerceptionKindAudit,
    ): PerceptionAudit =
        PerceptionAudit(
            worldClock = PerceptionWorldClockAudit(timeOfDay = 0, day = 0, isDay = true),
            entities = entities,
            blocks = blocks,
            droppedItems = droppedItems,
        )

    private fun emptyKind(): PerceptionKindAudit = kind(raw = 0, selected = 0)

    private fun kind(
        raw: Long,
        bodyOmitted: Long = 0,
        selected: Long,
    ): PerceptionKindAudit =
        PerceptionKindAudit(
            rawBodyListCount = raw,
            bodyReportedOmittedCount = bodyOmitted,
            jvmSelectedCount = selected,
            jvmCutOmittedCount = raw - selected,
        )

    private fun selectedTotal(audit: PerceptionAudit): Long =
        audit.entities.jvmSelectedCount +
            audit.blocks.jvmSelectedCount +
            audit.droppedItems.jvmSelectedCount
}
