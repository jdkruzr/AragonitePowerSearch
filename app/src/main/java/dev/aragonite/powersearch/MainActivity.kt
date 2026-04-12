package dev.aragonite.powersearch

// pattern: Imperative Shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import dev.aragonite.powersearch.ui.KeyMappingScreen
import dev.aragonite.powersearch.ui.clearKeyMapping
import dev.aragonite.powersearch.ui.isKeyMappingDone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aragonite.powersearch.R
import dev.aragonite.powersearch.ui.SearchScreen
import dev.aragonite.powersearch.ui.SearchViewModel
import dev.aragonite.powersearch.ui.SearchViewModelFactory

class MainActivity : ComponentActivity() {

    private var hasStoragePermission by mutableStateOf(false)

    // Prewarm binding for com.onyx.android.ksync/.service.KHwrService.
    // Held for the Activity lifetime so the ksync process is already running
    // by the time the user triggers indexing. Uses its own ServiceConnection
    // (not AragoniteHWR's singleton) so Indexer's finally-block unbind() won't
    // tear it down. See CLAUDE.md invariant "HWR prewarm".
    private var ksyncPrewarmBound = false
    private val ksyncPrewarmConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG_PREWARM, "ksync prewarm connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG_PREWARM, "ksync prewarm disconnected")
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasStoragePermission = Environment.isExternalStorageManager()

        prewarmKsync()

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

    override fun onResume() {
        super.onResume()
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    override fun onDestroy() {
        if (ksyncPrewarmBound) {
            try {
                unbindService(ksyncPrewarmConnection)
                Log.i(TAG_PREWARM, "ksync prewarm unbound")
            } catch (e: Exception) {
                Log.w(TAG_PREWARM, "ksync prewarm unbind threw: ${e.message}")
            }
            ksyncPrewarmBound = false
        }
        super.onDestroy()
    }

    private fun prewarmKsync() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        ksyncPrewarmBound = try {
            bindService(intent, ksyncPrewarmConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG_PREWARM, "ksync prewarm bind threw: ${e.message}")
            false
        }
        if (!ksyncPrewarmBound) {
            Log.w(TAG_PREWARM, "ksync prewarm bindService returned false")
        } else {
            Log.i(TAG_PREWARM, "ksync prewarm bind initiated")
        }
    }

    private fun requestStoragePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        storagePermissionLauncher.launch(intent)
    }

    companion object {
        private const val TAG_PREWARM = "KsyncPrewarm"
    }
}

@Composable
fun PowerSearchApp(
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit,
    viewModel: SearchViewModel
) {
    val context = LocalContext.current
    var keyMappingDone by remember { mutableStateOf(isKeyMappingDone(context)) }

    MaterialTheme {
        when {
            !hasStoragePermission -> {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.storage_permission_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            stringResource(R.string.storage_permission_message),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                        )
                        Button(onClick = onRequestPermission) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    }
                }
            }
            !keyMappingDone -> {
                KeyMappingScreen(context = context) {
                    keyMappingDone = true
                }
            }
            else -> {
                SearchScreen(viewModel = viewModel, onRemap = {
                    clearKeyMapping(context)
                    keyMappingDone = false
                })
            }
        }
    }
}
