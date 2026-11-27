package com.magichandy.mobile.voice

import com.magichandy.mobile.core.api.isSpokenStop

/**
 * Consumes one bounded native decode. Intermediate slices stay private until the whole turn ends.
 */
internal class VoiceDecoder(
    private val queue: RecognizerQueue,
    private val transcribe: (FloatArray) -> String,
    private val stop: (lastSpeechSample: Long) -> Unit,
    private val turn: (String) -> Unit,
) {
    fun decode(job: RecognizerJob) {
        when (job) {
            is RecognizerJob.StopCheck ->
                if (isSpokenStop(transcribe(job.samples))) stop(job.lastSpeechSample)
            is RecognizerJob.Turn -> {
                job.append(transcribe(job.nextAudio()))
                if (!job.finished) {
                    queue.continueTurn(job)
                    return
                }
                val text = job.text()
                if (isSpokenStop(text)) stop(job.utterance.end)
                else if (text.isNotBlank()) turn(text)
            }
        }
    }
}
