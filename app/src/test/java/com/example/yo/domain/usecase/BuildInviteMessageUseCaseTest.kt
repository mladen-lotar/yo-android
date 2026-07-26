package com.example.yo.domain.usecase

import com.example.yo.domain.model.PhoneContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInviteMessageUseCaseTest {
    private val buildInviteMessage = BuildInviteMessageUseCase()
    private val url = "https://yo.example/install"

    private fun contact(name: String) = PhoneContact(id = "1", displayName = name)

    @Test
    fun `includes the invite url and the sender in caps`() {
        val message = buildInviteMessage(contact("Alice"), url, "me")

        assertTrue(message.contains(url))
        assertTrue(message.contains("I'm ME on it."))
    }

    @Test
    fun `greets a contact by first name only`() {
        val message = buildInviteMessage(contact("Alice Smith"), url, "me")

        assertTrue(message.startsWith("Hey Alice, "))
        assertFalse("surname should not appear", message.contains("Smith"))
    }

    @Test
    fun `omits the greeting entirely when sharing without a contact`() {
        val message = buildInviteMessage(null, url, "me")

        assertFalse(message.contains("Hey"))
        assertTrue(message.startsWith("get Yo"))
        assertTrue(message.contains(url))
    }

    @Test
    fun `handles blank and whitespace-only contact names without producing "Hey ,"`() {
        for (name in listOf("", "   ", "\n")) {
            val message = buildInviteMessage(contact(name), url, "me")
            assertFalse("name=[$name] produced a dangling greeting", message.contains("Hey ,"))
            assertTrue(message.startsWith("get Yo"))
        }
    }

    @Test
    fun `single-word names survive intact`() {
        assertTrue(buildInviteMessage(contact("Mum"), url, "me").startsWith("Hey Mum, "))
    }

    @Test
    fun `carries Yo's own tagline verbatim`() {
        assertTrue(buildInviteMessage(null, url, "me").endsWith("Yo. It's that simple."))
    }

    @Test
    fun `message is plain text with no placeholders left unresolved`() {
        val message = buildInviteMessage(contact("Alice"), url, "me")

        assertFalse(message.contains("$"))
        assertFalse(message.contains("null"))
        assertEquals(message.trim(), message)
    }
}
