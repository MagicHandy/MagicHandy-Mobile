// Ported from internal/handyble/{codec,wire}.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import java.io.ByteArrayOutputStream
import java.io.IOException

internal class ProtoWriter {
    private val output = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write((remaining and 127).toInt() or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }

    fun uint(field: Int, value: Long) {
        varint(field.toLong() shl 3)
        varint(value)
    }

    fun float(field: Int, value: Float) {
        varint((field.toLong() shl 3) or 5)
        for (i in 0..3) output.write(value.toRawBits() ushr (8 * i))
    }

    fun message(field: Int, block: ProtoWriter.() -> Unit) {
        val bytes = ProtoWriter().apply(block).bytes()
        varint((field.toLong() shl 3) or 2)
        varint(bytes.size.toLong())
        output.write(bytes)
    }

    fun bytes(): ByteArray = output.toByteArray()
}

internal fun HandyCommand.encode(id: Long): ByteArray {
    require(id in 1..0xffff_ffffL)
    return ProtoWriter()
        .apply {
            uint(1, 1)
            message(2) {
                message(method) { body(this@encode) }
                uint(2, id)
            }
        }
        .bytes()
}

private fun ProtoWriter.body(command: HandyCommand) {
    when (command) {
        HandyCommand.StopCurrentMode,
        HandyCommand.HspStop,
        HandyCommand.ClockGet,
        HandyCommand.State -> Unit
        is HandyCommand.Setup -> uint(1, command.stream)
        is HandyCommand.Stroke -> {
            float(1, command.low.toFloat() / 100f)
            float(2, command.high.toFloat() / 100f)
        }
        is HandyCommand.Add -> {
            for (frame in command.frames) message(1) {
                uint(1, frame.timeMillis)
                uint(2, frame.position.value.toLong())
            }
            uint(2, if (command.flush) 1 else 0)
            uint(3, command.tail)
        }
        is HandyCommand.Play -> {
            uint(1, command.start.toLong())
            command.serverTimeMillis?.let { uint(2, it) }
            float(3, 1f)
            uint(4, 0)
            uint(5, 1)
        }
        is HandyCommand.ClockSet -> {
            uint(1, (command.offset shl 1) xor (command.offset shr 63))
            uint(2, command.roundTripMillis.toLong())
        }
    }
}

internal data class ProtoField(
    val number: Int,
    val value: Long? = null,
    val data: ByteArray? = null,
)

internal fun fields(bytes: ByteArray): List<ProtoField> {
    fun malformed(): Nothing = throw IOException("Invalid Handy RPC frame")
    if (bytes.size > 512) malformed()
    var offset = 0
    fun read(): Int = if (offset < bytes.size) bytes[offset++].toInt() and 255 else malformed()
    fun varint(): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            val next = read()
            if (shift == 63 && next > 1) malformed()
            value = value or ((next and 127).toLong() shl shift)
            if (next < 128) return value
        }
        malformed()
    }
    fun take(count: Long): ByteArray {
        if (count !in 0..(bytes.size - offset).toLong()) malformed()
        val start = offset
        offset += count.toInt()
        return bytes.copyOfRange(start, offset)
    }
    return buildList {
        while (offset < bytes.size) {
            val key = varint()
            if (key ushr 3 !in 1..0x1fff_ffffL) malformed()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> add(ProtoField(number, value = varint()))
                2 -> add(ProtoField(number, data = take(varint())))
                1 -> take(8)
                5 -> take(4)
                else -> malformed()
            }
        }
    }
}

internal data class RpcReply(
    val id: Long,
    val method: Int?,
    val rejected: Boolean,
    val deviceTimeMillis: Long? = null,
)

internal fun decodeReply(bytes: ByteArray): RpcReply? {
    val outer = fields(bytes)
    val kind = outer.lastOrNull { it.number == 1 }?.value
    if (kind == 4L) return null // Unsolicited notifications never acknowledge a command.
    val body = outer.lastOrNull { it.number == 4 }?.data
    if (kind != 3L || body == null) throw IOException("Missing Handy response")
    val reply = fields(body)
    val id = reply.lastOrNull { it.number == 1 }?.value ?: 0
    if (id !in 0..0xffff_ffffL) throw IOException("Invalid Handy response ID")
    val method = reply.singleOrNull { it.number > 10 && it.data != null }
    val deviceTime =
        if (method?.number == HandyCommand.ClockGet.method)
            fields(requireNotNull(method.data))
                .singleOrNull { it.number == 1 }
                ?.value
                ?.takeIf { it in 0..0xffff_ffffL }
        else null
    return RpcReply(
        id,
        method?.number,
        reply.any { it.number == 2 && it.data != null },
        deviceTime,
    )
}
