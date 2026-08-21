/*
 * What an observer may be shown, and how much of it.
 *
 * The counterpart of `PerceptionBound`, and for the same reason: a bound that lives in
 * the domain is a bound a test can pin, while one spelled inline at the call site is a
 * bound that drifts. Perception bounds what an *agent* is told about the world; this
 * bounds what an *operator* is told about an agent.
 *
 * Everything here is total and pure. Nothing reaches a sink without passing through
 * [ObserverBound.oneLine] or [ObserverBound.oneNote], which are the single choke point
 * where an unbounded string from the record - a model's own reasoning, most of all -
 * becomes something safe to put in front of somebody.
 */
package dev.loom.observe

/**
 * A watcher's line is one line: these are the characters that can break that.
 *
 * `(?U)` is load-bearing rather than tidiness. Without it Java reads `\p{Cntrl}` as
 * `[\x00-\x1F\x7F]` and `\s` as ASCII whitespace, so a model's answer could carry the C1
 * controls (U+0080-U+009F, U+0085 NEL among them) or U+2028 LINE SEPARATOR straight through
 * a function whose whole job is to flatten them. With it, `\p{Cntrl}` is every `Cc`
 * character and `\s` is every `White_Space` one, which covers both.
 *
 * The bidi controls are then listed by hand because they are `Cf`, not `Cc` or whitespace,
 * and no character class collapses them. U+202D and U+202E reorder everything after them,
 * so a line could be made to read back to front; the embeddings, the pop, the marks, and
 * the isolates do the same job less bluntly, so the whole set goes rather than the two that
 * happened to be reported.
 */
private val NOT_ONE_LINE =
    Regex("(?U)[\\p{Cntrl}\\s\\u00a7\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]+")

/** Camel humps are where a wire tag's words are joined. */
private val CAMEL_HUMP = Regex("[A-Z]")

/** Marks a line that had more to say. */
private const val ELLIPSIS = "..."

/**
 * How much of what an observer is shown fits, and how it is cut down to fit.
 *
 * Two bounds, because Loom's two sinks are not the same shape. The standing line is
 * repainted in place and has to fit a boss bar title; a note is one chat message, which
 * wraps and scrolls. Sizing both to the tighter of the two looked harmless until a real
 * run showed what it costs: the mind's own reasoning is the single most valuable thing on
 * an operator's screen, and at the boss bar's width it lost its second half.
 *
 * @param lineChars the most characters the standing status line may carry. Sized for the
 *   title of a Minecraft boss bar, which is the tightest sink Loom has and therefore the
 *   one that decides.
 * @param noteChars the most characters one note may carry. Wider than a standing line
 *   because chat has the room, and still a hard cap: a model that answers with a page
 *   costs this and no more.
 */
class ObserverBound(
    val lineChars: Int = DEFAULT_LINE_CHARS,
    val noteChars: Int = DEFAULT_NOTE_CHARS,
) {
    init {
        require(lineChars > ELLIPSIS.length) { "lineChars must exceed the ellipsis, was $lineChars" }
        require(noteChars >= lineChars) { "a note may not be tighter than a standing line, was $noteChars" }
    }

    /** Reduces [text] to one bounded standing line. See [cutTo]. */
    fun oneLine(text: String): String = cutTo(text, lineChars)

    /** Reduces [text] to one bounded note. See [cutTo]. */
    fun oneNote(text: String): String = cutTo(text, noteChars)

    /**
     * Reduces [text] to one line of at most [limit] characters.
     *
     * Three things happen and all three matter. Control characters, the bidi controls, and
     * Minecraft's own section sign collapse to spaces, so text that came out of a model
     * cannot colour, blank, reorder, or re-lay-out an operator's screen. Runs of whitespace
     * collapse, so a multi-line answer is one line rather than a wall. And the result is
     * cut to [limit] with an ellipsis, so *no* string from the record - reasoning, a
     * refusal, an id - can be longer than the line it was put on, however long it arrived.
     */
    private fun cutTo(
        text: String,
        limit: Int,
    ): String {
        val flattened = text.replace(NOT_ONE_LINE, " ").trim()
        if (flattened.length <= limit) {
            return flattened
        }
        return flattened.take(limit - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

    companion object {
        /** Sized for a boss bar title at default GUI scale. */
        const val DEFAULT_LINE_CHARS: Int = 96

        /** About three wrapped rows of chat: room for a real answer, and no more. */
        const val DEFAULT_NOTE_CHARS: Int = 192
    }
}

/**
 * Spells a wire tag as words.
 *
 * The log counts by `goalReview`; a person reads "goal review". Doing it by rule rather
 * than by a table means a new tag is legible the day it is added instead of showing up
 * as a gap in a lookup nobody remembered to extend.
 */
fun words(tag: String): String = tag.replace(CAMEL_HUMP) { " ${it.value.lowercase()}" }.trim()
