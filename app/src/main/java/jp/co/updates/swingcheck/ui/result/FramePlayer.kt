package jp.co.updates.swingcheck.ui.result

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import jp.co.updates.swingcheck.display.SeekMath
import java.io.File

/**
 * 動画の再生とコマ単位の移動。Media3 ExoPlayer を [SeekParameters.EXACT] で使う。
 *
 * コマ番号と時刻の対応は骨格ファイルに入っている各コマの時刻（ミリ秒、動画の先頭が 0）を使う。
 * [seekToFrame] はそのコマの時刻へ正確にシークする。EXACT のシークは「指定した位置以降で最初のコマ」を
 * 表示するので、コマの時刻（ミリ秒に切り捨てた値）を渡せば、そのコマが出る
 * （240fps でもコマの間隔は約 4.2ms で、切り捨ての誤差 1ms 未満より大きい）。
 */
@OptIn(markerClass = [UnstableApi::class])
class FramePlayer(context: Context, file: File, private val timestampsMs: LongArray) {
    private val exo: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setSeekParameters(SeekParameters.EXACT)
        repeatMode = Player.REPEAT_MODE_OFF
        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        playWhenReady = false
        prepare()
    }

    var isPlaying by mutableStateOf(false)
        private set

    private var ended = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@FramePlayer.isPlaying = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            ended = playbackState == Player.STATE_ENDED
        }
    }

    init {
        exo.addListener(listener)
    }

    val frameCount: Int get() = timestampsMs.size

    fun attach(view: SurfaceView) {
        exo.setVideoSurfaceView(view)
    }

    fun seekToFrame(frame: Int) {
        if (timestampsMs.isEmpty()) return
        exo.seekTo(timestampsMs[frame.coerceIn(0, timestampsMs.size - 1)])
    }

    /** いま表示しているコマ。 */
    fun currentFrame(): Int = SeekMath.frameAtPosition(timestampsMs, exo.currentPosition)

    fun play() {
        if (ended || currentFrame() >= timestampsMs.size - 1) exo.seekTo(0)
        exo.play()
    }

    fun pause() {
        exo.pause()
    }

    fun release() {
        exo.removeListener(listener)
        exo.release()
    }
}
