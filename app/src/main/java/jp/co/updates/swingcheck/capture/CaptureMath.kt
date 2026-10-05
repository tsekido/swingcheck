package jp.co.updates.swingcheck.capture

/** エンコーダーのビットレート。高 fps ほど 1 コマあたりの差が小さいので、1 画素・1 コマあたり 0.07 ビットを目安にする。 */
object Bitrate {
    private const val BITS_PER_PIXEL_FRAME = 0.07
    const val MIN_BPS = 4_000_000
    const val MAX_BPS = 80_000_000

    fun forVideo(width: Int, height: Int, fps: Int): Int =
        (width.toDouble() * height * fps * BITS_PER_PIXEL_FRAME).toLong().coerceIn(MIN_BPS.toLong(), MAX_BPS.toLong()).toInt()
}

/** 向きの計算。背面カメラ前提。 */
object CaptureOrientation {
    /**
     * 再生時に時計回りに回す角度（MediaMuxer.setOrientationHint、プレビューの回転に使う）。
     * @param sensorOrientation CameraCharacteristics.SENSOR_ORIENTATION
     * @param displayRotationDegrees 画面の回転（Surface.ROTATION_0 なら 0、ROTATION_90 なら 90）
     */
    fun rotationHint(sensorOrientation: Int, displayRotationDegrees: Int): Int =
        ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360

    /** 回転後（表示する向き）の大きさ。 */
    fun displaySize(size: CaptureSize, rotation: Int): CaptureSize =
        if (rotation % 180 == 0) size else CaptureSize(size.height, size.width)
}

/**
 * 約 [targetFps] に間引く。タイムスタンプ（ナノ秒でもミリ秒でも、単位をそろえて渡す）が前回採用した時刻から
 * 1 / targetFps 秒以上進んでいれば採用する。コマが来る間隔のばらつきで目標より少なくならないよう、
 * 次の採用時刻は「前回の予定 + 間隔」にそろえる（遅れすぎたときは今の時刻から数え直す）。
 */
class FrameDecimator(targetFps: Int, unitsPerSecond: Long) {
    private val intervalUnits = unitsPerSecond.toDouble() / targetFps
    private var nextUnits: Double? = null

    fun shouldSample(timestamp: Long): Boolean {
        val next = nextUnits
        if (next == null || timestamp >= next) {
            val base = if (next == null || timestamp - next > intervalUnits) timestamp.toDouble() else next
            nextUnits = base + intervalUnits
            return true
        }
        return false
    }

    fun reset() {
        nextUnits = null
    }
}

/** 1 秒あたりの処理回数（直近 [windowMs] の窓で数える）。 */
class RateMeter(private val windowMs: Long = 2000) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tick(nowMs: Long) {
        times.addLast(nowMs)
        trim(nowMs)
    }

    @Synchronized
    fun perSecond(nowMs: Long): Float {
        trim(nowMs)
        if (times.size < 2) return 0f
        val span = times.last() - times.first()
        return if (span <= 0) 0f else (times.size - 1) * 1000f / span
    }

    private fun trim(nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
    }
}

/** 画面（Surface）の中に、縦横比を保って収まる最大の長方形（余白は黒）。 */
data class ViewportRect(val x: Int, val y: Int, val width: Int, val height: Int)

object Letterbox {
    fun fit(surfaceWidth: Int, surfaceHeight: Int, imageWidth: Int, imageHeight: Int): ViewportRect {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return ViewportRect(0, 0, maxOf(surfaceWidth, 0), maxOf(surfaceHeight, 0))
        }
        val scale = minOf(surfaceWidth.toDouble() / imageWidth, surfaceHeight.toDouble() / imageHeight)
        val w = Math.round(imageWidth * scale).toInt().coerceIn(1, surfaceWidth)
        val h = Math.round(imageHeight * scale).toInt().coerceIn(1, surfaceHeight)
        return ViewportRect((surfaceWidth - w) / 2, (surfaceHeight - h) / 2, w, h)
    }
}
