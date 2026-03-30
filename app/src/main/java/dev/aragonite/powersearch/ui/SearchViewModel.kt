package dev.aragonite.powersearch.ui

// pattern: Imperative Shell

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aragonite.powersearch.data.IndexProgress
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.db.IndexedShape
import dev.aragonite.powersearch.service.IndexingService
import dev.aragonite.powersearch.service.IndexingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUiState(
    val results: List<IndexedShape> = emptyList(),
    val isIndexing: Boolean = false,
    val isPaused: Boolean = false,
    val indexProgress: IndexProgress? = null,
    val indexedShapeCount: Int = 0,
    val error: String? = null
)

class SearchViewModel(
    private val indexRepository: IndexRepository,
    private val context: Context
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<IndexedShape>> = _query
        .debounce(300L)
        .flatMapLatest { q ->
            flow {
                try {
                    emit(indexRepository.search(q))
                } catch (e: Exception) {
                    emit(emptyList())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { indexRepository.getIndexedShapeCount() }
            _uiState.value = _uiState.value.copy(indexedShapeCount = count)
        }
        viewModelScope.launch {
            searchResults.collect { results ->
                _uiState.value = _uiState.value.copy(results = results)
            }
        }
        // Observe the IndexingService state
        viewModelScope.launch {
            IndexingService.state.collect { serviceState ->
                // Refresh the indexed count from DB on every state change
                val count = withContext(Dispatchers.IO) { indexRepository.getIndexedShapeCount() }
                _uiState.value = _uiState.value.copy(
                    isIndexing = serviceState.isRunning,
                    isPaused = serviceState.isPaused,
                    indexProgress = if (serviceState.isRunning) {
                        IndexProgress(serviceState.phase, serviceState.current, serviceState.total)
                    } else null,
                    error = serviceState.error,
                    indexedShapeCount = count
                )
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun startIndexing() {
        IndexingService.start(context)
    }

    fun pauseIndexing() {
        IndexingService.pause(context)
    }

    fun resumeIndexing() {
        IndexingService.resume(context)
    }

    fun clearAndReindex() {
        IndexingService.clearAndReindex(context)
    }
}
