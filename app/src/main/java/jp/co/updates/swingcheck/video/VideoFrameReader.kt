package jp.co.updates.swingcheck.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import jp.co.updates.swingcheck.core.GrayImage
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 動画の情報。width／height は回転メタデータを反映した、表示する向きの大きさ。
 * frameCount と fps は、デコードせずにサンプルの時刻だけを数えて求めたもの。
 */
data class VideoInfo(
    val width: Int,
    val height: Int,
    /** 表示するために時計回りに回す角度（0／90／180／270）。縦撮りの動画は 90 や 270。 */
    val rotation: Int,
    val fps: Float,
    val frameCount: Int,
    val durationUs: Long,
    val firstTimestampUs: Long,
)

/** デコードした 1 コマ。変換は呼ばれたときだけ行う（不要なコマの変換を省くため）。 */
class DecodedFrame internal constructor(
    val index: Int,
    /** 動画の先頭のコマを 0 とした時刻（ミリ秒）。 */
    val timestampMs: Long,
    private val info: VideoInfo,
    private val color: YuvColor,
    private val yuv: () -> YuvFrame,
) {
    private var cached: YuvFrame? = null

    private fun frame(): YuvFrame = cached ?: yuv().also { cached = it }

    /** 向きを直した（回転後の）画像の大きさ。[toBitmap] で縮小しても、これは元の大きさ。 */
    fun displaySize(): Pair<Int, Int> {
        val f = frame()
        return rotatedSize(f.width, f.height, info.rotation)
    }

    /**
     * 向きを直した ARGB_8888 のビットマップ。呼び出し側が recycle する。
     * [maxLongSide] を指定すると、長辺がそれ以下になるように、色の変換と同時に縮小する。
     */
    fun toBitmap(maxLongSide: Int? = null): Bitmap {
        val f = frame()
        val (rw, rh) = rotatedSize(f.width, f.height, info.rotation)
        val (w, h) = if (maxLongSide == null) rw to rh else YuvConverter.scaledSize(rw, rh, maxLongSide)
        val pixels = if (maxLongSide == null) YuvConverter.toArgb(f, info.rotation, color)
        else YuvConverter.toArgbScaled(f, info.rotation, color, maxLongSide)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 向きを直したグレースケール画像（ボール判定用）。 */
    fun toGray(): GrayImage = YuvConverter.toGray(frame(), info.rotation, color)
}

/**
 * MP4 から全コマを順に取り出す。MediaCodec でデコードし、ImageReader（YUV_420_888）で受け取る。
 * 時刻の昇順（表示順）に出てくる。
 */
class VideoFrameReader(private val file: File) {

    /** サンプルの時刻を数えて動画の情報を求める（デコードはしない）。 */
    fun probe(): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = findVideoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            var count = 0
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                count++
                if (t < first) first = t
                if (t > last) last = t
                if (!extractor.advance()) break
            }
            if (count == 0) throw IOException("no video samples: $file")

            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val (w, h) = rotatedSize(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), rotation)
            val span = last - first
            val fps = when {
                count >= 2 && span > 0 -> ((count - 1) * 1_000_000.0 / span).toFloat()
                format.containsKey(MediaFormat.KEY_FRAME_RATE) -> format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                else -> 30f
            }
            // 最後のコマは 1 コマ分の長さを足して、動画の長さとする
            val frameDurationUs = if (count >= 2) span / (count - 1) else 0L
            return VideoInfo(w, h, rotation, fps, count, span + frameDurationUs, first)
        } finally {
            extractor.release()
        }
    }

    /**
     * 全コマを順に取り出す。[onFrame] が false を返したらそこで止める。
     * [onFrame] の中でだけ [DecodedFrame] が使える（次のコマに進むと元の画像は解放される）。
     * @return 取り出したコマ数
     */
    fun decode(info: VideoInfo = probe(), onFrame: (DecodedFrame) -> Boolean): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        val thread = HandlerThread("VideoFrameReader").also { it.start() }
        try {
            extractor.setDataSource(file.path)
            val track = findVideoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("no mime: $file")

            val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val available = Semaphore(0)
            reader = ImageReader.newInstance(codedWidth, codedHeight, ImageFormat.YUV_420_888, MAX_IMAGES).also {
                it.setOnImageAvailableListener({ available.release() }, Handler(thread.looper))
            }
            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(format, reader.surface, null, 0)
                it.start()
            }

            var color = colorOf(format, codedWidth, codedHeight)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var index = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: throw IOException("no input buffer")
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        color = colorOf(codec.outputFormat, codedWidth, codedHeight)
                    outIndex >= 0 -> {
                        val isFrame = bufferInfo.size > 0
                        codec.releaseOutputBuffer(outIndex, isFrame)
                        if (isFrame) {
                            if (!available.tryAcquire(FRAME_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                                throw IOException("decoder did not deliver a frame: $file")
                            }
                            val image = reader.acquireNextImage() ?: throw IOException("no image: $file")
                            try {
                                val frame = DecodedFrame(
                                    index,
                                    (bufferInfo.presentationTimeUs - info.firstTimestampUs) / 1000,
                                    info,
                                    color,
                                ) { copyOf(image) }
                                index++
                                if (!onFrame(frame)) return index
                            } finally {
                                image.close()
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return index
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            reader?.close()
            extractor.release()
            thread.quitSafely()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("video/")) return i
        }
        throw IOException("no video track: $file")
    }

    private fun colorOf(format: MediaFormat, width: Int, height: Int): YuvColor {
        val bt709 = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
        } else {
            width >= 1280 || height >= 720 // 指定がなければ、HD 以上は BT.709 とみなす
        }
        val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
            format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        return YuvColor.of(bt709, full)
    }

    private fun copyOf(image: Image): YuvFrame {
        val crop = image.cropRect
        val planes = image.planes
        fun bytes(p: Image.Plane): ByteArray {
            val b = p.buffer.duplicate()
            return ByteArray(b.remaining()).also { b.get(it) }
        }
        return YuvFrame(
            width = crop.width(),
            height = crop.height(),
            cropLeft = crop.left,
            cropTop = crop.top,
            y = bytes(planes[0]),
            yRowStride = planes[0].rowStride,
            yPixelStride = planes[0].pixelStride,
            u = bytes(planes[1]),
            v = bytes(planes[2]),
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
        )
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val FRAME_TIMEOUT_SEC = 5L
        const val MAX_IMAGES = 3
    }
}
