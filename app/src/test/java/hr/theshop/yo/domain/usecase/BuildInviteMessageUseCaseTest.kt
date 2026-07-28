package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.model.PhoneContact
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
    fun `handles blank and whitespace-only contact names without producing a bare Hey comma`() {
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
    fun `accented first names are greeted properly`() {
        assertTrue(buildInviteMessage(contact("Željko Đurić"), url, "me").startsWith("Hey Željko, "))
    }

    @Test
    fun `business names are not greeted as people`() {
        // Seen on a real device: "Hey AS;Pizza/1, get Yo" — a first token containing punctuation
        // or digits is not a name, so the greeting is dropped entirely.
        for (name in listOf("AS;Pizza/1", "Yo-Backend", "Cafe#2", "A1 Taxi")) {
            val message = buildInviteMessage(contact(name), url, "me")
            assertFalse("name=[$name] was greeted", message.startsWith("Hey"))
            assertTrue(message.startsWith("get Yo"))
        }
    }

    @Test
    fun `contacts stored as bare phone numbers are not greeted as people`() {
        // Also seen live: "Hey 031," from a nameless contact whose display name is its number.
        for (name in listOf("031 210 904", "091 646 4773", "+385 91 1234567")) {
            val message = buildInviteMessage(contact(name), url, "me")
            assertFalse("name=[$name] was greeted", message.startsWith("Hey"))
        }
    }

    @Test
    fun `single-letter names are not greeted, since Hey A reads like a bug`() {
        assertFalse(buildInviteMessage(contact("A"), url, "me").startsWith("Hey"))
    }

    @Test
    fun `an emoji-tagged name still greets on the leading word`() {
        assertTrue(buildInviteMessage(contact("Mum ❤"), url, "me").startsWith("Hey Mum, "))
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
