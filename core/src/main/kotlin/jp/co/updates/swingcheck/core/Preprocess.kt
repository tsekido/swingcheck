package jp.co.updates.swingcheck.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** 4章 平滑化の設定。minCutoff / beta / dCutoff は One Euro Filter のパラメータ。 */
data class SmoothingConfig(
    val minCutoff: Double = 1.0,
    val beta: Double = 0.05,
    val dCutoff: Double = 1.0,
    /** 手首と人さし指の beta（速い動きを鈍らせないため大きめ）。 */
    val wristBeta: Double = 0.3,
    /** visibility がこれ未満の関節は欠損として補間する。 */
    val minVisibility: Double = 0.5,
)

/** One Euro Filter（1 つの値用）。時刻は秒。 */
class OneEuroFilter(
    private val minCutoff: Double,
    private val beta: Double,
    private val dCutoff: Double,
) {
    private var prevT = 0.0
    private var prevX = 0.0
    private var prevDx = 0.0
    private var initialized = false

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun filter(t: Double, x: Double): Double {
        if (!initialized) {
            initialized = true
            prevT = t
            prevX = x
            prevDx = 0.0
            return x
        }
        val dt = t - prevT
        if (dt <= 0.0) return prevX
        val dx = (x - prevX) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * prevDx
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * prevX
        prevT = t
        prevX = xHat
        prevDx = dxHat
        return xHat
    }
}

object Preprocessor {
    private val WRIST_JOINTS = setOf(
        Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_INDEX, Joint.RIGHT_INDEX,
    )

    /**
     * 4章：右打ち基準に直す → ピクセル座標に直す → 欠損補間 → 平滑化。
     * 欠損補間は時刻に対する線形補間。前後どちらかにしかなければその値を使う。
     */
    fun preprocess(
        sequence: PoseSequence,
        handedness: Handedness = Handedness.RIGHT,
        config: SmoothingConfig = SmoothingConfig(),
    ): PixelSequence {
        val seq = handedness.toRightHanded(sequence)
        val n = seq.size
        val w = seq.width
        val h = seq.height
        val times = DoubleArray(n) { seq.frames[it].timestampMs / 1000.0 }
        val xs = Array(Joint.COUNT) { j -> DoubleArray(n) { seq.frames[it].landmarks[j].x.toDouble() * w } }
        val ys = Array(Joint.COUNT) { j -> DoubleArray(n) { seq.frames[it].landmarks[j].y.toDouble() * h } }
        val vis = Array(Joint.COUNT) { j -> DoubleArray(n) { seq.frames[it].landmarks[j].visibility.toDouble() } }

        for (j in 0 until Joint.COUNT) {
            val ok = BooleanArray(n) { vis[j][it] >= config.minVisibility }
            interpolate(times, xs[j], ok)
            interpolate(times, ys[j], ok)
            val beta = if (j in WRIST_JOINTS) config.wristBeta else config.beta
            val fx = OneEuroFilter(config.minCutoff, beta, config.dCutoff)
            val fy = OneEuroFilter(config.minCutoff, beta, config.dCutoff)
            for (i in 0 until n) {
                xs[j][i] = fx.filter(times[i], xs[j][i])
                ys[j][i] = fy.filter(times[i], ys[j][i])
            }
        }
        val poses = List(n) { i ->
            PixelPose(
                seq.frames[i].timestampMs,
                DoubleArray(Joint.COUNT) { xs[it][i] },
                DoubleArray(Joint.COUNT) { ys[it][i] },
                DoubleArray(Joint.COUNT) { vis[it][i] },
            )
        }
        return PixelSequence(poses, w, h)
    }

    /** ok でない要素を、前後の ok な要素から線形補間で埋める（in place）。 */
    private fun interpolate(t: DoubleArray, v: DoubleArray, ok: BooleanArray) {
        val n = v.size
        if (ok.none { it }) return // 全コマ欠損：元の値のまま
        var prev = -1
        var i = 0
        while (i < n) {
            if (ok[i]) {
                prev = i
                i++
                continue
            }
            var next = i
            while (next < n && !ok[next]) next++
            for (k in i until next) {
                v[k] = when {
                    prev < 0 -> v[next]
                    next >= n -> v[prev]
                    else -> v[prev] + (v[next] - v[prev]) * (t[k] - t[prev]) / (t[next] - t[prev])
                }
            }
            i = next
        }
    }
}

/** 派生点（4章）。 */
object Derived {
    /** 鼻・左耳・右耳の visibility 重み付き平均。重みがすべて 0 なら単純平均。 */
    fun head(p: PixelPose): Point {
        val js = intArrayOf(Joint.NOSE, Joint.LEFT_EAR, Joint.RIGHT_EAR)
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (j in js) {
            val w = p.visibility[j].coerceIn(0.0, 1.0)
            sw += w
            sx += w * p.xs[j]
            sy += w * p.ys[j]
        }
        if (sw <= 1e-9) {
            return Point(js.sumOf { p.xs[it] } / js.size, js.sumOf { p.ys[it] } / js.size)
        }
        return Point(sx / sw, sy / sw)
    }

    fun shoulderCenter(p: PixelPose): Point = p.mid(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER)

    fun hipCenter(p: PixelPose): Point = p.mid(Joint.LEFT_HIP, Joint.RIGHT_HIP)

    /** 左右の手首と左右の人さし指、計 4 点の平均。 */
    fun hand(p: PixelPose): Point {
        val js = intArrayOf(Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_INDEX, Joint.RIGHT_INDEX)
        return Point(js.sumOf { p.xs[it] } / 4, js.sumOf { p.ys[it] } / 4)
    }

    /** 肩幅（左肩と右肩の距離、ピクセル）。基準の長さ S は P1 でのこの値。 */
    fun shoulderWidth(p: PixelPose): Double =
        hypot(
            p.xs[Joint.LEFT_SHOULDER] - p.xs[Joint.RIGHT_SHOULDER],
            p.ys[Joint.LEFT_SHOULDER] - p.ys[Joint.RIGHT_SHOULDER],
        )

    /**
     * 手の速さ（ピクセル／秒）。前後 halfWindowSec（ただし最低 1 コマ）離れた 2 点の差から求める。
     * fps が変わっても同じ尺度になるようにするため、コマ数ではなく時間で窓を決める。
     */
    fun handSpeeds(seq: PixelSequence, halfWindowSec: Double): DoubleArray {
        val n = seq.size
        val hands = List(n) { hand(seq.poses[it]) }
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var a = i
            var b = i
            while (a > 0 && seq.timeSec(i) - seq.timeSec(a) < halfWindowSec) a--
            while (b < n - 1 && seq.timeSec(b) - seq.timeSec(i) < halfWindowSec) b++
            val dt = seq.timeSec(b) - seq.timeSec(a)
            out[i] = if (dt > 0) hands[a].distanceTo(hands[b]) / dt else 0.0
        }
        return out
    }
}
