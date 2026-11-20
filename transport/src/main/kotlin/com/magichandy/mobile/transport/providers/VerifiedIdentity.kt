// Ported from internal/openaiauth/jwt.go at 9a4c36cf.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

internal const val issuer = "https://auth.openai.com"
internal const val resource = "https://api.openai.com/v1"

internal fun verifiedSubject(
    token: Secret,
    clientId: String,
    nonce: String?,
    now: Long,
    jwks: JsonObject,
): String? {
    val raw = token.reveal()
    if (raw.length > 32768) return null
    val parts = raw.split('.')
    if (parts.size != 3) return null
    val decode = Base64.getUrlDecoder()
    fun jsonPart(i: Int) =
        Json.parseToJsonElement(decode.decode(parts[i]).decodeToString()) as? JsonObject
    try {
        val header = jsonPart(0) ?: return null
        if (!validSignature(parts, header, jwks)) return null
        val claims = jsonPart(1) ?: return null
        return claims.subject(clientId, nonce, now)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: GeneralSecurityException) {
        return null
    }
}

private fun validSignature(parts: List<String>, header: JsonObject, jwks: JsonObject): Boolean {
    val decode = Base64.getUrlDecoder()
    val kid = header.string("kid")?.takeIf { it.isNotEmpty() } ?: return false
    if (header.string("alg") != "RS256") return false
    val key =
        (jwks["keys"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {
            it.string("kid") == kid &&
                it.string("kty") == "RSA" &&
                it.string("use") in setOf(null, "sig") &&
                it.string("alg") in setOf(null, "RS256")
        } ?: return false
    val n = BigInteger(1, decode.decode(key.string("n") ?: return false))
    val e = BigInteger(1, decode.decode(key.string("e") ?: return false))
    if (n.bitLength() !in 2048..8192 || e.bitLength() > 31 || e.toLong() < 3 || !e.testBit(0))
        return false
    val signature = Signature.getInstance("SHA256withRSA")
    signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)))
    signature.update((parts[0] + "." + parts[1]).encodeToByteArray())
    return signature.verify(decode.decode(parts[2]))
}

private fun JsonObject.subject(clientId: String, nonce: String?, now: Long): String? {
    if (
        string("iss") != issuer ||
            (number("exp") ?: 0) <= now ||
            (number("iat") ?: 0) !in 1..now + 30 ||
            (number("nbf") ?: 0) > now + 30
    )
        return null
    if (nonce != null && string("nonce") != nonce) return null
    return if (hasAudience(clientId)) string("sub")?.takeIf { it.isNotBlank() } else null
}

private fun JsonObject.hasAudience(clientId: String): Boolean {
    val aud = this["aud"]
    val audiences =
        if (aud is JsonArray) aud.map { (it as? JsonPrimitive)?.content } else listOf(string("aud"))
    val party = string("azp")
    if (
        clientId !in audiences ||
            (audiences.size > 1 && party != clientId) ||
            (party != null && party != clientId)
    )
        return false
    return true
}
