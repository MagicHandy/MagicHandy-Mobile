package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.PhraseGroup
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class HandySamplingPolicyTest {
    private val cloud =
        DeviceRef(DeviceId("cloud"), "Fixture", DeviceFamily.Handy, ConnectionKind.HandyCloud)
    private val ble = cloud.copy(id = DeviceId("radio"), connection = ConnectionKind.Bluetooth)
    private val full =
        LayeredScore(
            0,
            100,
            25,
            100,
            100,
            anchor = 0,
            memory = 8,
            paceVariation = 0,
            drift = false,
            cycles = 6,
            layers = emptyList(),
        )

    @Test
    fun `twelve seconds of HSP positions and timestamps match the route-specific fixtures`() {
        // SHA-256 of the reviewed route output, one decimal "time,position" line per
        // point.
        fun verify(
            model: HandyModel,
            devices: List<DeviceRef>,
            score: LayeredScore?,
            wanted: Int,
            sha: String,
        ) {
            val timeline = ScoreTimeline(score?.speed ?: 10, model, PlaybackPosition())
            score?.let {
                assertTrue(timeline.install(it, assertNotNull(compileScore(it, model)), 0, 0))
            }
            val sampler = timeline.bufferedSampler(devices)
            val points = buildList {
                while (sampler.nextSampleMillis < 12000) addAll(sampler.nextChunk())
            }
                .filter { it.timeMillis < 12000 }
            assertEquals(wanted, points.size)
            val bytes =
                points
                    .joinToString("") { "${it.timeMillis},${it.position.value}\n" }
                    .encodeToByteArray()
            assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
            assertTrue(points.map { it.timeMillis }.containsAll(timeline.knotTimes(0, 12000)))
        }
        verify(
            HandyModel.Original,
            listOf(cloud),
            null,
            36,
            "8b61202436c0f15b2fd3273bdf90108ed3952e1761232a8f4d41fa0bf35d2fa3",
        )
        verify(
            HandyModel.Original,
            listOf(cloud),
            full,
            56,
            "430921598646f099b3e5bc5c07b18920cdbf7840a36f57f4242a7c73944f87bb",
        )
        // The single Original BLE route keeps sparse mid-strokes and rounds the two sides of a
        // turn.
        verify(
            HandyModel.Original,
            listOf(ble),
            null,
            51,
            "4063cb3e9bc9d28ecf5b7f49a5726282a0b2ec0def3f8c89f1d8ba95ec8da0f8",
        )
        verify(
            HandyModel.Original,
            listOf(ble),
            full,
            83,
            "56752fd7aecc2fe00210f0eb04fac388837c9c2709154005ae0d061ac73ac0c8",
        )
        verify(
            HandyModel.Original,
            listOf(cloud, ble),
            full,
            149,
            "2446f47105ea36369f9e92c540640f2fa0d6a84a8c3efb6a7d9d2a5f77c2f301",
        )
        verify(
            HandyModel.Standard,
            listOf(cloud),
            full,
            121,
            "ff800092eea93dfd6d55e97e2f6e34a94161769c16bfd9a2610be0cd9f83e2f5",
        )
        verify(
            HandyModel.Pro,
            listOf(cloud),
            full,
            131,
            "eecf94d5ac2f72e215e81a89d8f9106248a2dbbb49ee8ba863d23e506821a61d",
        )
        val compound =
            full.copy(
                speed = 100,
                floor = 28,
                groups = listOf(PhraseGroup(72, 100, 2, 25), PhraseGroup(0, 100, 1, 25)),
            )
        verify(
            HandyModel.Original,
            listOf(cloud),
            compound,
            58,
            "05e3108db21a399fb3bbdc93ad54de02deca3b4e68ef4af7fe629766bf88df7c",
        )
    }

    @Test
    fun `long Original Bluetooth replay retains every reversal with precise tip velocity and fewer points`() {
        val timeline = ScoreTimeline(25, HandyModel.Original, PlaybackPosition())
        val end = 715000L
        fun capture(devices: List<DeviceRef>): List<MotionFrame> {
            val sampler = timeline.bufferedSampler(devices)
            return buildList {
                while (sampler.nextSampleMillis < end) addAll(sampler.nextChunk())
            }
                .filter { it.timeMillis < end }
        }
        val mobile = capture(listOf(ble))
        val precise = capture(listOf(ble, cloud))
        val sparse = capture(listOf(cloud))
        fun turns(frames: List<MotionFrame>) =
            frames.windowed(3).mapNotNull { (a, b, c) ->
                val before =
                    1000.0 * (b.position.value - a.position.value) / (b.timeMillis - a.timeMillis)
                val after =
                    1000.0 * (c.position.value - b.position.value) / (c.timeMillis - b.timeMillis)
                if (before * after < 0) b.timeMillis to abs(after - before) else null
            }
        val reversals = turns(mobile)
        val reference = turns(precise)
        assertEquals(787, reversals.size)
        assertEquals(reference.map { it.first }, reversals.map { it.first })
        assertTrue(
            mobile.zipWithNext().all { (a, b) ->
                b.timeMillis > a.timeMillis && b.position != a.position
            }
        )
        val mobileVelocity = reversals.map { it.second }.sorted()[reversals.size / 2]
        val desktopVelocity = reference.map { it.second }.sorted()[reference.size / 2]
        assertEquals(desktopVelocity, mobileVelocity, 0.1)
        assertTrue(mobileVelocity in 34.0..35.0)
        assertTrue(mobile.size in sparse.size + 1..precise.size * 6 / 10)
        assertEquals(83, mobile.count { it.timeMillis < 12000 })
        assertEquals(149, precise.count { it.timeMillis < 12000 })
        assertEquals(56, sparse.count { it.timeMillis < 12000 })
    }

    @Test
    fun `a change of band inherits acceleration at both joins and uses precise fitting through the entry stroke`() {
        val t = ScoreTimeline(25, HandyModel.Original, PlaybackPosition())
        assertTrue(t.install(full, assertNotNull(compileScore(full, HandyModel.Original)), 0, 0))
        val s = t.bufferedSampler(listOf(cloud))
        s.nextChunk()
        val prefix = (0L until s.nextSampleMillis step 9).map(t::sampleAt)
        val next = full.copy(low = 25, high = 75, floor = 50, ceiling = 50, speed = 35)
        assertTrue(
            t.install(
                next,
                assertNotNull(compileScore(next, HandyModel.Original)),
                s.nextSampleMillis,
                100,
            )
        )
        assertEquals(prefix, (0L until s.nextSampleMillis step 9).map(t::sampleAt))
        val sections = requireNotNull(t.positionAt(0).frozen).sections
        val bridge = sections.single { it.path is TurnBridge }
        val after = sections.last()
        val start = bridge.start
        val end = after.start
        assertEquals(precisePositionTolerance, t.sparsePositionTolerance(start - 5, start + 5))
        assertEquals(precisePositionTolerance, t.sparsePositionTolerance(start + 1, end))
        for ((one, two) in sections.zipWithNext()) {
            val left = one.path.stateAt(two.start + one.offset)
            val right = two.path.stateAt(two.start + two.offset)
            assertEquals(left.position, right.position, 1e-6)
            assertEquals(left.velocity, right.velocity, 1e-7)
            assertEquals(left.acceleration, right.acceleration, 1e-8)
            assertTrue(abs(right.acceleration) > 1e-7)
        }
    }

    @Test
    fun `a turn rounding up onto the first millisecond of a bounded window is retained`() {
        val plan =
            MotionPlan(
                listOf(CurvePoint(0, 0.0), CurvePoint(238, 100.0), CurvePoint(1000, 0.0)),
                25,
                HandyModel.Original,
            )
        for (t in plan.knotTimes(0, plan.periodMillis * 3)) assertTrue(
            t in plan.knotTimes(t, t + 1)
        )
    }
}
