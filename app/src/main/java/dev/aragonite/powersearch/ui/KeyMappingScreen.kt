package dev.aragonite.powersearch.ui

// pattern: Imperative Shell

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aragonite.fleece.FleeceDecoder
import dev.aragonite.powersearch.data.NoteMetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "KeyMapping"
private const val PREFS_NAME = "key_mapping"
private const val KEY_TITLE_INDEX = "title_slot_index"
private const val KEY_FOLDER_INDEX = "folder_slot_index"
private const val KEY_MAPPING_DONE = "mapping_done"

/**
 * Represents a decoded value from a NOTE_TREE BLOB dict slot.
 */
data class SlotValue(
    val slotIndex: Int,
    val displayValue: String,
    val rawTag: Int
)

/**
 * Check if key mapping has been completed on this device.
 */
fun isKeyMappingDone(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_MAPPING_DONE, false)
}

/**
 * Get the saved title slot index, or -1 if not set.
 */
fun getSavedTitleSlotIndex(context: Context): Int {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getInt(KEY_TITLE_INDEX, -1)
}

/**
 * Get the saved folder slot index, or -1 if not set.
 */
fun getSavedFolderSlotIndex(context: Context): Int {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getInt(KEY_FOLDER_INDEX, -1)
}

/**
 * Clear the saved key mapping (for re-mapping).
 */
fun clearKeyMapping(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().clear().apply()
}

/**
 * Save the key mapping.
 */
private fun saveKeyMapping(context: Context, titleSlotIndex: Int, folderSlotIndex: Int) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putInt(KEY_TITLE_INDEX, titleSlotIndex)
        .putInt(KEY_FOLDER_INDEX, folderSlotIndex)
        .putBoolean(KEY_MAPPING_DONE, true)
        .apply()
    Log.i(TAG, "Saved key mapping: title=$titleSlotIndex, folder=$folderSlotIndex")
}

/**
 * Read a sample NOTE_TREE BLOB and decode all dict slot values for the user to choose from.
 */
@Suppress("SdCardPath")
suspend fun loadSampleSlotValues(context: Context): List<SlotValue> = withContext(Dispatchers.IO) {
    try {
        val ksyncRoot = File("/sdcard/.ksync")
        val repo = NoteMetadataRepository(ksyncRoot)
        val userId = repo.discoverUserId() ?: return@withContext emptyList()
        val dbPath = File(ksyncRoot, "couch/$userId-NOTE_TREE.cblite2/db.sqlite3")
        if (!dbPath.exists()) return@withContext emptyList()

        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Find a large BLOB (note or folder, not a sync record)
            val cursor = db.rawQuery(
                "SELECT body FROM kv_default WHERE length(body) > 500 LIMIT 20", null
            )
            var bestSlots: List<SlotValue> = emptyList()
            cursor.use {
                while (it.moveToNext()) {
                    val body = it.getBlob(0) ?: continue

                    // Strip wrapper prefix if present (Palma-style envelope)
                    val inner = if (body.size > 10 && (body[0].toInt() and 0xF0) == 0x70) {
                        body.copyOfRange(10, body.size)
                    } else body

                    val dict = FleeceDecoder.decodeAsDict(inner) ?: continue
                    if (dict.count < 15) continue // Need a meaty entry

                    // Decode all values
                    val slots = mutableListOf<SlotValue>()
                    val headerOffset = dict.value.offset
                    val sw = dict.slotWidth
                    for (i in 0 until dict.count) {
                        val vo = headerOffset + 2 + i * 2 * sw + sw
                        if (vo + sw > inner.size) break

                        val vb = inner[vo].toInt() and 0xFF
                        val vTag = (vb shr 4) and 0xF

                        var displayValue: String? = null
                        if (vTag == 0) {
                            // Small int
                            val raw = ((inner[vo].toInt() and 0xFF) shl 8) or (inner[vo + 1].toInt() and 0xFF)
                            val value = raw and 0x0FFF
                            displayValue = value.toString()
                        } else if (vb and 0x80 != 0) {
                            // Pointer — resolve to string
                            val target = if (dict.isWide) {
                                val raw32 = ((inner[vo].toInt() and 0xFF) shl 24) or
                                        ((inner[vo + 1].toInt() and 0xFF) shl 16) or
                                        ((inner[vo + 2].toInt() and 0xFF) shl 8) or
                                        (inner[vo + 3].toInt() and 0xFF)
                                vo - ((raw32 and 0x3FFFFFFF) * 2)
                            } else {
                                val raw16 = ((inner[vo].toInt() and 0xFF) shl 8) or (inner[vo + 1].toInt() and 0xFF)
                                vo - ((raw16 and 0x3FFF) * 2)
                            }
                            if (target in 0 until inner.size) {
                                val tTag = (inner[target].toInt() and 0xF0) shr 4
                                if (tTag == 4) {
                                    val strLen = inner[target].toInt() and 0x0F
                                    val strStart: Int
                                    val actualLen: Int
                                    if (strLen == 15) {
                                        actualLen = inner[target + 1].toInt() and 0xFF
                                        strStart = target + 2
                                    } else {
                                        actualLen = strLen
                                        strStart = target + 1
                                    }
                                    if (strStart + actualLen <= inner.size) {
                                        displayValue = inner.decodeToString(strStart, strStart + actualLen)
                                    }
                                } else if (tTag == 7) {
                                    displayValue = "[nested object]"
                                } else if (tTag == 6) {
                                    displayValue = "[list]"
                                }
                            }
                        } else if (vTag == 4) {
                            // Inline string
                            val strLen = vb and 0x0F
                            val strStart = vo + 1
                            if (strStart + strLen <= inner.size) {
                                displayValue = inner.decodeToString(strStart, strStart + strLen)
                            }
                        } else if (vTag == 3) {
                            // Special (bool/null)
                            val nibble = inner[vo + 1].toInt() and 0xFF
                            displayValue = when (nibble) {
                                0 -> "null"
                                4 -> "false"
                                8 -> "true"
                                else -> "special"
                            }
                        }

                        if (displayValue != null && displayValue.isNotEmpty()) {
                            slots.add(SlotValue(i, displayValue, vTag))
                        }
                    }

                    // Pick the entry with the most readable string values
                    if (slots.size > bestSlots.size) {
                        bestSlots = slots
                    }
                    if (bestSlots.size >= 15) break
                }
            }
            bestSlots
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load sample slots: ${e.message}", e)
        emptyList()
    }
}

@Composable
fun KeyMappingScreen(context: Context, onComplete: () -> Unit) {
    var slots by remember { mutableStateOf<List<SlotValue>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var step by remember { mutableStateOf(0) } // 0 = loading, 1 = pick title, 2 = pick folder, 3 = done
    var titleSlotIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        slots = loadSampleSlotValues(context)
        loading = false
        step = if (slots.isNotEmpty()) 1 else 3 // skip if no data
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when {
                loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Reading note database...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                step == 1 -> {
                    Text(
                        "Welcome to PowerSearch",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "We need to learn how your device stores note data. " +
                        "Below are values from one of your notes. " +
                        "Please tap the one that looks like a note title.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn {
                        val stringSlots = slots.filter {
                            it.displayValue.length > 1 &&
                            it.displayValue != "[nested object]" &&
                            it.displayValue != "[list]" &&
                            it.displayValue != "null" &&
                            it.displayValue != "true" &&
                            it.displayValue != "false"
                        }
                        itemsIndexed(stringSlots) { _, slot ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        titleSlotIndex = slot.slotIndex
                                        step = 2
                                    },
                                colors = CardDefaults.cardColors()
                            ) {
                                Text(
                                    text = slot.displayValue,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(16.dp),
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
                step == 2 -> {
                    Text(
                        "Now Pick the Folder Name",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap the value that looks like a folder name. " +
                        "If you don't see one, tap \"None of these\" at the bottom.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn {
                        val stringSlots = slots.filter {
                            it.slotIndex != titleSlotIndex &&
                            it.displayValue.length > 1 &&
                            it.displayValue != "[nested object]" &&
                            it.displayValue != "[list]" &&
                            it.displayValue != "null" &&
                            it.displayValue != "true" &&
                            it.displayValue != "false"
                        }
                        itemsIndexed(stringSlots) { _, slot ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        saveKeyMapping(context, titleSlotIndex, slot.slotIndex)
                                        step = 3
                                        onComplete()
                                    },
                                colors = CardDefaults.cardColors()
                            ) {
                                Text(
                                    text = slot.displayValue,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(16.dp),
                                    maxLines = 2
                                )
                            }
                        }
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        saveKeyMapping(context, titleSlotIndex, -1)
                                        step = 3
                                        onComplete()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "None of these",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
                step == 3 -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Setup complete!", style = MaterialTheme.typography.headlineMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading search...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
