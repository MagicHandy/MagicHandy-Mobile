package com.magichandy.mobile.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Schema v1 stores the two inclusive endpoints, independently of Kotlin's range implementation. */
internal object RangeSerializer : KSerializer<IntRange> {
    private val array = IntArraySerializer()
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor = array.descriptor

    override fun serialize(encoder: Encoder, value: IntRange) {
        array.serialize(encoder, intArrayOf(value.first, value.last))
    }

    override fun deserialize(decoder: Decoder): IntRange {
        val bounds = array.deserialize(decoder)
        require(bounds.size == 2 && bounds[0] <= bounds[1])
        return bounds[0]..bounds[1]
    }
}
