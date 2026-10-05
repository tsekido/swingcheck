package jp.co.updates.swingcheck.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.co.updates.swingcheck.R

/** 立ち位置を合わせるための人型のガイド枠（輪郭だけ。合っているかの判定はしない）。 */
@Composable
fun GuideOverlay(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.85f)) {
    val description = stringResource(R.string.capture_guide_description)
    Box(modifier.fillMaxSize().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            // 100 × 200 の座標系で描き、画面の縦 70% に収めて中央に置く
            val targetH = size.height * 0.7f
            val scale = targetH / FIGURE_HEIGHT
            val figureW = FIGURE_WIDTH * scale
            val origin = Offset((size.width - figureW) / 2f, (size.height - targetH) / 2f)
            val stroke = Stroke(
                width = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
            )
            // 頭
            val headR = HEAD_RADIUS * scale
            drawCircle(
                color,
                radius = headR,
                center = origin + Offset(FIGURE_WIDTH / 2f * scale, HEAD_CENTER_Y * scale),
                style = stroke,
            )
            // 体（肩・腕・脚の輪郭）
            val body = Path().apply {
                BODY_OUTLINE.forEachIndexed { i, (x, y) ->
                    val p = origin + Offset(x * scale, y * scale)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(body, color, style = stroke)
        }
    }
}

private const val FIGURE_WIDTH = 100f
private const val FIGURE_HEIGHT = 200f
private const val HEAD_RADIUS = 13f
private const val HEAD_CENTER_Y = 14f

/** 正面を向いて立つ人の輪郭（肩 → 右腕 → 右脚 → 左脚 → 左腕）。 */
private val BODY_OUTLINE = listOf(
    44f to 30f, 56f to 30f, // 首
    74f to 40f, 82f to 98f, 66f to 100f, // 右肩〜右腕〜手
    66f to 106f, 64f to 196f, 52f to 196f, 50f to 124f, // 右脚
    48f to 124f, 46f to 196f, 36f to 196f, 34f to 106f, // 左脚
    34f to 100f, 18f to 98f, 26f to 40f, // 左腕〜左肩
)
