/*
 * The one choke point every line passes through, tested as the safety property it is.
 *
 * The strings that reach an operator include one a language model wrote. So this is not
 * cosmetic tidying: a line that could carry a newline, a control character, or Minecraft's
 * own section sign is a line whose *sender* decides what an operator's screen looks like.
 */
package dev.loom.observe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The characters an ASCII-only class let straight through.
 *
 * Two C1 controls, the line and paragraph separators, and the bidi controls. Java matches
 * none of them with `\p{Cntrl}` or `\s` unless the pattern is made Unicode-aware, and
 * every one of them can blank, reorder, or re-lay-out the line it is in.
 */
private val ONCE_MISSED =
    listOf('\u0085', '\u009f', '\u2028', '\u2029', '\u200f', '\u202c', '\u202d', '\u202e', '\u2066', '\u2069')

/** One line of the shape a model could write, carrying every one of [ONCE_MISSED]. */
private const val HOSTILE =
    "walk\u0085north \u2028now \u2029then \u200f\u202dmarked\u202c \u202ereversed\u202c \u2066iso\u2069 home\u009f"

class ObserverBoundTest {
    private val bound = ObserverBound()

    @Test
    @DisplayName("a line that already fits is left exactly as it is")
    fun aShortLineIsUntouched() {
        assertEquals("energy 16/20 | moving", bound.oneLine("energy 16/20 | moving"))
    }

    @Test
    @DisplayName("no line can exceed the bound, however long it arrived")
    fun everyLineIsBounded() {
        val page = "reasoning ".repeat(500)

        val shown = bound.oneLine(page)

        assertTrue(shown.length <= bound.lineChars, "was ${shown.length} of ${bound.lineChars}")
        assertTrue(shown.endsWith("..."), "a cut line must admit it was cut; was \"$shown\"")
    }

    @Test
    @DisplayName("a model that answered with several lines is shown as one")
    fun newlinesCollapse() {
        val answer = "I will go north.\n\nThe village is there."

        assertEquals("I will go north. The village is there.", bound.oneLine(answer))
    }

    @Test
    @DisplayName("control characters and the section sign cannot reach a screen")
    fun formattingCharactersAreStripped() {
        // A section sign is Minecraft's own formatting escape: left in, a model could
        // colour an operator's display, or blank the line with an obfuscation code.
        val hostile = "walk\u00a7knorth\u0007\u001b[31m now"

        val shown = bound.oneLine(hostile)

        assertFalse(shown.contains('\u00a7'), shown)
        assertTrue(shown.none { it.isISOControl() }, shown)
        assertEquals("walk knorth [31m now", shown)
    }

    @Test
    @DisplayName("the formatting characters an ASCII class misses cannot reach a screen either")
    fun theNonAsciiFormattingCharactersAreStrippedToo() {
        val shown = bound.oneLine(HOSTILE)

        // Asserted character by character as well as whole, because `isISOControl` is true
        // of a plain space: an assertion made of that predicate alone says nothing at all
        // about a line that kept its line separator or its bidi override.
        for (hidden in ONCE_MISSED) {
            assertFalse(shown.contains(hidden), "U+%04X survived: %s".format(hidden.code, shown))
        }
        assertEquals("walk north now then marked reversed iso home", shown)
    }

    @Test
    @DisplayName("a model's own reasoning is flattened on the path it really arrives by")
    fun modelAuthoredReasoningIsFlattened() {
        // `decided` is the one detail carried verbatim to an operator and `reasoning` is
        // arbitrary model-authored UTF-8, so this is where a hostile string actually
        // arrives - as a note rather than as a standing line, which is why the flattening
        // cannot be the line bound's alone. An override is the interesting one: it reorders
        // everything after it, so a model could make its own reasoning read back to front.
        val reasoning = "moveTo: heading north \u202enot\u202c to the village now"

        val note = bound.oneNote(reasoning)

        assertEquals("moveTo: heading north not to the village now", note)
    }

    @Test
    @DisplayName("surrounding and repeated whitespace is collapsed away")
    fun whitespaceIsCollapsed() {
        assertEquals("a b", bound.oneLine("   a \t\t  b  "))
    }

    @Test
    @DisplayName("a cut line does not end in the middle of trailing space")
    fun aCutLineIsTrimmed() {
        val tight = ObserverBound(lineChars = 10)

        assertEquals("abcdef...", tight.oneLine("abcdef  ghijklmnop"))
    }

    @Test
    @DisplayName("a note has the room a standing line does not, and is still capped")
    fun aNoteIsBoundedMoreGenerouslyThanALine() {
        val page = "reasoning ".repeat(500)

        val note = bound.oneNote(page)

        assertTrue(note.length > bound.lineChars, "a note must not be as tight as a boss bar title")
        assertTrue(note.length <= bound.noteChars, "was ${note.length} of ${bound.noteChars}")
        assertTrue(note.endsWith("..."), "a cut note must admit it was cut; was \"$note\"")
        // Flattening is the safety property, so it cannot be the line bound's alone.
        assertFalse(bound.oneNote("walk\u00a7knorth\n now").contains('\u00a7'))
    }

    @Test
    @DisplayName("a bound too small to hold its own ellipsis is refused")
    fun anImpossibleBoundIsRefused() {
        assertThrows<IllegalArgumentException> { ObserverBound(lineChars = 3) }
    }

    @Test
    @DisplayName("a note tighter than a standing line is refused, since that is the tight one")
    fun aNoteTighterThanALineIsRefused() {
        assertThrows<IllegalArgumentException> { ObserverBound(lineChars = 96, noteChars = 40) }
    }

    @Test
    @DisplayName("a wire tag is spelled as words, by rule and not by a lookup table")
    fun tagsBecomeWords() {
        assertEquals("goal review", words("goalReview"))
        assertEquals("block sighted", words("blockSighted"))
        assertEquals("idle", words("idle"))
        // The point of doing it by rule: a tag added tomorrow is legible today.
        assertEquals("some tag nobody has added yet", words("someTagNobodyHasAddedYet"))
    }
}
