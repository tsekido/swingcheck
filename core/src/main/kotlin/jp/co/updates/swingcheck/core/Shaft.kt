package jp.co.updates.swingcheck.core

import kotlin.math.hypot

/** 推定したシャフト。grip は手の位置、tip は先端（表示用の目安）。 */
data class ShaftLine(val grip: Point, val tip: Point)

/** どの前腕を使うか。P8 付近（フォロー）ではトレイル側を使う（6章）。 */
enum class ShaftPhase { ADDRESS_TO_DOWNSWING, FOLLOW_THROUGH }

/** 6章：シャフトの推定。将来のクラブ検出モデルは、これを実装して差し替える。 */
interface ShaftEstimator {
    /** shoulderWidthPx は基準の長さ S。 */
    fun estimate(pose: PixelPose, shoulderWidthPx: Double, phase: ShaftPhase): ShaftLine
}

/** 前腕（肘→手首）の向きを、手の位置から延長したもの。長さは 1.5 × S。 */
class ForearmShaftEstimator(private val lengthInShoulderWidths: Double = 1.5) : ShaftEstimator {
    override fun estimate(pose: PixelPose, shoulderWidthPx: Double, phase: ShaftPhase): ShaftLine {
        val (elbow, wrist) = when (phase) {
            ShaftPhase.ADDRESS_TO_DOWNSWING -> Joint.LEFT_ELBOW to Joint.LEFT_WRIST
            ShaftPhase.FOLLOW_THROUGH -> Joint.RIGHT_ELBOW to Joint.RIGHT_WRIST
        }
        val grip = Derived.hand(pose)
        val dx = pose.xs[wrist] - pose.xs[elbow]
        val dy = pose.ys[wrist] - pose.ys[elbow]
        val n = hypot(dx, dy)
        val len = lengthInShoulderWidths * shoulderWidthPx
        if (n < 1e-9) return ShaftLine(grip, Point(grip.x, grip.y + len))
        return ShaftLine(grip, Point(grip.x + dx / n * len, grip.y + dy / n * len))
    }
}
