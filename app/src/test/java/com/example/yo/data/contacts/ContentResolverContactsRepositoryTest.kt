package com.example.yo.data.contacts

import android.Manifest
import android.content.Context
import android.database.MatrixCursor
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContentResolverContactsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun cursorOf(vararg rows: Pair<String, String?>) =
        MatrixCursor(
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
        ).apply { rows.forEach { (id, name) -> addRow(arrayOf(id, name)) } }

    private fun grantContacts() {
        shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    private fun repository() =
        ContentResolverContactsRepository(context, Dispatchers.Unconfined)

    @Test
    fun `returns empty and never touches the resolver without permission`() = runTest {
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            FakeContactsProvider(cursorOf("1" to "Alice")),
        )

        assertTrue(repository().loadContacts().isEmpty())
    }

    @Test
    fun `maps id and display name when granted`() = runTest {
        grantContacts()
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            FakeContactsProvider(cursorOf("1" to "Alice", "2" to "Bob")),
        )

        val contacts = repository().loadContacts()

        assertEquals(listOf("Alice", "Bob"), contacts.map { it.displayName })
        assertEquals(listOf("1", "2"), contacts.map { it.id })
    }

    @Test
    fun `drops blank names and de-duplicates the same person across linked accounts`() = runTest {
        grantContacts()
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            FakeContactsProvider(
                cursorOf(
                    "1" to "Alice",
                    "2" to "alice", // same person via a second account, different casing
                    "3" to "   ",
                    "4" to null,
                    "5" to "Bob",
                ),
            ),
        )

        val contacts = repository().loadContacts()

        assertEquals(listOf("Alice", "Bob"), contacts.map { it.displayName })
    }

    @Test
    fun `a null cursor from the provider yields an empty list rather than throwing`() = runTest {
        grantContacts()
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            FakeContactsProvider(null),
        )

        assertTrue(repository().loadContacts().isEmpty())
    }
}
