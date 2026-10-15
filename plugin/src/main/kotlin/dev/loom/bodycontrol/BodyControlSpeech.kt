/*
 * The one bounded line of player chat a `say` may carry, mirroring the schema's `SayText`.
 *
 * Nothing is normalised: vanilla collapses chat whitespace, so a line it would rewrite is
 * refused here instead, and the text the server observes is the text that was sent.
 */

package dev.loom.bodycontrol

/** The schema's `maxLength`, in code points; always within Minecraft's 256-unit chat bound. */
const val MAX_SAY_CHARS: Int = 128

/** The UTF-8 bound both codecs state beyond the schema. */
const val MAX_SAY_BYTES: Int = 256

private const val UTF8_ONE_BYTE_MAX = 0x7F
private const val UTF8_TWO_BYTE_MAX = 0x7FF
private const val UTF8_THREE_BYTE_MAX = 0xFFFF
private const val UTF8_THREE_BYTES = 3
private const val UTF8_FOUR_BYTES = 4

/** Closed ranges of code points a chat line may not contain, matching the schema pattern. */
private val REFUSED_CODE_POINTS: List<CharRange> =
    listOf(
        '\u0000'..'\u0020',
        '\u007F'..'\u00A0',
        '\u00A7'..'\u00A7',
        '\u061C'..'\u061C',
        '\u1680'..'\u1680',
        '\u2000'..'\u200F',
        '\u2028'..'\u202F',
        '\u205F'..'\u206F',
        '\u3000'..'\u3000',
        '\uFEFF'..'\uFEFF',
        '\uFFF9'..'\uFFFB',
    )

private const val SPACE = 0x20
private const val SLASH = '/'.code

/** Validation of a `say` line, shared by the codec, actuation and the server witness. */
object SayText {
    /** Why [text] is not a sendable chat line, or null when it is; never quotes the text. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun refusal(text: String): String? {
        if (text.isEmpty()) return "text must not be blank"
        var points = 0
        var bytes = 0
        var previousSpace = true
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            if (unit.isLowSurrogate()) return "text must not contain an unpaired surrogate"
            if (unit.isHighSurrogate() && (index + 1 >= text.length || !text[index + 1].isLowSurrogate())) {
                return "text must not contain an unpaired surrogate"
            }
            val point = text.codePointAt(index)
            index += Character.charCount(point)
            points += 1
            bytes += utf8Length(point)
            if (points == 1 && point == SLASH) return "text must not begin with '/'"
            if (point == SPACE) {
                if (previousSpace) return "text must separate words by exactly one space"
                previousSpace = true
                continue
            }
            if (Character.isBmpCodePoint(point) && REFUSED_CODE_POINTS.any { point.toChar() in it }) {
                return "text must not contain controls, escapes, bidi or invisible characters"
            }
            previousSpace = false
        }
        return when {
            previousSpace -> "text must not end with whitespace"
            points > MAX_SAY_CHARS -> "text must be at most $MAX_SAY_CHARS code points"
            bytes > MAX_SAY_BYTES -> "text must be at most $MAX_SAY_BYTES UTF-8 bytes"
            else -> null
        }
    }

    /** Whether [text] may be said. */
    fun admits(text: String): Boolean = refusal(text) == null

    private fun utf8Length(point: Int): Int =
        when {
            point <= UTF8_ONE_BYTE_MAX -> 1
            point <= UTF8_TWO_BYTE_MAX -> 2
            point <= UTF8_THREE_BYTE_MAX -> UTF8_THREE_BYTES
            else -> UTF8_FOUR_BYTES
        }
}

/** Refuses a `say` line, mirroring [SayText]. */
internal fun requireSayText(text: String) {
    val refusal = SayText.refusal(text)
    require(refusal == null) { refusal.orEmpty() }
}

/** Every terminal answer to one say. */
@kotlinx.serialization.Serializable
enum class SayOutcome {
    @kotlinx.serialization.SerialName("sent")
    SENT,

    @kotlinx.serialization.SerialName("rateLimited")
    RATE_LIMITED,
}
