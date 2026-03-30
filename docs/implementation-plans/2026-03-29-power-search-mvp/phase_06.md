# Power Search MVP Implementation Plan — Phase 6: Search UI & Deep Linking

**Goal:** Complete user-facing search interface with debounced FTS queries, result display, reindex button with progress, and deep linking to the BOOX Notes app.

**Architecture:** `SearchViewModel` exposes search state as Flows. Single Compose screen replaces the Phase 1 placeholder with a full search UI: SearchBar, LazyColumn results, reindex controls, empty states. Deep linking via explicit Intent to ScribbleActivity.

**Tech Stack:** Jetpack Compose, Material 3, Kotlin Flows, Android Intent

**Scope:** Phase 6 of 6 from original design

**Codebase verified:** 2026-03-29. After Phase 5: `Indexer`, `IndexRepository`, `HWRRepository`, `NoteMetadataRepository`, `StrokeDataRepository` all exist. Room database with FTS4 is set up. `MainActivity` exists from Phase 1 with a placeholder scaffold. Phase 6 replaces that placeholder.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### power-search-mvp.AC3: Search UI returns results and opens notes
- **power-search-mvp.AC3.1 Success:** Typing a query returns matching results from FTS index within ~300ms debounce
- **power-search-mvp.AC3.2 Success:** Search results display note title and matched recognized text
- **power-search-mvp.AC3.3 Success:** Tapping a result launches ScribbleActivity Intent with correct documentId and parentUniqueId
- **power-search-mvp.AC3.4 Edge:** Empty query shows no results (not all results)
- **power-search-mvp.AC3.5 Edge:** Query matching no indexed text shows 'no results' empty state

### power-search-mvp.AC4: Manual reindex with diff
- **power-search-mvp.AC4.4 Success:** Reindex button shows progress indicator and disables during indexing

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->

<!-- START_TASK_1 -->
### Task 1: SearchViewModel

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/ui/SearchViewModel.kt`

**Implementation:**

`SearchViewModel` manages search state and indexing state. It uses Kotlin Flows for reactive UI updates.

```kotlin
package dev.aragonite.powersearch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aragonite.powersearch.data.IndexProgress
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.Indexer
import dev.aragonite.powersearch.data.db.IndexedShape
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

    @OptIn(FlowPreview::class)
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
```

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add SearchViewModel with debounced search and indexing state`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: ViewModel factory and dependency wiring

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/ui/SearchViewModelFactory.kt`

**Implementation:**

Since this project uses no DI framework, create a manual factory for the ViewModel that wires up all dependencies.

```kotlin
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
```

Note: In a production app, the database instance should be a singleton. For the MVP, creating it in the factory is acceptable since ViewModels survive configuration changes and the factory is only called once per Activity lifecycle.

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add SearchViewModelFactory for manual dependency wiring`
<!-- END_TASK_2 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-4) -->

<!-- START_TASK_3 -->
### Task 3: Search screen Compose UI

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/ui/SearchScreen.kt`

**Implementation:**

The search screen Compose UI with:
- `TextField` at top for search query input
- `LazyColumn` showing result cards (note title + matched text)
- Reindex button in top area
- `LinearProgressIndicator` during indexing
- Empty states: "no results" when query matches nothing, "no indexed notes" on first launch

```kotlin
package dev.aragonite.powersearch.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aragonite.powersearch.data.db.IndexedShape

@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search input
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search handwriting") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Reindex controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${uiState.indexedShapeCount} shapes indexed",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = viewModel::reindex,
                    enabled = !uiState.isIndexing
                ) {
                    Text(if (uiState.isIndexing) "Indexing..." else "Reindex")
                }
            }

            // Progress indicator
            if (uiState.isIndexing) {
                val progress = uiState.indexProgress
                if (progress != null && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    Text(
                        text = "${progress.phase}: ${progress.current}/${progress.total}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }

            // Error display
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Results
            when {
                query.isBlank() && uiState.indexedShapeCount == 0 -> {
                    EmptyState("No indexed notes yet. Tap Reindex to start.")
                }
                query.isBlank() -> {
                    // AC3.4: Empty query shows no results
                }
                uiState.results.isEmpty() -> {
                    // AC3.5: No matches empty state
                    EmptyState("No results for \"$query\"")
                }
                else -> {
                    LazyColumn {
                        items(uiState.results, key = { it.shapeId }) { shape ->
                            SearchResultCard(
                                shape = shape,
                                onClick = { openNote(context, shape) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(shape: IndexedShape, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = shape.noteTitle.ifBlank { "Untitled Note" },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = shape.recognizedText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun openNote(context: Context, shape: IndexedShape) {
    val intent = Intent().apply {
        component = ComponentName(
            "com.onyx.android.note",
            "com.onyx.android.note.note.ui.ScribbleActivity"
        )
        putExtra("documentId", shape.documentId)
        putExtra("parentUniqueId", shape.parentUniqueId)
        // jump_from_document_path is used by ScribbleActivity to identify the calling app.
        // It is not a real filesystem path — it's an identifier string. Using the app package name.
        putExtra("jump_from_document_path", "dev.aragonite.powersearch")
    }
    context.startActivity(intent)
}
```

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add SearchScreen Compose UI with results, reindex, and deep linking`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Update MainActivity to use SearchScreen

**Files:**
- Modify: `app/src/main/java/dev/aragonite/powersearch/MainActivity.kt`

**Implementation:**

Replace the Phase 1 placeholder scaffold with the full search UI. The `PowerSearchApp` composable now shows `SearchScreen` when permission is granted.

Update `MainActivity.kt`:
- Add `SearchViewModel` creation via `SearchViewModelFactory`
- Replace the "Ready" placeholder with `SearchScreen(viewModel)`
- Keep the permission flow from Phase 1

Key changes to `onCreate`:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    hasStoragePermission = Environment.isExternalStorageManager()

    val viewModel: SearchViewModel by viewModels {
        SearchViewModelFactory(applicationContext)
    }

    setContent {
        PowerSearchApp(
            hasStoragePermission = hasStoragePermission,
            onRequestPermission = ::requestStoragePermission,
            viewModel = viewModel
        )
    }
}
```

Add import: `import androidx.activity.viewModels`

Update `PowerSearchApp` signature to accept `viewModel: SearchViewModel` and replace the "Ready" column with `SearchScreen(viewModel)` when `hasStoragePermission` is true.

**Verification:**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

**Commit:** `feat: wire SearchScreen into MainActivity replacing placeholder`
<!-- END_TASK_4 -->

<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 5-6) -->

<!-- START_TASK_5 -->
### Task 5: Search UI tests

**Verifies:** power-search-mvp.AC3.1, power-search-mvp.AC3.2, power-search-mvp.AC3.4, power-search-mvp.AC3.5, power-search-mvp.AC4.4

**Files:**
- Create: `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt`

**Implementation:**

Compose UI tests using `createComposeRule()`. These need a test `SearchViewModel` backed by an in-memory Room database pre-populated with test data.

Add test dependency to `app/build.gradle.kts`:
```kotlin
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

**Testing:**

Tests must verify each AC listed above:
- **power-search-mvp.AC3.1:** Type a query that matches pre-populated data. Assert results appear (using `waitUntil` to account for debounce).
- **power-search-mvp.AC3.2:** After search, verify result cards display note title text and recognized text content.
- **power-search-mvp.AC3.4:** With empty text field, assert no result cards are displayed.
- **power-search-mvp.AC3.5:** Type a query that matches nothing. Assert "No results" text is displayed.
- **power-search-mvp.AC4.4:** Tap reindex button. Assert it becomes disabled (shows "Indexing..."). Assert `LinearProgressIndicator` is displayed.

Test setup: create an in-memory `SearchDatabase`, insert a few `IndexedShape` rows with known `recognizedText` values, create `IndexRepository` and a stub `Indexer` that simulates progress, create `SearchViewModel`.

**Verification:**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.aragonite.powersearch.ui.SearchScreenTest`
Expected: All tests pass

**Commit:** `test: add SearchScreen Compose UI tests`
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Deep link Intent test

**Verifies:** power-search-mvp.AC3.3

**Files:**
- Create: `app/src/androidTest/java/dev/aragonite/powersearch/ui/DeepLinkTest.kt`

**Implementation:**

Test that tapping a search result constructs the correct Intent for ScribbleActivity.

**Testing:**

Tests must verify:
- **power-search-mvp.AC3.3:** Construct the Intent for a known `IndexedShape` with specific `documentId` and `parentUniqueId`. Assert: the Intent's component is `com.onyx.android.note/.note.ui.ScribbleActivity`, the `documentId` extra matches, and the `parentUniqueId` extra matches.

Since we can't easily test that `startActivity` is called from Compose, extract the Intent construction into a testable function (e.g., `buildNoteIntent(shape: IndexedShape): Intent`) and test that function directly in a plain instrumented test.

**Verification:**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.aragonite.powersearch.ui.DeepLinkTest`
Expected: All tests pass

**Commit:** `test: add deep link Intent construction test`
<!-- END_TASK_6 -->

<!-- END_SUBCOMPONENT_C -->

<!-- START_TASK_7 -->
### Task 7: Final build and on-device verification

This is a manual verification task. No code changes.

**Step 1: Build release APK**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 2: Install and run**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.aragonite.powersearch/.MainActivity
```

**Step 3: End-to-end walkthrough**

1. App launches → shows permission screen (if needed) or search screen
2. Tap "Reindex" → progress indicator shows, button disables
3. After indexing completes → shape count updates
4. Type a word known to be in handwritten notes → results appear after brief debounce
5. Results show note title and recognized text
6. Tap a result → BOOX Notes app opens to that note
7. Return to Power Search → search state preserved
8. Clear search → results disappear (empty state)
9. Type a query that matches nothing → "No results" message

**Done when:** Full MVP is functional end-to-end on BOOX device. All 6 phases complete.
<!-- END_TASK_7 -->
