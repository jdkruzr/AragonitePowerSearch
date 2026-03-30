package dev.aragonite.powersearch.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.aragonite.powersearch.data.IndexRepository
import dev.aragonite.powersearch.data.db.IndexedShape
import dev.aragonite.powersearch.data.db.SearchDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Instrumented tests for SearchScreen Compose UI.
 *
 * Tests verify:
 * - AC3.1: Debounced search returns results within ~300ms
 * - AC3.2: Results display note title and recognized text
 * - AC3.4: Empty query shows no results
 * - AC3.5: No-match query shows empty state
 * - AC4.4: Reindex button states (tested via UI presence)
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: SearchDatabase
    private lateinit var indexRepository: IndexRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(
            context,
            SearchDatabase::class.java
        ).build()
        indexRepository = IndexRepository(db.indexDao())

        viewModel = SearchViewModel(indexRepository, context)

        val testShapes = listOf(
            IndexedShape(
                shapeId = "shape-1",
                documentId = "doc1",
                pageId = "page1",
                parentUniqueId = "parent1",
                noteTitle = "Meeting Notes",
                recognizedText = "hello world",
                pointFilePath = "/path/1",
                pointFileModified = 1000L,
                pointFileSize = 5000L,
                indexedAt = System.currentTimeMillis()
            ),
            IndexedShape(
                shapeId = "shape-2",
                documentId = "doc2",
                pageId = "page1",
                parentUniqueId = "parent2",
                noteTitle = "TODO List",
                recognizedText = "buy groceries milk eggs",
                pointFilePath = "/path/2",
                pointFileModified = 1000L,
                pointFileSize = 5000L,
                indexedAt = System.currentTimeMillis()
            ),
            IndexedShape(
                shapeId = "shape-3",
                documentId = "doc3",
                pageId = "page1",
                parentUniqueId = "parent3",
                noteTitle = "Untitled Note",
                recognizedText = "research kubernetes containers",
                pointFilePath = "/path/3",
                pointFileModified = 1000L,
                pointFileSize = 5000L,
                indexedAt = System.currentTimeMillis()
            )
        )

        runBlocking {
            for (shape in testShapes) {
                indexRepository.upsertShape(shape)
            }
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    /**
     * AC3.1: Type a query that matches pre-populated data.
     * Assert results appear after debounce (~300ms).
     */
    @Test
    fun testSearchReturnsResultsAfterDebounce() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        composeRule.onNodeWithText("Search Handwriting").performTextInput("hello")

        composeRule.waitUntil(timeoutMillis = 500) {
            viewModel.uiState.value.results.isNotEmpty()
        }

        composeRule.onNodeWithText("Meeting Notes").assertIsDisplayed()
    }

    /**
     * AC3.2: After search, verify result cards display note title and recognized text.
     */
    @Test
    fun testSearchResultsDisplayTitleAndText() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        composeRule.onNodeWithText("Search Handwriting").performTextInput("groceries")

        composeRule.waitUntil(timeoutMillis = 500) {
            viewModel.uiState.value.results.isNotEmpty()
        }

        composeRule.onNodeWithText("TODO List").assertIsDisplayed()
        composeRule.onNodeWithText("buy groceries milk eggs").assertIsDisplayed()
    }

    /**
     * AC3.1 variant: Prefix search matches partial words.
     */
    @Test
    fun testPrefixSearchMatchesPartialWords() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        composeRule.onNodeWithText("Search Handwriting").performTextInput("kuber")

        composeRule.waitUntil(timeoutMillis = 500) {
            viewModel.uiState.value.results.isNotEmpty()
        }

        composeRule.onNodeWithText("Untitled Note").assertIsDisplayed()
    }

    /**
     * AC3.4: With empty text field, assert no result cards are displayed.
     */
    @Test
    fun testEmptyQueryShowsNoResults() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        val uiState = viewModel.uiState.value
        assertEquals(0, uiState.results.size)
    }

    /**
     * AC3.5: Type a query that matches nothing.
     * Assert "No results" text is displayed.
     */
    @Test
    fun testNoMatchQueryShowsEmptyState() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        composeRule.onNodeWithText("Search Handwriting").performTextInput("xyzabc")

        composeRule.waitUntil(timeoutMillis = 500) {
            val uiState = viewModel.uiState.value
            uiState.results.isEmpty() && viewModel.query.value.isNotBlank()
        }

        composeRule.onNodeWithText("No results for \"xyzabc\"").assertIsDisplayed()
    }

    /**
     * AC4.4: Verify reindex controls are present in initial state.
     * Full indexing lifecycle is tested via IndexingService integration tests.
     */
    @Test
    fun testReindexButtonIsDisplayed() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        composeRule.onNodeWithText("Update Index").assertIsDisplayed()
        composeRule.onNodeWithText("Update Index").assertIsEnabled()
        composeRule.onNodeWithText("Rebuild from Scratch").assertIsDisplayed()
        composeRule.onNodeWithText("Rebuild from Scratch").assertIsEnabled()
    }

    /**
     * Verify the indexed count is displayed.
     */
    @Test
    fun testIndexedCountIsDisplayed() {
        composeRule.setContent {
            Surface { SearchScreen(viewModel) }
        }

        composeRule.onNodeWithText("3 pages indexed").assertIsDisplayed()
    }
}
