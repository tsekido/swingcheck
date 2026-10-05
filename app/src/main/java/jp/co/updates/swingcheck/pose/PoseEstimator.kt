package jp.co.updates.swingcheck.pose

import android.graphics.Bitmap
import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Landmark
import jp.co.updates.swingcheck.core.PoseFrame

/**
 * 1 コマの画像から骨格を求める。端末内の MediaPipe 実装のほか、将来のサーバー解析もこの形で差し替える。
 *
 * - 同じインスタンスには、同じ動画のコマを時刻の昇順で渡す（VIDEO モードはコマ間の追跡を使う）
 * - 人が検出できなかったコマは null を返す。呼び出し側は [PoseFrames.undetected] で埋める
 */
interface PoseEstimator : AutoCloseable {
    /**
     * @param bitmap 向きを直した（縦撮りなら回転済みの）画像
     * @param timestampMs 動画の先頭を 0 とした時刻。呼び出しのたびに増えていくこと
     */
    fun estimate(bitmap: Bitmap, timestampMs: Long): PoseFrame?
}

object PoseFrames {
    /**
     * 検出できなかったコマの骨格。visibility がすべて 0 なので、`:core` の前処理で欠損として
     * 前後のコマから補間される。
     */
    fun undetected(timestampMs: Long): PoseFrame =
        PoseFrame(timestampMs, List(Joint.COUNT) { Landmark(0f, 0f, 0f, 0f) })
}
