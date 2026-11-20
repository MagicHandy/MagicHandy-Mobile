package com.magichandy.mobile.core.testing

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.RunJournalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Debug/test storage. Reuse this instance across simulated process restarts. */
public class InMemoryRunJournal(initial: RunJournal? = null) : RunJournalStore {
    private val current = MutableStateFlow(initial)

    override suspend fun read(): RunJournal? = current.value

    override suspend fun armIfEmpty(record: RunJournal): Boolean =
        current.compareAndSet(null, record.copy(devices = record.devices.toList()))

    override suspend fun transition(record: RunJournal): Boolean {
        while (true) {
            val old = current.value ?: return false
            if (
                old.runId != record.runId ||
                    old.devices != record.devices ||
                    old.version != record.version
            )
                return false
            if (current.compareAndSet(old, record.copy(devices = old.devices))) return true
        }
    }

    override suspend fun reconcile(runId: RunId, confirmed: Set<DeviceId>) {
        current.update { old ->
            if (old == null || old.runId != runId) old
            else {
                val remaining = old.devices.filterNot { it.id in confirmed }
                if (remaining.isEmpty()) null else old.copy(devices = remaining)
            }
        }
    }
}
