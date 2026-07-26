package com.example.yo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.yo.domain.repository.SessionStore
import com.example.yo.ui.auth.AuthScreen
import com.example.yo.ui.main.MainScreen
import com.example.yo.ui.theme.YoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * The whole UI branches on this: no session means the sign-in screen, a session means the
     * bands. It is a plain state branch rather than a NavHost because the app has exactly two
     * destinations and no back stack worth having - you cannot "go back" to being signed out.
     */
    @Inject
    lateinit var sessionStore: SessionStore

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
                val session by sessionStore.session.collectAsState()
                val current = session
                if (current == null) {
                    AuthScreen()
                } else {
                    // Keyed on the username so signing out and back in as somebody else builds a
                    // fresh MainViewModel instead of reusing the previous account's friend list.
                    MainScreen(viewModel = hiltViewModel(key = current.username))
                }
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
