package jp.co.updates.swingcheck.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * H.264 エンコーダー（Surface 入力）。出力は圧縮済みのコマとして [buffer] に入れる。
 * カメラの出力先は [inputSurface]。キーフレーム間隔は短め（[KEY_FRAME_INTERVAL_SEC]）にして、切り出しの開始位置を細かく選べるようにする。
 */
class VideoEncoder(
    codecInfo: MediaCodecInfo,
    private val size: CaptureSize,
    private val fps: Int,
    private val buffer: SampleRingBuffer,
    private val origin: TimeOrigin,
    /** コマを buffer に入れるたびに（エンコーダーのスレッドで）呼ぶ */
    private val onSampleAdded: () -> Unit,
) {
    val codecName: String = codecInfo.name
    val bitrate: Int = Bitrate.forVideo(size.width, size.height, fps)

    private val codec: MediaCodec = MediaCodec.createByCodecName(codecName)
    val inputSurface: Surface

    @Volatile
    var outputFormat: MediaFormat? = null
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // 実測値（デバッグ表示用）。エンコーダーのスレッドだけが書く
    @Volatile
    var keyFrameIntervalMs: Long? = null
        private set

    @Volatile
    var firstPtsUs: Long? = null
        private set
    private var lastKeyPtsUs: Long? = null
    private val rate = RateMeter()

    /** 実際にエンコーダーから出てくる fps。 */
    fun outputFps(): Float = rate.perSecond(System.currentTimeMillis())

    init {
        try {
            configure(withOptionalKeys = true)
        } catch (e: Exception) {
            // KEY_OPERATING_RATE などの付加的な設定で落ちる端末があるので、外してやり直す
            Log.w(TAG, "configure failed, retrying without optional keys", e)
            codec.reset()
            configure(withOptionalKeys = false)
        }
        inputSurface = codec.createInputSurface()
    }

    private fun configure(withOptionalKeys: Boolean) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 整数の秒しか受け付けない端末では 0 や 1 に丸められる。実測は keyFrameIntervalMs で確認する
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SEC)
            if (withOptionalKeys) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // 時刻が単調増加になるように
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // リアルタイム
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        running = true
        thread = Thread(::drainLoop, "swingcheck-encoder").also { it.start() }
    }

    /** カメラの出力を止めてから呼ぶこと。 */
    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop failed", e)
        }
        codec.release()
        inputSurface.release()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    index >= 0 -> {
                        try {
                            handleOutput(index, info)
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "encoder drain loop failed", e)
        }
    }

    private fun handleOutput(index: Int, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size <= 0) return // csd は outputFormat に入っている
        val out = codec.getOutputBuffer(index) ?: return
        out.position(info.offset)
        out.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        out.get(bytes)
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val pts = info.presentationTimeUs

        origin.ensure(pts)
        if (firstPtsUs == null) firstPtsUs = pts
        if (key) {
            lastKeyPtsUs?.let { keyFrameIntervalMs = (pts - it) / 1000 }
            lastKeyPtsUs = pts
        }
        rate.tick(System.currentTimeMillis())
        buffer.add(EncodedSample(bytes, pts, key))
        onSampleAdded()
    }

    companion object {
        private const val TAG = "VideoEncoder"
        const val KEY_FRAME_INTERVAL_SEC = 0.5f
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
