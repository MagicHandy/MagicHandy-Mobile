package com.magichandy.mobile.platform.storage

import com.magichandy.mobile.core.ports.DiagnosticEvent
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticsSink
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

    override suspend fun recent(limit: Int): List<DiagnosticEvent> =
        withContext(Dispatchers.IO) {
            owner.withLock {
                val maximum = limit.coerceIn(0, 2048)
                if (maximum == 0) return@withLock emptyList()
                val recent = ArrayDeque<DiagnosticEvent>(maximum)
                for (name in listOf("previous.log", "events.log")) {
                    val file = File(directory, name)
                    if (!file.isFile) continue
                    java.io.RandomAccessFile(file, "r").use { input ->
                        val start = (input.length() - 65536).coerceAtLeast(0)
                        input.seek(start)
                        if (start > 0) input.readLine()
                        while (input.filePointer < input.length()) {
                            val text = input.readLine() ?: break
                            val split = text.indexOf(' ')
                            if (split !in 1..20) continue
                            val time = text.substring(0, split).toLongOrNull() ?: continue
                            val kind =
                                DiagnosticKind.entries.firstOrNull {
                                    it.name == text.substring(split + 1)
                                } ?: continue
                            if (recent.size == maximum) recent.removeFirst()
                            recent.addLast(
                                DiagnosticEvent(Instant.fromEpochMilliseconds(time), kind)
                            )
                        }
                    }
                }
                recent.toList()
            }
        }

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
