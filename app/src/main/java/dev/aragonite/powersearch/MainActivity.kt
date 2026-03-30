package dev.aragonite.powersearch

// pattern: Imperative Shell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aragonite.powersearch.ui.SearchScreen
import dev.aragonite.powersearch.ui.SearchViewModel
import dev.aragonite.powersearch.ui.SearchViewModelFactory

class MainActivity : ComponentActivity() {

    private var hasStoragePermission by mutableStateOf(false)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = Environment.isExternalStorageManager()
    }

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

    override fun onResume() {
        super.onResume()
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    private fun requestStoragePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        storagePermissionLauncher.launch(intent)
    }
}

@Composable
fun PowerSearchApp(
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit,
    viewModel: SearchViewModel
) {
    MaterialTheme {
        if (hasStoragePermission) {
            // SearchScreen provides its own Scaffold
            SearchScreen(viewModel = viewModel)
        } else {
            // Permission-denied screen with its own Scaffold
            Scaffold { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Storage permission required",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "Power Search needs access to read handwriting data from .ksync files.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Button(onClick = onRequestPermission) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}
