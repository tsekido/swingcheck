package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SwingAnalyzerTest {
    private val s = SyntheticSwing.S_PX

    private fun analyze(fps: Double = 60.0, noisePx: Double = 0.0): Pair<SyntheticSwing.Result, SwingAnalysis> {
        val r = SyntheticSwing.generate(fps = fps, noisePx = noisePx)
        return r to SwingAnalyzer().analyze(r.sequence)
    }

    private fun SwingAnalysis.v(p: Phase, m: Metric) = metrics[p].getValue(m)

    @Test
    fun referenceLengthIsShoulderWidthAtAddress() {
        val (_, a) = analyze()
        assertEquals(s, a.metrics.referenceLengthPx, s * 0.02)
    }

    @Test
    fun addressValuesAreZeroOrSmall() {
        val (_, a) = analyze()
        assertEquals(0.0, a.v(Phase.P1, Metric.HEAD_SWAY), 1.0)
        assertEquals(0.0, a.v(Phase.P1, Metric.HEAD_LIFT), 1.0)
        assertEquals(0.0, a.v(Phase.P1, Metric.PELVIS_SWAY), 1.0)
        // リード肩のほうが少し高い（+4.6°）
        assertEquals(4.6, a.v(Phase.P1, Metric.SHOULDER_TILT), 1.5)
        // 腰もリード側がやや高い（+3.8°）
        assertEquals(3.8, a.v(Phase.P1, Metric.PELVIS_SIDE_BEND), 1.5)
        // 肩の中心がトレイル側（-x）に傾く → 正（約 5°）
        assertEquals(5.0, a.v(Phase.P1, Metric.SPINE_TILT), 1.5)
        // 膝は軽く曲がっている（約 19°）、腕はほぼ伸びている
        assertTrue(a.v(Phase.P1, Metric.KNEE_FLEX_LEFT) in 10.0..30.0)
        assertTrue(a.v(Phase.P1, Metric.KNEE_FLEX_RIGHT) in 10.0..30.0)
        assertTrue(a.v(Phase.P1, Metric.ELBOW_FLEX_LEFT) < 10.0)
        assertTrue(a.v(Phase.P1, Metric.ELBOW_FLEX_RIGHT) < 10.0)
    }

    @Test
    fun topValuesHaveExpectedSignsAndMagnitudes() {
        val (_, a) = analyze()
        // 頭はトレイル側へ約 0.07 S、骨盤もトレイル側へ約 0.08 S
        assertEquals(-0.07, a.v(Phase.P4, Metric.HEAD_SWAY) / s, 0.04)
        assertEquals(-0.08, a.v(Phase.P4, Metric.PELVIS_SWAY) / s, 0.04)
        // トップでは、リード肩が下がる（約 -23°）
        assertEquals(-22.7, a.v(Phase.P4, Metric.SHOULDER_TILT), 3.0)
        assertTrue(a.v(Phase.P4, Metric.HEAD_LIFT) / s in -0.1..0.15)
    }

    @Test
    fun impactValuesHaveExpectedSignsAndMagnitudes() {
        val (_, a) = analyze()
        // 骨盤は目標方向へ約 0.35 S（≒ 14 cm）
        assertEquals(0.35, a.v(Phase.P7, Metric.PELVIS_SWAY) / s, 0.06)
        // 頭は残る（トレイル側のまま）
        assertTrue(a.v(Phase.P7, Metric.HEAD_SWAY) < 0.0)
        assertEquals(18.0, a.v(Phase.P7, Metric.SHOULDER_TILT), 4.0)
        assertEquals(19.0, a.v(Phase.P7, Metric.SPINE_TILT), 4.0)
        // リード脚はほぼ伸びる
        assertTrue(a.v(Phase.P7, Metric.KNEE_FLEX_LEFT) < 8.0)
    }

    @Test
    fun valuesStayPlausibleWithNoiseAndOtherFps() {
        for (fps in listOf(30.0, 120.0)) {
            val (_, a) = analyze(fps = fps, noisePx = 2.0)
            assertEquals(0.35, a.v(Phase.P7, Metric.PELVIS_SWAY) / s, 0.1)
            assertEquals(-22.7, a.v(Phase.P4, Metric.SHOULDER_TILT), 6.0)
        }
    }

    @Test
    fun everyPhaseHasEveryMetric() {
        val (_, a) = analyze()
        for (p in Phase.entries) assertEquals(Metric.entries.toSet(), a.metrics[p].keys)
    }

    @Test
    fun recomputeAfterManualFixChangesOnlyThatPhase() {
        val (r, a) = analyze()
        val frames = a.phases.frames
        val moved = SwingAnalyzer.recompute(a.smoothed, frames, Phase.P7, frames[Phase.P7.ordinal] - 6)
        assertNotEquals(a.v(Phase.P7, Metric.PELVIS_SWAY), moved[Phase.P7].getValue(Metric.PELVIS_SWAY))
        for (p in Phase.entries.filter { it != Phase.P7 }) {
            for (m in Metric.entries) assertEquals(a.metrics[p].getValue(m), moved[p].getValue(m), 1e-9)
        }
        assertEquals(a.metrics.referenceLengthPx, moved.referenceLengthPx, 1e-9)
        assertTrue(r.sequence.size > 0)
    }

    @Test
    fun recomputeAfterFixingP1ChangesBaseline() {
        val (_, a) = analyze()
        val frames = a.phases.frames
        // P1 を P4 のコマに動かすと、P4 の Sway は 0 になる
        val moved = SwingAnalyzer.recompute(a.smoothed, frames, Phase.P1, frames[Phase.P4.ordinal])
        assertEquals(0.0, moved[Phase.P4].getValue(Metric.HEAD_SWAY), 1e-9)
        assertTrue(abs(moved[Phase.P7].getValue(Metric.PELVIS_SWAY) - a.v(Phase.P7, Metric.PELVIS_SWAY)) > 1.0)
    }

    @Test
    fun centimetersFromHeight() {
        val (_, a) = analyze()
        val sway = a.v(Phase.P7, Metric.PELVIS_SWAY)
        val cm = Units.pxToCm(sway, 175.0, a.metrics.referenceLengthPx)
        // 0.35 S × 0.259 × 175 cm ≒ 15.9 cm
        assertEquals(15.9, cm, 3.0)
    }

    @Test
    fun leftHandedMirrorGivesSameValuesAsRightHanded() {
        val r = SyntheticSwing.generate(fps = 60.0)
        val right = SwingAnalyzer().analyze(r.sequence)
        // 右打ちの骨格を鏡像にして LEFT として解析すると、同じ数値になる
        val mirrored = Handedness.LEFT.toRightHanded(r.sequence) // 反転は自己逆変換
        val left = SwingAnalyzer(handedness = Handedness.LEFT).analyze(mirrored)
        assertEquals(right.phases.frames, left.phases.frames)
        for (m in Metric.entries) {
            assertEquals(right.v(Phase.P7, m), left.v(Phase.P7, m), 1e-2, "$m")
        }
    }
}
