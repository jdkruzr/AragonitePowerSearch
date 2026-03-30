package dev.aragonite.powersearch.data

import android.content.Context
import androidx.room.Room
import dev.aragonite.powersearch.data.db.SearchDatabase
import kotlinx.coroutines.CompletableDeferred

/**
 * FakeIndexer for testing that simulates indexing with controllable completion.
 *
 * Allows tests to pause indexing mid-progress to assert UI state while indexing is "in progress".
 */
class FakeIndexer(context: Context) : Indexer(
    NoteMetadataRepository(),
    StrokeDataRepository(),
    IndexRepository(
        Room.inMemoryDatabaseBuilder(
            context,
            SearchDatabase::class.java
        ).build().indexDao()
    ),
    HWRRepository(context),
    storageChecker = { true }
) {
    private val completionDeferred = CompletableDeferred<Unit>()

    /**
     * Simulate indexing that waits for the test to signal completion.
     *
     * Calls the onProgress callback to indicate indexing has started,
     * then waits for the test to complete the deferred.
     */
    override suspend fun reindex(onProgress: (IndexProgress) -> Unit): IndexResult {
        onProgress(IndexProgress("Indexing", 1, 10))
        completionDeferred.await()
        return IndexResult(processed = 0, failed = 0, deleted = 0, error = null)
    }

    /**
     * Signal that indexing should complete (called by test).
     */
    fun completeIndexing() {
        if (!completionDeferred.isCompleted) {
            completionDeferred.complete(Unit)
        }
    }
}
