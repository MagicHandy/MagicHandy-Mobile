package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ModelConnection
import kotlinx.coroutines.flow.StateFlow

/** Persisted model configuration. Only the vault handle is kept next to public connection data. */
public data class SavedModel(
    public val info: ModelConnection,
    public val credential: SecretHandle? = null,
    public val address: String? = null,
)

/** Named connections and independently persisted roles. Null planner inherits the chat role. */
public data class ModelCatalog(
    public val models: Map<ConnectionId, SavedModel> = emptyMap(),
    public val chat: ConnectionId? = null,
    public val planner: ConnectionId? = null,
)

/** Store connections and role changes atomically, including clearing roles on removal. */
public interface ModelStore {
    /** Latest committed catalog. */
    public val state: StateFlow<ModelCatalog>

    /** Upsert a connection. The first saved connection becomes the chat role. */
    public suspend fun save(model: SavedModel)

    /** Remove a connection and all references to it. */
    public suspend fun remove(id: ConnectionId)

    /** Assign an existing connection to chat. */
    public suspend fun bindChat(id: ConnectionId)

    /** Assign an existing planner connection or select inheritance. */
    public suspend fun bindPlanner(id: ConnectionId?)
}
