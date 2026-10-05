package jp.co.updates.swingcheck.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** 書き出した動画の情報（Swing の作成に使う）。width／height は回転を反映した、表示する向きの大きさ。 */
data class WrittenClip(val fps: Float, val frameCount: Int, val width: Int, val height: Int)

/**
 * 切り出した範囲（先頭はキーフレーム）を MP4 に書き出す（MediaMuxer）。
 * 時刻は先頭のコマを 0 にそろえる。縦持ちのための回転は setOrientationHint で付ける。
 */
object ClipWriter {
    /**
     * @param format エンコーダーの出力フォーマット（csd を含む）
     * @param size エンコーダーに渡したサイズ（回転前）
     * @param rotationHint 再生時に時計回りに回す角度
     * @param fallbackFps 実測できなかったとき（コマが 1 つだけ）に使う fps
     * @throws java.io.IOException, IllegalStateException 書けなかったとき（出力ファイルは消す）
     */
    fun write(
        selection: ClipSelection,
        format: MediaFormat,
        size: CaptureSize,
        rotationHint: Int,
        fallbackFps: Int,
        dest: File,
    ): WrittenClip {
        dest.parentFile?.mkdirs()
        val muxer = MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            muxer.setOrientationHint(rotationHint)
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            for (sample in selection.samples) {
                info.set(
                    0,
                    sample.data.size,
                    selection.outputPtsUs(sample),
                    if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
            }
            muxer.stop()
        } catch (e: Throwable) {
            if (started) runCatching { muxer.stop() }
            dest.delete()
            throw e
        } finally {
            runCatching { muxer.release() }
        }
        val display = CaptureOrientation.displaySize(size, rotationHint)
        return WrittenClip(
            fps = selection.measuredFps() ?: fallbackFps.toFloat(),
            frameCount = selection.samples.size,
            width = display.width,
            height = display.height,
        )
    }
}
