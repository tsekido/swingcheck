package jp.co.updates.swingcheck.analysis

import jp.co.updates.swingcheck.core.Landmark
import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.core.PixelPose
import jp.co.updates.swingcheck.core.PixelSequence
import jp.co.updates.swingcheck.core.PoseFrame
import jp.co.updates.swingcheck.core.SwingAnalysis
import jp.co.updates.swingcheck.data.PositionMarkEntity

/** :core の解析結果と、保存する形（position_mark、ボール判定の入力）との変換。 */
object AnalysisMapper {
    /** P1〜P8 の 8 行。manualFrame は常に null（再解析で手動修正を引き継ぐかどうかは別途決める）。 */
    fun toMarks(swingId: Long, analysis: SwingAnalysis): List<PositionMarkEntity> =
        Phase.entries.map { p ->
            val mark = analysis.phases[p]
            PositionMarkEntity(
                swingId = swingId,
                position = p.number,
                autoFrame = mark.frame,
                manualFrame = null,
                autoUncertain = mark.uncertain,
                metricsJson = MetricsJson.encode(analysis.metrics[p], analysis.metrics.referenceLengthPx),
            )
        }

    /**
     * 平滑化後のコマを、正規化座標（0〜1）の PoseFrame に戻す。ボール判定の P1 の骨格に使う
     * （欠損を補間した後の座標を使うため）。z は使わないので 0。
     */
    fun toPoseFrame(pose: PixelPose, sequence: PixelSequence): PoseFrame =
        PoseFrame(
            pose.timestampMs,
            List(Joint.COUNT) { j ->
                Landmark(
                    (pose.xs[j] / sequence.width).toFloat(),
                    (pose.ys[j] / sequence.height).toFloat(),
                    0f,
                    pose.visibility[j].toFloat(),
                )
            },
        )
}
