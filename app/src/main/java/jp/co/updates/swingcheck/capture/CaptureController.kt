package jp.co.updates.swingcheck.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import jp.co.updates.swingcheck.SwingRegistrar
import jp.co.updates.swingcheck.analysis.AnalysisGate
import jp.co.updates.swingcheck.core.SwingClip
import jp.co.updates.swingcheck.core.SwingDetector
import jp.co.updates.swingcheck.core.SwingState
import jp.co.updates.swingcheck.data.SwingFiles
import jp.co.updates.swingcheck.pose.PoseEstimator
import jp.co.updates.swingcheck.settings.SettingsRepository
import jp.co.updates.swingcheck.video.YuvConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 撮影パイプラインで実際に選ばれた設定。画面と開発用画面に出す。 */
data class CaptureInfo(
    val config: CaptureConfig,
    val encoderName: String,
    val bitrate: Int,
    val sensorOrientation: Int,
    /** 動画に付ける回転（時計回り） */
    val rotationHint: Int,
    /** 骨格推定に渡す縮小フレームの大きさ（回転後） */
    val poseSize: CaptureSize,
) {
    fun toText(): String = buildString {
        appendLine("camera: ${config.cameraId}")
        appendLine("session: ${config.sessionType}")
        appendLine("size: ${config.size}")
        appendLine("fps: ${config.fps} (AE range ${config.fpsRange})")
        appendLine("encoder: $encoderName, ${bitrate / 1000} kbps")
        appendLine("sensor orientation: $sensorOrientation, rotation hint: $rotationHint")
        append("pose frame size: $poseSize")
    }
}

data class CaptureStatus(
    val state: State = State.IDLE,
    val info: CaptureInfo? = null,
    val error: String? = null,
) {
    enum class State { IDLE, STARTING, RUNNING, ERROR }
}

/** 撮影中の計測値（デバッグ表示用）。約 0.5 秒ごとに更新する。 */
data class CaptureDebug(
    val detectorState: SwingState? = null,
    val poseFps: Float = 0f,
    val glFps: Float = 0f,
    val encoderFps: Float = 0f,
    val droppedPoseFrames: Int = 0,
    val bufferSeconds: Float = 0f,
    val bufferMegabytes: Float = 0f,
    val keyFrameIntervalMs: Long? = null,
    /** エンコーダーの最初の時刻 − GL の最初の時刻（ミリ秒）。同じ時刻軸なら 0 に近い */
    val timeBaseOffsetMs: Long? = null,
    val savedClips: Int = 0,
    val lastExportError: String? = null,
) {
    fun toText(): String = buildString {
        append("state=${detectorState ?: "-"}  pose=${"%.1f".format(poseFps)}fps (dropped $droppedPoseFrames)\n")
        append("gl=${"%.0f".format(glFps)}fps enc=${"%.0f".format(encoderFps)}fps  kf=${keyFrameIntervalMs ?: "-"}ms\n")
        append("buf=${"%.1f".format(bufferSeconds)}s/${"%.1f".format(bufferMegabytes)}MB  clips=$savedClips  tb=${timeBaseOffsetMs ?: "-"}ms")
        lastExportError?.let { append("\nexport error: $it") }
    }
}

/**
 * 撮影の全体（design.md 2章）：カメラ → エンコーダー → リングバッファ、カメラ → GL → 縮小フレーム → 骨格推定 → スイング検出、
 * 検出 → 切り出し → MP4 → Swing を作って解析の順番待ちへ。
 *
 * [start]／[stop] はどのスレッドから呼んでもよい。実際の処理は専用の制御スレッドで 1 つずつ行う。
 * [stop] は終わるまで待つ（SurfaceView の surfaceDestroyed の前に GL が Surface を手放している必要があるため）。
 */
class CaptureController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val files: SwingFiles,
    private val registrar: SwingRegistrar,
    private val gate: AnalysisGate,
    private val estimatorFactory: () -> PoseEstimator,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(CaptureStatus())
    val status: StateFlow<CaptureStatus> = _status

    private val _debug = MutableStateFlow(CaptureDebug())
    val debug: StateFlow<CaptureDebug> = _debug

    /** 最後に選ばれた設定（撮影を止めても残る。開発用画面で見る）。 */
    private val _lastInfo = MutableStateFlow<CaptureInfo?>(null)
    val lastInfo: StateFlow<CaptureInfo?> = _lastInfo

    private val control = Executors.newSingleThreadExecutor { Thread(it, "swingcheck-capture-control") }
    private var run: Run? = null // 制御スレッドだけが触る

    @OptIn(ExperimentalCoroutinesApi::class)
    private val exportDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 撮影を始める。すでに同じ Surface で動いていれば何もしない。結果は [status] に出る。 */
    fun start(previewSurface: Surface, displayRotationDegrees: Int) {
        control.execute { startOnControl(previewSurface, displayRotationDegrees) }
    }

    /** 撮影を止めて、カメラ・エンコーダー・GL を解放する（終わるまで待つ）。動いていなければすぐ戻る。 */
    fun stop() {
        try {
            control.submit { stopOnControl() }.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
    }

    /** 設定を試す順番と、試すのに必要なもの。やり直しのときに使い回す。 */
    private class Attempts(
        val previewSurface: Surface,
        val displayRotationDegrees: Int,
        val configs: List<CaptureConfig>,
        val sensorOrientations: Map<String, Int>,
    )

    private fun startOnControl(previewSurface: Surface, displayRotationDegrees: Int) {
        run?.let {
            if (it.previewSurface === previewSurface) return
            stopOnControl()
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _status.value = CaptureStatus(CaptureStatus.State.ERROR, error = "camera permission is not granted")
            return
        }
        _status.value = CaptureStatus(CaptureStatus.State.STARTING)
        val attempts = try {
            val mode = runBlocking { settings.current().fpsMode }
            val failed = runBlocking { settings.failedCaptureSettings.first() }.mapNotNull(FailedSetting::decode).toSet()
            val cameras = CameraCapabilitiesReader.readBackCameras(context)
            val configs = CapturePlanner.plan(cameras.map { it.caps }, mode, CameraCapabilitiesReader::encoderSupports, failed)
            if (configs.isEmpty()) error("no supported capture configuration (back cameras: ${cameras.size})")
            Attempts(previewSurface, displayRotationDegrees, configs, cameras.associate { it.caps.cameraId to it.sensorOrientation })
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            _status.value = CaptureStatus(CaptureStatus.State.ERROR, error = e.message ?: e.toString())
            return
        }
        tryFrom(attempts, 0)
    }

    /** [from] 番目の設定から順に、動くものが見つかるまで試す。すべて失敗したらエラーにする。 */
    private fun tryFrom(attempts: Attempts, from: Int, previousError: String? = null) {
        var lastError = previousError ?: "no supported capture configuration"
        for (index in from until attempts.configs.size) {
            val config = attempts.configs[index]
            val r = Run(attempts, index, config)
            run = r
            try {
                r.start()
                gate.setCapturing(true)
                _lastInfo.value = r.info
                _status.value = CaptureStatus(CaptureStatus.State.RUNNING, r.info)
                return
            } catch (e: Throwable) {
                Log.e(TAG, "start failed (${FailedSetting.of(config)})", e)
                r.stop()
                run = null
                gate.setCapturing(false)
                lastError = e.message ?: e.toString()
                val kind = (e as? CameraFailure)?.kind ?: CameraFailure.Kind.RETRY
                if (kind == CameraFailure.Kind.RETRY_AND_RECORD) record(config)
                if (kind == CameraFailure.Kind.FATAL) break
            }
        }
        _status.value = CaptureStatus(CaptureStatus.State.ERROR, error = lastError)
    }

    /** 失敗した設定を覚えて、次回からの選択で外す。 */
    private fun record(config: CaptureConfig) {
        try {
            runBlocking { settings.addFailedCaptureSetting(FailedSetting.of(config).encode()) }
        } catch (e: Exception) {
            Log.w(TAG, "cannot record the failed setting", e)
        }
    }

    private fun stopOnControl() {
        val r = run ?: return
        run = null
        r.stop()
        gate.setCapturing(false)
        _status.value = CaptureStatus()
    }

    /**
     * カメラが止まった（切断など）。制御スレッドで止める。始めてすぐ（[RETRY_WINDOW_MS] 以内）なら
     * 次の設定でやり直し、それ以外はエラーにする。
     */
    private fun onCameraFailure(r: Run, failure: CameraFailure) {
        control.execute {
            if (run !== r) return@execute
            val early = SystemClock.elapsedRealtime() - r.startedAtMs < RETRY_WINDOW_MS
            Log.w(TAG, "camera stopped (${FailedSetting.of(r.config)}, early=$early): ${failure.message}")
            stopOnControl()
            if (early && failure.kind != CameraFailure.Kind.FATAL) {
                if (failure.kind == CameraFailure.Kind.RETRY_AND_RECORD) record(r.config)
                _status.value = CaptureStatus(CaptureStatus.State.STARTING)
                tryFrom(r.attempts, r.index + 1, failure.message)
            } else {
                _status.value = CaptureStatus(CaptureStatus.State.ERROR, error = failure.message)
            }
        }
    }

    /** 1 回の撮影の間だけ生きるものをまとめたもの。 */
    private inner class Run(val attempts: Attempts, val index: Int, val config: CaptureConfig) {
        val previewSurface: Surface get() = attempts.previewSurface

        /** 撮影が始まった時刻（elapsedRealtime）。始めてすぐの失敗かどうかを見る */
        var startedAtMs = 0L
            private set

        private val buffer = SampleRingBuffer(RING_DURATION_US, RING_MAX_BYTES)
        private val origin = TimeOrigin()
        private val coordinator = ClipCoordinator(buffer, origin)

        private var encoder: VideoEncoder? = null
        private var gl: GlPipeline? = null
        private var camera: CameraController? = null
        private var inference: PoseInference? = null
        private var ticker: ScheduledExecutorService? = null

        lateinit var info: CaptureInfo
            private set

        @Volatile
        private var savedClips = 0

        @Volatile
        private var lastExportError: String? = null

        @Volatile
        private var stopped = false

        fun start() {
            val sensorOrientation = attempts.sensorOrientations[config.cameraId] ?: 90
            val codecInfo = CameraCapabilitiesReader.findEncoder() ?: error("no H.264 encoder")
            val rotation = CaptureOrientation.rotationHint(sensorOrientation, attempts.displayRotationDegrees)
            val display = CaptureOrientation.displaySize(config.size, rotation)
            val (pw, ph) = YuvConverter.scaledSize(display.width, display.height, POSE_LONG_SIDE)
            val poseSize = CaptureSize(pw, ph)

            val enc = VideoEncoder(codecInfo, config.size, config.fps, buffer, origin) { export(coordinator.onSampleAdded()) }
            encoder = enc
            info = CaptureInfo(config, enc.codecName, enc.bitrate, sensorOrientation, rotation, poseSize)

            val detector = SwingDetector(width = pw, height = ph)
            val inf = PoseInference(estimatorFactory, detector) { clip -> onClip(clip) }
            inference = inf

            enc.start()
            val g = GlPipeline(attempts.previewSurface, config.size, rotation, poseSize, origin, inf)
            gl = g
            val cameraSurface = g.start()
            val cam = CameraController(context, config, listOf(enc.inputSurface, cameraSurface)) { onCameraFailure(this, it) }
            camera = cam
            cam.open()
            startedAtMs = SystemClock.elapsedRealtime()

            ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "swingcheck-capture-stats") }.also {
                it.scheduleWithFixedDelay(::publishDebug, 500, 500, TimeUnit.MILLISECONDS)
            }
        }

        fun stop() {
            if (stopped) return
            stopped = true
            ticker?.shutdownNow()
            // 入力（カメラ）から順に止める
            camera?.close()
            gl?.stop()
            encoder?.stop()
            inference?.close()
            // 検出済みで書き出し待ちの分は、バッファにあるところまでで書く
            export(coordinator.flush())
            _debug.value = CaptureDebug()
        }

        /** 推定スレッドから：検出されたスイング。endMs まで届いていれば、すぐ書き出す。 */
        private fun onClip(clip: SwingClip) {
            Log.i(TAG, "swing detected: $clip")
            export(coordinator.request(clip))
        }

        /** 書き出しは専用の（1 本だけの）スレッドで順に行い、撮影と推定を止めない。 */
        private fun export(selections: List<ClipSelection>) {
            if (selections.isEmpty()) return
            val format = encoder?.outputFormat ?: return
            val rotation = info.rotationHint
            for (selection in selections) {
                scope.launch(exportDispatcher) {
                    val path = files.newVideoPath()
                    try {
                        val written = ClipWriter.write(selection, format, config.size, rotation, config.fps, files.resolve(path))
                        try {
                            registrar.register(path, written.fps, written.frameCount, written.width, written.height)
                        } catch (e: Throwable) {
                            files.resolve(path).delete()
                            throw e
                        }
                        savedClips++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "export failed", e)
                        lastExportError = e.message ?: e.toString()
                    }
                }
            }
        }

        private fun publishDebug() {
            val enc = encoder ?: return
            val g = gl ?: return
            val inf = inference ?: return
            val oldest = buffer.oldestPtsUs
            val latest = buffer.latestPtsUs
            val encFirst = enc.firstPtsUs
            val glFirst = g.firstTimestampUs
            _debug.value = CaptureDebug(
                detectorState = inf.state,
                poseFps = inf.processedFps(),
                glFps = g.frameFps(),
                encoderFps = enc.outputFps(),
                droppedPoseFrames = inf.droppedCount,
                bufferSeconds = if (oldest != null && latest != null) (latest - oldest) / 1_000_000f else 0f,
                bufferMegabytes = buffer.byteCount / (1024f * 1024f),
                keyFrameIntervalMs = enc.keyFrameIntervalMs,
                timeBaseOffsetMs = if (encFirst != null && glFirst != null) (encFirst - glFirst) / 1000 else null,
                savedClips = savedClips,
                lastExportError = lastExportError,
            )
        }
    }

    companion object {
        private const val TAG = "CaptureController"

        /** 撮影を始めてからこの時間以内に止まったら、設定のせいとみなして次の設定でやり直す。 */
        private const val RETRY_WINDOW_MS = 5_000L

        /** design.md 8章：リングバッファの長さは 6 秒。 */
        const val RING_DURATION_US = 6_000_000L

        /** メモリの上限。240fps・1080p（約 35Mbps）の 6.5 秒分（約 28MB）に余裕を持たせた値。 */
        const val RING_MAX_BYTES = 64L * 1024 * 1024

        /** 縮小フレームの長辺（design.md：256〜320px 程度）。 */
        const val POSE_LONG_SIDE = 320
    }
}
