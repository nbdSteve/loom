package dev.loom.bodycontrol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SayTextTest {
    private val emoji = "😀"

    @Test
    fun `the exact bounds are admitted`() {
        assertTrue(SayText.admits("a".repeat(MAX_SAY_CHARS)))
        assertTrue(SayText.admits(emoji.repeat(MAX_SAY_BYTES / 4)))
        assertTrue(SayText.admits("héllo wörld 你好"))
        assertTrue(SayText.admits("a/b is fine mid-line"))
    }

    @Test
    fun `one past either bound is refused`() {
        assertFalse(SayText.admits("a".repeat(MAX_SAY_CHARS + 1)))
        assertFalse(SayText.admits(emoji.repeat(MAX_SAY_BYTES / 4 + 1)))
    }

    @Test
    fun `unsafe or normalisable lines are refused`() {
        val refused =
            listOf(
                "",
                " ",
                "   ",
                " hi",
                "hi ",
                "hi  there",
                "hi\tthere",
                "hi\nthere",
                "hi\u0007",
                "\u007f",
                "\u0085",
                "§cred",
                "\u001b[31mred",
                "a‮b",
                "a⁦b",
                "a‏b",
                "a؜b",
                "a​b",
                "a b",
                "a　b",
                "a b",
                "﻿hi",
                "a￹b",
                "/kill @a",
                "hi\uD800",
                "\uDC00hi",
                "a\uD800b",
            )
        for (line in refused) {
            assertFalse(SayText.admits(line), "admitted ${line.codePoints().toArray().toList()}")
        }
    }

    @Test
    fun `a refusal never quotes the line`() {
        val refusal = SayText.refusal("secret\u0007")
        assertTrue(refusal != null && "secret" !in refusal)
    }

    @Test
    fun `a request cannot be built around an unsendable line`() {
        assertThrows<IllegalArgumentException> {
            BodyRequest.Say(BODY_CONTROL_PROTOCOL_VERSION, "req-1", "agent-alpha", "hi  there")
        }
        val built = BodyRequest.Say(BODY_CONTROL_PROTOCOL_VERSION, "req-1", "agent-alpha", "hi there")
        assertEquals(built, decodeBodyRequest(encodeBodyRequest(built)))
    }
}
