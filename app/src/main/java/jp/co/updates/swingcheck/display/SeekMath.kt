package jp.co.updates.swingcheck.display

import kotlin.math.abs
import kotlin.math.roundToInt

/** シークバーの位置とコマ番号の変換など（UI から分けた計算）。 */
object SeekMath {
    /** コマ → 0〜1 の位置。コマが 1 つ以下なら 0。 */
    fun fractionOfFrame(frame: Int, frameCount: Int): Float =
        if (frameCount <= 1) 0f else frame.coerceIn(0, frameCount - 1).toFloat() / (frameCount - 1)

    /** 0〜1 の位置 → 最も近いコマ。範囲外は端に丸める。 */
    fun frameAtFraction(fraction: Float, frameCount: Int): Int {
        if (frameCount <= 1) return 0
        if (fraction.isNaN()) return 0
        return (fraction.coerceIn(0f, 1f) * (frameCount - 1)).roundToInt()
    }

    /** バーの x 座標（ピクセル）→ コマ。トラックは [trackStart] から [trackWidth] の幅。 */
    fun frameAtX(x: Float, trackStart: Float, trackWidth: Float, frameCount: Int): Int {
        if (trackWidth <= 0f) return 0
        return frameAtFraction((x - trackStart) / trackWidth, frameCount)
    }

    fun xOfFrame(frame: Int, trackStart: Float, trackWidth: Float, frameCount: Int): Float =
        trackStart + trackWidth * fractionOfFrame(frame, frameCount)

    /** コマ送り。端で止まる。 */
    fun step(frame: Int, delta: Int, frameCount: Int): Int =
        if (frameCount <= 0) 0 else (frame + delta).coerceIn(0, frameCount - 1)

    /**
     * 再生位置（ミリ秒）に表示されているコマ。時刻が位置以下のコマのうち最後のもの。
     * 先頭より前なら 0。[timestampsMs] は昇順。
     */
    fun frameAtPosition(timestampsMs: LongArray, positionMs: Long): Int {
        if (timestampsMs.isEmpty()) return 0
        var lo = 0
        var hi = timestampsMs.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (timestampsMs[mid] <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * 印（◆）のつかみ判定。[markXs] の中で、x から [radius] 以内にいちばん近いものの添字。
     * 同じ距離のものが複数あるときは [preferred]（選択中のP）を優先し、なければ添字の小さいほう。
     */
    fun hitTest(markXs: List<Float>, x: Float, radius: Float, preferred: Int? = null): Int? {
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        for (i in markXs.indices) {
            val d = abs(markXs[i] - x)
            if (d > radius) continue
            if (d < bestDist || (d == bestDist && i == preferred)) {
                best = i
                bestDist = d
            }
        }
        return best
    }
}
