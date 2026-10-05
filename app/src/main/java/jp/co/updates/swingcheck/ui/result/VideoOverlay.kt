package jp.co.updates.swingcheck.ui.result

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.core.Derived
import jp.co.updates.swingcheck.core.ForearmShaftEstimator
import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.PixelPose
import jp.co.updates.swingcheck.core.ShaftPhase

/** 動画の上に、骨格の線と推定シャフトの線を重ねて描く。 */
@Composable
fun VideoWithOverlay(
    player: FramePlayer,
    pose: PixelPose,
    imageWidth: Int,
    imageHeight: Int,
    shoulderWidthPx: Double,
    shaftPhase: ShaftPhase,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.result_video_description)
    Box(modifier.semantics { contentDescription = description }) {
        AndroidView(
            factory = { context -> SurfaceView(context).also(player::attach) },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / imageWidth
            val sy = size.height / imageHeight
            fun at(x: Double, y: Double) = Offset((x * sx).toFloat(), (y * sy).toFloat())
            fun joint(j: Int) = at(pose.xs[j], pose.ys[j])

            val bone = 3.dp.toPx()
            for ((a, b) in BONES) {
                drawLine(SKELETON_COLOR, joint(a), joint(b), bone, StrokeCap.Round)
            }
            val head = Derived.head(pose)
            drawCircle(SKELETON_COLOR, radius = 5.dp.toPx(), center = at(head.x, head.y))
            for (j in JOINTS) drawCircle(SKELETON_COLOR, radius = 4.dp.toPx(), center = joint(j))

            // 推定シャフト（骨格の向きからの推定。精度は低め）
            val shaft = ForearmShaftEstimator().estimate(pose, shoulderWidthPx, shaftPhase)
            drawLine(SHAFT_COLOR, at(shaft.grip.x, shaft.grip.y), at(shaft.tip.x, shaft.tip.y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(SHAFT_COLOR, radius = 5.dp.toPx(), center = at(shaft.grip.x, shaft.grip.y))
        }
    }
}

private val SKELETON_COLOR = Color(0xFF00E5FF)
private val SHAFT_COLOR = Color(0xFFFFB300)

private val BONES = listOf(
    Joint.LEFT_SHOULDER to Joint.RIGHT_SHOULDER,
    Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
    Joint.LEFT_ELBOW to Joint.LEFT_WRIST,
    Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
    Joint.RIGHT_ELBOW to Joint.RIGHT_WRIST,
    Joint.LEFT_WRIST to Joint.LEFT_INDEX,
    Joint.RIGHT_WRIST to Joint.RIGHT_INDEX,
    Joint.LEFT_SHOULDER to Joint.LEFT_HIP,
    Joint.RIGHT_SHOULDER to Joint.RIGHT_HIP,
    Joint.LEFT_HIP to Joint.RIGHT_HIP,
    Joint.LEFT_HIP to Joint.LEFT_KNEE,
    Joint.LEFT_KNEE to Joint.LEFT_ANKLE,
    Joint.RIGHT_HIP to Joint.RIGHT_KNEE,
    Joint.RIGHT_KNEE to Joint.RIGHT_ANKLE,
)

private val JOINTS = listOf(
    Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW,
    Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_HIP, Joint.RIGHT_HIP,
    Joint.LEFT_KNEE, Joint.RIGHT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE,
)
