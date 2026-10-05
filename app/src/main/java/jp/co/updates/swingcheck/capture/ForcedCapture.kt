package jp.co.updates.swingcheck.capture

/**
 * debug ビルド専用：撮影設定の強制指定（実機で通常は選ばないカメラ・設定を試すため）。
 * 値の型と変換だけをここに置く（Android 非依存で JVM テストできる）。保存と読み出しは debug のソースセットだけにある。
 */
data class ForcedCapture(
    val cameraId: String,
    val sessionType: SessionType,
    val size: CaptureSize,
    val fps: Int,
) {
    override fun toString(): String = "camera $cameraId $sessionType $size ${fps}fps"

    /** 指定どおりの設定。カメラがそのサイズ・fps を持っていなければ null。焦点距離による除外はしない。 */
    fun toConfig(cameras: List<CameraCaps>): CaptureConfig? {
        val caps = cameras.firstOrNull { it.cameraId == cameraId } ?: return null
        val ranges = when (sessionType) {
            SessionType.NORMAL -> if (caps.normalSizes.any { it.size == size && (it.maxFps == null || fps <= it.maxFps) }) caps.normalFpsRanges else return null
            SessionType.HIGH_SPEED -> caps.highSpeedSizes[size] ?: return null
        }
        val range = CaptureConfigSelector.bestRange(ranges, fps) ?: return null
        return CaptureConfig(cameraId, sessionType, size, fps, range)
    }

    companion object {
        /** そのカメラの、セッション種別ごとに選べるサイズ（大きい順）。 */
        fun sizeOptions(caps: CameraCaps, sessionType: SessionType): List<CaptureSize> = when (sessionType) {
            SessionType.NORMAL -> caps.normalSizes.map { it.size }
            SessionType.HIGH_SPEED -> caps.highSpeedSizes.keys.toList()
        }.distinct().sortedByDescending { it.area }

        /** そのカメラ・種別・サイズで選べる fps（大きい順）。 */
        fun fpsOptions(caps: CameraCaps, sessionType: SessionType, size: CaptureSize): List<Int> {
            val ranges = when (sessionType) {
                SessionType.NORMAL -> {
                    val maxFps = caps.normalSizes.firstOrNull { it.size == size }?.maxFps
                    caps.normalFpsRanges.filter { maxFps == null || it.upper <= maxFps }
                }
                SessionType.HIGH_SPEED -> caps.highSpeedSizes[size].orEmpty()
            }
            return ranges.map { it.upper }.filter { it > 0 }.distinct().sortedDescending()
        }
    }
}
