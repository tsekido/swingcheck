package jp.co.updates.swingcheck.capture

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * カメラの出力（SurfaceTexture）を OpenGL ES で受け、(1) 画面の Surface に描き、(2) 約 [POSE_FPS] fps に間引いて
 * 小さい FBO に描いて読み出し、[PoseSink] に渡す。EGL のコンテキストは専用の GL スレッドだけが触る。
 *
 * @param cameraSize カメラの出力サイズ（回転前）
 * @param rotation 画面に正立させるために時計回りに回す角度（[CaptureOrientation.rotationHint]）
 * @param poseSize 縮小フレームの大きさ（回転後の向き）
 */
class GlPipeline(
    private val previewSurface: Surface,
    private val cameraSize: CaptureSize,
    private val rotation: Int,
    private val poseSize: CaptureSize,
    private val origin: TimeOrigin,
    private val sink: PoseSink,
) {
    private val thread = HandlerThread("swingcheck-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var aPos = 0
    private var uTexMatrix = 0
    private var uFlip = 0
    private var fbo = 0
    private var fboTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
    private val stMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(poseSize.width * poseSize.height * 4)

    private val drawQueued = AtomicBoolean(false)

    @Volatile
    private var released = false
    private var lastTimestampNs = Long.MIN_VALUE
    private var lastPreviewNs = Long.MIN_VALUE
    private val poseDecimator = FrameDecimator(POSE_FPS, 1_000_000_000L)
    private val frameRate = RateMeter()

    /** 最初に届いたフレームのタイムスタンプ（マイクロ秒）。エンコーダー側の時刻と同じ軸か確かめるのに使う。 */
    @Volatile
    var firstTimestampUs: Long? = null
        private set

    /** GL に届いたフレームの fps（デバッグ表示用）。 */
    fun frameFps(): Float = frameRate.perSecond(System.currentTimeMillis())

    /** GL を初期化して、カメラの出力先にする Surface を返す。失敗したら例外（後始末はしてある）。 */
    fun start(): Surface {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                initGl()
            } catch (e: Throwable) {
                error = e
            }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) error = IllegalStateException("GL init timed out")
        error?.let {
            stop()
            throw it
        }
        return cameraSurface!!
    }

    /** GL のリソースを解放して、GL スレッドを終える。画面の Surface が壊れる前に戻る（同期）。 */
    fun stop() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseGl()
            } catch (e: Throwable) {
                Log.w(TAG, "releaseGl failed", e)
            }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ---- 以降は GL スレッドで動く ----

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        windowSurface = EGL14.eglCreateWindowSurface(display, configs[0], previewSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(windowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) { "eglMakeCurrent failed" }
        // 画面のリフレッシュ（60Hz）に待たされると、高 fps のコマを受けきれなくなる
        EGL14.eglSwapInterval(display, 0)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uFlip = GLES20.glGetUniformLocation(program, "uFlip")

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        fboTexture = ids[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, poseSize.width, poseSize.height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(cameraSize.width, cameraSize.height)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        cameraSurface = Surface(st)
    }

    private fun onFrameAvailable() {
        if (released) return
        if (!drawQueued.getAndSet(true)) handler.post(::drawFrame)
    }

    private fun drawFrame() {
        drawQueued.set(false)
        val st = surfaceTexture ?: return
        if (released) return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        val tsNs = st.timestamp
        if (tsNs == lastTimestampNs) return
        lastTimestampNs = tsNs
        frameRate.tick(System.currentTimeMillis())
        st.getTransformMatrix(stMatrix)
        // uv を中心まわりに回してから、SurfaceTexture の変換を掛ける（回転の向きは実機で確認する）
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rotMatrix, 0)

        val tsUs = tsNs / 1000
        origin.ensure(tsUs)
        if (firstTimestampUs == null) firstTimestampUs = tsUs

        if (!sink.isBusy() && poseDecimator.shouldSample(tsNs)) drawPoseFrame(origin.toMs(tsUs))
        if (lastPreviewNs == Long.MIN_VALUE || tsNs - lastPreviewNs >= PREVIEW_MIN_INTERVAL_NS) {
            lastPreviewNs = tsNs
            drawPreview()
        }
    }

    private fun drawPreview() {
        val size = IntArray(1)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, size, 0)
        val w = size[0]
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, size, 0)
        val h = size[0]
        val display0 = CaptureOrientation.displaySize(cameraSize, rotation)
        val rect = Letterbox.fit(w, h, display0.width, display0.height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(rect.x, rect.y, rect.width, rect.height)
        drawQuad(flipY = false)
        EGL14.eglSwapBuffers(display, windowSurface)
    }

    private fun drawPoseFrame(timestampMs: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, poseSize.width, poseSize.height)
        // 読み出した行は下から上の順なので、描くときに上下を反転して、Bitmap の行の並びに合わせる
        drawQuad(flipY = true)
        pixels.clear()
        GLES20.glReadPixels(0, 0, poseSize.width, poseSize.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bitmap = Bitmap.createBitmap(poseSize.width, poseSize.height, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        sink.submit(bitmap, timestampMs)
    }

    private fun drawQuad(flipY: Boolean) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uFlip, 1f, if (flipY) -1f else 1f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun releaseGl() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        cameraSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, windowSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        windowSurface = EGL14.EGL_NO_SURFACE
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        private const val TAG = "GlPipeline"

        /** 骨格推定に渡すコマの間引き先（design.md 2章：毎秒 15 コマ程度）。 */
        const val POSE_FPS = 15

        /** 高 fps（240 など）でも画面への描画は最大でおよそ 60fps にする。 */
        private const val PREVIEW_MIN_INTERVAL_NS = 15_000_000L

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform mat4 uTexMatrix;
            uniform vec2 uFlip;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos * uFlip, 0.0, 1.0);
                vTex = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """
    }
}
