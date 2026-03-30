package dev.aragonite.powersearch.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.aragonite.powersearch.data.HWRRepository
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.Indexer
import dev.aragonite.powersearch.data.NoteMetadataRepository
import dev.aragonite.powersearch.data.StrokeDataRepository
import dev.aragonite.powersearch.data.db.SearchDatabase

class SearchViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = SearchDatabase.create(context)
        val indexRepository = IndexRepository(db.indexDao())
        val noteMetadataRepository = NoteMetadataRepository()
        val strokeDataRepository = StrokeDataRepository()
        val hwrRepository = HWRRepository(context)
        val indexer = Indexer(noteMetadataRepository, strokeDataRepository, indexRepository, hwrRepository)
        return SearchViewModel(indexRepository, indexer) as T
    }
}
