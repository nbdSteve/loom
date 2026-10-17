package dev.loom.plugin

import dev.loom.mind.MAX_FOLLOW_UPS_PER_REVIEW
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class NativeCognitionStartTest {
    @Test
    @DisplayName("immediate native cognition starts once when the plugin enables")
    fun immediateStartsOnEnable() {
        var starts = 0
        val native = NativeCognitionStart(held = false) { starts += 1 }

        assertEquals(0, starts)
        native.enable()
        assertEquals(1, starts)
        assertFalse(native.begin())
        assertEquals(1, starts)
    }

    @Test
    @DisplayName("held native cognition starts only at begin and only once")
    fun heldStartsOnlyAtBegin() {
        var starts = 0
        val native = NativeCognitionStart(held = true) { starts += 1 }

        native.enable()
        assertEquals(0, starts)
        assertTrue(native.begin())
        assertEquals(1, starts)
        assertFalse(native.begin())
        assertEquals(1, starts)
    }

    @Test
    @DisplayName("the setting is immediate by default and refuses a misspelling")
    fun settingIsClosed() {
        assertFalse(NativeCognitionStart.heldBy(null))
        assertFalse(NativeCognitionStart.heldBy(""))
        assertFalse(NativeCognitionStart.heldBy("immediate"))
        assertTrue(NativeCognitionStart.heldBy(" held "))
        assertThrows(IllegalArgumentException::class.java) { NativeCognitionStart.heldBy("hold") }
        var starts = 0
        NativeCognitionStart.fromEnvironment(mapOf(NATIVE_COGNITION_ENV to "held")) { starts += 1 }.enable()
        assertEquals(0, starts)
        NativeCognitionStart.fromEnvironment(emptyMap()) { starts += 1 }.enable()
        assertEquals(1, starts)
    }
}

class FollowUpsPerReviewTest {
    @Test
    fun `the follow-up allowance is the plan's own count, and a bad one stops the plugin`() {
        assertEquals(MAX_FOLLOW_UPS_PER_REVIEW, followUpsPerReviewOf(null))
        assertEquals(1, followUpsPerReviewOf("1"))
        assertEquals(0, followUpsPerReviewOf(" 0 "))
        for (bad in listOf("3", "-1", "one", "")) {
            assertThrows(IllegalArgumentException::class.java, { followUpsPerReviewOf(bad) }, bad)
        }
    }
}
