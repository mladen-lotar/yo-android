package hr.theshop.yo.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import hr.theshop.yo.domain.model.PhoneContact
import hr.theshop.yo.domain.repository.ContactsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ContentResolverContactsRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ContactsRepository {

    // Dagger ignores Kotlin default arguments, so the injectable constructor is explicit rather
    // than a defaulted parameter. The two-arg form above stays available to tests.
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, Dispatchers.IO)

    override suspend fun loadContacts(): List<PhoneContact> {
        if (!hasPermission()) return emptyList()
        return withContext(ioDispatcher) { query(context.contentResolver) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun query(resolver: ContentResolver): List<PhoneContact> {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        // HAS_PHONE_NUMBER is not required — an invite goes out through the share sheet, so an
        // email-only or messaging-only contact is just as invitable as one with a phone number.
        val cursor = runCatching {
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
            )
        }.getOrNull() ?: return emptyList()

        val seenNames = HashSet<String>()
        val contacts = ArrayList<PhoneContact>()
        cursor.use {
            val idColumn = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameColumn = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            if (idColumn < 0 || nameColumn < 0) return emptyList()
            while (it.moveToNext()) {
                val name = it.getString(nameColumn)?.trim().orEmpty()
                if (name.isEmpty()) continue
                // The same person commonly appears once per linked account (Google, SIM, WhatsApp),
                // which would otherwise render as a wall of duplicate bands.
                if (!seenNames.add(name.lowercase())) continue
                contacts += PhoneContact(id = it.getString(idColumn).orEmpty(), displayName = name)
            }
        }
        return contacts
    }
}
