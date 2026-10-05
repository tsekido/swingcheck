package jp.co.updates.swingcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * adb から動画ファイルを取り込んで解析に回す（debug 版だけ）。ストレージ権限は無いので、
 * アプリ専用の外部領域（/sdcard/Android/data/jp.co.updates.swingcheck/files/）に adb push したファイルを指定する。
 *
 *   adb shell am broadcast -a jp.co.updates.swingcheck.DEBUG_IMPORT -p jp.co.updates.swingcheck   （初回だけ。置き場所をログに出す）
 *   adb push xxx.mp4 /sdcard/Android/data/jp.co.updates.swingcheck/files/xxx.mp4
 *   adb shell am broadcast -a jp.co.updates.swingcheck.DEBUG_IMPORT -p jp.co.updates.swingcheck \
 *       --es path /sdcard/Android/data/jp.co.updates.swingcheck/files/xxx.mp4
 *
 * 結果は logcat のタグ DebugImportReceiver と AnalysisPipeline に出る。
 */
class DebugImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // アプリ専用の外部領域を（アプリの持ち主で）先に作っておく。adb 側で先に作るとアプリから読めなくなる
        val dir = context.getExternalFilesDir(null)
        val path = intent.getStringExtra("path")
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            Log.w(TAG, "file not found: $path (put files under $dir)")
            return
        }
        val container = (context.applicationContext as SwingcheckApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = container.videoImporter.import(Uri.fromFile(file))
                Log.i(TAG, "imported: swing=$id path=$path")
            } catch (e: Throwable) {
                Log.e(TAG, "import failed: $path", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "jp.co.updates.swingcheck.DEBUG_IMPORT"
        private const val TAG = "DebugImportReceiver"
    }
}
