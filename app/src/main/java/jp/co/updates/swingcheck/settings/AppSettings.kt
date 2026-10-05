package jp.co.updates.swingcheck.settings

enum class FpsMode(val fps: Int?) {
    AUTO(null),
    FPS_30(30),
    FPS_60(60),
    FPS_120(120),
    FPS_240(240),
}

enum class LengthUnit {
    CM,
    INCH;

    companion object {
        /** インチを初期値にする地域（ISO 3166-1 の国コード）。 */
        private val INCH_REGIONS = setOf("US", "LR", "MM")

        /** 端末の地域（国コード）に合わせた初期値。不明なら CM。 */
        fun defaultFor(regionCode: String?): LengthUnit =
            if (regionCode != null && regionCode.uppercase() in INCH_REGIONS) INCH else CM
    }
}

/** design.md 10章 DataStore（設定）の 4 項目。 */
data class AppSettings(
    val fpsMode: FpsMode,
    /** 新しく撮ったスイングの身長の初期値。未設定なら null */
    val defaultHeightCm: Float?,
    val lengthUnit: LengthUnit,
    /** true なら素振りを判定して保存しない */
    val practiceFilterEnabled: Boolean,
)
