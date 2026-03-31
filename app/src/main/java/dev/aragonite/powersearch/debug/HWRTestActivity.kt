package dev.aragonite.powersearch.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aragonite.hwr.AragoniteHWR
import dev.aragonite.hwr.HWRPoint
import dev.aragonite.hwr.HWRStroke
import kotlinx.coroutines.*

private const val TAG = "HWRTest"

/**
 * Standalone test harness for KHwrService behavior.
 *
 * Tests:
 * - Bind/unbind lifecycle
 * - Single recognition call
 * - Rapid-fire calls (stress test)
 * - Sustained load over time
 * - Recovery after failure
 *
 * Launch: adb shell am start -n dev.aragonite.powersearch/.debug.HWRTestActivity
 */
class HWRTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HWRTestScreen()
            }
        }
    }

    @Composable
    fun HWRTestScreen() {
        val scope = rememberCoroutineScope()
        var log by remember { mutableStateOf("HWR Test Harness\n") }
        var isRunning by remember { mutableStateOf(false) }
        var testJob by remember { mutableStateOf<Job?>(null) }

        fun appendLog(msg: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            log = "$log$timestamp  $msg\n"
            Log.i(TAG, msg)
        }

        val rng = java.util.Random()

        // Generate a simple test stroke (a horizontal line)
        fun makeTestStrokes(count: Int = 5): List<HWRStroke> {
            return (0 until count).map { strokeIdx ->
                val points = (0..20).map { i ->
                    HWRPoint(
                        x = 100f + i * 20f + strokeIdx * 200f,
                        y = 500f + (Math.sin(i.toDouble()) * 30).toFloat(),
                        dt = i * 10,
                        pressure = 0.5f
                    )
                }
                HWRStroke(points = points, createdAtMs = System.currentTimeMillis())
            }
        }

        // Generate randomized strokes simulating real handwriting pages
        fun makeRealisticStrokes(): List<HWRStroke> {
            val strokeCount = rng.nextInt(80) + 10 // 10-90 strokes per page
            return (0 until strokeCount).map { strokeIdx ->
                val pointCount = rng.nextInt(40) + 5 // 5-45 points per stroke
                var x = rng.nextFloat() * 1600f + 100f
                var y = rng.nextFloat() * 2000f + 100f
                val points = (0 until pointCount).map { i ->
                    x += (rng.nextFloat() - 0.3f) * 15f // mostly rightward
                    y += (rng.nextFloat() - 0.5f) * 10f
                    HWRPoint(
                        x = x,
                        y = y,
                        dt = i * (rng.nextInt(5) + 5),
                        pressure = rng.nextFloat() * 0.8f + 0.1f
                    )
                }
                HWRStroke(points = points, createdAtMs = System.currentTimeMillis())
            }
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Text("KHwrService Test Harness", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Test 1: Basic bind/recognize/unbind
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Single call ---")
                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (bound) {
                                    val strokes = makeTestStrokes(3)
                                    appendLog("Recognizing ${strokes.size} strokes...")
                                    val start = System.currentTimeMillis()
                                    val result = AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                    val elapsed = System.currentTimeMillis() - start
                                    appendLog("Result (${elapsed}ms): '${result?.take(60) ?: "null"}'")
                                    AragoniteHWR.unbind(this@HWRTestActivity)
                                    appendLog("Unbound.")
                                }
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Single") }

                    // Test: Single-point stroke (suspected poison trigger)
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Single-point stroke ---")
                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (!bound) return@launch

                                // Sanity check first
                                val sanity = AragoniteHWR.recognizeStrokes(makeTestStrokes(3), 1860f, 2480f)
                                appendLog("Sanity: '${sanity?.take(20)}' (${if (sanity?.isNotEmpty() == true) "OK" else "FAIL"})")

                                // Now send a single stroke with just 1 point (DOWN only, no MOVE/UP)
                                appendLog("Sending 1-point stroke...")
                                val onePoint = listOf(HWRStroke(
                                    points = listOf(HWRPoint(x = 500f, y = 500f, dt = 0, pressure = 0.5f)),
                                    createdAtMs = System.currentTimeMillis()
                                ))
                                val start = System.currentTimeMillis()
                                val result = try {
                                    AragoniteHWR.recognizeStrokes(onePoint, 1860f, 2480f)
                                } catch (e: Exception) {
                                    appendLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                                    null
                                }
                                val elapsed = System.currentTimeMillis() - start
                                appendLog("1-point result (${elapsed}ms): '${result ?: "null"}'")

                                // Check if HWR survived
                                val post = try {
                                    AragoniteHWR.recognizeStrokes(makeTestStrokes(3), 1860f, 2480f)
                                } catch (e: Exception) { null }
                                appendLog("Post-test HWR alive: ${post?.isNotEmpty() == true}")

                                // If alive, try mixing: normal strokes + 1-point stroke
                                if (post?.isNotEmpty() == true) {
                                    appendLog("Sending mixed batch: 5 normal + 1 single-point...")
                                    val mixed = makeTestStrokes(5) + listOf(HWRStroke(
                                        points = listOf(HWRPoint(x = 700f, y = 700f, dt = 0, pressure = 0.5f)),
                                        createdAtMs = System.currentTimeMillis()
                                    ))
                                    val start2 = System.currentTimeMillis()
                                    val result2 = try {
                                        AragoniteHWR.recognizeStrokes(mixed, 1860f, 2480f)
                                    } catch (e: Exception) {
                                        appendLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                                        null
                                    }
                                    val elapsed2 = System.currentTimeMillis() - start2
                                    appendLog("Mixed result (${elapsed2}ms): '${result2 ?: "null"}'")

                                    val post2 = try {
                                        AragoniteHWR.recognizeStrokes(makeTestStrokes(3), 1860f, 2480f)
                                    } catch (e: Exception) { null }
                                    appendLog("Post-mixed HWR alive: ${post2?.isNotEmpty() == true}")
                                }

                                AragoniteHWR.unbind(this@HWRTestActivity)
                                appendLog("Done.")
                            }
                        }
                    ) { Text("1pt") }

                    // Test 2: Rapid fire - N calls with no delay
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Rapid fire (50 calls, no delay) ---")
                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (!bound) { isRunning = false; return@launch }

                                var hits = 0
                                var misses = 0
                                for (i in 1..50) {
                                    if (!isActive) break
                                    val strokes = makeTestStrokes(3)
                                    val start = System.currentTimeMillis()
                                    val result = try {
                                        AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                    } catch (e: Exception) {
                                        appendLog("  [$i] EXCEPTION: ${e.message}")
                                        null
                                    }
                                    val elapsed = System.currentTimeMillis() - start
                                    val got = result?.isNotEmpty() == true
                                    if (got) hits++ else misses++
                                    if (i <= 10 || i % 10 == 0 || !got) {
                                        appendLog("  [$i] ${elapsed}ms → ${if (got) "HIT" else "MISS"} '${result?.take(30) ?: "null"}'")
                                    }
                                }
                                appendLog("Done: $hits hits, $misses misses (${hits * 100 / (hits + misses)}%)")
                                AragoniteHWR.unbind(this@HWRTestActivity)
                                appendLog("Unbound.")
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Rapid") }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Test 3: Throttled - N calls with configurable delay
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Throttled (50 calls, 500ms delay) ---")
                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (!bound) { isRunning = false; return@launch }

                                var hits = 0
                                var misses = 0
                                for (i in 1..50) {
                                    if (!isActive) break
                                    val strokes = makeTestStrokes(3)
                                    val start = System.currentTimeMillis()
                                    val result = try {
                                        AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                    } catch (e: Exception) {
                                        appendLog("  [$i] EXCEPTION: ${e.message}")
                                        null
                                    }
                                    val elapsed = System.currentTimeMillis() - start
                                    val got = result?.isNotEmpty() == true
                                    if (got) hits++ else misses++
                                    if (i <= 10 || i % 10 == 0 || !got) {
                                        appendLog("  [$i] ${elapsed}ms → ${if (got) "HIT" else "MISS"} '${result?.take(30) ?: "null"}'")
                                    }
                                    delay(500)
                                }
                                appendLog("Done: $hits hits, $misses misses (${hits * 100 / (hits + misses)}%)")
                                AragoniteHWR.unbind(this@HWRTestActivity)
                                appendLog("Unbound.")
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("500ms") }

                    // Test 4: Endurance - 200 calls with adaptive delay
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Endurance (200 calls, adaptive) ---")
                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (!bound) { isRunning = false; return@launch }

                                var hits = 0
                                var misses = 0
                                var currentDelay = 0L
                                var consecutiveMiss = 0
                                for (i in 1..200) {
                                    if (!isActive) break
                                    val strokes = makeTestStrokes(5)
                                    val start = System.currentTimeMillis()
                                    val result = try {
                                        AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                    } catch (e: Exception) {
                                        appendLog("  [$i] EXCEPTION: ${e.message}")
                                        null
                                    }
                                    val elapsed = System.currentTimeMillis() - start
                                    val got = result?.isNotEmpty() == true
                                    if (got) { hits++; consecutiveMiss = 0 } else { misses++; consecutiveMiss++ }

                                    if (i <= 5 || i % 20 == 0 || !got) {
                                        appendLog("  [$i] ${elapsed}ms d=${currentDelay}ms → ${if (got) "HIT" else "MISS($consecutiveMiss)"} '${result?.take(30) ?: "null"}'")
                                    }

                                    // Adaptive delay
                                    if (consecutiveMiss >= 3) {
                                        currentDelay = (currentDelay + 200).coerceAtMost(2000)
                                        appendLog("  [$i] Throttle up → ${currentDelay}ms")
                                    } else if (got && currentDelay > 0) {
                                        currentDelay = (currentDelay - 50).coerceAtLeast(0)
                                    }

                                    if (consecutiveMiss >= 10) {
                                        appendLog("  [$i] 10 consecutive misses — rebinding")
                                        AragoniteHWR.unbind(this@HWRTestActivity)
                                        delay(1000)
                                        val rebound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                        appendLog("  [$i] Rebind: $rebound")
                                        consecutiveMiss = 0
                                        currentDelay = 500
                                    }

                                    if (currentDelay > 0) delay(currentDelay)
                                }
                                appendLog("Done: $hits hits, $misses misses (${hits * 100 / (hits + misses)}%)")
                                AragoniteHWR.unbind(this@HWRTestActivity)
                                appendLog("Unbound.")
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Endure") }

                    // Stop button
                    if (isRunning) {
                        OutlinedButton(onClick = {
                            testJob?.cancel()
                            isRunning = false
                            appendLog("--- CANCELLED ---")
                        }) { Text("Stop") }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Test 5: Real data - read actual point files from device
                    @Suppress("SdCardPath")
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Real data (point files from device) ---")
                                val pointDir = java.io.File("/sdcard/.ksync/point")
                                if (!pointDir.exists()) {
                                    appendLog("No point directory found!")
                                    isRunning = false
                                    return@launch
                                }

                                val pointFiles = pointDir.walkTopDown()
                                    .filter { it.isFile && it.length() > 200 }
                                    .take(100)
                                    .toList()
                                    .shuffled()
                                appendLog("Found ${pointFiles.size} point files")

                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (!bound) { isRunning = false; return@launch }

                                var hits = 0
                                var misses = 0
                                var errors = 0
                                val strokeRepo = dev.aragonite.powersearch.data.StrokeDataRepository()

                                for ((i, pointFile) in pointFiles.withIndex()) {
                                    if (!isActive) break
                                    try {
                                        val xref = dev.aragonite.powersearch.data.PointFileParser.readXref(pointFile)
                                        val strokes = xref.mapNotNull { entry ->
                                            strokeRepo.readStrokesForShape(pointFile, entry)
                                        }
                                        if (strokes.isEmpty()) continue

                                        val start = System.currentTimeMillis()
                                        val result = try {
                                            AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                        } catch (e: Exception) {
                                            appendLog("  [${i+1}] EXCEPTION: ${e.message}")
                                            errors++
                                            null
                                        }
                                        val elapsed = System.currentTimeMillis() - start
                                        val got = result?.isNotEmpty() == true
                                        if (got) hits++ else misses++

                                        val strokeCount = strokes.sumOf { it.points.size }
                                        if (i < 5 || (i + 1) % 10 == 0 || !got) {
                                            appendLog("  [${i+1}] ${strokes.size} strokes/${strokeCount}pts ${elapsed}ms → ${if (got) "HIT" else "MISS"} '${result?.take(40) ?: "null"}'")
                                        }
                                    } catch (e: Exception) {
                                        appendLog("  [${i+1}] File error: ${e.message}")
                                        errors++
                                    }
                                }
                                appendLog("Done: $hits hits, $misses misses, $errors errors (${if (hits + misses > 0) hits * 100 / (hits + misses) else 0}%)")
                                AragoniteHWR.unbind(this@HWRTestActivity)
                                appendLog("Unbound.")
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Real Data") }

                    // Test 6: Real data with randomized strokes
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Random realistic (50 calls, varied sizes) ---")
                                appendLog("Binding...")
                                val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                appendLog("Bound: $bound")
                                if (!bound) { isRunning = false; return@launch }

                                var hits = 0
                                var misses = 0
                                for (i in 1..50) {
                                    if (!isActive) break
                                    val strokes = makeRealisticStrokes()
                                    val totalPts = strokes.sumOf { it.points.size }
                                    val start = System.currentTimeMillis()
                                    val result = try {
                                        AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                    } catch (e: Exception) {
                                        appendLog("  [$i] EXCEPTION: ${e.message}")
                                        null
                                    }
                                    val elapsed = System.currentTimeMillis() - start
                                    val got = result?.isNotEmpty() == true
                                    if (got) hits++ else misses++
                                    if (i <= 5 || i % 10 == 0 || !got) {
                                        appendLog("  [$i] ${strokes.size}strk/${totalPts}pts ${elapsed}ms → ${if (got) "HIT" else "MISS"} '${result?.take(30) ?: "null"}'")
                                    }
                                }
                                appendLog("Done: $hits hits, $misses misses (${hits * 100 / (hits + misses)}%)")
                                AragoniteHWR.unbind(this@HWRTestActivity)
                                appendLog("Unbound.")
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Random") }

                    if (isRunning) {
                        OutlinedButton(onClick = {
                            testJob?.cancel()
                            isRunning = false
                            appendLog("--- CANCELLED ---")
                        }) { Text("Stop") }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Test: Poison page isolation — reads empty pages from DB, tests each with fresh bind
                    @Suppress("SdCardPath")
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Poison page isolation (from DB) ---")

                                val db = dev.aragonite.powersearch.data.db.SearchDatabase.create(this@HWRTestActivity)
                                val dao = db.indexDao()

                                // Get all empty pages, sorted by file size descending (biggest = most likely poison)
                                val emptyPages = kotlinx.coroutines.runBlocking {
                                    dao.search("") // won't work for empty, use raw query
                                }
                                // Use rawQuery to get empty pages
                                val candidates = mutableListOf<Pair<String, Long>>() // path, size
                                val cursor = db.openHelper.readableDatabase.query(
                                    "SELECT pointFilePath, pointFileSize FROM indexed_shapes WHERE length(recognizedText) = 0 ORDER BY pointFileSize DESC"
                                )
                                while (cursor.moveToNext()) {
                                    candidates.add(cursor.getString(0) to cursor.getLong(1))
                                }
                                cursor.close()

                                appendLog("Found ${candidates.size} empty pages in DB")
                                appendLog("Testing largest files first (most likely to be real poison)")

                                val strokeRepo = dev.aragonite.powersearch.data.StrokeDataRepository()
                                var poisonCount = 0
                                var okCount = 0
                                var skipCount = 0
                                var bindFailCount = 0
                                val poisonFiles = mutableListOf<String>()
                                val okFiles = mutableListOf<String>()

                                for ((i, pair) in candidates.withIndex()) {
                                    if (!isActive) break
                                    val (path, size) = pair
                                    val file = java.io.File(path)
                                    if (!file.exists()) { skipCount++; continue }

                                    // Fresh bind for each file
                                    val bound = AragoniteHWR.bindAndAwait(this@HWRTestActivity)
                                    if (!bound) {
                                        appendLog("  [${i+1}/${candidates.size}] BIND FAILED — waiting 3s")
                                        bindFailCount++
                                        delay(3000)
                                        continue
                                    }

                                    // Sanity check
                                    val sanity = try {
                                        AragoniteHWR.recognizeStrokes(makeTestStrokes(3), 1860f, 2480f)
                                    } catch (e: Exception) { null }
                                    if (sanity?.isEmpty() != false) {
                                        appendLog("  [${i+1}/${candidates.size}] SANITY FAIL — HWR dead, waiting 5s")
                                        AragoniteHWR.unbind(this@HWRTestActivity)
                                        bindFailCount++
                                        delay(5000)
                                        continue
                                    }

                                    // Read strokes
                                    val xref = dev.aragonite.powersearch.data.PointFileParser.readXref(file)
                                    val strokes = xref.mapNotNull { entry ->
                                        strokeRepo.readStrokesForShape(file, entry)
                                    }
                                    if (strokes.isEmpty()) {
                                        skipCount++
                                        AragoniteHWR.unbind(this@HWRTestActivity)
                                        continue
                                    }
                                    val totalPts = strokes.sumOf { it.points.size }

                                    // HWR call
                                    val start = System.currentTimeMillis()
                                    val result = try {
                                        AragoniteHWR.recognizeStrokes(strokes, 1860f, 2480f)
                                    } catch (e: Exception) { null }
                                    val elapsed = System.currentTimeMillis() - start

                                    val got = result?.isNotEmpty() == true
                                    if (got) {
                                        okCount++
                                        okFiles.add("${size/1024}KB ${strokes.size}stk/${totalPts}pts")
                                        if (okCount <= 5 || okCount % 20 == 0) {
                                            appendLog("  [${i+1}/${candidates.size}] OK (${elapsed}ms) ${size/1024}KB ${strokes.size}stk/${totalPts}pts → '${result?.take(30)}'")
                                        }
                                    } else {
                                        poisonCount++
                                        poisonFiles.add(path)
                                        appendLog("  [${i+1}/${candidates.size}] *** POISON *** (${elapsed}ms) ${size/1024}KB ${strokes.size}stk/${totalPts}pts")

                                        // Check if HWR survived
                                        val postCheck = try {
                                            AragoniteHWR.recognizeStrokes(makeTestStrokes(3), 1860f, 2480f)
                                        } catch (e: Exception) { null }
                                        appendLog("  [${i+1}] Post-poison HWR alive: ${postCheck?.isNotEmpty() == true}")
                                    }

                                    AragoniteHWR.unbind(this@HWRTestActivity)
                                    delay(500) // Brief cooldown

                                    // Progress summary every 25
                                    if ((i + 1) % 25 == 0) {
                                        appendLog("  --- Progress: ${i+1}/${candidates.size} tested, $poisonCount poison, $okCount ok, $skipCount skip, $bindFailCount bind fails ---")
                                    }
                                }

                                appendLog("")
                                appendLog("=== RESULTS ===")
                                appendLog("Tested: ${poisonCount + okCount + skipCount} / ${candidates.size}")
                                appendLog("Poison: $poisonCount")
                                appendLog("OK (was cascade victim): $okCount")
                                appendLog("Skipped (no strokes/missing): $skipCount")
                                appendLog("Bind failures: $bindFailCount")
                                if (poisonFiles.isNotEmpty()) {
                                    appendLog("")
                                    appendLog("=== POISON FILES ===")
                                    poisonFiles.forEach { appendLog("  $it") }
                                }
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Poison") }

                    if (isRunning) {
                        OutlinedButton(onClick = {
                            testJob?.cancel()
                            isRunning = false
                            appendLog("--- CANCELLED ---")
                        }) { Text("Stop") }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Test 7: Run ACTUAL indexer pipeline from Activity context
                    @Suppress("SdCardPath")
                    Button(
                        onClick = {
                            isRunning = true
                            testJob = scope.launch(Dispatchers.IO) {
                                appendLog("--- TEST: Full indexer from Activity context ---")
                                appendLog("Context: ${this@HWRTestActivity.javaClass.simpleName}")
                                appendLog("Thread: ${Thread.currentThread().name}")
                                appendLog("Dispatcher: Dispatchers.IO")

                                val db = dev.aragonite.powersearch.data.db.SearchDatabase.create(this@HWRTestActivity)
                                val indexRepo = dev.aragonite.powersearch.data.IndexRepository(db.indexDao())
                                val noteMetaRepo = dev.aragonite.powersearch.data.NoteMetadataRepository()
                                val strokeRepo = dev.aragonite.powersearch.data.StrokeDataRepository()
                                val hwrRepo = dev.aragonite.powersearch.data.HWRRepository(this@HWRTestActivity)

                                appendLog("Binding HWR via HWRRepository (Activity context)...")
                                val bound = hwrRepo.bind()
                                appendLog("HWR bound: $bound")

                                if (!bound) {
                                    appendLog("FAILED to bind. Aborting.")
                                    isRunning = false
                                    return@launch
                                }

                                // Scan point files
                                val pointDir = java.io.File("/sdcard/.ksync/point")
                                val allFiles = pointDir.walkTopDown().filter { it.isFile }.toList()
                                appendLog("Found ${allFiles.size} point files")

                                // Compute diff
                                val currentFiles = allFiles.associate { it.absolutePath to Pair(it.lastModified(), it.length()) }
                                val diff = indexRepo.computeDiff(currentFiles)
                                appendLog("Diff: ${diff.newFiles.size} new, ${diff.modifiedFiles.size} modified")

                                val filesToProcess = (diff.newFiles + diff.modifiedFiles).take(500) // Cap at 500 for test
                                appendLog("Processing ${filesToProcess.size} files (capped at 500)")

                                // Discover metadata
                                val userId = noteMetaRepo.discoverUserId()
                                appendLog("User ID: $userId")
                                val noteMetadataMap = if (userId != null) {
                                    noteMetaRepo.getNoteMetadata(userId).associateBy { it.documentId }
                                } else emptyMap()
                                appendLog("Note metadata: ${noteMetadataMap.size} entries")

                                val handwritingShapeCache = mutableMapOf<String, Set<String>>()
                                var hits = 0
                                var misses = 0
                                var skipped = 0
                                var errors = 0
                                val startTime = System.currentTimeMillis()

                                for ((i, pointFile) in filesToProcess.withIndex()) {
                                    if (!isActive) break

                                    try {
                                        val parts = pointFile.absolutePath.split("/")
                                        val pointIdx = parts.indexOf("point")
                                        if (pointIdx < 0 || pointIdx + 3 >= parts.size) { skipped++; continue }
                                        val documentId = parts[pointIdx + 1]
                                        val pageId = parts[pointIdx + 2]

                                        // Read xref
                                        val xref = dev.aragonite.powersearch.data.PointFileParser.readXref(pointFile)
                                        if (xref.isEmpty()) { skipped++; continue }

                                        // Handwriting filter
                                        val hwShapeIds = if (userId != null) {
                                            val cached = handwritingShapeCache.getOrPut(documentId) {
                                                noteMetaRepo.getHandwritingShapes(userId, documentId)
                                                    .map { it.uniqueId }.toSet()
                                            }
                                            cached.ifEmpty { null }
                                        } else null

                                        // Collect strokes
                                        val allStrokes = xref.mapNotNull { entry ->
                                            if (hwShapeIds != null && entry.shapeUuid !in hwShapeIds) return@mapNotNull null
                                            strokeRepo.readStrokesForShape(pointFile, entry)
                                        }
                                        if (allStrokes.isEmpty()) { skipped++; continue }

                                        // Page dimensions
                                        val pageDims = strokeRepo.getPageDimensions(documentId, pageId)
                                        val vw = pageDims?.width ?: 1860f
                                        val vh = pageDims?.height ?: 2480f

                                        // HWR call — the critical part
                                        val hwrStart = System.currentTimeMillis()
                                        val text = try {
                                            hwrRepo.recognizeStrokes(allStrokes, vw, vh) ?: ""
                                        } catch (e: Exception) {
                                            appendLog("  [${i+1}] HWR EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                                            errors++
                                            ""
                                        }
                                        val hwrMs = System.currentTimeMillis() - hwrStart

                                        val got = text.isNotEmpty()
                                        if (got) hits++ else misses++

                                        // Store in index
                                        val note = noteMetadataMap[documentId]
                                        val shape = dev.aragonite.powersearch.data.db.IndexedShape(
                                            shapeId = "${documentId}_${pageId}_${pointFile.name}",
                                            documentId = documentId,
                                            pageId = pageId,
                                            parentUniqueId = note?.parentUniqueId ?: "",
                                            noteTitle = note?.title ?: "",
                                            recognizedText = text,
                                            pointFilePath = pointFile.absolutePath,
                                            pointFileModified = pointFile.lastModified(),
                                            pointFileSize = pointFile.length(),
                                            indexedAt = System.currentTimeMillis()
                                        )
                                        indexRepo.upsertShape(shape)

                                        // Verbose logging
                                        if (i < 10 || (i + 1) % 25 == 0 || !got || hwrMs > 5000) {
                                            val total = hits + misses
                                            val rate = if (total > 0) hits * 100 / total else 0
                                            appendLog("  [${i+1}] ${allStrokes.size}stk ${hwrMs}ms → ${if (got) "HIT" else "MISS"} rate=${rate}% '${text.take(35)}'")
                                        }

                                        // Log every 100 with summary
                                        if ((i + 1) % 100 == 0) {
                                            val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                            val memInfo = Runtime.getRuntime()
                                            val usedMB = (memInfo.totalMemory() - memInfo.freeMemory()) / 1024 / 1024
                                            val maxMB = memInfo.maxMemory() / 1024 / 1024
                                            appendLog("  --- Checkpoint: ${i+1} processed, $hits hits, $misses misses, $skipped skipped, $errors errors, ${elapsed}s elapsed, heap=${usedMB}/${maxMB}MB ---")
                                        }
                                    } catch (e: Exception) {
                                        appendLog("  [${i+1}] ERROR: ${e.javaClass.simpleName}: ${e.message}")
                                        errors++
                                    }
                                }

                                val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
                                appendLog("DONE: $hits hits, $misses misses, $skipped skipped, $errors errors in ${totalElapsed}s")
                                appendLog("Hit rate: ${if (hits + misses > 0) hits * 100 / (hits + misses) else 0}%")
                                appendLog("Index count: ${indexRepo.getIndexedShapeCount()}")

                                hwrRepo.unbind()
                                appendLog("Unbound.")
                                isRunning = false
                            }
                        },
                        enabled = !isRunning
                    ) { Text("Indexer (Activity)") }

                    if (isRunning) {
                        OutlinedButton(onClick = {
                            testJob?.cancel()
                            isRunning = false
                            appendLog("--- CANCELLED ---")
                        }) { Text("Stop") }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Clear log
                TextButton(onClick = { log = "" }) { Text("Clear Log") }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable log output
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
