package jp.co.updates.swingcheck.ui.list

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.co.updates.swingcheck.ImportResult
import jp.co.updates.swingcheck.VideoImporter
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.data.SwingRepository
import jp.co.updates.swingcheck.video.ImportPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 動画の読み込みの結果として、画面に出す知らせ。 */
sealed interface ImportEvent {
    /** 読み込めた。[stretched] なら、引き延ばされたスロー動画として時刻を補正した。 */
    data class Imported(val stretched: Boolean) : ImportEvent

    data class TooLong(val maxSec: Int) : ImportEvent

    data object Failed : ImportEvent
}

class SwingListViewModel(
    private val repository: SwingRepository,
    private val importer: VideoImporter,
) : ViewModel() {
    /** 撮影日時の新しい順。最初の読み込みが終わるまで null。 */
    val swings: StateFlow<List<SwingEntity>?> =
        repository.observeSwings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val requested = MutableStateFlow<Set<Long>>(emptySet())

    /** 選択中の id。一覧から消えたもの（別の経路で削除など）は自動で外す。選択が空なら選択モードではない。 */
    val selected: StateFlow<Set<Long>> = combine(swings, requested) { list, sel ->
        if (list == null) sel else sel intersect list.mapTo(HashSet()) { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _importing = MutableStateFlow(false)

    /** 動画を読み込み中。 */
    val importing: StateFlow<Boolean> = _importing

    private val importEvents = Channel<ImportEvent>(Channel.BUFFERED)

    /** 読み込みの結果。1 回ずつ受け取る。 */
    val events = importEvents.receiveAsFlow()

    fun startSelection(id: Long) = requested.update { it + id }

    fun toggle(id: Long) = requested.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        requested.value = emptySet()
    }

    /** 選択中のものをまとめて削除（動画と骨格のファイルも消える）。 */
    fun deleteSelected() {
        val ids = selected.value.toList()
        clearSelection()
        viewModelScope.launch { repository.deleteAll(ids) }
    }

    /** 端末内の動画を 1 本読み込む。読み込み中は新しく始めない。 */
    fun importVideo(uri: Uri) {
        if (!_importing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val event = try {
                when (val r = importer.import(uri)) {
                    is ImportResult.Success -> ImportEvent.Imported(r.stretched)
                    is ImportResult.TooLong -> ImportEvent.TooLong(ImportPolicy.MAX_DURATION_SEC.toInt())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportEvent.Failed
            } finally {
                _importing.value = false
            }
            importEvents.trySend(event)
        }
    }
}
