package jp.co.updates.swingcheck.video

import kotlin.math.roundToLong

/** 読み込んだ動画を受け付けるかの判定結果。 */
sealed interface ImportVerdict {
    /** [timeScale] は時刻の補正倍率（1 なら補正なし）。実時間 = 動画の時刻 ÷ timeScale。 */
    data class Accept(val timeScale: Float) : ImportVerdict {
        val stretched: Boolean get() = timeScale > 1f
    }

    /** 長すぎる。[durationSec] は補正後（実時間）の長さ。 */
    data class TooLong(val durationSec: Double) : ImportVerdict
}

/**
 * 読み込む動画のチェック（Android に依存しない判定だけ）。
 *
 * - 長すぎる動画は解析時間とメモリのため受け付けない
 * - 引き延ばされたスロー動画（コマの時刻から求めた fps に対し、撮影時の fps が [STRETCH_RATIO_THRESHOLD] 倍以上）は、
 *   時刻を撮影時の fps に合わせて補正する
 */
object ImportPolicy {
    /** 読み込める動画の長さの上限（秒、実時間）。 */
    const val MAX_DURATION_SEC = 10.0

    /** 撮影時の fps が実測 fps のこの倍率以上なら、引き延ばされたスロー動画とみなす。 */
    const val STRETCH_RATIO_THRESHOLD = 1.5f

    /**
     * @param measuredFps コマの時刻から求めた fps
     * @param captureFps 動画のメタデータ（com.android.capture.fps）の撮影時の fps。なければ null
     * @param durationUs コマの時刻から求めた動画の長さ（補正前）
     */
    fun judge(measuredFps: Float, captureFps: Float?, durationUs: Long): ImportVerdict {
        val scale = timeScale(measuredFps, captureFps)
        val durationSec = durationUs / 1_000_000.0 / scale
        return if (durationSec > MAX_DURATION_SEC) ImportVerdict.TooLong(durationSec) else ImportVerdict.Accept(scale)
    }

    fun timeScale(measuredFps: Float, captureFps: Float?): Float {
        if (captureFps == null || !captureFps.isFinite() || measuredFps <= 0f) return 1f
        return if (captureFps >= measuredFps * STRETCH_RATIO_THRESHOLD) captureFps / measuredFps else 1f
    }
}

/** 動画の時刻（ミリ秒）から、解析に使う実時間（ミリ秒）への変換。再生の位置の指定には動画の時刻をそのまま使う。 */
object TimeScale {
    fun toRealMs(videoMs: Long, scale: Float): Long =
        if (scale == 1f) videoMs else (videoMs / scale.toDouble()).roundToLong()
}
