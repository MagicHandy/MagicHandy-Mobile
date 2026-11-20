package com.magichandy.mobile.platform.storage

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.ports.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class FileJournal(
    private val file: AtomicBytes,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RunJournalStore {
    constructor(directory: File) : this(AtomicBytes(File(directory, "active-run.json")))

    private val owner = Mutex()
    private val codec = Json { encodeDefaults = true }

    private fun existing(): RunJournal? {
        val bytes = file.read(128 * 1024) ?: return null
        return try {
            Json.decodeFromString<RunJournal?>(bytes.decodeToString(throwOnInvalidSequence = true))
                .also {
                    if (it != null && it.version != 1)
                        throw IOException("Unsupported journal version")
                }
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid run journal", e)
        }
    }

    private suspend fun <T> access(block: () -> T): T =
        withContext(io) { owner.withLock { block() } }

    private fun replace(record: RunJournal?) {
        file.replace(codec.encodeToString(record).encodeToByteArray())
    }

    override suspend fun read(): RunJournal? = access { existing() }

    override suspend fun armIfEmpty(record: RunJournal): Boolean = access {
        if (record.version != 1 || existing() != null) false
        else {
            replace(record)
            true
        }
    }

    override suspend fun transition(record: RunJournal): Boolean = access {
        val previous = existing()
        if (
            previous == null ||
                previous.runId != record.runId ||
                previous.devices != record.devices ||
                record.version != 1
        )
            false
        else {
            replace(record)
            true
        }
    }

    override suspend fun reconcile(runId: RunId, confirmed: Set<DeviceId>) {
        access {
            val old = existing() ?: return@access
            if (old.runId != runId) return@access
            val remaining = old.devices.filterNot { it.id in confirmed }
            if (remaining.size != old.devices.size)
                replace(if (remaining.isEmpty()) null else old.copy(devices = remaining))
        }
    }
}
