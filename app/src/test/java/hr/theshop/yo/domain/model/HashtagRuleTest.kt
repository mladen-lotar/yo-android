package hr.theshop.yo.domain.model

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client half of a two-sided rule.
 *
 * The backend refuses a hashtag outside `\A[\w-]+\Z` with a 400 that fails the ENTIRE Yo, not
 * just the attachment, and the failure banner's retry re-issues the identical request forever.
 * So the acceptance criterion is not "the client sanitises" - it is that the server's rejection
 * is UNREACHABLE from this client. These tests assert that direction as a property over the
 * character space, rather than over a handful of examples somebody thought of, because the two
 * defects this rule has had were both invisible to example-based tests: a space (the ordinary
 * thing to type in a two-word tag), and a split surrogate pair (invisible in any ASCII case).
 */
class HashtagRuleTest {

    /** What the SERVER accepts, expressed in the terms available here. */
    private val serverAccepts = Regex("[\\p{L}\\p{N}_-]+")

    private fun survivesUtf8RoundTrip(value: String): Boolean =
        value.toByteArray(StandardCharsets.UTF_8).toString(StandardCharsets.UTF_8) == value

    @Test
    fun `anything it emits is something the server accepts`() {
        val hostile = listOf(
            "world cup",
            "#already-hashed",
            "  ·  TAP TO OPEN paypal.com",
            "x  ·  TAP TO OPEN MAP",
            "line\nbreak",
            "tab\there",
            "‮evil",           // RTL override
            "zero‍width",      // zero-width joiner
            "emoji😀tag",
            "quote\"tag",
            "back\\slash",
            "semi;colon",
            "svjetsko-prvenstvo",   // legitimate, with the one allowed punctuation
            "世界",
            "日本語のタグ",
            "𝐁𝐨𝐥𝐝",               // astral letters
            "a".repeat(500),
        )
        for (input in hostile) {
            val result = HashtagRule.sanitize(input) ?: continue
            assertTrue(
                "sanitize(${input.take(24)}...) produced '$result', which the server would 400",
                serverAccepts.matches(result),
            )
            assertTrue(
                "'$result' does not survive a UTF-8 round trip, so the server sees replacement bytes",
                survivesUtf8RoundTrip(result),
            )
        }
    }

    @Test
    fun `truncation never splits a character in half`() {
        // 31 BMP letters then one ASTRAL letter: take(32) used to cut between the surrogates,
        // leaving a lone high surrogate that Java's UTF-8 encoder writes as '?', which is not in
        // the server's [\w-]. The result was a 400 that failed the whole Yo and a retry that
        // could never succeed - the exact failure the send-path sanitiser exists to prevent.
        val astral = "𝐀" // U+1D400 MATHEMATICAL BOLD CAPITAL A, category Lu
        val input = "A".repeat(31) + astral

        val result = HashtagRule.sanitize(input)!!

        assertTrue("a lone high surrogate survived", survivesUtf8RoundTrip(result))
        assertTrue("last char is an unpaired surrogate", !result.last().isHighSurrogate())
        assertTrue(serverAccepts.matches(result))
        assertEquals("the split character is dropped whole", 31, result.length)
    }

    @Test
    fun `an astral hashtag that lands on the boundary keeps whole characters`() {
        val astral = "𝐀"
        val input = astral.repeat(20) // 40 UTF-16 units

        val result = HashtagRule.sanitize(input)!!

        assertEquals(32, result.length)
        assertTrue(survivesUtf8RoundTrip(result))
        assertTrue(serverAccepts.matches(result))
    }

    @Test
    fun `the ordinary two-word case travels rather than failing`() {
        assertEquals("worldcup", HashtagRule.sanitize("world cup"))
    }

    @Test
    fun `a leading hash is not doubled`() {
        assertEquals("worldcup", HashtagRule.sanitize("#worldcup"))
    }

    @Test
    fun `nothing usable yields null rather than an empty tag`() {
        for (empty in listOf(null, "", "   ", "#", "###", "· · ·", "😀😀")) {
            assertNull("'$empty' should not become a hashtag", HashtagRule.sanitize(empty))
        }
    }

    @Test
    fun `the bound is well inside the server's byte cap`() {
        // The server caps at 140 BYTES. Worst case here is 32 UTF-16 units of 3-byte characters.
        val worst = HashtagRule.sanitize("世".repeat(100))!!
        assertTrue(
            "sanitised hashtag is ${worst.toByteArray(StandardCharsets.UTF_8).size} bytes",
            worst.toByteArray(StandardCharsets.UTF_8).size <= 140,
        )
    }
}
