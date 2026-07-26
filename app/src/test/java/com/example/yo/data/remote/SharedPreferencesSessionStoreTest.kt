package com.example.yo.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.yo.domain.model.YoSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The session survives a process death, so the app does not send the caller back to the sign-in
 * screen every cold start. Exercised against Robolectric's real SharedPreferences rather than a
 * stub, because persistence is the whole behaviour under test.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesSessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        val SESSION = YoSession(username = "ADA", token = "token-1")
    }

    @Test
    fun `a saved session is read back by a later instance`() {
        SharedPreferencesSessionStore(context).save(SESSION)

        // A second instance stands in for the next cold start.
        val reopened = SharedPreferencesSessionStore(context)

        assertEquals(SESSION, reopened.current())
        assertEquals("ADA", reopened.current()?.username)
        assertEquals("token-1", reopened.current()?.token)
        assertEquals(SESSION, reopened.session.value)
    }

    @Test
    fun `a store with nothing stored has no session`() {
        val store = SharedPreferencesSessionStore(context)

        assertNull(store.current())
        assertNull(store.session.value)
    }

    @Test
    fun `clear empties the flow and the persisted values`() {
        val store = SharedPreferencesSessionStore(context)
        store.save(SESSION)

        store.clear()

        assertNull(store.current())
        assertNull(store.session.value)
        // Persisted too, not merely forgotten in memory.
        assertNull(SharedPreferencesSessionStore(context).current())
    }

    @Test
    fun `saving over an existing session replaces it`() {
        val store = SharedPreferencesSessionStore(context)
        store.save(SESSION)
        val second = YoSession(username = "LEO", token = "token-2")

        store.save(second)

        assertEquals(second, SharedPreferencesSessionStore(context).current())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the exposed flow emits the change when save is called`() = runTest {
        val store = SharedPreferencesSessionStore(context)
        val emissions = mutableListOf<YoSession?>()
        // The whole UI branches on this flow, so an unemitted save would leave the sign-in screen
        // on top of a signed-in account.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.session.toList(emissions)
        }

        store.save(SESSION)
        runCurrent()

        assertEquals(listOf(null, SESSION), emissions)

        store.clear()
        runCurrent()

        assertEquals(listOf(null, SESSION, null), emissions)
    }
}
