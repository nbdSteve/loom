/*
 * The two questions about a line of JSON that have to be answered before it is parsed.
 *
 * These are scanners over raw text, and they know nothing about the body-control
 * protocol: they are separate from `BodyControlCodec.kt` for exactly that reason, and
 * they share the one string-literal walk that makes either of them sound.
 *
 *  - **Whether it names the same member twice.** A duplicate member is a fact about
 *    *bytes* that no decoded value can be asked about, because JSON's data model has no
 *    room for one - a parser believes one of the two values, drops the other, and reports
 *    nothing - so the check has to happen before anything parses the line.
 *  - **How deeply it nests.** A parser is what a depth bound protects, so the answer is
 *    no use after the parse. `nestsDeeperThan` is therefore a flat walk with a counter
 *    and deliberately not a recursive one: a check against stack exhaustion that recursed
 *    per level would be the thing it exists to refuse.
 *
 * The Node side's `body/src/protocol.js` carries both scanners, written the same way, for
 * the same place in its own decode path.
 */
package dev.loom.bodycontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** The four characters JSON counts as whitespace between a member name and its colon. */
private const val JSON_WHITESPACE = " \t\n\r"

/**
 * Whether any object in the raw text names the same member twice.
 *
 * Names are tracked per object depth, so the same name in two different objects is not
 * a duplicate; string literals are stepped over whole, so a brace or a colon inside a
 * value is not read as structure; and a name is compared *decoded*, so `"op"` and
 * `"\u006fp"` are the one name they spell. Text this cannot walk to the end of is not
 * reported as a duplicate: it is not valid JSON either, and the parse a moment later
 * refuses it for that instead.
 */
internal fun namesAMemberTwice(text: String): Boolean {
    val namesByDepth = ArrayDeque<MutableSet<String>>()
    var index = 0
    var twice = false
    while (index < text.length && !twice) {
        when (text[index]) {
            '{' -> {
                namesByDepth.addLast(mutableSetOf())
                index += 1
            }
            '}' -> {
                namesByDepth.removeLastOrNull()
                index += 1
            }
            '"' -> {
                val after = endOfStringLiteral(text, index)
                twice = after != null && repeatsAName(text, index, after, namesByDepth)
                index = after ?: text.length
            }
            else -> index += 1
        }
    }
    return twice
}

/**
 * Whether any point in the raw text has more than [depth] objects or arrays open.
 *
 * String literals are stepped over whole, so a brace or a bracket inside a value is not
 * read as structure; and text this cannot walk to the end of is not reported as too deep,
 * since it is not valid JSON either and the parse a moment later refuses it for that.
 *
 * Nothing here recurses. That is the point rather than a style: the reason a caller asks
 * is that the parse it is about to do recurses per level, so an answer computed by
 * recursing per level would exhaust the same stack while deciding whether to.
 */
internal fun nestsDeeperThan(
    text: String,
    depth: Int,
): Boolean {
    var open = 0
    var index = 0
    var deeper = false
    while (index < text.length && !deeper) {
        when (text[index]) {
            '{', '[' -> {
                open += 1
                deeper = open > depth
                index += 1
            }
            '}', ']' -> {
                open -= 1
                index += 1
            }
            '"' -> index = endOfStringLiteral(text, index) ?: text.length
            else -> index += 1
        }
    }
    return deeper
}

/** Whether the literal starting at [open] is a member name this object already has. */
private fun repeatsAName(
    text: String,
    open: Int,
    after: Int,
    namesByDepth: ArrayDeque<MutableSet<String>>,
): Boolean {
    val names = namesByDepth.lastOrNull()
    val name = memberName(text, open, after)
    return names != null && name != null && !names.add(name)
}

/**
 * The index just past the closing quote of the string literal starting at [open], or
 * null when the text ends inside it. A backslash consumes the character after it, so a
 * literal containing an escaped quote is stepped over whole.
 */
private fun endOfStringLiteral(
    text: String,
    open: Int,
): Int? {
    var index = open + 1
    var end: Int? = null
    while (index < text.length && end == null) {
        when (text[index]) {
            '\\' -> index += 2
            '"' -> end = index + 1
            else -> index += 1
        }
    }
    return end
}

/**
 * The name the literal spells, or null when it is a value rather than a member name.
 *
 * A colon after the literal is what makes it a name; the decode is what makes two
 * spellings of one name compare equal. The library's own default [Json] does that decode,
 * deliberately rather than the wire's configured one: unescaping a string literal is not
 * reading a frame, and this file has no business holding a second copy of the settings
 * `BodyControlCodec.kt` states.
 */
private fun memberName(
    text: String,
    open: Int,
    after: Int,
): String? {
    val literal = if (isFollowedByColon(text, after)) text.substring(open, after) else null
    val parsed = literal?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    return (parsed as? JsonPrimitive)?.content
}

/** Whether the next non-whitespace character at or after [index] is a colon. */
private fun isFollowedByColon(
    text: String,
    index: Int,
): Boolean {
    var at = index
    while (at < text.length && text[at] in JSON_WHITESPACE) {
        at += 1
    }
    return at < text.length && text[at] == ':'
}
