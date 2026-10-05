package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.core.Metric
import jp.co.updates.swingcheck.core.MetricKind
import jp.co.updates.swingcheck.core.MetricValues
import jp.co.updates.swingcheck.core.Units
import jp.co.updates.swingcheck.settings.LengthUnit

/**
 * 数値の一覧の 1 行分。LENGTH は肩幅に対する % と（身長があれば）設定の単位での長さ、ANGLE は度。
 * 基準の長さ S が求められないときは、LENGTH の percent と length は null。
 */
data class MetricRow(
    val metric: Metric,
    /** LENGTH はピクセル、ANGLE は度（保存されている値そのまま） */
    val raw: Double,
    /** 「肩幅の◯%」（LENGTH のみ） */
    val percentOfShoulderWidth: Double?,
    /** 「約◯cm」または「約◯in」（LENGTH で、身長が入力されているときだけ。単位は [unit]） */
    val length: Double?,
    val unit: LengthUnit,
)

object MetricRows {
    /** [Metric] の定義順に並べる。値が無い項目は出さない。 */
    fun build(
        values: MetricValues,
        referenceLengthPx: Double,
        heightCm: Float?,
        unit: LengthUnit,
    ): List<MetricRow> {
        val hasReference = referenceLengthPx.isFinite() && referenceLengthPx > 0
        return Metric.entries.mapNotNull { m ->
            val v = values[m]?.takeIf { it.isFinite() } ?: return@mapNotNull null
            when (m.kind) {
                MetricKind.ANGLE -> MetricRow(m, v, null, null, unit)
                MetricKind.LENGTH -> {
                    val percent = if (hasReference) Units.toPercentOfShoulderWidth(v, referenceLengthPx) else null
                    val cm = if (hasReference && heightCm != null && heightCm > 0) {
                        Units.pxToCm(v, heightCm.toDouble(), referenceLengthPx)
                    } else {
                        null
                    }
                    val length = when (unit) {
                        LengthUnit.CM -> cm
                        LengthUnit.INCH -> cm?.let(Units::cmToInch)
                    }
                    MetricRow(m, v, percent, length, unit)
                }
            }
        }
    }
}
