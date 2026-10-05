package jp.co.updates.swingcheck.capture

import android.content.Context

/** 撮影設定の強制指定（リリース版では存在しない）。debug 版の同名ファイルと対になる。 */
object DebugForcedCapture {
    @Suppress("UNUSED_PARAMETER")
    fun read(context: Context): ForcedCapture? = null
}
