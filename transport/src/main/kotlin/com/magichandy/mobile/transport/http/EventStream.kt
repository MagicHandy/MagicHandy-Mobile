package com.magichandy.mobile.transport.http

import okhttp3.Response
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Options

/**
 * Event streams can arrive without usable media metadata. Other successful HTTP bodies are not
 * streams.
 */
internal fun Response.hasEventStream(): Boolean =
    code == 200 &&
        (body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } == true ||
            body.source().hasEventStreamPrefix())

internal val utf8Bom = "\uFEFF".encodeUtf8()
private val eventFields =
    Options.of(
        ":".encodeUtf8(),
        "event:".encodeUtf8(),
        "data:".encodeUtf8(),
        "id:".encodeUtf8(),
        "retry:".encodeUtf8(),
    )

/** Inspect a bounded prefix without consuming the stream. */
private fun BufferedSource.hasEventStreamPrefix(): Boolean {
    val prefix = peek()
    if (prefix.rangeEquals(0, utf8Bom)) prefix.skip(utf8Bom.size.toLong())
    repeat(256) {
        if (!prefix.request(1)) return false
        val next = prefix.buffer[0].toInt().toChar()
        if (next != '\n' && next != '\r') return prefix.select(eventFields) >= 0
        prefix.skip(1)
    }
    return false
}
