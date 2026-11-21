package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.ports.PreferenceField
import com.magichandy.mobile.core.ports.TilePresence
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * System UI may deliver several observations before Room finishes opening. Only the latest matters.
 */
internal class TileSettings(d: CoreDependencies, failed: () -> Unit) {
    private val tile = Channel<TilePresence>(Channel.CONFLATED)

    fun observe(added: Boolean) {
        tile.trySend(if (added) TilePresence.Added else TilePresence.Removed)
    }

    init {
        d.scope.launch(d.dispatchers.session) {
            for (observed in tile) try {
                var doc = d.settings.state.value
                if (
                    doc.values.stopTile != observed &&
                        !d.settings.patch(
                            doc.revision,
                            listOf(PreferenceField.StopTile.edit(observed)),
                        )
                ) {
                    doc = d.settings.state.value
                    if (doc.values.stopTile != observed)
                        if (
                            !d.settings.patch(
                                doc.revision,
                                listOf(PreferenceField.StopTile.edit(observed)),
                            )
                        )
                            failed()
                }
            } catch (_: IOException) {
                failed()
            }
        }
    }
}
