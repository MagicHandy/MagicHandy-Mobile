package com.magichandy.mobile.platform.storage

import com.magichandy.mobile.core.ports.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RotatingDiagnostics(private val directory: File, private val size: Int = 64 * 1024) :
    DiagnosticsSink {
    private val owner = Mutex()

    override suspend fun record(at: Instant, kind: DiagnosticKind) {
        val line = at.toEpochMilliseconds().toString() + " " + kind.name + "\n"
        withContext(Dispatchers.IO) {
            owner.withLock {
                directory.mkdirs()
                val file = File(directory, "events.log")
                if (file.exists() && file.length() + line.length > size)
                    Files.move(
                        file.toPath(),
                        File(directory, "previous.log").toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                file.appendText(line, Charsets.US_ASCII)
            }
        }
    }
}
