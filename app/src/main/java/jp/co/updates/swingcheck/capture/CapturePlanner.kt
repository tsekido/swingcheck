package jp.co.updates.swingcheck.capture

import jp.co.updates.swingcheck.settings.FpsMode
import kotlin.math.abs

/** 撮影を始めようとして失敗した設定。同じ端末で落ち続けないよう、次回からの選択で除外する（サイズは区別しない）。 */
data class FailedSetting(val cameraId: String, val sessionType: SessionType, val fps: Int) {
    fun encode(): String = "$cameraId|$sessionType|$fps"

    override fun toString(): String = "camera $cameraId $sessionType ${fps}fps"

    companion object {
        fun of(config: CaptureConfig) = FailedSetting(config.cameraId, config.sessionType, config.fps)

        fun decode(text: String): FailedSetting? {
            val parts = text.split('|')
            if (parts.size != 3) return null
            val type = SessionType.entries.firstOrNull { it.name == parts[1] } ?: return null
            val fps = parts[2].toIntOrNull() ?: return null
            return FailedSetting(parts[0], type, fps)
        }
    }
}

/**
 * 撮影の設定を試す順番を決める（design.md 2章）。Android に依存しないので JVM でテストできる。
 *
 * - 候補にするカメラは、最初の背面カメラ（通常はメイン）と焦点距離が同じものだけ。超広角・望遠は、
 *   高速撮影に対応していても選ばない（ゆがみが大きく解析に向かない）
 * - 順番は、要求 fps 以下を高い方から（AUTO なら 240 → 120 → 60 → 30）、そのあと要求より上を低い方から。
 *   同じ fps ではカメラ ID の順
 * - 失敗した設定は除外する
 */
object CapturePlanner {
    /** 焦点距離を「同じ」とみなす相対的な許容差。 */
    const val FOCAL_TOLERANCE = 0.05f

    /** 先頭のカメラ（getCameraIdList の最初の背面カメラ）と焦点距離が同じカメラ。先頭の焦点距離が不明なら先頭だけ。 */
    fun mainCameras(cameras: List<CameraCaps>): List<CameraCaps> {
        val first = cameras.firstOrNull() ?: return emptyList()
        val reference = first.focalLengthMm ?: return listOf(first)
        return cameras.filter { c ->
            c === first || (c.focalLengthMm?.let { abs(it - reference) <= reference * FOCAL_TOLERANCE } ?: false)
        }
    }

    /** 試す順に並べた設定。除外で空になるなら、記録が古いだけかもしれないので除外なしで並べる。 */
    fun plan(
        cameras: List<CameraCaps>,
        mode: FpsMode,
        encoderSupports: (CaptureSize, Int) -> Boolean,
        failed: Set<FailedSetting>,
    ): List<CaptureConfig> {
        val all = candidates(cameras, mode, encoderSupports)
        val kept = all.filter { FailedSetting.of(it) !in failed }
        return kept.ifEmpty { all }
    }

    private fun candidates(
        cameras: List<CameraCaps>,
        mode: FpsMode,
        encoderSupports: (CaptureSize, Int) -> Boolean,
    ): List<CaptureConfig> {
        val target = minOf(mode.fps ?: CaptureConfigSelector.AUTO_MAX_FPS, CaptureConfigSelector.AUTO_MAX_FPS)
        val configs = mainCameras(cameras).flatMap { caps ->
            CaptureConfigSelector.candidates(caps, encoderSupports)
                .groupBy { FailedSetting.of(it) }
                .values.map(CaptureConfigSelector::pickBySize)
        }
        // sortedBy は安定ソートなので、同じ fps ではカメラの順が保たれる
        return configs.sortedBy { if (it.fps <= target) -it.fps else Int.MAX_VALUE / 2 + it.fps }
    }
}
