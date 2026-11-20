package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Percent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ManualTimelineTest {
    @Test
    fun `edits at one unsubmitted cursor preserve the accepted path and take the last requested speed`() {
        val unchanged = ManualTimeline(25, HandyModel.Standard, PlaybackPosition(0.37))
        val edited = ManualTimeline(25, HandyModel.Standard, PlaybackPosition(0.37))
        val lastEdit = ManualTimeline(25, HandyModel.Standard, PlaybackPosition(0.37))
        val cutoff = 1841L
        for (pace in listOf(15, 20, 10)) edited.retarget(pace, cutoff, 450)
        lastEdit.retarget(10, cutoff, 450)
        val turn = unchanged.knotTimes(cutoff, cutoff + 10_000).min()
        for (at in (0L until turn step 7)) assertEquals(unchanged.sampleAt(at), edited.sampleAt(at))
        for (at in (turn..turn + 10_000 step 7)) assertEquals(
            lastEdit.sampleAt(at),
            edited.sampleAt(at),
        )
        assertTrue(
            kotlin.math.abs(unchanged.sampleAt(turn).position - edited.sampleAt(turn).position) <
                0.01
        )
    }

    @Test
    fun `linked calibration is chosen from participating Handy models independently of order`() {
        assertEquals(HandyModel.Original, HandyModel.calibration(emptySet()))
        assertEquals(
            HandyModel.Pro,
            HandyModel.calibration(listOf("handy_original", "handy_2_pro")),
        )
        val all = listOf("handy_original", "handy_2_standard", "handy_2_pro")
        assertEquals(HandyModel.Standard, HandyModel.calibration(all))
        assertEquals(HandyModel.Standard, HandyModel.calibration(all.reversed()))
    }

    @Test
    fun `frame ordering rejects a repeated or backwards timestamp including the final pair`() {
        fun frames(vararg times: Long) = times.map { MotionFrame(it, Percent(50)) }
        assertTrue(frames(0, 1, 200, Long.MAX_VALUE).hasIncreasingTimes())
        assertFalse(frames(10, 20, 20).hasIncreasingTimes())
        assertFalse(frames(20, 10).hasIncreasingTimes())
    }
}
