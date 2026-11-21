package com.magichandy.mobile.core.ports

import kotlinx.serialization.json.JsonObject

/** A bounded, composed request. The schema describes semantic proposals and never wire commands. */
public data class ModelRequest(
    public val messages: List<TextMessage>,
    public val schema: JsonObject,
    public val name: String = "motion_proposal",
    public val preserveTail: Int = 1,
    /** Output tokens reserved by the composer. A provider may already require a higher floor. */
    public val outputAllowance: Int = 0,
)

/** Providers negotiating a response contract. Raw model JSON is private to the validator. */
public interface PlanningProvider {
    public suspend fun plan(request: ModelRequest): TextResult
}

public interface StructuredProvider : ModelProvider, PlanningProvider {
    override suspend fun plan(request: ModelRequest): TextResult = complete(request)

    /** Return only an authoritative completed generation. */
    public suspend fun complete(request: ModelRequest, preview: (String) -> Unit = {}): TextResult
}
