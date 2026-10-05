package jp.co.updates.swingcheck.core

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** グレースケール画像（0〜255、行優先）。 */
class GrayImage private constructor(val width: Int, val height: Int, private val pixels: ByteArray) {
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    companion object {
        fun ofBytes(width: Int, height: Int, data: ByteArray): GrayImage {
            require(width > 0 && height > 0 && data.size == width * height) { "size mismatch" }
            return GrayImage(width, height, data)
        }

        /** 各要素は 0〜255 の明るさ。範囲外は丸める。 */
        fun ofInts(width: Int, height: Int, data: IntArray): GrayImage {
            require(width > 0 && height > 0 && data.size == width * height) { "size mismatch" }
            return GrayImage(width, height, ByteArray(data.size) { data[it].coerceIn(0, 255).toByte() })
        }
    }
}

enum class BallResult { HIT, PRACTICE, UNKNOWN }

/** 見つけたボール（デバッグ・表示用）。座標・直径はピクセル。 */
data class BallCandidate(val x: Double, val y: Double, val diameter: Double, val brightness: Double, val contrast: Double)

class BallDetection(
    val result: BallResult,
    val ball: BallCandidate?,
    /** P1 と最後のコマでの、ボール位置の（中心の明るさ − 周囲の明るさ）。 */
    val contrastAtAddress: Double?,
    val contrastAtEnd: Double?,
)

/** 9章の閾値。長さは S（肩幅）に対する比。 */
data class BallDetectorConfig(
    val searchSideMargin: Double = 0.3,
    val searchTop: Double = 0.15,
    val searchBottom: Double = 0.35,
    /** ボール直径の目安（S に対する比）。 */
    val ballDiameter: Double = 0.11,
    val minDiameterFactor: Double = 0.6,
    val maxDiameterFactor: Double = 1.6,
    val minCircularity: Double = 0.6,
    /** 「周囲より明るい」とみなす最小の差（0〜255）。 */
    val minContrast: Double = 25.0,
    /** これ未満の明るさの塊はボールとみなさない（暗すぎる映像は UNKNOWN）。 */
    val minBrightness: Double = 100.0,
    /** 最後のコマの差が P1 の差のこの割合未満なら「消えた」。 */
    val vanishRatio: Double = 0.4,
    /** 周囲の明るさを測る範囲：塊の直径に対する外側の半径の倍率（内側は 1 倍）。 */
    val surroundOuterFactor: Double = 2.5,
    /** 画像上のボールの直径がこれ未満（ピクセル）なら判定しない。 */
    val minDiameterPx: Double = 3.0,
)

/**
 * 9章：ボール判定。入力は P1 のコマの画像と、最後のコマ（フィニッシュ後）の画像（同じ大きさ）、
 * および P1 の骨格。骨格の座標は画像の幅・高さを掛けてピクセルに直す。
 */
class BallDetector(private val config: BallDetectorConfig = BallDetectorConfig()) {

    fun detect(addressImage: GrayImage, endImage: GrayImage, addressPose: PoseFrame): BallResult =
        analyze(addressImage, endImage, addressPose).result

    fun analyze(addressImage: GrayImage, endImage: GrayImage, addressPose: PoseFrame): BallDetection {
        val w = addressImage.width
        val h = addressImage.height
        if (endImage.width != w || endImage.height != h) return unknown()
        val pose = PixelPose(
            addressPose.timestampMs,
            DoubleArray(Joint.COUNT) { addressPose.landmarks[it].x.toDouble() * w },
            DoubleArray(Joint.COUNT) { addressPose.landmarks[it].y.toDouble() * h },
            DoubleArray(Joint.COUNT) { addressPose.landmarks[it].visibility.toDouble() },
        )
        val s = Derived.shoulderWidth(pose)
        if (s <= 0.0) return unknown()
        val expected = config.ballDiameter * s
        if (expected * config.minDiameterFactor < config.minDiameterPx) return unknown()

        val ankleXs = doubleArrayOf(pose.xs[Joint.LEFT_ANKLE], pose.xs[Joint.RIGHT_ANKLE])
        val ankleY = (pose.ys[Joint.LEFT_ANKLE] + pose.ys[Joint.RIGHT_ANKLE]) / 2
        val x0 = max(0, (ankleXs.min() - config.searchSideMargin * s).toInt())
        val x1 = min(w - 1, (ankleXs.max() + config.searchSideMargin * s).toInt())
        val y0 = max(0, (ankleY - config.searchTop * s).toInt())
        val y1 = min(h - 1, (ankleY + config.searchBottom * s).toInt())
        if (x1 <= x0 || y1 <= y0) return unknown()

        val ball = findBall(addressImage, x0, y0, x1, y1, expected) ?: return unknown()
        val atAddress = contrastAt(addressImage, ball.x, ball.y, ball.diameter)
        val atEnd = contrastAt(endImage, ball.x, ball.y, ball.diameter)
        val result = if (atEnd < atAddress * config.vanishRatio) BallResult.HIT else BallResult.PRACTICE
        return BallDetection(result, ball, atAddress, atEnd)
    }

    private fun unknown() = BallDetection(BallResult.UNKNOWN, null, null, null)

    /** 範囲内で、周囲より明るい塊のうち、大きさと丸さが合うものを最も明るい順に 1 つ選ぶ。 */
    private fun findBall(img: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int, expected: Double): BallCandidate? {
        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        // 周囲の平均：探す範囲に大きめの余白を足した領域の積分画像で求める
        val win = max(2, (expected * config.surroundOuterFactor).toInt())
        val ix0 = max(0, x0 - win)
        val iy0 = max(0, y0 - win)
        val ix1 = min(img.width - 1, x1 + win)
        val iy1 = min(img.height - 1, y1 + win)
        val iw = ix1 - ix0 + 1
        val ih = iy1 - iy0 + 1
        val integral = LongArray((iw + 1) * (ih + 1))
        for (y in 0 until ih) {
            var row = 0L
            for (x in 0 until iw) {
                row += img[ix0 + x, iy0 + y]
                integral[(y + 1) * (iw + 1) + x + 1] = integral[y * (iw + 1) + x + 1] + row
            }
        }
        fun boxMean(cx: Int, cy: Int): Double {
            val ax = max(ix0, cx - win) - ix0
            val ay = max(iy0, cy - win) - iy0
            val bx = min(ix1, cx + win) - ix0 + 1
            val by = min(iy1, cy + win) - iy0 + 1
            val sum = integral[by * (iw + 1) + bx] - integral[ay * (iw + 1) + bx] -
                integral[by * (iw + 1) + ax] + integral[ay * (iw + 1) + ax]
            return sum.toDouble() / ((bx - ax) * (by - ay))
        }

        val mask = BooleanArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val v = img[x0 + x, y0 + y]
            mask[y * rw + x] = v >= config.minBrightness && v - boxMean(x0 + x, y0 + y) >= config.minContrast
        }

        val minD = expected * config.minDiameterFactor
        val maxD = expected * config.maxDiameterFactor
        val maxArea = PI / 4 * maxD * maxD * 1.5
        val visited = BooleanArray(rw * rh)
        var best: BallCandidate? = null
        val stack = IntArray(rw * rh)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            val members = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]
                members.add(p)
                val px = p % rw
                val py = p / rw
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                    val q = ny * rw + nx
                    if (mask[q] && !visited[q]) {
                        visited[q] = true
                        stack[sp++] = q
                    }
                }
            }
            val area = members.size
            if (area > maxArea) continue
            val diameter = 2 * sqrt(area / PI)
            if (diameter < minD || diameter > maxD) continue
            var cx = 0.0
            var cy = 0.0
            var bright = 0.0
            for (p in members) {
                cx += p % rw
                cy += p / rw
                bright += img[x0 + p % rw, y0 + p / rw]
            }
            cx /= area
            cy /= area
            bright /= area
            var r = 0.0
            for (p in members) r = max(r, hypot(p % rw - cx, p / rw - cy) + 0.5)
            val circularity = area / (PI * r * r)
            if (circularity < config.minCircularity) continue
            if (best == null || bright > best.brightness) {
                val gx = x0 + cx
                val gy = y0 + cy
                best = BallCandidate(gx, gy, diameter, bright, contrastAt(img, gx, gy, diameter))
            }
        }
        return best
    }

    /** 中心付近（半径 0.5 × 直径）の平均の明るさ − 周囲（半径 1〜outerFactor 倍の輪）の平均の明るさ。 */
    private fun contrastAt(img: GrayImage, cx: Double, cy: Double, diameter: Double): Double {
        val rIn = diameter * 0.5
        val rOut = diameter * config.surroundOuterFactor
        var sumIn = 0.0
        var nIn = 0
        var sumOut = 0.0
        var nOut = 0
        val xa = max(0, (cx - rOut).toInt())
        val xb = min(img.width - 1, (cx + rOut).toInt() + 1)
        val ya = max(0, (cy - rOut).toInt())
        val yb = min(img.height - 1, (cy + rOut).toInt() + 1)
        for (y in ya..yb) for (x in xa..xb) {
            val d = hypot(x - cx, y - cy)
            if (d <= rIn) {
                sumIn += img[x, y]
                nIn++
            } else if (d >= diameter && d <= rOut) {
                sumOut += img[x, y]
                nOut++
            }
        }
        if (nIn == 0 || nOut == 0) return 0.0
        return sumIn / nIn - sumOut / nOut
    }
}
