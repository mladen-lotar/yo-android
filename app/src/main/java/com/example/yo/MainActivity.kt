package com.example.yo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.example.yo.ui.main.MainScreen
import com.example.yo.ui.theme.YoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * Asked for together on first launch so the user answers once instead of being interrupted
     * later: notifications to receive a Yo, contacts to invite people to it. Both are requested
     * only when missing, so a returning user sees nothing.
     */
    private val startupPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStartupPermissions()
        setContent {
            YoTheme {
                MainScreen()
            }
        }
    }

    private fun requestStartupPermissions() {
        val wanted = buildList {
            // POST_NOTIFICATIONS only exists from Tiramisu; asking below that throws.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_CONTACTS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (wanted.isNotEmpty()) {
            startupPermissionRequest.launch(wanted.toTypedArray())
        }
    }
}
