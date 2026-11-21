package com.magichandy.mobile.platform.storage

import java.util.UUID

private val uuidV4 =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

/** OpenAI accepts a UUIDv4 URN. Upgrading a bare v4 from 0.1.0 preserves the installation. */
internal fun chatGptHostId(storage: AtomicBytes): String {
    val stored = storage.read(128)?.decodeToString()
    val uuid =
        stored?.removePrefix("urn:uuid:")?.takeIf(uuidV4::matches)?.lowercase()
            ?: UUID.randomUUID().toString()
    val uri = "urn:uuid:$uuid"
    if (uri != stored) storage.replace(uri.encodeToByteArray())
    return uri
}
