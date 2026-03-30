package dev.aragonite.powersearch.service

// pattern: Imperative Shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.aragonite.powersearch.data.HWRRepository
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.Indexer
import dev.aragonite.powersearch.data.NoteMetadataRepository
import dev.aragonite.powersearch.data.StrokeDataRepository
import dev.aragonite.powersearch.data.db.SearchDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "IndexingService"
private const val CHANNEL_ID = "indexing_channel"
private const val NOTIFICATION_ID = 1

data class IndexingState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val phase: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val pagesIndexed: Int = 0
)

class IndexingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var indexingJob: Job? = null

    companion object {
        private val _state = MutableStateFlow(IndexingState())
        val state: StateFlow<IndexingState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, IndexingService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, IndexingService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, IndexingService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IndexingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun clearAndReindex(context: Context) {
            val intent = Intent(context, IndexingService::class.java).apply {
                action = ACTION_CLEAR_AND_REINDEX
            }
            context.startForegroundService(intent)
        }

        private const val ACTION_START = "dev.aragonite.powersearch.START_INDEXING"
        private const val ACTION_PAUSE = "dev.aragonite.powersearch.PAUSE_INDEXING"
        private const val ACTION_RESUME = "dev.aragonite.powersearch.RESUME_INDEXING"
        private const val ACTION_STOP = "dev.aragonite.powersearch.STOP_INDEXING"
        private const val ACTION_CLEAR_AND_REINDEX = "dev.aragonite.powersearch.CLEAR_AND_REINDEX"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting indexing..."))
                if (indexingJob?.isActive != true) {
                    startIndexing(clearFirst = false)
                }
            }
            ACTION_CLEAR_AND_REINDEX -> {
                startForeground(NOTIFICATION_ID, buildNotification("Clearing index..."))
                indexingJob?.cancel()
                startIndexing(clearFirst = true)
            }
            ACTION_PAUSE -> {
                Log.i(TAG, "Pausing indexing")
                indexingJob?.cancel()
                indexingJob = null
                _state.value = _state.value.copy(isPaused = true, isRunning = false)
                updateNotification("Indexing paused — ${_state.value.pagesIndexed} pages indexed")
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping indexing service")
                indexingJob?.cancel()
                indexingJob = null
                _state.value = IndexingState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startIndexing(clearFirst: Boolean) {
        Log.i(TAG, "Starting indexing job (clearFirst=$clearFirst)")
        _state.value = _state.value.copy(isRunning = true, isPaused = false, error = null, pagesIndexed = 0)

        indexingJob = scope.launch {
            try {
                val db = SearchDatabase.create(applicationContext)
                val indexRepository = IndexRepository(db.indexDao())

                if (clearFirst) {
                    Log.i(TAG, "Clearing index")
                    indexRepository.clearIndex()
                }
                val noteMetadataRepository = NoteMetadataRepository()
                val strokeDataRepository = StrokeDataRepository()
                val hwrRepository = HWRRepository(applicationContext)
                val indexer = Indexer(noteMetadataRepository, strokeDataRepository, indexRepository, hwrRepository)

                val result = indexer.reindex { progress ->
                    _state.value = _state.value.copy(
                        phase = progress.phase,
                        current = progress.current,
                        total = progress.total,
                        pagesIndexed = if (progress.phase == "Indexing") progress.current else _state.value.pagesIndexed
                    )
                    if (progress.total > 0) {
                        updateNotification("${progress.phase}: ${progress.current}/${progress.total}")
                    } else {
                        updateNotification("${progress.phase}...")
                    }
                }

                val indexedCount = indexRepository.getIndexedShapeCount()
                _state.value = _state.value.copy(
                    isRunning = false,
                    isPaused = false,
                    error = result.error,
                    pagesIndexed = indexedCount
                )
                Log.i(TAG, "Indexing complete: processed=${result.processed}, failed=${result.failed}")
                updateNotification("Indexing complete — $indexedCount pages indexed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Indexing cancelled (paused or stopped)")
            } catch (e: Exception) {
                Log.e(TAG, "Indexing failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    isRunning = false,
                    error = e.message ?: "Indexing failed"
                )
                updateNotification("Indexing failed")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Handwriting Indexing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while indexing handwritten notes"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Power Search")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
