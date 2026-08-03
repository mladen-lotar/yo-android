package hr.theshop.yo.domain.model

import hr.theshop.yo.data.remote.YoRemoteDeliveryPortImpl
import hr.theshop.yo.testing.FakeYoBackendApi
import hr.theshop.yo.testing.SendCall
import java.lang.reflect.Modifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every field of a [YoMessage] is either sent to the recipient or deliberately kept on this
 * device, and this is the file that forces somebody to say which.
 *
 * The bug it exists to prevent has now shipped twice in the same shape. A field was added to
 * [YoMessage], written to Room, and rendered back to the sender as an attachment - and left out
 * of the `sendYo` call, so the recipient could never receive it. Nothing crashed, no test failed,
 * and the sender's own history showed the attachment exactly as they typed it. Location was the
 * first (gap G20); link and hashtag were the second (G23), and they survived a full test suite
 * that asserted only that the Room row was correct.
 *
 * A test per field cannot catch the *next* one, because the next field has no test yet. Only an
 * enumeration can: a tenth property fails here until it is classified, and the classification is
 * then checked against what actually goes out on the wire.
 *
 * kotlin-reflect is NOT on this module's unit-test classpath (checked against
 * `debugUnitTestRuntimeClasspath`) and is deliberately not being added for a test, so the
 * enumeration uses plain `java.lang.reflect` over the data class's backing fields instead of
 * `YoMessage::class.memberProperties`. For a Kotlin data class whose properties are all primary
 * constructor `val`s this is the same set: one private final instance field per property. Static
 * and synthetic members (the Compose compiler's `$stable`, any `Companion`) are filtered out
 * because they are not properties of a message.
 */
class YoMessageTransmissionTest {
    private companion object {
        /**
         * Reaches the recipient. Every name here must be named in
         * `YoRemoteDeliveryPortImpl.deliver`'s call to `sendYoOutcome`, which the second test
         * checks.
         */
        val TRANSMITTED =
            setOf(
                "recipient",
                "latitude",
                "longitude",
                "link",
                "hashtag",
            )

        /**
         * Never leaves the phone, and must not.
         *
         * - `id` is a local Room primary key; the server issues nothing from it.
         * - `sender` is derived server-side from the bearer token. Sending it would let a client
         *   name somebody else as the sender.
         * - `timestamp` is when this device wrote the row; the server stamps its own.
         */
        val LOCAL_ONLY =
            setOf(
                "id",
                "sender",
                "timestamp",
                // `delivered` is this device's record of whether ITS OWN send was confirmed. The
                // recipient has no use for it and must not be told: it describes the sender's
                // outcome, not the message. It exists so history stops rendering a failed send
                // identically to one that arrived (G29), which is a fact about this screen.
                "delivered",
            )
    }

    @Test
    fun `every field of a Yo is classified as transmitted or deliberately local`() {
        val declared =
            YoMessage::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()

        // Not `forEach { assertTrue(...) }`: comparing the whole set names the offending field in
        // the failure message, which is the difference between "classify `expiresAt`" and "some
        // assertion in some test is false".
        assertEquals(
            "A field of YoMessage is not classified. Add it to TRANSMITTED (and to " +
                "YoRemoteDeliveryPortImpl.deliver, and to the backend, and to the FCM payload, " +
                "and to YoNotifier) or to LOCAL_ONLY - but decide, because an unclassified " +
                "field defaults to being shown to the sender and never reaching anyone else.",
            TRANSMITTED + LOCAL_ONLY,
            declared,
        )
    }

    // The classification above is only worth having if it describes reality. This runs a message
    // with every transmitted field set to a distinct value through the real delivery port and
    // checks each one arrives - so moving a field into TRANSMITTED without wiring it up fails.
    @Test
    fun `every field classified as transmitted actually reaches the backend`() = runTest {
        val backendApi = FakeYoBackendApi()
        val message =
            YoMessage(
                id = "message-1",
                sender = "me",
                recipient = "ADA",
                timestamp = 1_000L,
                link = "https://example.com/live",
                hashtag = "worldcup",
                latitude = 45.815,
                longitude = 15.982,
            )

        YoRemoteDeliveryPortImpl(backendApi).deliver(message)

        assertEquals(
            listOf(
                SendCall(
                    recipient = message.recipient,
                    latitude = message.latitude,
                    longitude = message.longitude,
                    link = message.link,
                    hashtag = message.hashtag,
                ),
            ),
            backendApi.sends,
        )
        // SendCall records exactly the arguments sendYo accepts, so a transmitted field with
        // nowhere to go in it has not actually been wired to the backend yet.
        assertEquals(
            "TRANSMITTED names a field that sendYo has no argument for.",
            TRANSMITTED,
            SendCall::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }
}
