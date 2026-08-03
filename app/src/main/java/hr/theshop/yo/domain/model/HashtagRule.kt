package hr.theshop.yo.domain.model

/**
 * What a hashtag may contain, stated once.
 *
 * This rule previously existed twice - once in `MainViewModel` for the SEND path and once in
 * `YoNotifier` for the DISPLAY path - each with a comment explaining that it was written
 * identically so the two could not disagree. They agreed on the character set and disagreed on
 * nothing, right up until both copies turned out to carry the same truncation bug, which had to
 * be found and fixed twice. Two identical statements of a rule are not one rule; they are two
 * rules that happen to match today.
 *
 * The character set is deliberately strict. A hashtag is sender-authored text that lands between
 * the app's own separators in someone else's notification shade, so anything that could imitate
 * the app - a space, the `·` separator, a newline, an RTL override - is removed rather than
 * escaped. `\p{L}` excludes format characters, so zero-width joiners go with the spaces.
 *
 * It is also the CLIENT half of a two-sided rule. The backend sanitises anything outside
 * `[\w-]` rather than 400ing (a 400 used to fail the entire Yo, and G25's retry re-issued that
 * request forever) - so this side exists to make that server-side stripping unreachable in the
 * first place, not merely survivable. `HashtagRuleTest` asserts that direction against concrete
 * expected outputs rather than against a copy of the server's own regex.
 *
 * Five of `\p{L}`'s own letters are excluded by exact code point rather than by category:
 * U+037A, U+115F, U+1160, U+3164 and U+FFA0 render as nothing (or as blank whitespace) in every
 * mainstream renderer, so a hashtag spelled with these instead of ASCII spaces reproduces the
 * same forgery a plain `\p{L}` filter was meant to stop - "TAP TO OPEN paypal.com" spelled with
 * blanks passes a category-only check exactly as well as the real thing. Dot-rendering letters
 * such as U+1427 (Canadian syllabics) stay allowed: they are real letters in a real script, and
 * it is the missing WHITESPACE that makes a forgery legible, not the dot.
 *
 * `\p{L}\p{N}_-` also does not cover Unicode categories Mn (non-spacing mark) or Mc (spacing
 * combining mark), so a vowel sign or virama used to be stripped exactly as unconditionally as a
 * literal space. That is not narrowing a hashtag, it is corrupting one: Devanagari and vocalised
 * Arabic spell a real word by attaching a mark to the letter in front of it, and a category-blind
 * strip reduced "नमस्ते" (namaste) to "नमसत", a consonant skeleton that reads as nonsense -
 * see `a Devanagari hashtag keeps its vowel signs`. `\p{Mn}\p{Mc}` is now admitted alongside
 * `\p{L}\p{N}_-`; none of the five codepoints above is Mn or Mc (they are Lm/Lo), so this cannot
 * let any of them back in.
 *
 * This cannot reopen the forgery those five codepoints exist to stop, either. Unicode's General
 * Category is a strict partition, so a code point that is Mn or Mc can never simultaneously be
 * Zs (a literal space) or the Po that '·' belongs to - see
 * `Mn and Mc can never be space or middle dot`. A combining mark is also zero-width by
 * definition: it draws on the character before it rather than occupying a column of its own, so
 * unlike the five excluded letters above it cannot reproduce the VISIBLE word-separating width
 * that made "TAP TO OPEN" read as three words in the first place - see
 * `a zero-width mark cannot recreate visible word separation`.
 *
 * A mark has no meaning without the base character it modifies, so a hashtag left with nothing
 * but marks after sanitising is treated the same as one that sanitised to nothing entirely - see
 * `a hashtag of only combining marks yields null`.
 */
object HashtagRule {
    /**
     * In UTF-16 units, comfortably inside the server's 140-BYTE cap even for multibyte scripts.
     */
    const val MAX_CHARS = 32

    private const val BLANK_RENDERING_CODEPOINTS = "ͺᅟᅠㅤﾠ"
    private val DISALLOWED = Regex("[^\\p{L}\\p{N}\\p{Mn}\\p{Mc}_-]|[$BLANK_RENDERING_CODEPOINTS]")
    private val ONLY_COMBINING_MARKS = Regex("^[\\p{Mn}\\p{Mc}]+$")

    /**
     * The hashtag as it may be sent and shown, or null when nothing usable is left.
     *
     * "world cup" becomes "worldcup" rather than being rejected: it is the reading the user
     * meant, it is what every other product does with a hashtag, and it means the server's own
     * sanitiser - see the class doc - has nothing left to strip for a well-behaved client.
     */
    fun sanitize(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }
            ?.trimStart('#')
            ?.replace(DISALLOWED, "")
            ?.let(::truncateWholeCharacters)
            ?.takeIf { it.isNotEmpty() && !ONLY_COMBINING_MARKS.matches(it) }

    /**
     * Truncate to [MAX_CHARS] without splitting a character in half.
     *
     * `String.take` counts UTF-16 units, so cutting at 32 can land between the two halves of an
     * astral character - a mathematical-bold letter, a CJK extension ideograph - and leave a lone
     * high surrogate at the end. Java's UTF-8 encoder turns an unpaired surrogate into `?`, and
     * `?` is not in the server's `[\w-]`, so the request came back 400 and failed the WHOLE Yo,
     * with a retry that re-issued the identical doomed request forever. That is exactly the
     * failure the send-path sanitiser was added to remove, reintroduced one layer down by the
     * sanitiser itself: the rule was correct per character and wrong per string.
     */
    private fun truncateWholeCharacters(value: String): String {
        if (value.length <= MAX_CHARS) return value
        val end = if (value[MAX_CHARS - 1].isHighSurrogate()) MAX_CHARS - 1 else MAX_CHARS
        return value.substring(0, end)
    }
}
