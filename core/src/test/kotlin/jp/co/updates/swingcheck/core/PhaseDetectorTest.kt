package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs

class PhaseDetectorTest {
    /** Pごとに許す誤差（秒）。P1 は静止の終わりを探すので、動き出しの立ち上がり分だけ広く取る。 */
    private val toleranceSec = mapOf(
        Phase.P1 to 0.15, Phase.P2 to 0.07, Phase.P3 to 0.07, Phase.P4 to 0.07,
        Phase.P5 to 0.05, Phase.P6 to 0.05, Phase.P7 to 0.05, Phase.P8 to 0.07,
    )

    private fun detect(r: SyntheticSwing.Result): PhaseDetectionResult {
        val smoothed = Preprocessor.preprocess(r.sequence)
        return PhaseDetector().detect(smoothed)
    }

    private fun describe(r: SyntheticSwing.Result, d: PhaseDetectionResult): String =
        Phase.entries.joinToString { p ->
            "${p}:${d[p].frame}${if (d[p].uncertain) "?" else ""}(truth ${r.truth[p.ordinal]})"
        }

    private fun assertClose(r: SyntheticSwing.Result, d: PhaseDetectionResult, tolScale: Double = 1.0) {
        for (p in Phase.entries) {
            val diffSec = abs(d[p].frame - r.truth[p.ordinal]) / r.fps
            val tol = toleranceSec.getValue(p) * tolScale
            // 1 コマ分の丸めは常に許す
            assertTrue(diffSec <= tol + 1.0 / r.fps, "$p off by ${diffSec}s (tol $tol): ${describe(r, d)}")
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [30.0, 60.0, 120.0, 240.0])
    fun cleanSwingIsDetectedWithinTolerance(fps: Double) {
        val r = SyntheticSwing.generate(fps = fps)
        val d = detect(r)
        assertClose(r, d)
        assertTrue(d.marks.none { it.uncertain }, "no uncertain marks expected: ${describe(r, d)}")
    }

    @ParameterizedTest
    @ValueSource(doubles = [30.0, 60.0, 240.0])
    fun noisySwingIsDetectedWithinTolerance(fps: Double) {
        val r = SyntheticSwing.generate(fps = fps, noisePx = 2.0)
        assertClose(r, detect(r), tolScale = 1.6)
    }

    @Test
    fun missingJointsAreInterpolated() {
        val r = SyntheticSwing.generate(fps = 60.0, noisePx = 1.0, dropouts = true)
        assertClose(r, detect(r), tolScale = 1.6)
    }

    @Test
    fun slowTempoSwingIsDetected() {
        val r = SyntheticSwing.generate(fps = 60.0, tempo = 1.5)
        assertClose(r, detect(r), tolScale = 1.5)
    }

    @Test
    fun phasesAreStrictlyIncreasing() {
        val d = detect(SyntheticSwing.generate(fps = 60.0, noisePx = 3.0, dropouts = true, seed = 7))
        val f = d.frames
        for (i in 1 until f.size) assertTrue(f[i] > f[i - 1], "order broken: $f")
    }

    @Test
    fun finishHigherThanTopDoesNotConfuseTop() {
        // 合成スイングはフィニッシュの手がトップより高い。P4 はトップのままであること
        val r = SyntheticSwing.generate(fps = 60.0)
        val d = detect(r)
        assertTrue(abs(d[Phase.P4].frame - r.truth[3]) <= 4)
    }

    @Test
    fun staticSequenceGivesUncertainButOrderedMarks() {
        val r = SyntheticSwing.generate(fps = 60.0, includeSwing = false)
        val d = detect(r)
        assertTrue(d.marks.any { it.uncertain })
        val f = d.frames
        for (i in 1 until f.size) assertTrue(f[i] >= f[i - 1], "order broken: $f")
        assertTrue(f.all { it in 0 until r.sequence.size })
    }

    @Test
    fun tooShortSequenceFallsBack() {
        val r = SyntheticSwing.generate(fps = 60.0)
        val short = PoseSequence(r.sequence.frames.take(5), r.sequence.width, r.sequence.height)
        val d = PhaseDetector().detect(Preprocessor.preprocess(short))
        assertEquals(8, d.marks.size)
        assertTrue(d.marks.all { it.uncertain })
    }
}
