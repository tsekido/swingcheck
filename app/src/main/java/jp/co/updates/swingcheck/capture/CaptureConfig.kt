package jp.co.updates.swingcheck.capture

import jp.co.updates.swingcheck.settings.FpsMode

/** カメラのセッションの種類。120fps 以上は高速撮影（constrained high speed）が必要。 */
enum class SessionType { HIGH_SPEED, NORMAL }

data class CaptureSize(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    override fun toString(): String = "${width}x$height"
}

data class FpsRange(val lower: Int, val upper: Int) {
    override fun toString(): String = "[$lower, $upper]"
}

/** 通常セッションで使えるサイズ。maxFps は、そのサイズの最小フレーム間隔から求めた上限（不明なら null）。 */
data class NormalSize(val size: CaptureSize, val maxFps: Int?)

/**
 * 1 台のカメラから読んだ撮影の能力。Android に依存しない形にしてあり、設定の選択を JVM でテストできる。
 *
 * @param highSpeedSizes 高速撮影のサイズごとの fps の範囲。高速撮影に対応していなければ空
 * @param normalSizes SurfaceTexture とエンコーダーの両方に出せるサイズ
 * @param normalFpsRanges CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
 * @param focalLengthMm LENS_INFO_AVAILABLE_FOCAL_LENGTHS の先頭（不明なら null）。メインカメラかどうかの判定に使う
 * @param physicalIds 論理カメラが束ねている物理カメラの ID（なければ空。表示用）
 */
data class CameraCaps(
    val cameraId: String,
    val highSpeedSizes: Map<CaptureSize, List<FpsRange>>,
    val normalSizes: List<NormalSize>,
    val normalFpsRanges: List<FpsRange>,
    val focalLengthMm: Float? = null,
    val physicalIds: List<String> = emptyList(),
)

/** 実際に使うことにした撮影の設定。 */
data class CaptureConfig(
    val cameraId: String,
    val sessionType: SessionType,
    val size: CaptureSize,
    val fps: Int,
    /** CONTROL_AE_TARGET_FPS_RANGE に入れる範囲 */
    val fpsRange: FpsRange,
)

/**
 * 設定の fpsMode とカメラの能力から、使う設定を決める（design.md 2章、spec 3章）。
 *
 * - AUTO：対応する最大の fps（[AUTO_MAX_FPS] が上限）
 * - 指定の fps が使えなければ、使える中で最も近い低い値に落とす
 * - 60fps 以下は通常セッション、それより上は高速撮影セッション
 * - サイズは 1080p を優先し、なければ 720p、それもなければ 1080p 以下で最大のもの
 */
object CaptureConfigSelector {
    const val AUTO_MAX_FPS = 240

    /** この fps 以下は通常のセッションで撮る。 */
    const val NORMAL_MAX_FPS = 60

    private val PREFERRED_SIZES = listOf(CaptureSize(1920, 1080), CaptureSize(1280, 720))

    /** @param encoderSupports そのサイズ・fps でエンコーダーが動くか（MediaCodecInfo で調べる） */
    fun select(
        caps: CameraCaps,
        mode: FpsMode,
        encoderSupports: (CaptureSize, Int) -> Boolean = { _, _ -> true },
    ): CaptureConfig? {
        val target = minOf(mode.fps ?: AUTO_MAX_FPS, AUTO_MAX_FPS)
        val candidates = candidates(caps, encoderSupports)
        if (candidates.isEmpty()) return null
        // 目標以下で最大の fps。なければ、目標より上で最小のもの
        val fps = candidates.map { it.fps }.filter { it <= target }.maxOrNull()
            ?: candidates.minOf { it.fps }
        return candidates.filter { it.fps == fps }.let(::pickBySize)
    }

    internal fun pickBySize(list: List<CaptureConfig>): CaptureConfig {
        for (preferred in PREFERRED_SIZES) {
            list.firstOrNull { it.size == preferred }?.let { return it }
        }
        val limit = PREFERRED_SIZES.first().area
        return list.filter { it.size.area <= limit }.maxByOrNull { it.size.area }
            ?: list.minByOrNull { it.size.area }!!
    }

    /** 使える (サイズ, fps, セッション) の全部。同じ fps なら通常セッションを先に（60fps 以下のとき）。 */
    internal fun candidates(caps: CameraCaps, encoderSupports: (CaptureSize, Int) -> Boolean): List<CaptureConfig> {
        val out = ArrayList<CaptureConfig>()
        for (ns in caps.normalSizes) {
            for (fps in fpsValues(caps.normalFpsRanges)) {
                if (fps > NORMAL_MAX_FPS) continue
                if (ns.maxFps != null && fps > ns.maxFps) continue
                if (!encoderSupports(ns.size, fps)) continue
                out += CaptureConfig(caps.cameraId, SessionType.NORMAL, ns.size, fps, bestRange(caps.normalFpsRanges, fps)!!)
            }
        }
        for ((size, ranges) in caps.highSpeedSizes) {
            for (fps in fpsValues(ranges)) {
                if (!encoderSupports(size, fps)) continue
                // 60fps 以下で通常セッションが使えるなら、そちらを使う（高速撮影は 120fps 以上のときだけ）
                if (fps <= NORMAL_MAX_FPS && out.any { it.sessionType == SessionType.NORMAL && it.size == size && it.fps == fps }) continue
                out += CaptureConfig(caps.cameraId, SessionType.HIGH_SPEED, size, fps, bestRange(ranges, fps)!!)
            }
        }
        return out
    }

    private fun fpsValues(ranges: List<FpsRange>): List<Int> = ranges.map { it.upper }.filter { it > 0 }.distinct()

    /** upper が fps の範囲のうち、lower がいちばん高い（fps が安定する）もの。 */
    internal fun bestRange(ranges: List<FpsRange>, fps: Int): FpsRange? =
        ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
}
