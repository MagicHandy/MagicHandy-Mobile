package com.magichandy.mobile.platform.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.CredentialEdit
import com.magichandy.mobile.core.ports.ErasedCredential
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.ports.UnusableCredential
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidVault(
    private val file: (SecretHandle) -> AtomicBytes,
    private val key: () -> SecretKey,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val replaceInvalidKey: (() -> SecretKey)? = null,
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
        replaceInvalidKey = ::generateKey,
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

    private fun read(h: SecretHandle): Secret? = file(h).read(262144)?.let { decrypt(h, it) }

    private fun decrypt(h: SecretHandle, packet: ByteArray): Secret {
        if (packet.size < 30 || packet[0] != 1.toByte()) throw UnusableCredential()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packet, 1, 12))
            cipher.updateAAD(h.value.encodeToByteArray())
            val plain = cipher.doFinal(packet, 13, packet.size - 13)
            return try {
                Secret(plain.decodeToString(throwOnInvalidSequence = true))
            } finally {
                plain.fill(0)
            }
        } catch (_: AEADBadTagException) {
            throw UnusableCredential()
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw UnusableCredential()
        } catch (e: UnrecoverableKeyException) {
            if (
                generateSequence<Throwable>(e) { it.cause }
                    .any { it is KeyPermanentlyInvalidatedException }
            )
                throw UnusableCredential()
            throw e
        } catch (_: CharacterCodingException) {
            throw UnusableCredential()
        }
    }

    override suspend fun prepareSignIn(handle: SecretHandle): CredentialEdit = access {
        val store = file(handle)
        val original = store.read(262144)
        val value =
            try {
                original?.let { decrypt(handle, it) }
            } catch (_: UnusableCredential) {
                null
            }
        CredentialEdit(value) { replacement ->
            access {
                if (!original.contentEquals(store.read(262144))) false
                else {
                    write(handle, replacement)
                    true
                }
            }
        }
    }

    private fun write(h: SecretHandle, v: Secret) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: KeyPermanentlyInvalidatedException) {
            cipher.init(Cipher.ENCRYPT_MODE, replaceInvalidKey?.invoke() ?: throw e)
        } catch (e: UnrecoverableKeyException) {
            if (
                generateSequence<Throwable>(e) { it.cause }
                    .none { it is KeyPermanentlyInvalidatedException }
            )
                throw e
            cipher.init(Cipher.ENCRYPT_MODE, replaceInvalidKey?.invoke() ?: throw e)
        }
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

    override suspend fun takeForRemoval(handle: SecretHandle): ErasedCredential = access {
        val store = file(handle)
        val packet = store.read(262144)
        store.remove()
        ErasedCredential {
            access { if (packet != null && store.read(262144) == null) store.replace(packet) }
        }
    }

    override suspend fun remove(handle: SecretHandle) {
        access { file(handle).remove() }
    }
}

private fun installKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(vaultAlias, null) as? SecretKey)?.let {
        return it
    }
    return generateKey()
}

private const val vaultAlias = "magichandy.vault.1"

private fun generateKey(): SecretKey {
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(
                vaultAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
    )
    return generator.generateKey()
}
