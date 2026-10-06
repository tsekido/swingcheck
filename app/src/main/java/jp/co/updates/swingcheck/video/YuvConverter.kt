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

    /** 長辺を maxLongSide 以下にしたときの出力の大きさ。すでに収まっていれば元のまま（拡大はしない）。 */
    fun scaledSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        require(maxLongSide > 0) { "maxLongSide must be positive" }
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / longSide
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** ARGB_8888 の画素列（行優先）。回転後の幅は [rotatedSize] で求める。 */
    fun toArgb(frame: YuvFrame, degrees: Int, color: YuvColor): IntArray {
        val (ow, oh) = rotatedSize(frame.width, frame.height, degrees)
        return toArgbScaled(frame, degrees, color, maxOf(ow, oh))
    }

    /**
     * [toArgb] と同じ変換を、長辺が maxLongSide 以下になるように縮小しながら行う（最近傍のサンプリング）。
     * 変換する画素数が減るので、縮小してから変換するより速い。出力の大きさは
     * `scaledSize(回転後の幅, 回転後の高さ, maxLongSide)`。
     */
    fun toArgbScaled(frame: YuvFrame, degrees: Int, color: YuvColor, maxLongSide: Int): IntArray {
        val w = frame.width
        val h = frame.height
        val (rw, rh) = rotatedSize(w, h, degrees)
        val (tw, th) = scaledSize(rw, rh, maxLongSide)
        val out = IntArray(tw * th)

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

        // 元画像のどこを読むかは、出力の列 dx だけで決まる部分と行 dy だけで決まる部分の和になる
        // （回転しても同じ）。先に表を作って、内側のループでは足し算だけにする
        val yCol = IntArray(tw)
        val yRow = IntArray(th)
        val uvCol = IntArray(tw)
        val uvRow = IntArray(th)
        for (dx in 0 until tw) {
            val rx = ((dx * 2L + 1) * rw / (tw * 2L)).toInt().coerceIn(0, rw - 1)
            // 回転後の (rx, ·) が元画像のどの x（または y）に当たるか
            when (degrees) {
                0 -> { yCol[dx] = (rx + frame.cropLeft) * frame.yPixelStride; uvCol[dx] = ((rx + frame.cropLeft) shr 1) * frame.uvPixelStride }
                90 -> { val y = h - 1 - rx; yCol[dx] = (y + frame.cropTop) * frame.yRowStride; uvCol[dx] = ((y + frame.cropTop) shr 1) * frame.uvRowStride }
                180 -> { val x = w - 1 - rx; yCol[dx] = (x + frame.cropLeft) * frame.yPixelStride; uvCol[dx] = ((x + frame.cropLeft) shr 1) * frame.uvPixelStride }
                270 -> { yCol[dx] = (rx + frame.cropTop) * frame.yRowStride; uvCol[dx] = ((rx + frame.cropTop) shr 1) * frame.uvRowStride }
                else -> throw IllegalArgumentException("rotation must be 0, 90, 180 or 270: $degrees")
            }
        }
        for (dy in 0 until th) {
            val ry = ((dy * 2L + 1) * rh / (th * 2L)).toInt().coerceIn(0, rh - 1)
            when (degrees) {
                0 -> { yRow[dy] = (ry + frame.cropTop) * frame.yRowStride; uvRow[dy] = ((ry + frame.cropTop) shr 1) * frame.uvRowStride }
                90 -> { val x = ry; yRow[dy] = (x + frame.cropLeft) * frame.yPixelStride; uvRow[dy] = ((x + frame.cropLeft) shr 1) * frame.uvPixelStride }
                180 -> { val y = h - 1 - ry; yRow[dy] = (y + frame.cropTop) * frame.yRowStride; uvRow[dy] = ((y + frame.cropTop) shr 1) * frame.uvRowStride }
                270 -> { val x = w - 1 - ry; yRow[dy] = (x + frame.cropLeft) * frame.yPixelStride; uvRow[dy] = ((x + frame.cropLeft) shr 1) * frame.uvPixelStride }
            }
        }

        val yPlane = frame.y
        val uPlane = frame.u
        val vPlane = frame.v
        val round = ONE shr 1
        var o = 0
        for (dy in 0 until th) {
            val yr = yRow[dy]
            val uvr = uvRow[dy]
            for (dx in 0 until tw) {
                val yy = ((yPlane[yr + yCol[dx]].toInt() and 0xFF) - yOffset) * yScale
                val uvIndex = uvr + uvCol[dx]
                val d = (uPlane[uvIndex].toInt() and 0xFF) - 128
                val e = (vPlane[uvIndex].toInt() and 0xFF) - 128
                var r = (yy + rv * e + round) shr SHIFT
                var g = (yy - gu * d - gv * e + round) shr SHIFT
                var bl = (yy + bu * d + round) shr SHIFT
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (bl < 0) bl = 0 else if (bl > 255) bl = 255
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return out
    }
}
