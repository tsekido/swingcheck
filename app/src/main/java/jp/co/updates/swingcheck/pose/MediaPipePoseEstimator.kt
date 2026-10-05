package jp.co.updates.swingcheck.pose

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
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
class MediaPipePoseEstimator(context: Context) : PoseEstimator {
    private val landmarker: PoseLandmarker

    init {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        landmarker = PoseLandmarker.createFromOptions(context.applicationContext, options)
    }

    override fun estimate(bitmap: Bitmap, timestampMs: Long): PoseFrame? {
        val image = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(image, timestampMs)
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
        const val MODEL_ASSET = "pose_landmarker_full.task"
    }
}
