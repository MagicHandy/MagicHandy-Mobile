package com.magichandy.mobile.transport.http

import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.ports.ReleaseFeed
import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.Request

/** Reads the mobile repository's public stable release. Never downloads or installs an APK. */
public class MobileReleases(private val http: okhttp3.Call.Factory) : ReleaseFeed {
    override suspend fun check(installed: String): UpdateCheck {
        val req =
            Request.Builder()
                .url("https://api.github.com/repos/MagicHandy/MagicHandy-Mobile/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        return try {
            http.exchange(req) { res ->
                if (!res.isSuccessful) return@exchange UpdateCheck.Failed
                val release = Json.parseToJsonElement(res.boundedText(256 * 1024)).jsonObject
                val tag =
                    release["tag_name"]?.jsonPrimitive?.content
                        ?: return@exchange UpdateCheck.Failed
                val next = version(tag) ?: return@exchange UpdateCheck.Failed
                val current = version(installed) ?: return@exchange UpdateCheck.Failed
                val diff = next.zip(current).firstOrNull { (a, b) -> a != b }
                if (diff != null && diff.first > diff.second) UpdateCheck.Available(tag)
                else UpdateCheck.UpToDate
            }
        } catch (_: IOException) {
            UpdateCheck.Failed
        } catch (_: IllegalStateException) {
            UpdateCheck.Failed
        } catch (_: IllegalArgumentException) {
            UpdateCheck.Failed
        }
    }

    private fun version(raw: String): List<Int>? {
        val match = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(raw) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
}
