package jp.co.updates.swingcheck.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class Phase {
    P1, P2, P3, P4, P5, P6, P7, P8;

    val number: Int get() = ordinal + 1
}

/** 1 つのPの判定結果。uncertain は「推定できなかった（または順序が崩れて置き直した）」印。 */
data class PhaseMark(val frame: Int, val uncertain: Boolean)

class PhaseDetectionResult(val marks: List<PhaseMark>) {
    init {
        require(marks.size == Phase.entries.size)
    }

    operator fun get(phase: Phase): PhaseMark = marks[phase.ordinal]

    val frames: List<Int> get() = marks.map { it.frame }
}

/** 7章の閾値。速さはすべて S（肩幅）／秒で正規化した値。 */
data class PhaseDetectorConfig(
    /** P1 判定：この速さ未満を静止とみなす。 */
    val addressSpeed: Double = 0.3,
    /** P1 判定：静止がこの秒数以上続いた区間を探す。 */
    val addressMinHoldSec: Double = 0.3,
    /** 手の速さを求める窓（前後この秒数、最低 1 コマ）。 */
    val speedHalfWindowSec: Double = 0.03,
    /** P4：T の前後この秒数の範囲で、手が止まるコマを探す。 */
    val topPauseWindowSec: Double = 0.15,
    /** P4：止まったとみなす速さ。 */
    val topPauseSpeed: Double = 1.0,
    /** P7：インパクト候補にするための最低の速さ。 */
    val impactMinSpeed: Double = 3.0,
    /** 暫定の S（P1 前の判定用）を、先頭からこの割合のコマの肩幅の中央値で求める。 */
    val provisionalSFraction: Double = 0.2,
)

class PhaseDetector(
    private val config: PhaseDetectorConfig = PhaseDetectorConfig(),
    private val shaftEstimator: ShaftEstimator = ForearmShaftEstimator(),
) {
    fun detect(seq: PixelSequence): PhaseDetectionResult {
        val n = seq.size
        if (n < 8) return fallbackAll(n)

        val s = provisionalS(seq)
        val speeds = Derived.handSpeeds(seq, config.speedHalfWindowSec).map { it / s }.toDoubleArray()
        val hands = List(n) { Derived.hand(seq.poses[it]) }

        // 手の最高点は、速さが最大のコマ（インパクト付近）より前から探す。
        // フィニッシュで手がトップより高くなる人がいるため（設計書 7章からの変更）。
        val fastest = speeds.indices.maxByOrNull { speeds[it] } ?: 0
        var t = 0
        for (i in 0..fastest) if (hands[i].y < hands[t].y) t = i

        val found = arrayOfNulls<Int>(8)

        // P4：T の近くで手が止まるコマがあればそちら
        found[3] = refineTop(seq, speeds, t)
        val top = found[3]!!

        // P1
        found[0] = findAddress(seq, speeds, top)

        val lo1 = found[0] ?: 0
        // 腕の水平：リード肩→リード手首の角度（度、手首が上なら正）
        val leadArm = DoubleArray(n) {
            val p = seq.poses[it]
            atan2(p.ys[Joint.LEFT_SHOULDER] - p.ys[Joint.LEFT_WRIST], abs(p.xs[Joint.LEFT_WRIST] - p.xs[Joint.LEFT_SHOULDER])) * RAD
        }
        // 前腕の水平：推定シャフトの向きの水平からの角度（度、先端が上なら正）
        val shaftLead = shaftElevations(seq, s, ShaftPhase.ADDRESS_TO_DOWNSWING)
        val shaftTrail = shaftElevations(seq, s, ShaftPhase.FOLLOW_THROUGH)

        // P3
        found[2] = findCrossing(leadArm, lo1, top, rising = true) { true }
        // P2：シャフトがトレイル側（-x）を向いて水平を横切る
        found[1] = findCrossing(shaftLead.values, lo1, found[2] ?: top, rising = true) { shaftLead.dx[it] < 0 }
        // P5：トップより後、最高速より前で、リード腕が水平を横切る（下向き）
        found[4] = findCrossing(leadArm, top, max(top, fastest), rising = false) { true }
        // P7：P5 より後で、手が速く動いている区間のうち P1 の手の位置に最も近いコマ
        val after5 = found[4] ?: top
        found[6] = findImpact(hands, speeds, after5, fastest, found[0])
        // P6：P5〜P7 で、手の高さが P2 での手の高さに戻ったコマ
        val p2 = found[1]
        if (p2 != null) {
            val y2 = hands[p2].y
            val ys = DoubleArray(n) { hands[it].y - y2 } // 下向き（y 増加）に横切る
            found[5] = findCrossing(ys, after5, found[6] ?: max(after5, fastest), rising = true) { true }
        }
        // P8：P7 より後で、トレイル前腕の向きが目標側（+x）を向いて水平を横切る
        val after7 = found[6] ?: found[4] ?: top
        found[7] = findCrossing(shaftTrail.values, after7, n - 1, rising = true) { shaftTrail.dx[it] > 0 }

        return resolve(found, n)
    }

    private fun refineTop(seq: PixelSequence, speeds: DoubleArray, t: Int): Int {
        var best = t
        var bestSpeed = Double.MAX_VALUE
        for (i in speeds.indices) {
            if (abs(seq.timeSec(i) - seq.timeSec(t)) > config.topPauseWindowSec) continue
            if (speeds[i] < bestSpeed) {
                bestSpeed = speeds[i]
                best = i
            }
        }
        return if (bestSpeed < config.topPauseSpeed) best else t
    }

    /** P1：T より前で、静止が一定時間続いた最後の区間の最後のコマ。 */
    private fun findAddress(seq: PixelSequence, speeds: DoubleArray, top: Int): Int? {
        var result: Int? = null
        var i = 0
        while (i <= top) {
            if (speeds[i] >= config.addressSpeed) {
                i++
                continue
            }
            var j = i
            while (j + 1 <= top && speeds[j + 1] < config.addressSpeed) j++
            if (seq.timeSec(j) - seq.timeSec(i) >= config.addressMinHoldSec) result = j
            i = j + 1
        }
        return result
    }

    private fun findImpact(
        hands: List<Point>,
        speeds: DoubleArray,
        from: Int,
        fastest: Int,
        p1: Int?,
    ): Int? {
        val ref = hands[p1 ?: return null]
        var end = max(from, fastest)
        while (end + 1 < speeds.size && speeds[end + 1] >= config.impactMinSpeed) end++
        var best: Int? = null
        var bestD = Double.MAX_VALUE
        for (i in from..end) {
            if (speeds[i] < config.impactMinSpeed) continue
            val d = hands[i].distanceTo(ref)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private class Shaft(val values: DoubleArray, val dx: DoubleArray)

    private fun shaftElevations(seq: PixelSequence, s: Double, phase: ShaftPhase): Shaft {
        val n = seq.size
        val values = DoubleArray(n)
        val dx = DoubleArray(n)
        for (i in 0 until n) {
            val line = shaftEstimator.estimate(seq.poses[i], s, phase)
            val vx = line.tip.x - line.grip.x
            val vyUp = -(line.tip.y - line.grip.y)
            values[i] = atan2(vyUp, abs(vx)) * RAD
            dx[i] = vx
        }
        return Shaft(values, dx)
    }

    private fun provisionalS(seq: PixelSequence): Double {
        val k = max(3, ceil(seq.size * config.provisionalSFraction).toInt()).coerceAtMost(seq.size)
        val w = (0 until k).map { Derived.shoulderWidth(seq.poses[it]) }.sorted()
        return max(w[w.size / 2], 1e-6)
    }

    /**
     * values が 0 を横切る最初のコマ（from..to）。横切る前後 2 コマのうち 0 に近いほう。
     * rising=true は負→0以上、false は正→0以下。accept が false のものは飛ばす。
     */
    private fun findCrossing(
        values: DoubleArray,
        from: Int,
        to: Int,
        rising: Boolean,
        accept: (Int) -> Boolean,
    ): Int? {
        val lo = max(from, 0)
        val hi = min(to, values.size - 1)
        for (i in lo + 1..hi) {
            val a = values[i - 1]
            val b = values[i]
            val crossed = if (rising) a < 0 && b >= 0 else a > 0 && b <= 0
            if (!crossed) continue
            val idx = if (abs(a) < abs(b)) i - 1 else i
            if (accept(idx)) return idx
        }
        return null
    }

    /** 見つからない／順序が崩れたPを前後の中間に置き直す。 */
    private fun resolve(found: Array<Int?>, n: Int): PhaseDetectionResult {
        val keep = longestIncreasing(found)
        val frames = IntArray(8)
        val uncertain = BooleanArray(8) { it !in keep }
        for (i in 0 until 8) if (i in keep) frames[i] = found[i]!!
        var i = 0
        while (i < 8) {
            if (i in keep) {
                i++
                continue
            }
            var j = i
            while (j < 8 && j !in keep) j++
            val lo = if (i == 0) 0 else frames[i - 1]
            val hi = if (j == 8) n - 1 else frames[j]
            val k = j - i
            for (m in 0 until k) {
                frames[i + m] = lo + ((hi - lo) * (m + 1).toDouble() / (k + 1)).toInt()
            }
            i = j
        }
        return PhaseDetectionResult(List(8) { PhaseMark(frames[it], uncertain[it]) })
    }

    /** 見つかったPのうち、P の順に厳密に増加する最長の組（添字の集合）。 */
    private fun longestIncreasing(found: Array<Int?>): Set<Int> {
        val len = IntArray(8)
        val prev = IntArray(8) { -1 }
        var bestEnd = -1
        for (i in 0 until 8) {
            val vi = found[i] ?: continue
            len[i] = 1
            for (j in 0 until i) {
                val vj = found[j] ?: continue
                if (vj < vi && len[j] + 1 > len[i]) {
                    len[i] = len[j] + 1
                    prev[i] = j
                }
            }
            if (bestEnd < 0 || len[i] > len[bestEnd]) bestEnd = i
        }
        val out = mutableSetOf<Int>()
        var c = bestEnd
        while (c >= 0) {
            out += c
            c = prev[c]
        }
        return out
    }

    private fun fallbackAll(n: Int): PhaseDetectionResult {
        val last = max(n - 1, 0)
        return PhaseDetectionResult(List(8) { PhaseMark(last * it / 7, true) })
    }

    private companion object {
        const val RAD = 180.0 / Math.PI
    }
}
