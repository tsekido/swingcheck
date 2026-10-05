package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.analysis.MetricsJson
import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Metric
import jp.co.updates.swingcheck.core.PixelPose
import jp.co.updates.swingcheck.core.PixelSequence
import jp.co.updates.swingcheck.core.ShaftPhase
import jp.co.updates.swingcheck.data.PositionMarkEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrameMetricsTest {
    /** 全関節が (x, 100) にあり、両肩だけ幅 100 の人形。コマごとに全体を右へ [shift] ずらす。 */
    private fun pose(i: Int, shift: Double): PixelPose {
        val xs = DoubleArray(Joint.COUNT) { 500.0 + shift }
        val ys = DoubleArray(Joint.COUNT) { 100.0 }
        xs[Joint.LEFT_SHOULDER] = 550.0 + shift
        xs[Joint.RIGHT_SHOULDER] = 450.0 + shift
        return PixelPose(i * 4L, xs, ys, DoubleArray(Joint.COUNT) { 1.0 })
    }

    private val seq = PixelSequence(List(5) { pose(it, it * 10.0) }, 1000, 1000)

    private fun mark(pos: Int, frame: Int, json: String = "{}") = PositionMarkEntity(1, pos, frame, null, false, json)

    @Test
    fun phaseFrameUsesStoredMetrics() {
        val json = MetricsJson.encode(mapOf(Metric.HEAD_SWAY to 123.0), 77.0)
        val marks = listOf(mark(1, 0), mark(2, 3, json))
        val fm = FrameMetricsResolver.resolve(3, marks, seq)!!
        assertEquals(2, fm.phase)
        assertEquals(123.0, fm.values.getValue(Metric.HEAD_SWAY), 0.0)
        assertEquals(77.0, fm.referenceLengthPx, 0.0)
    }

    @Test
    fun otherFramesAreComputedRelativeToP1() {
        val marks = listOf(mark(1, 1), mark(2, 3))
        val fm = FrameMetricsResolver.resolve(4, marks, seq)!!
        assertNull(fm.phase)
        assertEquals(30.0, fm.values.getValue(Metric.HEAD_SWAY), 1e-9) // P1（コマ 1）から 30px 右
        assertEquals(30.0, fm.values.getValue(Metric.PELVIS_SWAY), 1e-9)
        assertEquals(100.0, fm.referenceLengthPx, 1e-9)
    }

    @Test
    fun brokenStoredJsonFallsBackToComputation() {
        val marks = listOf(mark(1, 0), mark(2, 3, "not json"))
        val fm = FrameMetricsResolver.resolve(3, marks, seq)!!
        assertEquals(2, fm.phase)
        assertEquals(30.0, fm.values.getValue(Metric.HEAD_SWAY), 1e-9)
    }

    @Test
    fun unavailableCases() {
        assertNull(FrameMetricsResolver.resolve(0, emptyList(), seq))
        assertNull(FrameMetricsResolver.resolve(99, listOf(mark(1, 0)), seq))
        assertNotNull(FrameMetricsResolver.resolve(2, listOf(mark(1, 0)), seq))
    }

    @Test
    fun shaftSwitchesToTrailArmFromP8() {
        val marks = listOf(mark(1, 0), mark(8, 50))
        assertEquals(ShaftPhase.ADDRESS_TO_DOWNSWING, ShaftPhases.at(49, marks))
        assertEquals(ShaftPhase.FOLLOW_THROUGH, ShaftPhases.at(50, marks))
        assertEquals(ShaftPhase.ADDRESS_TO_DOWNSWING, ShaftPhases.at(60, listOf(mark(1, 0))))
    }
}
