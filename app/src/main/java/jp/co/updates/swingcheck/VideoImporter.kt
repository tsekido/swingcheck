package jp.co.updates.swingcheck

import android.content.Context
import android.net.Uri
import jp.co.updates.swingcheck.data.SwingFiles
import jp.co.updates.swingcheck.video.VideoFrameReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 端末内の動画（content Uri）を取り込んで解析の順番待ちに入れる。開発用（debug ビルドでだけ呼ぶ想定。
 * 仕様では動画の取り込みは将来対応）。
 */
class VideoImporter(
    private val context: Context,
    private val files: SwingFiles,
    private val registrar: SwingRegistrar,
) {
    /** @return 作った Swing の id。動画として読めなければ [IOException]（コピーは消す）。 */
    suspend fun import(uri: Uri): Long {
        val path = files.newVideoPath()
        val dest = files.resolve(path)
        val info = withContext(Dispatchers.IO) {
            try {
                dest.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                input.use { src -> dest.outputStream().use { src.copyTo(it) } }
                VideoFrameReader(dest).probe()
            } catch (e: Throwable) {
                dest.delete()
                throw e
            }
        }
        return registrar.register(path, info.fps, info.frameCount, info.width, info.height)
    }
}
