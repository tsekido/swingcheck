package jp.co.updates.swingcheck.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.display.PhaseButtonKind
import jp.co.updates.swingcheck.display.SeekMath

/**
 * 各Pの位置に◆の印を付けたシークバー。
 * - バーのどこかをタップ／ドラッグ：そのコマへ移動（[onSeek]）
 * - ◆の近くから横にドラッグ：その◆（Pのコマ）をおおまかに動かす。動かしている間も映像はそのコマを表示し、
 *   指を離したら [onMarkMoved] でそのコマをそのPとして登録する
 * - ◆をタップ：そのPのコマへ移動
 *
 * [markFrames] と [markKinds] は P1〜P8 の順。
 */
@Composable
fun PhaseSeekBar(
    frameCount: Int,
    currentFrame: Int,
    markFrames: List<Int>,
    markKinds: List<PhaseButtonKind>,
    selectedMark: Int?,
    onSeek: (Int) -> Unit,
    onMarkMoved: (markIndex: Int, frame: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val padPx = with(density) { PAD.toPx() }
    val hitRadiusPx = with(density) { HIT_RADIUS.toPx() }

    // ドラッグ中の◆の位置。離した後、DB の更新が届くまでも残す（◆が一瞬元に戻らないように）
    var override by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(markFrames) { override = null }

    val frames by rememberUpdatedState(markFrames)
    val selected by rememberUpdatedState(selectedMark)
    val onSeekLatest by rememberUpdatedState(onSeek)
    val onMarkMovedLatest by rememberUpdatedState(onMarkMoved)

    val colors = MaterialTheme.colorScheme
    val trackColor = colors.outlineVariant
    val progressColor = colors.primary
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurface)
    val description = stringResource(R.string.result_seekbar_description)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .semantics { contentDescription = description }
            .pointerInput(frameCount, padPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val trackWidth = size.width - 2 * padPx
                    val xs = frames.mapIndexed { i, f ->
                        SeekMath.xOfFrame(override?.takeIf { it.first == i }?.second ?: f, padPx, trackWidth, frameCount)
                    }
                    val hit = SeekMath.hitTest(xs, down.position.x, hitRadiusPx, selected)
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop == null) {
                        // タップ（◆の近くならそのPのコマへ、それ以外はその位置のコマへ）
                        val f = if (hit != null) frames[hit] else SeekMath.frameAtX(down.position.x, padPx, trackWidth, frameCount)
                        onSeekLatest(f)
                    } else {
                        var last = SeekMath.frameAtX(slop.position.x, padPx, trackWidth, frameCount)
                        if (hit != null) override = hit to last
                        onSeekLatest(last)
                        horizontalDrag(slop.id) { change ->
                            val f = SeekMath.frameAtX(change.position.x, padPx, trackWidth, frameCount)
                            if (f != last) {
                                last = f
                                if (hit != null) override = hit to f
                                onSeekLatest(f)
                            }
                            change.consume()
                        }
                        if (hit != null) onMarkMovedLatest(hit, last)
                    }
                }
            },
    ) {
        val trackWidth = size.width - 2 * padPx
        val trackY = size.height - TRACK_BOTTOM.toPx()
        val strokeW = 4.dp.toPx()
        drawLine(trackColor, Offset(padPx, trackY), Offset(padPx + trackWidth, trackY), strokeW, StrokeCap.Round)
        val thumbX = SeekMath.xOfFrame(currentFrame, padPx, trackWidth, frameCount)
        drawLine(progressColor, Offset(padPx, trackY), Offset(thumbX, trackY), strokeW, StrokeCap.Round)

        // ◆の印（選択中のPは大きく）。同じ位置に重なるときは後ろのPが上に描かれる
        val diamondY = trackY - DIAMOND_OFFSET.toPx()
        frames.forEachIndexed { i, f ->
            val frame = override?.takeIf { it.first == i }?.second ?: f
            val x = SeekMath.xOfFrame(frame, padPx, trackWidth, frameCount)
            val isSelected = i == selected
            val half = (if (isSelected) 9.dp else 7.dp).toPx()
            val color = when (markKinds.getOrNull(i)) {
                PhaseButtonKind.MANUAL -> colors.tertiary
                PhaseButtonKind.UNCERTAIN -> colors.error
                else -> colors.primary
            }
            val diamond = Path().apply {
                moveTo(x, diamondY - half)
                lineTo(x + half, diamondY)
                lineTo(x, diamondY + half)
                lineTo(x - half, diamondY)
                close()
            }
            drawPath(diamond, color)
            if (isSelected) drawPath(diamond, colors.onSurface, style = Stroke(width = 1.5.dp.toPx()))
            val label = textMeasurer.measure((i + 1).toString(), labelStyle)
            drawText(label, topLeft = Offset(x - label.size.width / 2f, diamondY - half - label.size.height))
        }

        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(thumbX, trackY))
        drawCircle(progressColor, radius = 7.dp.toPx(), center = Offset(thumbX, trackY))
    }
}

private val PAD = 20.dp
private val HIT_RADIUS = 24.dp
private val BAR_HEIGHT = 68.dp
private val TRACK_BOTTOM = 14.dp
private val DIAMOND_OFFSET = 22.dp
