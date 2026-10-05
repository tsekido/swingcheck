package jp.co.updates.swingcheck.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb から撮影設定の強制指定を設定・解除する（debug 版だけ）。反映は撮影の開始時なので、撮影画面を開き直す（またはアプリを再起動する）。
 *
 *   adb shell am broadcast -a jp.co.updates.swingcheck.DEBUG_FORCE_CAPTURE -p jp.co.updates.swingcheck \
 *       --es camera 2 --es session HIGH_SPEED --ei width 1920 --ei height 1080 --ei fps 120
 *   adb shell am broadcast -a jp.co.updates.swingcheck.DEBUG_FORCE_CAPTURE -p jp.co.updates.swingcheck --ez clear true
 */
class ForceCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (intent.getBooleanExtra("clear", false)) {
            DebugForcedCapture.write(context, null)
            Log.i(TAG, "forced capture cleared")
            reply("cleared")
            return
        }
        val camera = intent.getStringExtra("camera")
        val session = SessionType.entries.firstOrNull { it.name == intent.getStringExtra("session") }
        val width = intent.getIntExtra("width", 0)
        val height = intent.getIntExtra("height", 0)
        val fps = intent.getIntExtra("fps", 0)
        if (camera == null || session == null || width <= 0 || height <= 0 || fps <= 0) {
            Log.w(TAG, "invalid arguments: camera=$camera session=${intent.getStringExtra("session")} width=$width height=$height fps=$fps")
            reply("invalid arguments")
            return
        }
        val forced = ForcedCapture(camera, session, CaptureSize(width, height), fps)
        DebugForcedCapture.write(context, forced)
        Log.i(TAG, "forced capture set: $forced")
        reply("set: $forced")
    }

    private fun reply(text: String) {
        if (isOrderedBroadcast) resultData = text
    }

    companion object {
        const val ACTION = "jp.co.updates.swingcheck.DEBUG_FORCE_CAPTURE"
        private const val TAG = "ForceCaptureReceiver"
    }
}
