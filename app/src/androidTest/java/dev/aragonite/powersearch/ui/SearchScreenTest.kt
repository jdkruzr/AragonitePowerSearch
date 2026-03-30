package dev.aragonite.powersearch.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * - AC4.4: Reindex button disabled during indexing
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
        // Create in-memory database for testing
        db = Room.inMemoryDatabaseBuilder(
            context,
            SearchDatabase::class.java
        ).build()
        indexRepository = IndexRepository(db.indexDao())

        // Create a real Indexer instance (won't be called in these tests)
        val indexer = dev.aragonite.powersearch.data.Indexer(
            dev.aragonite.powersearch.data.NoteMetadataRepository(),
            dev.aragonite.powersearch.data.StrokeDataRepository(),
            indexRepository,
            dev.aragonite.powersearch.data.HWRRepository(context),
            storageChecker = { true }
        )

        viewModel = SearchViewModel(indexRepository, indexer)

        // Pre-populate database with test data
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

        // Insert test data synchronously
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
            Surface {
                SearchScreen(viewModel)
            }
        }

        // Find the search field and type a query
        composeRule.onNodeWithText("Search handwriting").performTextInput("hello")

        // Wait for debounce: results should appear
        composeRule.waitUntil(timeoutMillis = 500) {
            viewModel.uiState.value.results.isNotEmpty()
        }

        // Assert: Result card is displayed
        composeRule.onNodeWithText("Meeting Notes").assertIsDisplayed()
    }

    /**
     * AC3.2: After search, verify result cards display note title and recognized text.
     */
    @Test
    fun testSearchResultsDisplayTitleAndText() {
        composeRule.setContent {
            Surface {
                SearchScreen(viewModel)
            }
        }

        // Type a query
        composeRule.onNodeWithText("Search handwriting").performTextInput("groceries")

        // Wait for debounce
        composeRule.waitUntil(timeoutMillis = 500) {
            viewModel.uiState.value.results.isNotEmpty()
        }

        // Assert: Both title and recognized text are displayed
        composeRule.onNodeWithText("TODO List").assertIsDisplayed()
        composeRule.onNodeWithText("buy groceries milk eggs").assertIsDisplayed()
    }

    /**
     * AC3.4: With empty text field, assert no result cards are displayed.
     */
    @Test
    fun testEmptyQueryShowsNoResults() {
        composeRule.setContent {
            Surface {
                SearchScreen(viewModel)
            }
        }

        // Don't type anything (search field is empty by default)

        // Assert: No result cards are displayed
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
            Surface {
                SearchScreen(viewModel)
            }
        }

        // Type a query that doesn't match anything
        composeRule.onNodeWithText("Search handwriting").performTextInput("xyzabc")

        // Wait for debounce
        composeRule.waitUntil(timeoutMillis = 500) {
            val uiState = viewModel.uiState.value
            uiState.results.isEmpty() && viewModel.query.value.isNotBlank()
        }

        // Assert: "No results" message is displayed
        composeRule.onNodeWithText("No results for \"xyzabc\"").assertIsDisplayed()
    }

    /**
     * AC4.4: Tap reindex button.
     * Assert it becomes disabled and shows "Indexing...".
     */
    @Test
    fun testReindexButtonShowsDisabledState() {
        // Use FakeIndexer to pause indexing mid-progress
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fakeIndexer = dev.aragonite.powersearch.data.FakeIndexer(context)
        val testViewModel = SearchViewModel(indexRepository, fakeIndexer)

        composeRule.setContent {
            Surface {
                SearchScreen(testViewModel)
            }
        }

        // Verify initial state: button is enabled and shows "Reindex"
        composeRule.onNodeWithText("Reindex").assertIsDisplayed()
        composeRule.onNodeWithText("Reindex").assertIsEnabled()

        // Tap the reindex button
        composeRule.onNodeWithText("Reindex").performClick()

        // Wait for the button text to change to "Indexing..."
        composeRule.waitUntil(timeoutMillis = 1000) {
            try {
                composeRule.onNodeWithText("Indexing...").assertIsDisplayed()
                true
            } catch (e: Exception) {
                false
            }
        }

        // Assert button is now disabled
        composeRule.onNodeWithText("Indexing...").assertIsDisplayed()
        composeRule.onNodeWithText("Indexing...").assertIsNotEnabled()

        // Assert LinearProgressIndicator is displayed during indexing
        composeRule.onNode(
            androidx.compose.ui.test.hasTestTag("LinearProgressIndicator")
        ).assertIsDisplayed()

        // Complete the indexing
        fakeIndexer.completeIndexing()

        // Wait for button to re-enable and show "Reindex" again
        composeRule.waitUntil(timeoutMillis = 1000) {
            try {
                composeRule.onNodeWithText("Reindex").assertIsEnabled()
                true
            } catch (e: Exception) {
                false
            }
        }

        // Assert button is enabled again
        composeRule.onNodeWithText("Reindex").assertIsEnabled()
    }

    /**
     * AC3.1: Verify search matches both title and recognized text.
     */
    @Test
    fun testSearchMatchesTitleOrText() {
        composeRule.setContent {
            Surface {
                SearchScreen(viewModel)
            }
        }

        // Search for text in title
        composeRule.onNodeWithText("Search handwriting").performTextInput("TODO")

        composeRule.waitUntil(timeoutMillis = 500) {
            viewModel.uiState.value.results.isNotEmpty()
        }

        composeRule.onNodeWithText("TODO List").assertIsDisplayed()
    }
}
