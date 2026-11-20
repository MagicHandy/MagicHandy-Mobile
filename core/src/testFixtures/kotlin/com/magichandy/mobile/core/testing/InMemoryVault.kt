package com.magichandy.mobile.core.testing

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import java.util.concurrent.ConcurrentHashMap

/** Volatile secret storage for simulated accounts in debug builds and tests. */
public class InMemoryVault : SecretsVault {
    private val values = ConcurrentHashMap<SecretHandle, Secret>()

    override suspend fun get(handle: SecretHandle): Secret? = values[handle]

    override suspend fun put(handle: SecretHandle, value: Secret) {
        values[handle] = value
    }

    override suspend fun remove(handle: SecretHandle) {
        values.remove(handle)
    }

    override suspend fun replace(handle: SecretHandle, expected: Secret?, value: Secret): Boolean =
        if (expected == null) values.putIfAbsent(handle, value) == null
        else values.replace(handle, expected, value)
}
