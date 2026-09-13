package dev.loom.bodycontrol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Writes an integral movement distance the same way JSON.stringify writes it.
 *
 * Kotlin's Double serializer writes `0.0`, while the Node binding writes the same JSON
 * number as `0`. Movement distances can be exactly integral, most importantly the required
 * zero on a pre-search outOfRange report, and valid fixtures round-trip byte for byte through
 * both handwritten codecs. Non-integral values keep the ordinary Double representation.
 */
internal object CanonicalDistance : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.loom.bodycontrol.CanonicalDistance", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()

    override fun serialize(
        encoder: Encoder,
        value: Double,
    ) {
        if (value.isFinite() && value % 1.0 == 0.0) {
            encoder.encodeLong(value.toLong())
        } else {
            encoder.encodeDouble(value)
        }
    }
}
