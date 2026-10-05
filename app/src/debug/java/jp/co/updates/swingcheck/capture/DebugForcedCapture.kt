package jp.co.updates.swingcheck.capture

import android.content.Context

/** 撮影設定の強制指定の保存先（debug 版だけ）。release 版には、常に null を返す同名の空実装が入る。 */
object DebugForcedCapture {
    private const val PREFS = "debug_forced_capture"
    private const val KEY = "forced"

    fun read(context: Context): ForcedCapture? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return decode(text)
    }

    fun write(context: Context, forced: ForcedCapture?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (forced == null) edit.remove(KEY) else edit.putString(KEY, encode(forced))
        edit.apply()
    }

    private fun encode(f: ForcedCapture) = "${f.cameraId}|${f.sessionType}|${f.size.width}|${f.size.height}|${f.fps}"

    private fun decode(text: String): ForcedCapture? {
        val p = text.split('|')
        if (p.size != 5) return null
        val type = SessionType.entries.firstOrNull { it.name == p[1] } ?: return null
        val w = p[2].toIntOrNull() ?: return null
        val h = p[3].toIntOrNull() ?: return null
        val fps = p[4].toIntOrNull() ?: return null
        return ForcedCapture(p[0], type, CaptureSize(w, h), fps)
    }
}
