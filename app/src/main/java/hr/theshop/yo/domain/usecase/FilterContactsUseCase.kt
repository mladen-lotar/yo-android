package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.model.PhoneContact
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

/**
 * Narrows the invite list as the user types. Pure and injectable so the matching rules are unit
 * tested rather than buried in a Composable.
 *
 * Matching is deliberately forgiving, because an address book is messy:
 * - case-insensitive
 * - **diacritic-insensitive**, so "marjanovic" finds "Marjanović" and "zeljko" finds "Željko".
 *   Without this the filter is useless on any non-English address book.
 * - matches any word, not just the start of the name, so "vego" finds "Petra Vego"
 * - multi-word queries must all match, in any order, so "petra v" and "v petra" both work
 */
class FilterContactsUseCase @Inject constructor() {
    operator fun invoke(contacts: List<PhoneContact>, query: String): List<PhoneContact> {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return contacts
        return contacts.filter { contact ->
            val haystack = normalize(contact.displayName)
            terms.all { haystack.contains(it) }
        }
    }

    /**
     * Lower-cases and strips accents by decomposing to NFD and dropping the combining marks, so
     * "Ć" becomes "c". Digits and punctuation are left alone — a contact stored as a phone number
     * should still be findable by typing part of that number.
     */
    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            // Croatian đ/Đ has no combining-mark decomposition, so it needs an explicit mapping.
            .replace('đ', 'd')

    private companion object {
        val COMBINING_MARKS = "\\p{Mn}+".toRegex()
    }
}
