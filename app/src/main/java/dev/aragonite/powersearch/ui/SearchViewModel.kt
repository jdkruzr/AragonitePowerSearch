package dev.aragonite.powersearch.ui

// pattern: Imperative Shell

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aragonite.powersearch.data.IndexProgress
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.db.IndexedShape
import dev.aragonite.powersearch.service.IndexingService
import dev.aragonite.powersearch.service.IndexingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUiState(
    val results: List<IndexedShape> = emptyList(),
    val isIndexing: Boolean = false,
    val isPaused: Boolean = false,
    val indexProgress: IndexProgress? = null,
    val indexedShapeCount: Int = 0,
    val error: String? = null,
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null  // null = all folders
)

class SearchViewModel(
    private val indexRepository: IndexRepository,
    private val context: Context
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { indexRepository.getIndexedShapeCount() }
            val folders = withContext(Dispatchers.IO) { indexRepository.getDistinctFolders() }
            _uiState.value = _uiState.value.copy(indexedShapeCount = count, folders = folders)
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

    fun executeSearch() {
        val q = _query.value
        val folder = _uiState.value.selectedFolder
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    val all = indexRepository.search(q)
                    if (folder != null) all.filter { it.parentUniqueId == folder } else all
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _uiState.value = _uiState.value.copy(results = results)
        }
    }

    fun selectFolder(folder: String?) {
        _uiState.value = _uiState.value.copy(selectedFolder = folder)
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

    @Suppress("SdCardPath")
    fun exportIndex() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dbFile = context.getDatabasePath("power_search.db")
                    val exportDir = java.io.File("/sdcard/PowerSearch")
                    exportDir.mkdirs()
                    val exportFile = java.io.File(exportDir, "power_search.db")
                    // Checkpoint WAL to ensure all data is in the main db file
                    indexRepository.checkpoint()
                    dbFile.copyTo(exportFile, overwrite = true)
                    // Also copy WAL and SHM if they exist
                    val walFile = java.io.File(dbFile.path + "-wal")
                    val shmFile = java.io.File(dbFile.path + "-shm")
                    if (walFile.exists()) walFile.copyTo(java.io.File(exportDir, "power_search.db-wal"), overwrite = true)
                    if (shmFile.exists()) shmFile.copyTo(java.io.File(exportDir, "power_search.db-shm"), overwrite = true)
                    Log.i("SearchViewModel", "Index exported to ${exportFile.absolutePath} (${exportFile.length() / 1024}KB)")
                }
                _uiState.value = _uiState.value.copy(error = "Index exported to /sdcard/PowerSearch/")
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Export failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = "Export failed: ${e.message}")
            }
        }
    }

    @Suppress("SdCardPath")
    fun importIndex() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val importFile = java.io.File("/sdcard/PowerSearch/power_search.db")
                    if (!importFile.exists()) {
                        throw java.io.FileNotFoundException("No index file at /sdcard/PowerSearch/power_search.db")
                    }
                    val dbFile = context.getDatabasePath("power_search.db")
                    // Close the current database so we can overwrite it
                    dev.aragonite.powersearch.data.db.SearchDatabase.close()
                    importFile.copyTo(dbFile, overwrite = true)
                    val walFile = java.io.File(importFile.path + "-wal")
                    val shmFile = java.io.File(importFile.path + "-shm")
                    if (walFile.exists()) walFile.copyTo(java.io.File(dbFile.path + "-wal"), overwrite = true)
                    if (shmFile.exists()) shmFile.copyTo(java.io.File(dbFile.path + "-shm"), overwrite = true)
                    // Reopen
                    dev.aragonite.powersearch.data.db.SearchDatabase.create(context)
                    Log.i("SearchViewModel", "Index imported from ${importFile.absolutePath}")
                }
                // Refresh count
                val count = withContext(Dispatchers.IO) { indexRepository.getIndexedShapeCount() }
                _uiState.value = _uiState.value.copy(
                    indexedShapeCount = count,
                    error = "Index imported successfully ($count pages)"
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Import failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = "Import failed: ${e.message}")
            }
        }
    }
}
