package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal class ScoreTimelineTest {
    @Test
    fun `a model edit preserves submitted points and crosses continuously into the new band`() {
        val timeline = ScoreTimeline(25, HandyModel.Original, PlaybackPosition())
        val first = LayeredScore(10, 70, 25, 60, 60, layers = emptyList())
        val second = LayeredScore(30, 90, 42, 60, 60, layers = emptyList())
        val one = assertNotNull(compileScore(first, HandyModel.Original))
        val two = assertNotNull(compileScore(second, HandyModel.Original))
        assertTrue(timeline.install(first, one, 0, 0))
        val sampler = BufferedSampler(timeline)
        val sent = sampler.nextChunk()
        val cursor = sampler.nextSampleMillis
        val prefix = (0 until cursor).map(timeline::sampleAt)
        assertTrue(timeline.install(second, two, cursor, 0))
        assertEquals(prefix, (0 until cursor).map(timeline::sampleAt))
        val future = buildList { repeat(15) { addAll(sampler.nextChunk()) } }
        assertTrue(future.first().timeMillis > sent.last().timeMillis)
        assertTrue(future.takeLast(50).all { it.position.value in 30..90 })
        assertTrue(
            (sent + future).zipWithNext().all { (a, b) ->
                kotlin.math.abs(a.position.value - b.position.value) * 1000.0 /
                    (b.timeMillis - a.timeMillis) <= HandyModel.Original.travelRate(100) + 1e-7
            }
        )
    }

    @Test
    fun `a late validated score cannot retarget a stopped stream or a replacement run`() = runTest {
        val engine = EngineFixture(this).engine
        val owner = FakeOwner("handy")
        engine.attach(owner)
        val epoch = engine.epoch
        assertTrue(engine.admit(epoch, listOf(owner)))
        val journal = assertNotNull(engine.state.value.journal)
        val score = LayeredScore()
        val req =
            ScoreRequest(
                score,
                MotionSource.Chat,
                assertNotNull(compileScore(score, HandyModel.Original)),
            )
        engine.stop(StopSource.Notification)
        assertFalse(engine.selectScore(epoch, journal, req))
        assertEquals(null, engine.state.value.score)
    }
}
