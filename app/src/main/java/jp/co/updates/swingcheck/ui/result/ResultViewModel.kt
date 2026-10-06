package jp.co.updates.swingcheck.ui.result

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.core.PixelSequence
import jp.co.updates.swingcheck.core.Preprocessor
import jp.co.updates.swingcheck.data.AnalysisStatus
import jp.co.updates.swingcheck.data.PositionMarkEntity
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 骨格の列（平滑化後、ピクセル座標。時刻は実時間）と、各コマの動画の時刻。
 * 再生の位置の指定とコマ番号の変換には [timestampsMs]（動画の時刻）を使う。
 */
class LoadedPose(val smoothed: PixelSequence, val timestampsMs: LongArray) {
    val frameCount: Int get() = smoothed.size
}

class ResultViewModel(private val container: AppContainer, private val swingId: Long) : ViewModel() {
    private val repository = container.swingRepository

    val swing: StateFlow<SwingEntity?> =
        repository.observeSwing(swingId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val marks: StateFlow<List<PositionMarkEntity>> =
        repository.observeMarks(swingId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings?> =
        container.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var pose by mutableStateOf<LoadedPose?>(null)
        private set

    /** 骨格ファイルが読めなかった。 */
    var loadFailed by mutableStateOf(false)
        private set

    /** 表示中のコマ（0 始まり）。再生中は画面側が更新する。 */
    var currentFrame by mutableIntStateOf(0)

    init {
        viewModelScope.launch {
            val s = swing.first { it?.analysisStatus == AnalysisStatus.DONE }!!
            try {
                val stored = repository.loadPose(swingId)
                val sequence = stored?.sequence
                if (stored == null || sequence == null || sequence.size == 0) {
                    loadFailed = true
                    return@launch
                }
                val smoothed = withContext(Dispatchers.Default) { Preprocessor.preprocess(sequence, s.handedness) }
                val p1 = repository.getMarks(swingId).firstOrNull { it.position == Phase.P1.number }?.frame ?: 0
                currentFrame = p1.coerceIn(0, smoothed.size - 1)
                pose = LoadedPose(smoothed, stored.videoTimestampsMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadFailed = true
            }
        }
    }

    /** そのPのコマを手で直す（数値は :app の SwingRepository が計算し直して保存する）。 */
    fun setManualFrame(phase: Phase, frame: Int) {
        viewModelScope.launch { runCatching { repository.setManualFrame(swingId, phase, frame) } }
    }

    fun resetToAuto(phase: Phase) {
        viewModelScope.launch { runCatching { repository.resetToAuto(swingId, phase) } }
    }

    fun updateHeightCm(cm: Float?) {
        viewModelScope.launch { repository.updateHeightCm(swingId, cm) }
    }

    fun updateMemo(memo: String) {
        viewModelScope.launch { repository.updateMemo(swingId, memo) }
    }
}
