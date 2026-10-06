package jp.co.updates.swingcheck

import android.content.Context
import android.net.Uri
import jp.co.updates.swingcheck.data.SwingFiles
import jp.co.updates.swingcheck.data.SwingSource
import jp.co.updates.swingcheck.video.ImportPolicy
import jp.co.updates.swingcheck.video.ImportVerdict
import jp.co.updates.swingcheck.video.VideoFrameReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** 動画の読み込みの結果。 */
sealed interface ImportResult {
    /** [stretched]：引き延ばされたスロー動画として、時刻を補正して読み込んだ。 */
    data class Success(val swingId: Long, val stretched: Boolean) : ImportResult

    /** 長すぎて読み込まなかった。 */
    data class TooLong(val durationSec: Double) : ImportResult
}

/**
 * 端末内の動画（content Uri）を読み込んで解析の順番待ちに入れる。
 * 動画をアプリの領域にコピーして probe し、[ImportPolicy] で受け付けるかを判定する。
 */
class VideoImporter(
    private val context: Context,
    private val files: SwingFiles,
    private val registrar: SwingRegistrar,
) {
    /** 動画として読めなければ [IOException]。受け付けなかったときも、失敗したときも、コピーは消す。 */
    suspend fun import(uri: Uri): ImportResult {
        val path = files.newVideoPath()
        val dest = files.resolve(path)
        var keep = false
        try {
            val info = withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                input.use { src -> dest.outputStream().use { src.copyTo(it) } }
                VideoFrameReader(dest).probe()
            }
            val verdict = ImportPolicy.judge(info.fps, info.captureFps, info.durationUs)
            return when (verdict) {
                is ImportVerdict.TooLong -> ImportResult.TooLong(verdict.durationSec)
                is ImportVerdict.Accept -> {
                    // 登録の途中で止められて、行き場のないファイルが残らないようにする
                    val id = withContext(NonCancellable) {
                        registrar.register(
                            path,
                            fps = info.fps * verdict.timeScale,
                            frameCount = info.frameCount,
                            width = info.width,
                            height = info.height,
                            source = SwingSource.IMPORTED,
                            timeScale = verdict.timeScale,
                        ).also { keep = true }
                    }
                    ImportResult.Success(id, verdict.stretched)
                }
            }
        } finally {
            if (!keep) dest.delete()
        }
    }
}
