package jp.co.updates.swingcheck.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** カメラの失敗。[kind] で、次の設定でやり直すか・失敗した設定として記録するかを分ける。 */
class CameraFailure(message: String, val kind: Kind, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Kind {
        /** 使用中・無効化など。設定を変えても直らないのでやり直さない */
        FATAL,

        /** 次の設定でやり直す。設定のせいとは限らない（切断など）ので記録はしない */
        RETRY,

        /** 次の設定でやり直し、この設定は次回から選ばない（セッション作成の失敗、HAL の落ちなど） */
        RETRY_AND_RECORD,
    }
}

/**
 * Camera2 で背面カメラを開き、2 つの出力（エンコーダーの入力 Surface と SurfaceTexture の Surface）へ流す。
 * 高速撮影（[SessionType.HIGH_SPEED]）でも通常でも、同じ 2 出力の構成にする。
 * コールバックはすべて専用のカメラスレッドで受ける。
 */
class CameraController(
    private val context: Context,
    private val config: CaptureConfig,
    private val outputs: List<Surface>,
    /** 開いたあとにカメラが止まった（切断・エラー）とき。カメラスレッドから呼ぶ */
    private val onFailure: (CameraFailure) -> Unit,
) {
    private val thread = HandlerThread("swingcheck-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = java.util.concurrent.Executor { handler.post(it) }

    @Volatile
    private var device: CameraDevice? = null

    @Volatile
    private var session: CameraCaptureSession? = null

    @Volatile
    private var closing = false

    /** セッション作成の待ち。待っている間にカメラが止まったら、すぐ起こす */
    @Volatile
    private var pendingLatch: CountDownLatch? = null

    @Volatile
    private var stoppedFailure: CameraFailure? = null

    /** カメラを開いてセッションを作り、リピートリクエストを始める。失敗したら例外（後始末は [close] で）。 */
    @SuppressLint("MissingPermission") // 呼び出し側（CaptureController）が権限を確認してから呼ぶ
    fun open() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val opened = CountDownLatch(1)
        var openError: CameraFailure? = null
        manager.openCamera(
            config.cameraId,
            executor,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handleStopped(CameraFailure("camera disconnected", CameraFailure.Kind.RETRY)) { openError = it }
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val kind = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE, CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                            CameraFailure.Kind.RETRY_AND_RECORD
                        else -> CameraFailure.Kind.FATAL // 使用中・台数超過・無効化
                    }
                    handleStopped(CameraFailure("camera error $error", kind)) { openError = it }
                    opened.countDown()
                }
            },
        )
        if (!opened.await(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            throw CameraFailure("open camera timed out", CameraFailure.Kind.RETRY_AND_RECORD)
        }
        val camera = device ?: throw (openError ?: CameraFailure("cannot open camera", CameraFailure.Kind.RETRY))

        val sessionLatch = CountDownLatch(1)
        var sessionError: CameraFailure? = null
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                sessionLatch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessionError = CameraFailure(
                    "createCaptureSession failed (${config.sessionType} ${config.size} ${config.fps}fps)",
                    CameraFailure.Kind.RETRY_AND_RECORD,
                )
                sessionLatch.countDown()
            }

            override fun onClosed(s: CameraCaptureSession) {
                if (session === s) session = null
            }
        }
        try {
            pendingLatch = sessionLatch
            val type = if (config.sessionType == SessionType.HIGH_SPEED) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
            camera.createCaptureSession(SessionConfiguration(type, outputs.map(::OutputConfiguration), executor, callback))
            if (!sessionLatch.await(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw CameraFailure("create session timed out", CameraFailure.Kind.RETRY_AND_RECORD)
            }
            startRepeating(camera, session ?: throw (sessionError ?: stoppedFailure ?: CameraFailure("cannot create session", CameraFailure.Kind.RETRY)))
        } catch (e: CameraFailure) {
            throw e
        } catch (e: CameraAccessException) {
            throw CameraFailure("camera access failed: ${e.message}", accessKind(e), e)
        } catch (e: IllegalArgumentException) {
            throw CameraFailure("camera rejected the configuration: ${e.message}", CameraFailure.Kind.RETRY_AND_RECORD, e)
        } catch (e: IllegalStateException) {
            // 開始の途中でカメラが閉じた（HAL の落ちなど）
            throw CameraFailure("camera closed while starting: ${e.message}", CameraFailure.Kind.RETRY_AND_RECORD, e)
        }
    }

    /** カメラが止まったときの共通処理。以後は閉じたものとして扱う（閉じたカメラに stopRepeating などを呼ばない）。 */
    private fun handleStopped(failure: CameraFailure, whileOpening: (CameraFailure) -> Unit) {
        val wasOpen = device != null
        device = null
        session = null
        stoppedFailure = failure
        pendingLatch?.countDown()
        if (!wasOpen) whileOpening(failure) else if (!closing) onFailure(failure)
    }

    private fun accessKind(e: CameraAccessException) = when (e.reason) {
        CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE, CameraAccessException.CAMERA_DISABLED ->
            CameraFailure.Kind.FATAL
        else -> CameraFailure.Kind.RETRY_AND_RECORD
    }

    private fun startRepeating(camera: CameraDevice, s: CameraCaptureSession) {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fpsRange.lower, config.fpsRange.upper))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            outputs.forEach(::addTarget)
            if (config.sessionType == SessionType.NORMAL) {
                // 手ぶれ補正は切り出しとコマの遅れの原因になるので切る（高速撮影では指定できない）
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
        }
        if (config.sessionType == SessionType.HIGH_SPEED) {
            val hs = s as CameraConstrainedHighSpeedCaptureSession
            hs.setRepeatingBurst(hs.createHighSpeedRequestList(builder.build()), null, handler)
        } else {
            s.setRepeatingRequest(builder.build(), null, handler)
        }
    }

    /** 止めて閉じる（同期）。何度呼んでもよい。 */
    fun close() {
        if (closing) return
        closing = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                // カメラが止まったあと（device == null）は、セッションも閉じているので stopRepeating などは呼ばない
                if (device != null) {
                    session?.let {
                        try {
                            it.stopRepeating()
                        } catch (e: CameraAccessException) {
                            Log.w(TAG, "stopRepeating failed", e)
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "stopRepeating failed", e)
                        }
                        it.close()
                    }
                }
                session = null
                device?.close()
                device = null
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
            latch.countDown()
        }
        latch.await(CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraController"
        private const val OPEN_TIMEOUT_SEC = 5L
        private const val CLOSE_TIMEOUT_SEC = 3L
    }
}
