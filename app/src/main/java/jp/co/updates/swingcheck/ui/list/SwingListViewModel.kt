package jp.co.updates.swingcheck.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.data.SwingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SwingListViewModel(private val repository: SwingRepository) : ViewModel() {
    /** 撮影日時の新しい順。最初の読み込みが終わるまで null。 */
    val swings: StateFlow<List<SwingEntity>?> =
        repository.observeSwings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val requested = MutableStateFlow<Set<Long>>(emptySet())

    /** 選択中の id。一覧から消えたもの（別の経路で削除など）は自動で外す。選択が空なら選択モードではない。 */
    val selected: StateFlow<Set<Long>> = combine(swings, requested) { list, sel ->
        if (list == null) sel else sel intersect list.mapTo(HashSet()) { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
}
