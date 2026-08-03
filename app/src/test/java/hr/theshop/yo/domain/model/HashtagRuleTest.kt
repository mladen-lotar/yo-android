package hr.theshop.yo.domain.model

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client half of a two-sided rule.
 *
 * The backend used to refuse a hashtag outside `\A[\w-]+\Z` with a 400 that failed the ENTIRE
 * Yo, not just the attachment, and the failure banner's retry re-issued the identical request
 * forever; it now sanitises instead. Either way the point of this side is the same: make the
 * server's own charset work unreachable from a well-behaved client, not merely survivable.
 *
 * These tests assert concrete expected outputs rather than "the result matches a regex copied
 * from the server" - that copy previously stood in this file under a comment claiming it was
 * the server's own rule, and being a verbatim restatement of `DISALLOWED` it could not fail no
 * matter what `sanitize` actually did. Concrete outputs were what caught the two real defects
 * this rule has had: a space (the ordinary thing to type in a two-word tag), and a split
 * surrogate pair (invisible in any ASCII case) - and would have caught a third, the homoglyph
 * forgery below, which the old regex-copy assertion could not have failed either.
 */
class HashtagRuleTest {

    private fun survivesUtf8RoundTrip(value: String): Boolean =
        value.toByteArray(StandardCharsets.UTF_8).toString(StandardCharsets.UTF_8) == value

    @Test
    fun `hostile input sanitises to the exact expected survivor`() {
        val cases = listOf(
            "world cup" to "worldcup",
            "#already-hashed" to "already-hashed",
            "  ·  TAP TO OPEN paypal.com" to "TAPTOOPENpaypalcom",
            "x  ·  TAP TO OPEN MAP" to "xTAPTOOPENMAP",
            "line\nbreak" to "linebreak",
            "tab\there" to "tabhere",
            "‮evil" to "evil",           // RTL override
            "zero‍width" to "zerowidth", // zero-width joiner
            "emoji😀tag" to "emojitag",
            "quote\"tag" to "quotetag",
            "back\\slash" to "backslash",
            "semi;colon" to "semicolon",
            "svjetsko-prvenstvo" to "svjetsko-prvenstvo", // the one allowed punctuation
            "世界" to "世界",
            "日本語のタグ" to "日本語のタグ",
            "𝐁𝐨𝐥𝐝" to "𝐁𝐨𝐥𝐝",               // astral letters
            "a".repeat(500) to "a".repeat(HashtagRule.MAX_CHARS),
        )
        for ((input, expected) in cases) {
            assertEquals("sanitize(${input.take(24)}...)", expected, HashtagRule.sanitize(input))
            assertTrue(
                "'$expected' does not survive a UTF-8 round trip, so the server sees replacement bytes",
                survivesUtf8RoundTrip(expected),
            )
        }
    }

    /**
     * RED-FIRST for this rule: G30's forgery survives an ASCII-space-only filter by spelling
     * "TAP TO OPEN paypal.com" with five letters that render as blank space instead. `\p{L}`
     * calls every one of U+037A, U+115F, U+1160, U+3164 and U+FFA0 a letter, so before
     * `DISALLOWED` named them explicitly this whole string passed straight through unsanitised -
     * the identical shade-forging text the plain-space case above exists to stop, just spelled
     * with blanks. U+1427 (a real letter, Canadian syllabics) is deliberately included and must
     * NOT be stripped: it is the missing whitespace that makes a forgery legible, not the dot.
     */
    @Test
    fun `blank-rendering letters cannot recreate the app's own separator`() {
        val filler = 'ㅤ' // HANGUL FILLER - category Lo, renders as nothing
        val dot = 'ᐧ' // CANADIAN SYLLABICS FINAL MIDDLE DOT - a real letter, stays allowed
        val hostile = "x" + filler.toString().repeat(2) + dot + filler.toString().repeat(2) +
            "TAP" + filler + "TO" + filler + "OPEN" + filler + "paypal" + dot + "com"

        val result = HashtagRule.sanitize(hostile)!!

        assertEquals("x$dot" + "TAPTOOPENpaypal$dot" + "com", result)
        val blankRenderingCodepoints = "ͺᅟᅠㅤﾠ"
        for (codepoint in blankRenderingCodepoints) {
            assertFalse(
                "'$result' still contains U+${codepoint.code.toString(16)}, which forges the app's own separator",
                result.contains(codepoint),
            )
        }
        // The dot survives - it is a real letter in a real script - but with the fillers gone
        // there is no whitespace left to make it read as a separator between words.
        assertTrue(result.contains(dot))
        assertFalse(result.contains(' '))
    }

    @Test
    fun `each blank-rendering code point is stripped on its own`() {
        val blankRenderingCodepoints = listOf('ͺ', 'ᅟ', 'ᅠ', 'ㅤ', 'ﾠ')
        for (codepoint in blankRenderingCodepoints) {
            val input = "a$codepoint" + "b"
            assertEquals(
                "sanitize of U+${codepoint.code.toString(16)}",
                "ab",
                HashtagRule.sanitize(input),
            )
        }
    }

    @Test
    fun `an astral letter this JVM's own table does not recognise yet is dropped, not the whole tag`() {
        // U+2EBF0, CJK Unified Ideographs Extension I - a real character in real Chinese names,
        // added in Unicode 15.1 (2023). The JDK this module compiles against ships Unicode 13.0
        // character data, so `\p{L}` does not (yet) call this one a letter - measured here
        // rather than assumed, because it is the exact table-version divergence that made the
        // SERVER 400 an astral hashtag a modern handset's ICU had already accepted (see
        // test_an_astral_letter_this_process_barely_knows_about_still_sends in
        // test_yo_server.py). Sanitising rather than rejecting means an unrecognised letter is
        // simply dropped: the rest of a hashtag still ships instead of the whole attachment
        // failing over one character this table has not caught up to yet.
        val astralLetter = "𮯰" // surrogate pair for U+2EBF0
        val input = "family$astralLetter"

        assertEquals("family", HashtagRule.sanitize(input))
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
        assertEquals("the split character is dropped whole", "A".repeat(31), result)
    }

    @Test
    fun `an astral hashtag that lands on the boundary keeps whole characters`() {
        val astral = "𝐀"
        val input = astral.repeat(20) // 40 UTF-16 units

        val result = HashtagRule.sanitize(input)!!

        assertEquals(astral.repeat(16), result)
        assertTrue(survivesUtf8RoundTrip(result))
    }

    /**
     * RED-FIRST: `\p{L}\p{N}_-` does not cover Unicode categories Mn (non-spacing mark) or Mc
     * (spacing combining mark), so `DISALLOWED` stripped a vowel sign or virama exactly as
     * unconditionally as it strips a literal space. Those marks are not decoration - a script
     * assigns them the meaning that distinguishes one word from another, and they belong to the
     * consonant in front of them. "नमस्ते" (Devanagari for "namaste") is न+म+स+् (virama, Mn)+
     * त+े (vowel sign E, Mn); stripping the two Mn marks reduces it to "नमसत", a bare consonant
     * skeleton that is a different, nonsensical string, not a narrowed version of the same word.
     */
    @Test
    fun `a Devanagari hashtag keeps its vowel signs`() {
        assertEquals("नमस्ते", HashtagRule.sanitize("नमस्ते"))
    }

    /**
     * The shared table: `SendAttachmentTest.test_a_hashtag_and_the_client_agree_on_the_same_survivors`
     * in the Python suite asserts the identical pairs against the server's own sanitiser, so
     * client and server cannot silently drift onto two different rules again.
     */
    @Test
    fun `client and server agree on the same survivors`() {
        val cases = listOf(
            "नमस्ते" to "नमस्ते",
            "#नमस्ते" to "नमस्ते", // the client always drops a leading hash, unlike the server
            "مَرْحَبًا" to "مَرْحَبًا",
            "नमस्ते दुनिया" to "नमस्तेदुनिया",
            "world cup" to "worldcup",
            "world_cup" to "world_cup",
        )
        for ((input, expected) in cases) {
            assertEquals("sanitize($input)", expected, HashtagRule.sanitize(input))
        }
    }

    /**
     * A combining mark has no meaning without the base character it modifies - a lone vowel
     * sign or accent renders as garbage attached to whatever precedes the hashtag in the
     * notification body, not as a word of its own. Widening the charset to admit Mn/Mc must not
     * let a hashtag consisting ENTIRELY of such marks through: `sanitize` already returns null
     * for an empty result (see `nothing usable yields null rather than an empty tag` above), and
     * a pure-marks hashtag has to land there too.
     */
    @Test
    fun `a hashtag of only combining marks yields null`() {
        val pureMarkHashtags = listOf(
            "́", // COMBINING ACUTE ACCENT alone, category Mn
            "्", // DEVANAGARI SIGN VIRAMA alone, category Mn
            "्́", // more than one mark, still no base character
            "#́", // the leading hash is stripped first, still nothing but a mark left
        )
        for (hashtag in pureMarkHashtags) {
            assertNull("sanitize($hashtag) should have no base character to survive", HashtagRule.sanitize(hashtag))
        }
    }

    /**
     * Widening `DISALLOWED` to admit Mn/Mc must not reopen the forgery the five blank-rendering
     * codepoints and the base `\p{L}\p{N}_-` rule exist to stop. Unicode's General Category is a
     * strict partition - a code point is never in two categories at once - so proving the
     * literal space and the app's own '·' separator are NOT category Mn or Mc is a structural
     * guarantee, not a sample: nothing `\p{Mn}\p{Mc}` admits can BE either character.
     */
    @Test
    fun `Mn and Mc can never be space or middle dot`() {
        assertFalse(' '.toString().matches(Regex("[\\p{Mn}\\p{Mc}]")))
        assertFalse('·'.toString().matches(Regex("[\\p{Mn}\\p{Mc}]")))
        for (codepoint in "ͺᅟᅠㅤﾠ") {
            assertFalse(
                "U+${codepoint.code.toString(16)} must not be Mn/Mc",
                codepoint.toString().matches(Regex("[\\p{Mn}\\p{Mc}]")),
            )
        }
    }

    /**
     * A combining mark draws on the character before it rather than occupying a column of its
     * own, so - unlike the five excluded letters above, which render as blank WIDTH - admitting
     * one cannot reproduce the visible word-separating gap that made "TAP TO OPEN" legible as
     * three words. U+034F (COMBINING GRAPHEME JOINER, category Mn) is now admitted rather than
     * stripped, but it carries no width: the base letters it sits between still read as run
     * together, not as separate words.
     */
    @Test
    fun `a zero-width mark cannot recreate visible word separation`() {
        val cgj = "͏"
        val result = HashtagRule.sanitize("TAP${cgj}TO${cgj}OPEN")!!

        assertFalse(result.contains(' '))
        assertFalse(result.contains('·'))
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
