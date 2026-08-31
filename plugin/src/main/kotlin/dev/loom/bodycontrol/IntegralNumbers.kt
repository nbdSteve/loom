/*
 * How this wire reads a whole number, which is not how kotlinx-serialization reads one.
 *
 * JSON Schema 2020-12 defines `integer` as any number whose fractional part is zero,
 * so `1`, `1.0`, `1e0` and `1.00e2` are four spellings of two integers and the schema
 * accepts all four. `JSON.parse` agrees, so the Node binding does too. kotlinx decodes
 * an `Int` or a `Long` from the raw literal *text*, so it accepts the exponent
 * spellings and refuses the decimal-point ones - which made a frame that is legal to
 * the contract and readable by one runtime unreadable by the other, on a wire whose
 * whole purpose is that the two agree. The divergence failed safe and no encoder on
 * either side can produce it, which is why it was a low-severity parity defect rather
 * than a live bug, but a third party writing a schema-legal frame by hand would have
 * met it.
 *
 * So the serializers below decode the schema's definition rather than kotlinx's. They
 * are reached through `@file:UseSerializers` in `BodyControl.kt`, which applies them to
 * every `Int` and `Long` property on this wire at once - deliberately, so a numeric
 * field added later inherits the contract's own reading instead of the library's.
 *
 * Encoding is untouched: `encodeInt`/`encodeLong` write bare digits exactly as before,
 * which is what keeps the valid fixtures round-tripping byte-for-byte.
 *
 * Three things are still refused, and the third is the reason this file does its own
 * arithmetic instead of calling `BigDecimal.toBigIntegerExact` and hoping:
 *
 *  - **A fractional value**, whatever it is spelled like. `1.5` is not an integer to
 *    the schema and is not one here.
 *  - **A value outside what the field can hold**, which is the ordinary range check the
 *    types already state; this file only has to get the literal as far as a `Long`.
 *  - **A literal whose cost is its point.** `1e2000000000` is twelve characters that
 *    ask for a two-billion-digit number, and `1e-100000000` is twelve that ask for a
 *    hundred-million-digit division: `BigDecimal` parses both happily, storing an
 *    unscaled `1` and a scale of either sign, and only *materialises* the digits when
 *    something asks for the integer value. So the scale is read first, from both ends,
 *    and the value is materialised only once it is known to be affordable - see
 *    `affordablyWhole`, whose two sides are one bound and not a ceiling with a
 *    courtesy. The measurement is done in `Long` arithmetic because `precision - scale`
 *    overflows `Int` for exactly the literals worth refusing.
 *
 * Reading the *literal* rather than a parsed value is what both runtimes now have in
 * common, and it is `BigDecimal` here for the same reason it is a scan of the frame's
 * own bytes in Node: a number that has been through `JSON.parse` no longer knows how it
 * was written, so `9007199254740991.4` arrives as a whole number and
 * `1.0000000000000000001` arrives as exactly `1`. Both are fractional to the schema and
 * invisible to any check of the value. Neither binding therefore inherits its parser's
 * idea of an integer, and the schema's definition is the only reading either of them
 * has.
 */
package dev.loom.bodycontrol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The longest numeric literal this wire will look at.
 *
 * The largest legal value on it is sixteen digits, and this admits twice that: a sign,
 * a full mantissa with a decimal point, and an exponent. Past it a literal is not a
 * peer spelling a number a different way but a line asking to be expensive, and the
 * bound is applied before anything parses it. Node states the same number for itself,
 * and needs it for the same reason: it reads the literal out of the frame's own bytes
 * rather than trusting what `JSON.parse` rounded it to, so it too has an unbounded
 * string in hand before anything measures it.
 */
private const val MAX_NUMBER_LITERAL_CHARS = 32

/**
 * A literal every one of whose mantissa digits is a zero, and so spells zero.
 *
 * Recognised before `BigDecimal`, not after, because `BigDecimal(String)` refuses a
 * literal whose scale will not fit an `int`: `0e-9999999999999999999999999` is a
 * schema-legal spelling of `0` that Node reads and this binding threw on, which is
 * the same parity defect as reading `1.0` differently, in the other direction. The
 * exponent cannot change a zero, so there is nothing to compute and nothing to
 * materialise - and the length bound above is what keeps this from being asked about
 * an unbounded string.
 */
private val ZERO_LITERAL = Regex("""^-?0+(?:\.0*)?(?:[eE][+-]?\d+)?$""")

/**
 * The most integer digits a value on this wire may have before it is materialised.
 *
 * Twenty, which is a digit more than a `Long` holds, so that this bound never becomes
 * the thing that refuses a value the *wire's* own maximum should have refused: the
 * largest count on it is sixteen digits, and a seventeen- to nineteen-digit value fits a
 * `Long`, so it is materialised and refused by the field's own range check with the
 * field's own message. Only past that is the slack spent. A twenty-digit value has a
 * magnitude of at least `1e19` and so overflows `Long.MAX_VALUE` itself, which
 * `integralLongOrNull` reports as no integer at all and this wire says with the one
 * fixed [NOT_AN_INTEGER] message - honest rather than ideal, and unambiguous either way,
 * since nothing with twenty integer digits could have been in range. Past twenty the
 * digit count is absurd and is refused here without anything being built at all. See the
 * file header for why the arithmetic is done in `Long`.
 *
 * It is the ceiling only. What refuses an absurd count of digits to the *right* of the
 * point - the same cost, reached by a negative exponent instead of a positive one - is
 * the floor in `affordablyWhole`, and neither side is sufficient alone.
 */
private const val MAX_INTEGER_DIGITS = 20L

/** Said when a number is not a whole one this wire can hold; carries nothing from it. */
private const val NOT_AN_INTEGER =
    "a body-control frame carries a number that is not a whole number this wire can hold"

/**
 * The exact integer the literal spells, or null when it does not spell one affordably.
 *
 * Integrality is decided by [BigDecimal.toBigIntegerExact], which is the schema's own
 * rule rather than a rounding: it throws for a nonzero fractional part however the
 * value is written, so `1.0` and `1e0` pass and `1.5` and `1.5e0` do not.
 */
private fun integralOrNull(literal: String): BigInteger? =
    when {
        literal.length > MAX_NUMBER_LITERAL_CHARS -> null
        ZERO_LITERAL.matches(literal) -> BigInteger.ZERO
        else ->
            runCatching { BigDecimal(literal) }.getOrNull()
                ?.takeIf { affordablyWhole(it) }
                ?.let { runCatching { it.toBigIntegerExact() }.getOrNull() }
    }

/**
 * Whether a nonzero literal can spell a whole number this wire holds, cheaply enough to try.
 *
 * The bound has to be two-sided, and a one-sided one is what this had: `precision - scale`
 * is how many digits the value has left of the point, so a *negative* exponent makes it
 * negative and it passed the ceiling trivially. `1e-100000000` is twelve characters that
 * reached [BigDecimal.toBigIntegerExact], which divides by `10^scale` and so materialised a
 * hundred-million-digit number to discover a fraction it could have been told about from the
 * scale - forty-seven seconds of one core for one frame from any peer, against nothing at all
 * in Node. A length bound cannot catch it, for the same reason it cannot catch the positive
 * direction.
 *
 * The floor is `scale < precision`, which is exact rather than cautious: it is the statement
 * that the value's magnitude is at least one, and every nonzero whole number's is. So nothing
 * it refuses could have been whole, and every spelling with a fractional tail this wire
 * accepts - `1.0`, `1.00000000000000000`, `20e-1`, `0.0000000000000000001e19` - is on the
 * legal side of it and still goes through the exact test, which is what decides integrality.
 *
 * Both sides together bound what may be built: past the floor a positive scale is smaller
 * than the precision, which the literal's own length bounds, and a negative scale is bounded
 * by the ceiling.
 */
private fun affordablyWhole(decimal: BigDecimal): Boolean {
    val precision = decimal.precision().toLong()
    val scale = decimal.scale().toLong()
    return scale < precision && precision - scale <= MAX_INTEGER_DIGITS
}

/** The literal a decoder is sitting on, or null when it is not an unquoted number. */
private fun numberLiteral(decoder: Decoder): String? {
    val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
    return primitive?.takeIf { !it.isString }?.content
}

/** The `Long` the literal spells, whatever integral spelling it uses. */
internal fun integralLongOrNull(literal: String): Long? =
    integralOrNull(literal)?.let { runCatching { it.longValueExact() }.getOrNull() }

/** The `Int` the literal spells, whatever integral spelling it uses. */
internal fun integralIntOrNull(literal: String): Int? =
    integralOrNull(literal)?.let { runCatching { it.intValueExact() }.getOrNull() }

internal fun integralIntOrNull(primitive: JsonPrimitive?): Int? =
    primitive?.takeIf { !it.isString }?.content?.let { integralIntOrNull(it) }

/** Reads a `Long` the way the schema reads an integer. Writes it as bare digits. */
internal object IntegralLong : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.loom.bodycontrol.IntegralLong", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val value = numberLiteral(decoder)?.let { integralLongOrNull(it) }
        return requireNotNull(value) { NOT_AN_INTEGER }
    }
}

/** Reads an `Int` the way the schema reads an integer. Writes it as bare digits. */
internal object IntegralInt : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.loom.bodycontrol.IntegralInt", PrimitiveKind.INT)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        val value = numberLiteral(decoder)?.let { integralIntOrNull(it) }
        return requireNotNull(value) { NOT_AN_INTEGER }
    }
}
