package jp.co.updates.swingcheck.video

import jp.co.updates.swingcheck.analysis.AnalysisTime
import jp.co.updates.swingcheck.core.Landmark
import jp.co.updates.swingcheck.core.PoseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportPolicyTest {
    private fun sec(s: Double) = (s * 1_000_000).toLong()

    @Test
    fun realTimeSlowMotionIsAcceptedWithoutCorrection() {
        // 240fps の実時間のタイムスタンプ（Zenfone 9 の標準カメラ）。capture.fps も 240
        val v = ImportPolicy.judge(240f, 240f, sec(3.0))
        assertEquals(ImportVerdict.Accept(1f), v)
        assertFalse((v as ImportVerdict.Accept).stretched)
    }

    @Test
    fun normalVideoWithoutCaptureFpsIsAccepted() {
        assertEquals(ImportVerdict.Accept(1f), ImportPolicy.judge(30f, null, sec(5.0)))
    }

    @Test
    fun stretchedSlowMotionIsCorrectedToCaptureFps() {
        val v = ImportPolicy.judge(30f, 240f, sec(8.0)) as ImportVerdict.Accept
        assertEquals(8f, v.timeScale, 1e-6f)
        assertTrue(v.stretched)
    }

    @Test
    fun ratioBelowThresholdIsNotCorrected() {
        assertEquals(1f, ImportPolicy.timeScale(30f, 44f))
        assertEquals(1f, ImportPolicy.timeScale(30f, 29.97f)) // ふつうの動画の誤差
        assertEquals(1.5f, ImportPolicy.timeScale(30f, 45f)) // ちょうど 1.5 倍は補正する
    }

    @Test
    fun captureFpsLowerThanMeasuredIsIgnored() {
        assertEquals(1f, ImportPolicy.timeScale(240f, 30f))
    }

    @Test
    fun invalidFpsValuesAreIgnored() {
        assertEquals(1f, ImportPolicy.timeScale(0f, 240f))
        assertEquals(1f, ImportPolicy.timeScale(30f, Float.NaN))
        assertEquals(1f, ImportPolicy.timeScale(30f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun tooLongVideoIsRejected() {
        val v = ImportPolicy.judge(30f, null, sec(10.5))
        assertTrue(v is ImportVerdict.TooLong)
        assertEquals(10.5, (v as ImportVerdict.TooLong).durationSec, 1e-9)
    }

    @Test
    fun durationLimitIsInclusive() {
        assertTrue(ImportPolicy.judge(30f, null, sec(ImportPolicy.MAX_DURATION_SEC)) is ImportVerdict.Accept)
    }

    @Test
    fun durationIsMeasuredInRealTimeAfterCorrection() {
        // 引き延ばされて 40 秒に見えても、実時間は 5 秒なので読み込める
        assertTrue(ImportPolicy.judge(30f, 240f, sec(40.0)) is ImportVerdict.Accept)
        // 実時間でも 12.5 秒なら長すぎる
        val v = ImportPolicy.judge(30f, 240f, sec(100.0))
        assertEquals(12.5, (v as ImportVerdict.TooLong).durationSec, 1e-9)
    }

    @Test
    fun toRealMsDividesByScale() {
        assertEquals(1000L, TimeScale.toRealMs(1000, 1f))
        assertEquals(125L, TimeScale.toRealMs(1000, 8f))
        assertEquals(4L, TimeScale.toRealMs(33, 8f)) // 4.125 を丸める
    }

    private fun frame(ms: Long) = PoseFrame(ms, List(33) { Landmark(0f, 0f, 0f, 1f) })

    @Test
    fun analysisTimeKeepsFramesWhenNoCorrection() {
        val frames = listOf(frame(0), frame(33))
        assertSame(frames, AnalysisTime.toRealTime(frames, 1f))
    }

    @Test
    fun analysisTimeScalesAndStaysIncreasing() {
        // 30fps に引き延ばされた 240fps（コマ間隔 33.3ms → 実時間 4.17ms）
        val frames = (0 until 30).map { frame((it * 1000L) / 30) }
        val scaled = AnalysisTime.toRealTime(frames, 8f).map { it.timestampMs }
        assertEquals(0L, scaled.first())
        assertTrue(scaled.zipWithNext().all { (a, b) -> b > a })
        // 29 コマ分（約 0.966 秒）が、約 121ms になる
        assertEquals(121L, scaled.last())
        // 元のコマの時刻は変えない
        assertEquals(966L, frames.last().timestampMs)
    }

    @Test
    fun analysisTimeFixesDuplicatesFromRounding() {
        // 補正後の間隔が 1ms 未満になっても、時刻は増え続ける
        val frames = (0 until 5).map { frame(it * 2L) }
        val scaled = AnalysisTime.toRealTime(frames, 8f).map { it.timestampMs }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), scaled)
    }
}
