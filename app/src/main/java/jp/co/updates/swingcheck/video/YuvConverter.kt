package jp.co.updates.swingcheck.video

import jp.co.updates.swingcheck.core.GrayImage

/**
 * デコーダーが出した YUV_420_888 の 1 コマ分。Android の Image に依存しない形にコピーしたもの
 * （JVM の単体テストで変換を確かめられるようにするため）。
 *
 * 各 ByteArray は plane 全体。(cropLeft, cropTop) から width × height が実際に使う範囲。
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
)

/** YUV → RGB の係数。動画の color standard／range に合わせる。 */
enum class YuvColor(val bt709: Boolean, val fullRange: Boolean) {
    BT601_LIMITED(false, false),
    BT601_FULL(false, true),
    BT709_LIMITED(true, false),
    BT709_FULL(true, true);

    companion object {
        fun of(bt709: Boolean, fullRange: Boolean): YuvColor = entries.first { it.bt709 == bt709 && it.fullRange == fullRange }
    }
}

/** 回転後（表示する向き）の幅・高さ。degrees は時計回りで 0／90／180／270。 */
fun rotatedSize(width: Int, height: Int, degrees: Int): Pair<Int, Int> =
    if (degrees == 90 || degrees == 270) height to width else width to height

object YuvConverter {
    private const val SHIFT = 16
    private const val ONE = 1 shl SHIFT

    private fun fix(v: Double): Int = Math.round(v * ONE).toInt()

    /** 回転後の画像（行優先）での、元の (x, y) の位置。 */
    private fun destIndex(x: Int, y: Int, w: Int, h: Int, degrees: Int): Int = when (degrees) {
        0 -> y * w + x
        90 -> x * h + (h - 1 - y)
        180 -> (h - 1 - y) * w + (w - 1 - x)
        270 -> (w - 1 - x) * h + y
        else -> throw IllegalArgumentException("rotation must be 0, 90, 180 or 270: $degrees")
    }

    /** 明るさだけを取り出したグレースケール画像（ボール判定用）。回転は画像と同じ向きに直す。 */
    fun toGray(frame: YuvFrame, degrees: Int, color: YuvColor): GrayImage {
        val w = frame.width
        val h = frame.height
        val (ow, oh) = rotatedSize(w, h, degrees)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val rowBase = (y + frame.cropTop) * frame.yRowStride
            for (x in 0 until w) {
                val yy = frame.y[rowBase + (x + frame.cropLeft) * frame.yPixelStride].toInt() and 0xFF
                val v = if (color.fullRange) yy else ((yy - 16) * 255 + 109) / 219
                out[destIndex(x, y, w, h, degrees)] = v.coerceIn(0, 255).toByte()
            }
        }
        return GrayImage.ofBytes(ow, oh, out)
    }

    /** ARGB_8888 の画素列（行優先）。回転後の幅は [rotatedSize] で求める。 */
    fun toArgb(frame: YuvFrame, degrees: Int, color: YuvColor): IntArray {
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * h)

        // 固定小数点の係数（Y のスケール、V→R、U→G、V→G、U→B）
        val yScale: Int
        val yOffset: Int
        if (color.fullRange) {
            yScale = ONE
            yOffset = 0
        } else {
            yScale = fix(255.0 / 219.0)
            yOffset = 16
        }
        val chromaScale = if (color.fullRange) 1.0 else 255.0 / 224.0
        val rv: Int
        val gu: Int
        val gv: Int
        val bu: Int
        if (color.bt709) {
            rv = fix(1.5748 * chromaScale)
            gu = fix(0.1873 * chromaScale)
            gv = fix(0.4681 * chromaScale)
            bu = fix(1.8556 * chromaScale)
        } else {
            rv = fix(1.402 * chromaScale)
            gu = fix(0.344136 * chromaScale)
            gv = fix(0.714136 * chromaScale)
            bu = fix(1.772 * chromaScale)
        }

        for (y in 0 until h) {
            val yRow = (y + frame.cropTop) * frame.yRowStride
            val uvRow = ((y + frame.cropTop) shr 1) * frame.uvRowStride
            for (x in 0 until w) {
                val yy = ((frame.y[yRow + (x + frame.cropLeft) * frame.yPixelStride].toInt() and 0xFF) - yOffset) * yScale
                val uvIndex = uvRow + ((x + frame.cropLeft) shr 1) * frame.uvPixelStride
                val d = (frame.u[uvIndex].toInt() and 0xFF) - 128
                val e = (frame.v[uvIndex].toInt() and 0xFF) - 128
                val r = (yy + rv * e + (ONE shr 1)) shr SHIFT
                val g = (yy - gu * d - gv * e + (ONE shr 1)) shr SHIFT
                val b = (yy + bu * d + (ONE shr 1)) shr SHIFT
                out[destIndex(x, y, w, h, degrees)] =
                    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return out
    }
}
