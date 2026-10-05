package jp.co.updates.swingcheck.ui.dev

/** 高速撮影のサイズ 1 つと、そのサイズで使える fps の範囲（最小, 最大）。 */
data class HighSpeedSize(val width: Int, val height: Int, val fpsRanges: List<Pair<Int, Int>>)

/** 1 台のカメラの、高速撮影まわりの対応状況。名前は CameraCharacteristics の呼び方のまま（技術情報なので翻訳しない）。 */
data class CameraReport(
    val id: String,
    /** BACK／FRONT／EXTERNAL など */
    val facing: String,
    val hardwareLevel: String,
    /** REQUEST_AVAILABLE_CAPABILITIES の名前 */
    val capabilities: List<String>,
    val supportsConstrainedHighSpeed: Boolean,
    val highSpeedSizes: List<HighSpeedSize>,
    /** CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES（通常のセッション） */
    val aeFpsRanges: List<Pair<Int, Int>>,
    val physicalIds: List<String>,
)

data class DeviceReport(
    val device: String,
    val androidVersion: String,
    val cameras: List<CameraReport>,
) {
    /** コピーして貼り付けられる文章にする。 */
    fun toText(): String = buildString {
        appendLine("device: $device")
        appendLine("android: $androidVersion")
        appendLine("cameras: ${cameras.size}")
        for (c in cameras) {
            appendLine()
            appendLine("camera ${c.id} (${c.facing})")
            appendLine("  INFO_SUPPORTED_HARDWARE_LEVEL: ${c.hardwareLevel}")
            appendLine("  REQUEST_AVAILABLE_CAPABILITIES: ${c.capabilities.joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("  CONSTRAINED_HIGH_SPEED_VIDEO: ${if (c.supportsConstrainedHighSpeed) "yes" else "no"}")
            if (c.physicalIds.isNotEmpty()) appendLine("  physical camera ids: ${c.physicalIds.joinToString(", ")}")
            appendLine("  CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES: ${c.aeFpsRanges.joinToString(", ") { rangeText(it) }.ifEmpty { "(none)" }}")
            if (c.highSpeedSizes.isEmpty()) {
                appendLine("  high speed video sizes: (none)")
            } else {
                appendLine("  high speed video sizes:")
                for (s in c.highSpeedSizes) {
                    appendLine("    ${s.width}x${s.height}: ${s.fpsRanges.joinToString(", ") { rangeText(it) }.ifEmpty { "(none)" }}")
                }
            }
        }
    }

    private fun rangeText(r: Pair<Int, Int>): String = "[${r.first}, ${r.second}]"
}
