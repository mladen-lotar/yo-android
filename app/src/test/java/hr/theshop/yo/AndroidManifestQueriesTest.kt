package hr.theshop.yo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Reads the manifest as a FILE and asserts the `<queries>` declarations are present.
 *
 * This is a crude test and it is here on purpose, because it is the only kind that can fail.
 *
 * From API 30 an application cannot see packages it has not declared an interest in. Browsers are
 * NOT on the automatic visibility allowlist, so without a `<queries>` entry for https,
 * `Intent.resolveActivity` returns null on every modern device, `YoNotifier.linkPendingIntent`
 * returns null, and every attached link is silently dropped - the sender is shown a link
 * attached, the recipient gets a plain Yo. The same rule, with the same consequence, applies to
 * `geo:` and shared locations.
 *
 * Robolectric's ShadowPackageManager does NOT enforce visibility filtering, so every link test in
 * YoNotifierTest passes with or without these entries. That is exactly the blind spot that let
 * this ship: the behaviour is invisible to the emulator-shaped tests and only appears on a real
 * handset. A comment in the manifest cannot fail a build; this can.
 */
class AndroidManifestQueriesTest {
    @Test
    fun `the manifest declares package visibility for https so links stay openable`() {
        val queries = queriesIntents()

        assertTrue(
            "AndroidManifest.xml has no <queries><intent> for scheme https. Without it, " +
                "resolveActivity() sees no browser on API 30+ and EVERY link attached to a Yo " +
                "is silently dropped before it can be made tappable.",
            queries.any { it.action == VIEW && it.schemes.contains("https") },
        )
    }

    @Test
    fun `the manifest declares package visibility for geo so locations stay openable`() {
        val queries = queriesIntents()

        assertTrue(
            "AndroidManifest.xml has no <queries><intent> for scheme geo. Without it, a shared " +
                "location silently falls back to the browser even with Google Maps installed.",
            queries.any { it.action == VIEW && it.schemes.contains("geo") },
        )
    }

    // The browser entry is matched on BROWSABLE as well: an <intent> filter in <queries> matches
    // only activities whose own filter is at least as broad, and a browser's is VIEW + BROWSABLE.
    @Test
    fun `the https visibility entry is categorised BROWSABLE like a real browser filter`() {
        val https = queriesIntents().single { it.action == VIEW && it.schemes.contains("https") }

        assertEquals(setOf("android.intent.category.BROWSABLE"), https.categories)
    }

    private data class QueryIntent(
        val action: String?,
        val categories: Set<String>,
        val schemes: Set<String>,
    )

    private fun queriesIntents(): List<QueryIntent> {
        val manifest = manifestFile()
        val document =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)

        val queries = document.documentElement.childElements().filter { it.tagName == "queries" }
        assertTrue(
            "${manifest.path} declares no <queries> element at all.",
            queries.isNotEmpty(),
        )

        return queries
            .flatMap { it.childElements() }
            .filter { it.tagName == "intent" }
            .map { intent ->
                QueryIntent(
                    action = intent.firstAndroidName("action"),
                    categories = intent.allAndroidNames("category"),
                    schemes =
                        intent.childElements()
                            .filter { it.tagName == "data" }
                            .mapNotNull { it.androidAttribute("scheme") }
                            .toSet(),
                )
            }
    }

    /**
     * Resolved rather than hardcoded, and asserted to exist: a test that quietly passes because
     * it could not find the file would be worse than no test. Gradle runs Android unit tests with
     * the module directory as the working directory, but that is a default, not a contract.
     */
    private fun manifestFile(): File {
        val candidates =
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) {
                it.parentFile
            }
                .take(4)
                .flatMap {
                    sequenceOf(
                        File(it, "src/main/AndroidManifest.xml"),
                        File(it, "app/src/main/AndroidManifest.xml"),
                    )
                }

        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "Could not locate AndroidManifest.xml from working directory " +
                    "${System.getProperty("user.dir")}. Tried src/main and app/src/main up to " +
                    "four levels up. Fix the lookup - do not delete this test.",
            )
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }

    private fun Element.androidAttribute(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    private fun Element.firstAndroidName(tag: String): String? =
        childElements().firstOrNull { it.tagName == tag }?.androidAttribute("name")

    private fun Element.allAndroidNames(tag: String): Set<String> =
        childElements().filter { it.tagName == tag }.mapNotNull { it.androidAttribute("name") }
            .toSet()

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val VIEW = "android.intent.action.VIEW"
    }
}
