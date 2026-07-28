package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.PhoneContact

interface ContactsRepository {
    /**
     * Device contacts, de-duplicated by display name and sorted. Returns an empty list when the
     * READ_CONTACTS permission has not been granted — the caller decides how to present that,
     * rather than this throwing.
     */
    suspend fun loadContacts(): List<PhoneContact>
}
