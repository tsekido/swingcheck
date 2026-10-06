package jp.co.updates.swingcheck.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Landmark
import jp.co.updates.swingcheck.core.PoseFrame

/**
 * MediaPipe Pose Landmarker（VIDEO モード）による骨格推定。モデルは assets の [MODEL_ASSET]
 * （ビルド時にダウンロードして入れる。app/build.gradle.kts の downloadPoseModel）。
 * 1 本の動画につき 1 インスタンスを作り、コマを時刻の昇順で渡す。スレッドセーフではない。
 */
class MediaPipePoseEstimator(context: Context, preferGpu: Boolean = true) : PoseEstimator {
    private val appContext = context.applicationContext
    private var landmarker: PoseLandmarker

    /** 実際に使っているデリゲート。GPU の初期化や最初の推定に失敗したら CPU になる。 */
    var delegate: Delegate = if (preferGpu) Delegate.GPU else Delegate.CPU
        private set

    /** GPU で 1 コマも推定できていない間は true。最初の推定で失敗したら CPU で作り直す。 */
    private var gpuUnverified: Boolean

    init {
        var created: PoseLandmarker? = null
        if (delegate == Delegate.GPU) {
            try {
                created = create(Delegate.GPU)
            } catch (e: Throwable) {
                Log.w(TAG, "GPU delegate init failed, falling back to CPU", e)
                delegate = Delegate.CPU
            }
        }
        landmarker = created ?: create(Delegate.CPU)
        gpuUnverified = delegate == Delegate.GPU
        Log.i(TAG, "backend=$delegate")
    }

    private fun create(delegate: Delegate): PoseLandmarker {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        return PoseLandmarker.createFromOptions(appContext, options)
    }

    override fun estimate(bitmap: Bitmap, timestampMs: Long): PoseFrame? {
        val image = BitmapImageBuilder(bitmap).build()
        val result = try {
            landmarker.detectForVideo(image, timestampMs).also { gpuUnverified = false }
        } catch (e: Throwable) {
            if (!gpuUnverified) throw e
            // GPU は作れても推定で失敗する端末がある。最初の 1 コマで失敗したら CPU で作り直してやり直す
            Log.w(TAG, "GPU delegate failed on first frame, falling back to CPU", e)
            runCatching { landmarker.close() }
            delegate = Delegate.CPU
            gpuUnverified = false
            landmarker = create(Delegate.CPU)
            Log.i(TAG, "backend=$delegate")
            landmarker.detectForVideo(image, timestampMs)
        }
        val pose = result.landmarks().firstOrNull() ?: return null
        if (pose.size != Joint.COUNT) return null
        return PoseFrame(
            timestampMs,
            pose.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) },
        )
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        private const val TAG = "MediaPipePose"
        const val MODEL_ASSET = "pose_landmarker_full.task"
    }
}
