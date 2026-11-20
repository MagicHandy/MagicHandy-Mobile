package com.magichandy.mobile.platform.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.*
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidVault(
    private val file: (SecretHandle) -> AtomicBytes,
    private val key: () -> SecretKey,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretsVault {
    constructor(
        directory: File
    ) : this(
        { h ->
            AtomicBytes(
                File(
                    directory,
                    MessageDigest.getInstance("SHA-256")
                        .digest(h.value.encodeToByteArray())
                        .joinToString("") { "%02x".format(it) },
                )
            )
        },
        ::installKey,
    )

    private val serial = Mutex()

    private suspend fun <T> access(block: () -> T): T =
        withContext(io) {
            serial.withLock {
                try {
                    block()
                } catch (e: GeneralSecurityException) {
                    throw IOException("Vault unavailable", e)
                }
            }
        }

    private fun read(h: SecretHandle): Secret? {
        val packet = file(h).read(262144) ?: return null
        if (packet.size < 30 || packet[0] != 1.toByte())
            throw IOException("Invalid credential record")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packet, 1, 12))
        cipher.updateAAD(h.value.encodeToByteArray())
        val plain = cipher.doFinal(packet, 13, packet.size - 13)
        return try {
            Secret(plain.decodeToString(throwOnInvalidSequence = true))
        } finally {
            plain.fill(0)
        }
    }

    private fun write(h: SecretHandle, v: Secret) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(h.value.encodeToByteArray())
        val plain = v.reveal().encodeToByteArray()
        try {
            require(plain.size <= 240000 && cipher.iv.size == 12)
            file(h).replace(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun get(handle: SecretHandle): Secret? = access { read(handle) }

    override suspend fun put(handle: SecretHandle, value: Secret) {
        access { write(handle, value) }
    }

    override suspend fun replace(handle: SecretHandle, expected: Secret?, value: Secret): Boolean =
        access {
            if (read(handle) != expected) false
            else {
                write(handle, value)
                true
            }
        }

    override suspend fun remove(handle: SecretHandle) {
        access { file(handle).remove() }
    }
}

private fun installKey(): SecretKey {
    val alias = "magichandy.vault.1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let {
        return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
    )
    return generator.generateKey()
}
