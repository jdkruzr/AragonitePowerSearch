package dev.aragonite.powersearch.ui

// pattern: Imperative Shell

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.db.SearchDatabase

class SearchViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = SearchDatabase.create(context)
        val indexRepository = IndexRepository(db.indexDao())
        return SearchViewModel(indexRepository, context.applicationContext) as T
    }
}
