package hr.theshop.yo.data.remote

import android.content.Context
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the session in MODE_PRIVATE preferences, the same idiom the app already uses for the
 * device registration.
 *
 * The token is stored in the clear. That is a deliberate, bounded choice rather than an oversight:
 * MODE_PRIVATE is unreadable by other apps, the file sits inside file-based-encrypted app storage,
 * and `allowBackup="false"` keeps it out of cloud backups. The threat this whole change exists to
 * kill - a shared key extracted from a downloaded APK - is unaffected by encryption at rest, since
 * a token only appears here after a real login. See gap G8 in docs/PRD.md.
 */
@Singleton
class SharedPreferencesSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) : SessionStore {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    override val session: StateFlow<YoSession?> = state.asStateFlow()

    override fun current(): YoSession? = state.value

    override fun save(session: YoSession) {
        preferences.edit()
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_TOKEN, session.token)
            .apply()
        state.value = session
    }

    override fun clear() {
        preferences.edit().remove(KEY_USERNAME).remove(KEY_TOKEN).apply()
        state.value = null
    }

    private fun read(): YoSession? {
        val username = preferences.getString(KEY_USERNAME, null)
        val token = preferences.getString(KEY_TOKEN, null)
        return if (username.isNullOrBlank() || token.isNullOrBlank()) {
            null
        } else {
            YoSession(username = username, token = token)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "yo_session"
        const val KEY_USERNAME = "username"
        const val KEY_TOKEN = "token"
    }
}
