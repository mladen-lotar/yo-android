package hr.theshop.yo.data.remote

import hr.theshop.yo.testing.FakeSessionStore
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The only test in this suite that looks at the bytes on the wire.
 *
 * Everything above it stops at `YoBackendApi.sendYo(...)`: YoMessageTransmissionTest proves an
 * attachment reaches that call, and the Python suite proves the server reads a given JSON key.
 * Neither one would notice if the Kotlin side renamed the key it writes. Rename `link` to `url`
 * here and every Kotlin test and every Python test still passes, while every link ever sent
 * vanishes between the two. That is the same shows-as-attached, arrives-as-nothing failure as
 * G20 and G23, one layer lower down, and this is the layer where it would hide.
 *
 * The key names asserted below are the ones `backend/test_yo_server.py` posts to `/v1/send`
 * (`recipient`, `latitude`, `longitude`, `link`, `hashtag`) and the one it reads back
 * (`delivered`). They are written out literally rather than referenced, because a shared
 * constant would be renamed along with the code it is supposed to pin.
 *
 * The server is hand-rolled on [ServerSocket] rather than `com.sun.net.httpserver`: an Android
 * unit test compiles against android.jar, which has no `com.sun.*`. Robolectric is here only for
 * `org.json` - the stubbed android.jar throws "Stub!" from every JSONObject method on a bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
class HttpYoBackendApiTest {
    private lateinit var server: LoopbackHttpServer
    private lateinit var api: HttpYoBackendApi
    private lateinit var sessionStore: FakeSessionStore

    @Before
    fun startServer() {
        server = LoopbackHttpServer().also { it.start() }
        sessionStore = FakeSessionStore()
        api =
            HttpYoBackendApi(
                baseUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.port}",
                sessionStore = sessionStore,
                ioDispatcher = Dispatchers.IO,
            )
    }

    @After
    fun stopServer() {
        server.close()
    }

    private val sentBody: JSONObject
        get() = JSONObject(server.requests.single().body)

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    // ---- the JSON keys ---------------------------------------------------------------------

    @Test
    fun `sendYo posts exactly the keys the backend reads`() = runTest {
        api.sendYo(
            recipient = "BOB",
            latitude = 45.815,
            longitude = 15.982,
            link = "https://example.com/live",
            hashtag = "worldcup",
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/send", request.path)

        val body = sentBody
        assertEquals(
            setOf("recipient", "latitude", "longitude", "link", "hashtag"),
            body.keyNames(),
        )
        assertEquals("BOB", body.getString("recipient"))
        assertEquals(45.815, body.getDouble("latitude"), 0.0)
        assertEquals(15.982, body.getDouble("longitude"), 0.0)
        assertEquals("https://example.com/live", body.getString("link"))
        assertEquals("worldcup", body.getString("hashtag"))
    }

    // No sender field, ever: the server reads it off the bearer token, so a client that could
    // name its own sender could impersonate anybody.
    @Test
    fun `sendYo names no sender and carries the session token instead`() = runTest {
        api.sendYo(recipient = "BOB")

        assertFalse(sentBody.keyNames().contains("sender"))
        assertEquals("Bearer test-token", server.requests.single().authorization)
    }

    @Test
    fun `sendYo omits every attachment that was not supplied`() = runTest {
        api.sendYo(recipient = "BOB")

        // Not present as JSON null, not present as "": absent. A key carrying an empty string is
        // a key the server has to defend against, and `hashtag: ""` would render as a bare "#".
        assertEquals(setOf("recipient"), sentBody.keyNames())
    }

    @Test
    fun `sendYo omits blank attachments rather than sending empty strings`() = runTest {
        api.sendYo(recipient = "BOB", link = "   ", hashtag = "")

        assertEquals(setOf("recipient"), sentBody.keyNames())
    }

    // Both or neither: a lone coordinate is not a position, and the server answers 400 to the
    // pair rather than guessing at the missing half - which would fail the whole Yo, not just
    // its location.
    @Test
    fun `sendYo omits a lone coordinate instead of sending half a position`() = runTest {
        api.sendYo(recipient = "BOB", latitude = 45.815, longitude = null)

        assertEquals(setOf("recipient"), sentBody.keyNames())
    }

    @Test
    fun `sendYo omits coordinates that are out of range`() = runTest {
        api.sendYo(recipient = "BOB", latitude = 640.0, longitude = 15.982)

        assertEquals(setOf("recipient"), sentBody.keyNames())
    }

    @Test
    fun `sendYo sends coordinates as numbers, not strings`() = runTest {
        api.sendYo(recipient = "BOB", latitude = -33.8688, longitude = -151.2093)

        val body = sentBody
        assertTrue("latitude must be a JSON number", body.get("latitude") is Number)
        assertTrue("longitude must be a JSON number", body.get("longitude") is Number)
        assertEquals(-33.8688, body.getDouble("latitude"), 0.0)
        assertEquals(-151.2093, body.getDouble("longitude"), 0.0)
    }

    // ---- reading the answer ----------------------------------------------------------------

    @Test
    fun `a confirmed push is reported as delivered`() = runTest {
        server.responseBody = """{"delivered":true}"""

        assertTrue(api.sendYo(recipient = "BOB"))
    }

    /**
     * The case a 2xx check alone gets wrong, and the reason `YoSendOutcome.NotDelivered` exists:
     * an unconfigured FCM, a rate limit and an unregistered recipient all answer HTTP 200 with
     * `delivered:false`. Treating any 2xx as sent reports every one of them as arrived.
     */
    @Test
    fun `HTTP 200 with delivered false is not a delivery`() = runTest {
        server.responseBody = """{"delivered":false,"reason":"recipient_unregistered"}"""

        assertFalse(api.sendYo(recipient = "BOB"))
    }

    @Test
    fun `a 200 with no delivered field is not a delivery`() = runTest {
        server.responseBody = """{"ok":true}"""

        assertFalse(api.sendYo(recipient = "BOB"))
    }

    @Test
    fun `a rate limited send is not a delivery`() = runTest {
        server.responseStatus = 429
        server.responseBody = """{"delivered":false,"reason":"rate_limited"}"""

        assertFalse(api.sendYo(recipient = "BOB"))
    }

    /**
     * A body that is not JSON at all - a proxy error page, a captive portal, a truncated response
     * - must not quietly become `false`. It is a broken connection, not a refusal, and the
     * distinction is the whole difference between YoSendOutcome.NotDelivered and Unreachable;
     * YoRepositoryImpl turns the throw into the latter.
     */
    @Test
    fun `a 200 whose body is not JSON throws rather than reporting a refusal`() = runTest {
        server.responseBody = "<html><body>502 Bad Gateway</body></html>"

        val thrown =
            try {
                api.sendYo(recipient = "BOB")
                null
            } catch (e: Throwable) {
                e
            }

        assertNotNull("an unparseable body must not be reported as a clean refusal", thrown)
    }

    // ---- the 401 guard --------------------------------------------------------------------
    //
    // TOKEN_TTL_SECONDS on the server is 90 days (backend/yo_server.py), so this path fires on
    // nothing but the clock — the user never asked to sign out. It clears the session, and
    // deliberately nothing else: this class holds no reference to history, groups or the device
    // registration cache, so there is nothing here for it to wipe even if it wanted to.

    @Test
    fun `a 401 on an authenticated request clears the session`() = runTest {
        server.responseStatus = 401
        server.responseBody = """{"error":"invalid_token"}"""

        // fetchFriends throws on a non-2xx response; the guard runs before that throw, so the
        // session is already gone by the time this call surfaces its failure to the caller.
        runCatching { api.fetchFriends() }

        assertNull(sessionStore.current())
        assertNull(sessionStore.session.value)
    }

    // The guard is exact rather than a blanket 401 check: a signed-out caller carries no
    // Authorization header at all, so a 401 here (e.g. a bad login) must not be mistaken for a
    // dead token that was never sent.
    @Test
    fun `a 401 with no token attached does not touch a session that was never there`() = runTest {
        sessionStore.clear()
        server.responseStatus = 401
        server.responseBody = """{"error":"invalid_credentials"}"""

        runCatching { api.fetchFriends() }

        assertNull(sessionStore.current())
    }

    data class RecordedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )

    /**
     * A single-connection HTTP/1.1 server, just enough to record one request and answer it.
     * Deliberately not a mock of HttpURLConnection: the point of this file is that a real socket
     * carried real bytes, so anything short of parsing them off the wire would defeat it.
     */
    private class LoopbackHttpServer {
        // Port 0 lets the OS choose, so parallel runs cannot collide. Loopback only - this must
        // never be reachable from off the machine.
        private val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

        val port: Int get() = socket.localPort

        /** Copy-on-write for the cross-thread publish: written by the accept loop, read by the
         *  test thread. The client blocks until the response is written, which happens after the
         *  record is added, so a request is always visible by the time the call returns. */
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var responseStatus = 200

        @Volatile
        var responseBody = """{"delivered":true}"""

        private val thread = Thread(::acceptLoop, "yo-test-http").apply { isDaemon = true }

        fun start() = thread.start()

        fun close() = socket.close()

        private fun acceptLoop() {
            while (!socket.isClosed) {
                val client =
                    try {
                        socket.accept()
                    } catch (e: Throwable) {
                        return
                    }
                client.use(::serve)
            }
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.first().split(" ")
            val headers =
                lines.drop(1)
                    .mapNotNull { line ->
                        val separator = line.indexOf(':')
                        if (separator <= 0) {
                            null
                        } else {
                            line.take(separator).trim().lowercase() to
                                line.substring(separator + 1).trim()
                        }
                    }
                    .toMap()

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n <= 0) break
                read += n
            }

            requests +=
                RecordedRequest(
                    method = requestLine.getOrElse(0) { "" },
                    path = requestLine.getOrElse(1) { "" },
                    authorization = headers["authorization"],
                    body = String(body, 0, read, Charsets.UTF_8),
                )

            val payload = responseBody.toByteArray(Charsets.UTF_8)
            val status = responseStatus
            client.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 $status ${reason(status)}\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            // Close per request: HttpURLConnection pools keep-alive sockets, and
                            // a pooled one outliving the test would hang the next accept().
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8),
                )
                write(payload)
                flush()
            }
        }

        /** Reads up to and including the blank line that ends the request headers. */
        private fun readHead(input: InputStream): String? {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next == -1) return null
                head.append(next.toChar())
            }
            return head.toString().removeSuffix("\r\n\r\n")
        }

        private fun reason(status: Int): String =
            when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                429 -> "Too Many Requests"
                else -> "Status"
            }
    }
}
