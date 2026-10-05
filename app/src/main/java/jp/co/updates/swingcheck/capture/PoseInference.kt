package jp.co.updates.swingcheck.capture

import android.graphics.Bitmap
import android.util.Log
import jp.co.updates.swingcheck.analysis.Timestamps
import jp.co.updates.swingcheck.core.SwingClip
import jp.co.updates.swingcheck.core.SwingDetector
import jp.co.updates.swingcheck.core.SwingState
import jp.co.updates.swingcheck.pose.PoseEstimator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 推定スレッド。縮小フレームを 1 コマずつ骨格推定にかけ、結果を [SwingDetector] に与える。
 * 推定が終わっていないときに来たコマは捨てる（待たせない）。検出したら [onClip] を推定スレッドから呼ぶ。
 * 骨格推定は VIDEO モードの同期呼び出し（時刻は増え続けるように [Timestamps.nextAfter] で整える）。
 */
class PoseInference(
    private val estimatorFactory: () -> PoseEstimator,
    private val detector: SwingDetector,
    private val onClip: (SwingClip) -> Unit,
) : PoseSink, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "swingcheck-pose") }
    private val busy = AtomicBoolean(false)
    private val rate = RateMeter()
    private val dropped = AtomicInteger(0)
    private var estimator: PoseEstimator? = null // 推定スレッドだけが触る
    private var lastTimestampMs = -1L

    @Volatile
    var state: SwingState = SwingState.IDLE
        private set

    /** 推定の処理 fps（デバッグ表示用）。 */
    fun processedFps(): Float = rate.perSecond(System.currentTimeMillis())

    /** 推定が追いつかず捨てたコマの数。 */
    val droppedCount: Int get() = dropped.get()

    override fun isBusy(): Boolean = busy.get()

    override fun submit(bitmap: Bitmap, timestampMs: Long) {
        if (!busy.compareAndSet(false, true)) {
            dropped.incrementAndGet()
            bitmap.recycle()
            return
        }
        try {
            executor.execute { process(bitmap, timestampMs) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            bitmap.recycle()
            busy.set(false)
        }
    }

    private fun process(bitmap: Bitmap, timestampMs: Long) {
        try {
            val ts = Timestamps.nextAfter(lastTimestampMs, timestampMs)
            lastTimestampMs = ts
            val est = estimator ?: estimatorFactory().also { estimator = it }
            val pose = est.estimate(bitmap, ts)
            val update = detector.update(ts, pose)
            state = update.state
            rate.tick(System.currentTimeMillis())
            update.detected?.let(onClip)
        } catch (e: Exception) {
            Log.e(TAG, "pose inference failed", e)
        } finally {
            bitmap.recycle()
            busy.set(false)
        }
    }

    /** 推定スレッドを止めて、推定器を閉じる。実行中の 1 コマが終わるまで待つ。 */
    override fun close() {
        executor.execute {
            runCatching { estimator?.close() }
            estimator = null
        }
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    private companion object {
        const val TAG = "PoseInference"
    }
}
