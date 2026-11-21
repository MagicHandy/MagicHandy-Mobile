package com.magichandy.mobile.core.fixtures

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun fixtureBytes(path: String): ByteArray =
    requireNotNull(PortFixturesTest::class.java.getResourceAsStream("/" + path)) { path }
        .use { it.readBytes() }

internal fun fixtureJson(path: String) =
    Json.parseToJsonElement(fixtureBytes(path).decodeToString())

internal class PortFixturesTest {
    @Test
    fun `the fixture manifest identifies unique baseline cases by snapshot and SHA256`() {
        val manifest = fixtureJson("manifest.json").jsonObject
        assertEquals(
            "1b80d72ee4b90a5ab80a9bb3fe373b1640388f80",
            manifest.getValue("source_sha").jsonPrimitive.content,
        )
        val cases = manifest.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(cases.size, cases.distinctBy { it["case_id"] }.size)
        assertEquals(cases.size, cases.distinctBy { it["path"] }.size)
        for (case in cases) {
            val path = case.getValue("path").jsonPrimitive.content
            val digest =
                MessageDigest.getInstance("SHA-256").digest(fixtureBytes(path)).toHexString()
            assertEquals(case.getValue("sha256").jsonPrimitive.content, digest, path)
        }
        val roles = cases.groupingBy { it.getValue("role").jsonPrimitive.content }.eachCount()
        assertEquals(
            mapOf(
                "motion-atlas" to 21,
                "creative-atlas" to 135,
                "layered-score" to 3,
                "tempo" to 6,
                "ble-wire" to 10,
                "cloud-wire" to 5,
                "chat" to 3,
                "local-history" to 4,
                "persona-request" to 70,
                "translations" to 5,
                "content" to 3,
                "brand" to 2,
            ),
            roles,
        )
    }

    @Test
    fun `Terra fixtures contain the plan request with strict schema and low reasoning`() {
        val cases = fixtureJson("manifest.json").jsonObject.getValue("cases").jsonArray
        for (case in
            cases.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "chat" }) {
            val request = fixtureJson(case.getValue("path").jsonPrimitive.content).jsonObject
            assertEquals("gpt-6.1-terra", request["model"]?.jsonPrimitive?.content)
            assertEquals(
                "low",
                request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
            )
            assertEquals(
                "developer",
                request
                    .getValue("input")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("role")
                    .jsonPrimitive
                    .content,
            )
            val format = request.getValue("text").jsonObject.getValue("format").jsonObject
            assertEquals("true", format.getValue("strict").jsonPrimitive.content)
            assertNotNull(format["schema"])
            assertTrue("max_output_tokens" !in request)
        }
    }
}
