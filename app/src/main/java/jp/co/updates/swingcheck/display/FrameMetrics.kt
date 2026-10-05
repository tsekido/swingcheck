package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.analysis.MetricsJson
import jp.co.updates.swingcheck.core.Derived
import jp.co.updates.swingcheck.core.MetricValues
import jp.co.updates.swingcheck.core.MetricsCalculator
import jp.co.updates.swingcheck.core.PixelSequence
import jp.co.updates.swingcheck.core.ShaftPhase
import jp.co.updates.swingcheck.data.PositionMarkEntity

/** 表示中のコマの数値。[phase] はそのコマがPのコマのとき（1〜8）、それ以外は null。 */
class FrameMetrics(val phase: Int?, val values: MetricValues, val referenceLengthPx: Double)

object FrameMetricsResolver {
    /**
     * Pのコマなら保存してある数値（metricsJson）、それ以外のコマは P1 のコマを基準に :core で計算した値。
     * 保存した値が読めないとき（壊れている）も計算した値にする。P1 の行が無ければ null。
     */
    fun resolve(frame: Int, marks: List<PositionMarkEntity>, smoothed: PixelSequence): FrameMetrics? {
        if (frame !in 0 until smoothed.size) return null
        val phase = PhaseButtonState.phaseAtFrame(marks, frame)
        if (phase != null) {
            val stored = runCatching { MetricsJson.decode(marks.first { it.position == phase }.metricsJson) }.getOrNull()
            if (stored != null) return FrameMetrics(phase, stored.values, stored.referenceLengthPx)
        }
        val p1 = marks.firstOrNull { it.position == 1 }?.frame?.takeIf { it in 0 until smoothed.size } ?: return null
        val baseline = smoothed.poses[p1]
        val values = MetricsCalculator.compute(smoothed.poses[frame], baseline)
        return FrameMetrics(phase, values, Derived.shoulderWidth(baseline))
    }
}

object ShaftPhases {
    /** P8 のコマ以降（フォロー）はトレイル側の前腕、それ以前はリード側の前腕（design.md 6章）。 */
    fun at(frame: Int, marks: List<PositionMarkEntity>): ShaftPhase {
        val p8 = marks.firstOrNull { it.position == 8 }?.frame ?: return ShaftPhase.ADDRESS_TO_DOWNSWING
        return if (frame >= p8) ShaftPhase.FOLLOW_THROUGH else ShaftPhase.ADDRESS_TO_DOWNSWING
    }
}
