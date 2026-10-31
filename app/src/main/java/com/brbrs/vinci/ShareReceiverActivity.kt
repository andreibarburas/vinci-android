package com.brbrs.vinci

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brbrs.vinci.ui.screens.ShareReceiverScreen
import com.brbrs.vinci.ui.theme.ThemeRepository
import com.brbrs.vinci.ui.theme.VinciTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : FragmentActivity() {

    @Inject lateinit var themeRepository: ThemeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedText: String? = if (intent?.type == "text/plain") intent.getStringExtra(android.content.Intent.EXTRA_TEXT) else null
        val sharedImageUri: Uri? = if (intent?.type?.startsWith("image/") == true)
            @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        else null

        setContent {
            val isDark by themeRepository.isDark.collectAsStateWithLifecycle(initialValue = true)
            VinciTheme(isDark = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShareReceiverScreen(
                        sharedText     = sharedText,
                        sharedImageUri = sharedImageUri,
                        onDone         = { finish() },
                        onCancel       = { finish() },
                    )
                }
            }
        }
    }
}
