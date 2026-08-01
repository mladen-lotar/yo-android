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
 * It is also the CLIENT half of a two-sided rule. The backend refuses anything outside
 * `\A[\w-]+\Z` with a 400 that fails the entire Yo, and G25's retry re-issues that request
 * forever - so the server's rejection has to be unreachable from our own client, not merely
 * present. `HashtagRuleTest` asserts that direction against the real character set rather than
 * against a handful of examples.
 */
object HashtagRule {
    /**
     * In UTF-16 units, comfortably inside the server's 140-BYTE cap even for multibyte scripts.
     */
    const val MAX_CHARS = 32

    private val DISALLOWED = Regex("[^\\p{L}\\p{N}_-]")

    /**
     * The hashtag as it may be sent and shown, or null when nothing usable is left.
     *
     * "world cup" becomes "worldcup" rather than being rejected: it is the reading the user
     * meant, it is what every other product does with a hashtag, and it keeps the server's rule
     * intact by making its 400 unreachable instead of by relaxing it.
     */
    fun sanitize(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }
            ?.trimStart('#')
            ?.replace(DISALLOWED, "")
            ?.let(::truncateWholeCharacters)
            ?.takeIf { it.isNotEmpty() }

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
