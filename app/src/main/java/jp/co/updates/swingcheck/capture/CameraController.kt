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
    private val onFailure: (String) -> Unit,
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

    /** カメラを開いてセッションを作り、リピートリクエストを始める。失敗したら例外（後始末は [close] で）。 */
    @SuppressLint("MissingPermission") // 呼び出し側（CaptureController）が権限を確認してから呼ぶ
    fun open() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val opened = CountDownLatch(1)
        var openError: String? = null
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
                    if (device == null) openError = "camera disconnected" else if (!closing) onFailure("camera disconnected")
                    device = null
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device == null) openError = "camera error $error" else if (!closing) onFailure("camera error $error")
                    device = null
                    opened.countDown()
                }
            },
        )
        if (!opened.await(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)) error("open camera timed out")
        val camera = device ?: error(openError ?: "cannot open camera")

        val sessionLatch = CountDownLatch(1)
        var sessionError: String? = null
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                sessionLatch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessionError = "createCaptureSession failed (${config.sessionType} ${config.size} ${config.fps}fps)"
                sessionLatch.countDown()
            }

            override fun onClosed(s: CameraCaptureSession) {
                if (session === s) session = null
            }
        }
        val type = if (config.sessionType == SessionType.HIGH_SPEED) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        camera.createCaptureSession(SessionConfiguration(type, outputs.map(::OutputConfiguration), executor, callback))
        if (!sessionLatch.await(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)) error("create session timed out")
        val s = session ?: error(sessionError ?: "cannot create session")

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
