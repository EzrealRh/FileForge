package com.fileforge.converter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.fileforge.converter.ui.WorkbenchScreen
import com.fileforge.converter.ui.WorkbenchViewModel
import com.fileforge.converter.ui.theme.FileForgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WorkbenchViewModel by viewModels()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::publish)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FileForgeTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    WorkbenchScreen(
                        viewModel = viewModel,
                        onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                        onExport = { pickFolder.launch(null) },
                    )
                }
            }
        }
        if (intent?.action == Intent.ACTION_SEND) acceptShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) acceptShared(intent)
    }

    /** 让别的 App 能把图片/视频/PDF 直接分享进来处理。 */
    private fun acceptShared(intent: Intent?) {
        val uri = intent?.let {
            @Suppress("DEPRECATION")
            it.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: it.clipData?.getItemAt(0)?.uri
        }
        if (uri != null) viewModel.import(listOf(uri))
    }
}
