package dev.aragonite.powersearch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aragonite.powersearch.data.IndexProgress
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.Indexer
import dev.aragonite.powersearch.data.db.IndexedShape
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class SearchUiState(
    val results: List<IndexedShape> = emptyList(),
    val isIndexing: Boolean = false,
    val indexProgress: IndexProgress? = null,
    val indexedShapeCount: Int = 0,
    val error: String? = null
)

class SearchViewModel(
    private val indexRepository: IndexRepository,
    private val indexer: Indexer
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<IndexedShape>> = _query
        .debounce(300L)
        .flatMapLatest { q ->
            flow { emit(indexRepository.search(q)) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                indexedShapeCount = indexRepository.getIndexedShapeCount()
            )
        }
        // Collect search results into UI state
        viewModelScope.launch {
            searchResults.collect { results ->
                _uiState.value = _uiState.value.copy(results = results)
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun reindex() {
        if (_uiState.value.isIndexing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isIndexing = true, error = null)
            try {
                val result = indexer.reindex { progress ->
                    _uiState.value = _uiState.value.copy(indexProgress = progress)
                }
                _uiState.value = _uiState.value.copy(
                    isIndexing = false,
                    indexProgress = null,
                    indexedShapeCount = indexRepository.getIndexedShapeCount(),
                    error = result.error
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isIndexing = false,
                    indexProgress = null,
                    error = e.message ?: "Indexing failed"
                )
            }
        }
    }
}
