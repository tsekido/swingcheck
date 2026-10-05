package jp.co.updates.swingcheck.analysis

import jp.co.updates.swingcheck.core.MetricValues
import jp.co.updates.swingcheck.core.PixelSequence
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.core.SwingAnalyzer

/** Pを手で直したとき（または自動に戻したとき）の数値の計算し直し。 */
object PhaseCorrection {
    class Result(
        /** 直したあとの P1〜P8 のコマ */
        val frames: List<Int>,
        /** 数値が変わるP → 新しい数値。P1 を直したときは基準（Sway と Lift の 0、S）が変わるので全P */
        val metrics: Map<Phase, MetricValues>,
        val referenceLengthPx: Double,
    )

    /**
     * @param frames 直す前の P1〜P8 のコマ（手動があればそれを使ったもの）
     * @param newFrame 直したあとの [phase] のコマ。自動に戻すときは自動判定のコマを渡す
     */
    fun apply(smoothed: PixelSequence, frames: List<Int>, phase: Phase, newFrame: Int): Result {
        require(newFrame in 0 until smoothed.size) { "frame out of range: $newFrame" }
        val updated = frames.toMutableList().also { it[phase.ordinal] = newFrame }
        val all = SwingAnalyzer.computeMetrics(smoothed, updated)
        val changed = if (phase == Phase.P1) Phase.entries.toList() else listOf(phase)
        return Result(updated, changed.associateWith { all[it] }, all.referenceLengthPx)
    }
}
