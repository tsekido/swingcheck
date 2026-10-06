package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PoseInterpolationTest {
    private fun frame(ts: Long, x: Float, vis: Float = 1f) =
        PoseFrame(ts, List(Joint.COUNT) { Landmark(x, 0.5f, 0f, vis) })

    private val undetected = { ts: Long -> frame(ts, 0f, 0f) }

    @Test
    fun betweenInterpolatesLinearlyAndTakesMinVisibility() {
        val out = PoseInterpolation.between(frame(0, 0.2f, 0.9f), frame(30, 0.8f, 0.4f), 0.5, 15)
        assertEquals(15L, out.timestampMs)
        assertEquals(0.5f, out.landmarks[0].x, 1e-6f)
        assertEquals(0.4f, out.landmarks[0].visibility, 1e-6f)
    }

    @Test
    fun fillUsesFramePositionsAsWeights() {
        val frames = mutableListOf(frame(0, 0f), frame(10, 9f), frame(20, 9f), frame(30, 9f), frame(40, 4f))
        PoseInterpolation.fill(frames, booleanArrayOf(true, false, false, false, true), undetected)
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f), frames.map { it.landmarks[0].x })
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), frames.map { it.timestampMs })
    }

    @Test
    fun fillMarksInterpolationBesideMissingFrameAsMissing() {
        val frames = mutableListOf(frame(0, 0f), frame(10, 0f), frame(20, 0f, 0f))
        PoseInterpolation.fill(frames, booleanArrayOf(true, false, true), undetected)
        assertEquals(0f, frames[1].landmarks[0].visibility)
    }

    @Test
    fun fillLeavesEdgesBeforeFirstAndAfterLastAsMissing() {
        val frames = mutableListOf(frame(0, 1f), frame(10, 1f), frame(20, 1f), frame(30, 1f))
        PoseInterpolation.fill(frames, booleanArrayOf(false, true, false, false), undetected)
        assertEquals(0f, frames[0].landmarks[0].visibility)
        assertEquals(1f, frames[1].landmarks[0].visibility)
        assertEquals(0f, frames[3].landmarks[0].visibility)
    }
}
