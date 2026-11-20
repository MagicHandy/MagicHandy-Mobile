package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.Secret

/** Stable opaque reference. Its value must contain no credential material. */
@kotlinx.serialization.Serializable
@JvmInline
public value class SecretHandle(public val value: String)

/** Keystore-backed storage in release builds. An unusable key fails closed or requires sign-in. */
public interface SecretsVault {
    /** Retrieve a credential, or null when the handle has no usable value. */
    public suspend fun get(handle: SecretHandle): Secret?

    /** Atomically replace the entire credential, including refresh rotation. */
    public suspend fun put(handle: SecretHandle, value: Secret)

    /** Install rotated credentials only if the original credential still owns the handle. */
    public suspend fun replace(handle: SecretHandle, expected: Secret?, value: Secret): Boolean

    /**
     * Erase before removing its Room row. The short-lived rollback restores only an empty handle.
     */
    public suspend fun takeForRemoval(handle: SecretHandle): ErasedCredential {
        val prior = get(handle)
        remove(handle)
        return ErasedCredential { if (prior != null) replace(handle, null, prior) }
    }

    /** Erase local authority for this handle. */
    public suspend fun remove(handle: SecretHandle)
}

/**
 * An in-process compensation for a failed database removal. Implementations expose no key material.
 */
public fun interface ErasedCredential {
    public suspend fun restoreIfMissing()
}
