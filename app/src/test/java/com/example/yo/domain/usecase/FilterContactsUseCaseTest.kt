package com.example.yo.domain.usecase

import com.example.yo.domain.model.PhoneContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterContactsUseCaseTest {
    private val filter = FilterContactsUseCase()

    private val contacts = listOf(
        "Adam Marjanović",
        "Petra Vego",
        "Željko Đurić",
        "Zena",
        "AS;Pizza/1",
        "031 210 904",
        "Ada Lovelace",
    ).mapIndexed { index, name -> PhoneContact(id = index.toString(), displayName = name) }

    private fun names(query: String) = filter(contacts, query).map { it.displayName }

    @Test
    fun `an empty or blank query returns everything untouched`() {
        assertEquals(contacts, filter(contacts, ""))
        assertEquals(contacts, filter(contacts, "   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("Petra Vego"), names("PETRA"))
        assertEquals(listOf("Petra Vego"), names("petra"))
    }

    @Test
    fun `matching ignores diacritics so an ASCII keyboard finds accented names`() {
        // The whole point: a Croatian address book is unsearchable without this.
        assertEquals(listOf("Adam Marjanović"), names("marjanovic"))
        assertEquals(listOf("Željko Đurić"), names("zeljko"))
        assertEquals(listOf("Željko Đurić"), names("duric"))
    }

    @Test
    fun `typing the accented form still matches`() {
        assertEquals(listOf("Adam Marjanović"), names("Marjanović"))
        assertEquals(listOf("Željko Đurić"), names("Željko"))
    }

    @Test
    fun `matches any word, not only the start of the name`() {
        assertEquals(listOf("Petra Vego"), names("vego"))
        assertEquals(listOf("Ada Lovelace"), names("lovelace"))
    }

    @Test
    fun `all terms must match but order does not matter`() {
        assertEquals(listOf("Petra Vego"), names("petra vego"))
        assertEquals(listOf("Petra Vego"), names("vego petra"))
        assertTrue(names("petra lovelace").isEmpty())
    }

    @Test
    fun `contacts stored as bare phone numbers stay searchable by digits`() {
        assertEquals(listOf("031 210 904"), names("210"))
    }

    @Test
    fun `punctuation-heavy business names are matchable`() {
        assertEquals(listOf("AS;Pizza/1"), names("pizza"))
    }

    @Test
    fun `a query matching nothing returns an empty list rather than everything`() {
        assertTrue(names("zzzzz").isEmpty())
    }

    @Test
    fun `a prefix shared by several contacts returns all of them in original order`() {
        // "Ada Lovelace" and "Adam Marjanović" both start with "ada".
        assertEquals(listOf("Adam Marjanović", "Ada Lovelace"), names("ada"))
    }
}
