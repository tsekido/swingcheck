package jp.co.updates.swingcheck.analysis

import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Metric
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.core.PixelPose
import jp.co.updates.swingcheck.core.PixelSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PhaseCorrectionTest {
    /**
     * コマ i で、頭の x が 10 i、肩幅が 100 + i、腰の中心の x が 5 i になる骨格列。
     * これで「どのコマを P1 にするか」「どのコマを直すか」が数値に出る。
     */
    private fun sequence(n: Int = 30): PixelSequence {
        val poses = List(n) { i ->
            val xs = DoubleArray(Joint.COUNT)
            val ys = DoubleArray(Joint.COUNT) { 500.0 }
            xs[Joint.NOSE] = 10.0 * i
            xs[Joint.LEFT_EAR] = 10.0 * i
            xs[Joint.RIGHT_EAR] = 10.0 * i
            xs[Joint.LEFT_SHOULDER] = 100.0 + i
            xs[Joint.RIGHT_SHOULDER] = 0.0
            xs[Joint.LEFT_HIP] = 5.0 * i + 10
            xs[Joint.RIGHT_HIP] = 5.0 * i - 10
            PixelPose(i * 10L, xs, ys, DoubleArray(Joint.COUNT) { 1.0 })
        }
        return PixelSequence(poses, 1080, 1920)
    }

    private val frames = listOf(2, 4, 6, 8, 10, 12, 14, 16)

    @Test
    fun correctingOnePhaseChangesOnlyThatPhase() {
        val r = PhaseCorrection.apply(sequence(), frames, Phase.P4, 20)
        assertEquals(listOf(2, 4, 6, 20, 10, 12, 14, 16), r.frames)
        assertEquals(setOf(Phase.P4), r.metrics.keys)
        // 頭の横移動 = head(20).x − head(P1 = コマ 2).x
        assertEquals(10.0 * 20 - 10.0 * 2, r.metrics.getValue(Phase.P4).getValue(Metric.HEAD_SWAY), 1e-9)
        assertEquals(5.0 * 20 - 5.0 * 2, r.metrics.getValue(Phase.P4).getValue(Metric.PELVIS_SWAY), 1e-9)
        assertEquals(102.0, r.referenceLengthPx, 1e-9) // S は P1（コマ 2）の肩幅のまま
    }

    @Test
    fun correctingP1ChangesEveryPhaseAndReference() {
        val r = PhaseCorrection.apply(sequence(), frames, Phase.P1, 0)
        assertEquals(Phase.entries.toSet(), r.metrics.keys)
        assertEquals(100.0, r.referenceLengthPx, 1e-9)
        // P5（コマ 10）の頭の横移動は、新しい P1（コマ 0）が基準になる
        assertEquals(100.0, r.metrics.getValue(Phase.P5).getValue(Metric.HEAD_SWAY), 1e-9)
        assertEquals(0.0, r.metrics.getValue(Phase.P1).getValue(Metric.HEAD_SWAY), 1e-9)
    }

    @Test
    fun resettingToAutoIsTheSameAsCorrectingToTheAutoFrame() {
        val seq = sequence()
        val manual = PhaseCorrection.apply(seq, frames, Phase.P6, 25)
        val back = PhaseCorrection.apply(seq, manual.frames, Phase.P6, frames[Phase.P6.ordinal])
        assertEquals(frames, back.frames)
        val original = PhaseCorrection.apply(seq, frames, Phase.P6, frames[Phase.P6.ordinal])
        assertEquals(original.metrics, back.metrics)
    }

    @Test
    fun rejectsFrameOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { PhaseCorrection.apply(sequence(30), frames, Phase.P3, 30) }
        assertThrows(IllegalArgumentException::class.java) { PhaseCorrection.apply(sequence(30), frames, Phase.P3, -1) }
    }
}
