package jp.co.updates.swingcheck.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * テスト用の合成スイング（正面撮影・右打ち）。
 * P1〜P8 のキー姿勢を座標で定義し、その間を単調な補間（PCHIP）でつないでコマを作る。
 * 座標は S（肩幅）を 1 とする単位で、x は画面右（目標方向）が正、y は上が正（地面が 0）。
 */
object SyntheticSwing {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val S_PX = 250.0
    private const val GROUND_Y_PX = 1700.0

    private class P(val x: Double, val y: Double)

    private fun p(x: Double, y: Double) = P(x, y)

    private class Key(
        val nose: P,
        val shL: P, val shR: P,
        val elL: P, val elR: P,
        val wrL: P, val wrR: P,
        val hipL: P, val hipR: P,
        val bowL: Double, val bowR: Double,
    )

    /** キー姿勢の時刻（秒、P1 を 0 とする）。 */
    val PHASE_TIMES = doubleArrayOf(0.0, 0.35, 0.55, 0.80, 0.90, 1.00, 1.07, 1.25)
    private const val FINISH_TIME = 1.70

    private val KEYS = listOf(
        // P1 アドレス
        Key(p(-0.15, 3.82), p(0.40, 3.34), p(-0.60, 3.26), p(0.30, 2.45), p(-0.30, 2.45),
            p(0.12, 1.60), p(0.02, 1.55), p(0.30, 2.17), p(-0.30, 2.13), 0.17, -0.17),
        // P2 シャフトが地面と平行（リード前腕が水平）
        Key(p(-0.17, 3.82), p(0.30, 3.36), p(-0.55, 3.22), p(0.00, 2.40), p(-0.75, 2.00),
            p(-0.60, 2.40), p(-0.60, 2.38), p(0.28, 2.18), p(-0.32, 2.12), 0.17, -0.17),
        // P3 左腕が地面と平行
        Key(p(-0.20, 3.84), p(0.20, 3.38), p(-0.52, 3.16), p(-0.30, 3.20), p(-0.95, 2.90),
            p(-0.85, 3.38), p(-0.88, 3.30), p(0.26, 2.17), p(-0.34, 2.11), 0.17, -0.15),
        // P4 トップ
        Key(p(-0.22, 3.85), p(0.12, 3.22), p(-0.43, 3.45), p(-0.35, 3.65), p(-0.95, 3.30),
            p(-0.75, 4.05), p(-0.80, 4.00), p(0.20, 2.12), p(-0.36, 2.16), 0.10, -0.20),
        // P5 切り返し後、左腕が地面と平行
        Key(p(-0.22, 3.80), p(0.30, 3.28), p(-0.45, 3.35), p(0.00, 3.10), p(-0.55, 2.85),
            p(-0.30, 3.28), p(-0.36, 3.20), p(0.40, 2.18), p(-0.20, 2.10), 0.10, -0.20),
        // P6 ダウンスイングで手が P2 と同じ高さ
        Key(p(-0.22, 3.78), p(0.38, 3.25), p(-0.55, 3.25), p(0.40, 2.55), p(-0.25, 2.55),
            p(0.10, 2.46), p(0.05, 2.44), p(0.50, 2.14), p(-0.10, 2.06), 0.05, -0.20),
        // P7 インパクト
        Key(p(-0.25, 3.78), p(0.35, 3.38), p(-0.45, 3.12), p(0.35, 2.35), p(-0.15, 2.40),
            p(0.16, 1.62), p(0.06, 1.57), p(0.70, 2.15), p(0.00, 2.05), 0.00, -0.20),
        // P8 フォローでトレイル前腕が水平
        Key(p(-0.20, 3.78), p(0.40, 3.40), p(-0.20, 3.16), p(0.95, 2.60), p(0.30, 2.90),
            p(0.75, 2.95), p(0.85, 2.90), p(0.80, 2.16), p(0.20, 2.08), 0.00, -0.10),
        // フィニッシュ（手は P4 のトップより高い）
        Key(p(-0.20, 3.80), p(0.45, 3.36), p(-0.05, 3.20), p(0.70, 3.60), p(0.20, 3.50),
            p(0.20, 4.30), p(0.35, 4.25), p(0.85, 2.18), p(0.20, 2.10), 0.00, -0.10),
    )

    private val ANKLE_L = p(0.55, 0.0)
    private val ANKLE_R = p(-0.55, 0.0)

    /** キー姿勢を 33 関節の座標（S 単位、y 上向き）に展開する。 */
    private fun expand(k: Key): Array<P> {
        val out = Array(Joint.COUNT) { p(0.0, 0.0) }
        fun set(j: Int, v: P) { out[j] = v }
        set(Joint.NOSE, k.nose)
        for (j in intArrayOf(1, 2, 3, 4, 5, 6)) set(j, k.nose)
        set(9, p(k.nose.x, k.nose.y - 0.1))
        set(10, p(k.nose.x, k.nose.y - 0.1))
        set(Joint.LEFT_EAR, p(k.nose.x + 0.12, k.nose.y + 0.07))
        set(Joint.RIGHT_EAR, p(k.nose.x - 0.12, k.nose.y + 0.07))
        set(Joint.LEFT_SHOULDER, k.shL)
        set(Joint.RIGHT_SHOULDER, k.shR)
        set(Joint.LEFT_ELBOW, k.elL)
        set(Joint.RIGHT_ELBOW, k.elR)
        set(Joint.LEFT_WRIST, k.wrL)
        set(Joint.RIGHT_WRIST, k.wrR)
        set(17, k.wrL); set(18, k.wrR); set(21, k.wrL); set(22, k.wrR)
        set(Joint.LEFT_INDEX, finger(k.elL, k.wrL))
        set(Joint.RIGHT_INDEX, finger(k.elR, k.wrR))
        set(Joint.LEFT_HIP, k.hipL)
        set(Joint.RIGHT_HIP, k.hipR)
        set(Joint.LEFT_KNEE, p((k.hipL.x + ANKLE_L.x) / 2 + k.bowL, 1.1))
        set(Joint.RIGHT_KNEE, p((k.hipR.x + ANKLE_R.x) / 2 + k.bowR, 1.1))
        set(Joint.LEFT_ANKLE, ANKLE_L)
        set(Joint.RIGHT_ANKLE, ANKLE_R)
        set(29, ANKLE_L); set(31, ANKLE_L); set(30, ANKLE_R); set(32, ANKLE_R)
        return out
    }

    private fun finger(elbow: P, wrist: P): P {
        val dx = wrist.x - elbow.x
        val dy = wrist.y - elbow.y
        val n = hypot(dx, dy)
        return p(wrist.x + 0.12 * dx / n, wrist.y + 0.12 * dy / n)
    }

    /** 単調な 3 次エルミート補間（Fritsch–Carlson）。端の傾きは 0。 */
    private class Pchip(val t: DoubleArray, val y: DoubleArray) {
        private val m = DoubleArray(t.size)

        init {
            val n = t.size
            val h = DoubleArray(n - 1) { t[it + 1] - t[it] }
            val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / h[it] }
            for (k in 1 until n - 1) {
                m[k] = if (d[k - 1] * d[k] <= 0) 0.0 else {
                    val w1 = 2 * h[k] + h[k - 1]
                    val w2 = h[k] + 2 * h[k - 1]
                    (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
                }
            }
        }

        fun at(x: Double): Double {
            if (x <= t.first()) return y.first()
            if (x >= t.last()) return y.last()
            var k = 0
            while (x > t[k + 1]) k++
            val h = t[k + 1] - t[k]
            val s = (x - t[k]) / h
            val s2 = s * s
            val s3 = s2 * s
            return (2 * s3 - 3 * s2 + 1) * y[k] + (s3 - 2 * s2 + s) * h * m[k] +
                (-2 * s3 + 3 * s2) * y[k + 1] + (s3 - s2) * h * m[k + 1]
        }
    }

    /** 素振り（ワッグル）：手を左右に小さく振る。 */
    class Waggle(val startSec: Double, val endSec: Double, val amplitudeS: Double = 0.25, val hz: Double = 2.0)

    class Result(
        val sequence: PoseSequence,
        /** P1〜P8 の正解のコマ番号（P1 のキー姿勢の時刻に最も近いコマ）。スイングなしなら空。 */
        val truth: List<Int>,
        val fps: Double,
        /** 先頭コマの時刻（秒、P1 のキー姿勢を 0 とする）。 */
        val startSec: Double,
    ) {
        fun frameAt(sec: Double): Int = Math.round((sec - startSec) * fps).toInt()
    }

    /**
     * @param preHoldSec P1 より前の静止の長さ
     * @param postHoldSec フィニッシュ後の静止の長さ
     * @param tempo 1.0 より大きいとゆっくり（時刻を掛ける）
     * @param noisePx 各座標に加えるガウスノイズの標準偏差（ピクセル）
     * @param dropouts true なら、ランダムな関節・コマで visibility を下げて座標を大きくずらす
     * @param includeSwing false なら P1 の姿勢で静止（＋ワッグル）のみ
     */
    fun generate(
        fps: Double = 60.0,
        preHoldSec: Double = 1.0,
        postHoldSec: Double = 1.2,
        tempo: Double = 1.0,
        noisePx: Double = 0.0,
        dropouts: Boolean = false,
        includeSwing: Boolean = true,
        waggle: Waggle? = null,
        seed: Long = 1234,
    ): Result {
        val times = if (includeSwing) {
            doubleArrayOf(-preHoldSec) + PHASE_TIMES.map { it * tempo } + doubleArrayOf(FINISH_TIME * tempo, FINISH_TIME * tempo + postHoldSec)
        } else {
            doubleArrayOf(-preHoldSec, 0.0, postHoldSec)
        }
        val keys = if (includeSwing) listOf(KEYS[0]) + KEYS + KEYS.last() else listOf(KEYS[0], KEYS[0], KEYS[0])
        val expanded = keys.map { expand(it) }
        val fx = Array(Joint.COUNT) { j -> Pchip(times, DoubleArray(times.size) { expanded[it][j].x }) }
        val fy = Array(Joint.COUNT) { j -> Pchip(times, DoubleArray(times.size) { expanded[it][j].y }) }

        val rnd = Random(seed)
        val start = -preHoldSec
        val end = times.last()
        val count = ((end - start) * fps).toInt() + 1
        val frames = ArrayList<PoseFrame>(count)
        for (i in 0 until count) {
            val t = start + i / fps
            val lms = ArrayList<Landmark>(Joint.COUNT)
            for (j in 0 until Joint.COUNT) {
                var x = fx[j].at(t)
                var y = fy[j].at(t)
                if (waggle != null && t >= waggle.startSec && t <= waggle.endSec &&
                    j in intArrayOf(Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_INDEX, Joint.RIGHT_INDEX, 17, 18, 21, 22, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW)
                ) {
                    val env = sin(PI * (t - waggle.startSec) / (waggle.endSec - waggle.startSec))
                    x += waggle.amplitudeS * env * sin(2 * PI * waggle.hz * (t - waggle.startSec))
                    y += 0.3 * waggle.amplitudeS * env * sin(2 * PI * waggle.hz * (t - waggle.startSec))
                }
                var px = WIDTH / 2.0 + x * S_PX
                var py = GROUND_Y_PX - y * S_PX
                var vis = 0.95f
                if (noisePx > 0) {
                    px += rnd.nextGaussian() * noisePx
                    py += rnd.nextGaussian() * noisePx
                }
                if (dropouts && rnd.nextDouble() < 0.04) {
                    vis = 0.1f
                    px += rnd.nextGaussian() * 150
                    py += rnd.nextGaussian() * 150
                }
                lms.add(Landmark((px / WIDTH).toFloat(), (py / HEIGHT).toFloat(), 0f, vis))
            }
            frames.add(PoseFrame(Math.round(t * 1000 - start * 1000), lms))
        }
        val truth = if (includeSwing) PHASE_TIMES.map { Math.round((it * tempo - start) * fps).toInt() } else emptyList()
        return Result(PoseSequence(frames, WIDTH, HEIGHT), truth, fps, start)
    }

    /** 15fps 程度に間引いた骨格（撮影中のスイング検出用）。timestampMs は元のまま。 */
    fun decimate(frames: List<PoseFrame>, fps: Double, targetFps: Double = 15.0): List<PoseFrame> {
        val step = fps / targetFps
        val out = ArrayList<PoseFrame>()
        var k = 0
        while (Math.round(k * step).toInt() < frames.size) {
            out.add(frames[Math.round(k * step).toInt()])
            k++
        }
        return out
    }
}
