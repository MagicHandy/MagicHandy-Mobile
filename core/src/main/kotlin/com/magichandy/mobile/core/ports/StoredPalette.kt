package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.Palette
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

internal fun Palette.current(): Palette = if (this == Palette.Lowlight) Palette.OledBlack else this

/**
 * The v1 settings document stores enum names. Lowlight was retired in the 20 November UI update.
 */
internal object StoredPalette : KSerializer<Palette> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StoredPalette", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Palette {
        val name = decoder.decodeString()
        return Palette.entries.firstOrNull { it.name == name }?.current()
            ?: throw SerializationException("Unknown stored palette")
    }

    override fun serialize(encoder: Encoder, value: Palette) {
        encoder.encodeString(value.current().name)
    }
}
