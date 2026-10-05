package jp.co.updates.swingcheck.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YuvConverterTest {
    /** 4 × 2 の画像。Y は 0,1,2,...,7 を（limited range の範囲で）並べ、クロマは無彩色（128）。 */
    private fun frame(yValues: IntArray, w: Int, h: Int, rowStride: Int = w, cropLeft: Int = 0, cropTop: Int = 0): YuvFrame {
        val y = ByteArray(rowStride * (h + cropTop))
        for (r in 0 until h) for (c in 0 until w) y[(r + cropTop) * rowStride + c + cropLeft] = yValues[r * w + c].toByte()
        val uv = ByteArray(rowStride * (h + cropTop)) { 128.toByte() }
        return YuvFrame(w, h, cropLeft, cropTop, y, rowStride, 1, uv, uv, rowStride, 1)
    }

    @Test
    fun grayWithoutRotationExpandsLimitedRange() {
        val f = frame(intArrayOf(16, 126, 235, 100), 2, 2)
        val g = YuvConverter.toGray(f, 0, YuvColor.BT601_LIMITED)
        assertEquals(2, g.width)
        assertEquals(0, g[0, 0])
        assertEquals(128, g[1, 0])
        assertEquals(255, g[0, 1])
        assertEquals(98, g[1, 1])
    }

    @Test
    fun rotation90MovesTopLeftToTopRight() {
        // 3 × 2： [a b c / d e f] を時計回りに 90 度回すと、2 × 3： [d a / e b / f c]
        val f = frame(intArrayOf(10, 20, 30, 40, 50, 60), 3, 2)
        val g = YuvConverter.toGray(f, 90, YuvColor.BT601_FULL)
        assertEquals(2, g.width)
        assertEquals(3, g.height)
        val got = (0 until 3).flatMap { y -> (0 until 2).map { x -> g[x, y] } }
        assertEquals(listOf(40, 10, 50, 20, 60, 30), got)
    }

    @Test
    fun rotation180And270() {
        val f = frame(intArrayOf(10, 20, 30, 40, 50, 60), 3, 2)
        val g180 = YuvConverter.toGray(f, 180, YuvColor.BT601_FULL)
        assertEquals(listOf(60, 50, 40, 30, 20, 10), (0 until 2).flatMap { y -> (0 until 3).map { x -> g180[x, y] } })
        // 時計回り 270 度 = 反時計回り 90 度： [c f / b e / a d]
        val g270 = YuvConverter.toGray(f, 270, YuvColor.BT601_FULL)
        assertEquals(2, g270.width)
        assertEquals(listOf(30, 60, 20, 50, 10, 40), (0 until 3).flatMap { y -> (0 until 2).map { x -> g270[x, y] } })
    }

    @Test
    fun rowStrideAndCropAreRespected() {
        // 行の途中にパディング（stride 8）があり、(2, 1) から 3 × 2 を切り出す
        val f = frame(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, rowStride = 8, cropLeft = 2, cropTop = 1)
        val g = YuvConverter.toGray(f, 0, YuvColor.BT601_FULL)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), (0 until 2).flatMap { y -> (0 until 3).map { x -> g[x, y] } })
    }

    @Test
    fun argbOfGrayInputIsGray() {
        val f = frame(intArrayOf(16, 126, 235, 16), 2, 2)
        val px = YuvConverter.toArgb(f, 0, YuvColor.BT709_LIMITED)
        assertEquals(0xFF000000.toInt(), px[0])
        assertEquals(0xFFFFFFFF.toInt(), px[2])
        val mid = px[1]
        val r = (mid shr 16) and 0xFF
        assertEquals(r, (mid shr 8) and 0xFF)
        assertEquals(r, mid and 0xFF)
        assertEquals(128.0, r.toDouble(), 2.0)
    }

    @Test
    fun argbColorsFollowBt601() {
        // 赤: Y=81, U=90, V=240（BT.601 limited）
        val y = byteArrayOf(81, 81, 81, 81)
        val u = byteArrayOf(90.toByte())
        val v = byteArrayOf(240.toByte())
        val f = YuvFrame(2, 2, 0, 0, y, 2, 1, u, v, 1, 1)
        val px = YuvConverter.toArgb(f, 0, YuvColor.BT601_LIMITED)
        val c = px[0]
        assertEquals(255.0, ((c shr 16) and 0xFF).toDouble(), 3.0)
        assertEquals(0.0, ((c shr 8) and 0xFF).toDouble(), 3.0)
        assertEquals(0.0, (c and 0xFF).toDouble(), 3.0)
    }

    @Test
    fun argbRotationMatchesGrayRotation() {
        val f = frame(intArrayOf(16, 60, 100, 140, 180, 235), 3, 2)
        val gray = YuvConverter.toGray(f, 90, YuvColor.BT601_LIMITED)
        val argb = YuvConverter.toArgb(f, 90, YuvColor.BT601_LIMITED)
        for (y in 0 until gray.height) for (x in 0 until gray.width) {
            assertEquals(gray[x, y].toDouble(), (argb[y * gray.width + x] and 0xFF).toDouble(), 1.0)
        }
    }

    @Test
    fun rotatedSizeSwapsForPortrait() {
        assertEquals(1080 to 1920, rotatedSize(1920, 1080, 90))
        assertEquals(1080 to 1920, rotatedSize(1920, 1080, 270))
        assertEquals(1920 to 1080, rotatedSize(1920, 1080, 180))
        assertEquals(1920 to 1080, rotatedSize(1920, 1080, 0))
    }
}
