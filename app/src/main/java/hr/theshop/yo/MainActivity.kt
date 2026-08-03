package hr.theshop.yo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import hr.theshop.yo.domain.repository.SessionStore
import hr.theshop.yo.ui.auth.AuthScreen
import hr.theshop.yo.ui.main.MainScreen
import hr.theshop.yo.ui.theme.YoTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YoTheme {
                val session by sessionStore.session.collectAsState()
                val current = session
                if (current == null) {
                    AuthScreen()
                } else {
                    // Keyed on the username so signing out and back in as somebody else builds a
                    // fresh MainViewModel instead of reusing the previous account's friend list.
                    //
                    // Notifications also used to be requested from here, in onCreate, same as
                    // contacts used to be - which put the prompt on the sign-in screen, before an
                    // account existed and before a signed-out user could receive anything a grant
                    // would even mean. It is requested from inside MainScreen instead now, because
                    // this branch - not onCreate - is what actually runs once per session
                    // appearing: gating onCreate on "a session already existed" would still have
                    // skipped a user who signs up during this same launch, since that transitions
                    // straight into this branch with no restart in between for onCreate to rerun.
                    MainScreen(viewModel = hiltViewModel(key = current.username))
                }
            }
        }
    }
}
