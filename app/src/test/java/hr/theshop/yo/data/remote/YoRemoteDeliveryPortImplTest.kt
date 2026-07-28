package hr.theshop.yo.data.remote

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.testing.FakeYoBackendApi
import hr.theshop.yo.testing.SendCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoRemoteDeliveryPortImplTest {
    @Test
    fun `deliver sends the Yo and reports what the backend said`() = runTest {
        val backendApi = FakeYoBackendApi(sendResult = false)
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi)

        val delivered =
            deliveryPort.deliver(YoMessage(id = "message-1", sender = "me", recipient = "Ada"))

        assertFalse(delivered)
        assertEquals(listOf(SendCall("Ada")), backendApi.sends)
    }

    @Test
    fun `deliver reports success when the backend confirms it`() = runTest {
        val backendApi = FakeYoBackendApi()
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi)

        val delivered =
            deliveryPort.deliver(YoMessage(id = "message-2", sender = "me", recipient = "Lin"))

        assertTrue(delivered)
        assertEquals(listOf(SendCall("Lin")), backendApi.sends)
    }

    // The regression this guards is gap G20: an attached location was written to local history
    // and never sent, so the sender saw "location attached" while the recipient got a bare Yo.
    // Nothing crashed and no test failed - the coordinates simply stopped at the phone.
    @Test
    fun `deliver forwards an attached location to the backend`() = runTest {
        val backendApi = FakeYoBackendApi()
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi)

        deliveryPort.deliver(
            YoMessage(
                id = "message-8",
                sender = "me",
                recipient = "Ada",
                latitude = 45.815,
                longitude = 15.982,
            ),
        )

        assertEquals(listOf(SendCall("Ada", 45.815, 15.982)), backendApi.sends)
    }

    // Gap G23, and the exact repeat of G20 one attachment over: link and hashtag were written to
    // Room, rendered back to the sender as attached, and never named in the sendYo call, so the
    // recipient's notification could not possibly carry them.
    @Test
    fun `deliver forwards an attached link and hashtag to the backend`() = runTest {
        val backendApi = FakeYoBackendApi()
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi)

        deliveryPort.deliver(
            YoMessage(
                id = "message-10",
                sender = "me",
                recipient = "Ada",
                link = "https://example.com/live",
                hashtag = "worldcup",
            ),
        )

        assertEquals(
            listOf(
                SendCall(
                    recipient = "Ada",
                    link = "https://example.com/live",
                    hashtag = "worldcup",
                ),
            ),
            backendApi.sends,
        )
    }

    @Test
    fun `deliver forwards every attachment at once`() = runTest {
        val backendApi = FakeYoBackendApi()
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi)

        deliveryPort.deliver(
            YoMessage(
                id = "message-11",
                sender = "me",
                recipient = "Ada",
                link = "https://example.com/live",
                hashtag = "worldcup",
                latitude = 45.815,
                longitude = 15.982,
            ),
        )

        assertEquals(
            listOf(
                SendCall(
                    recipient = "Ada",
                    latitude = 45.815,
                    longitude = 15.982,
                    link = "https://example.com/live",
                    hashtag = "worldcup",
                ),
            ),
            backendApi.sends,
        )
    }

    @Test
    fun `deliver sends no attachments when the message carries none`() = runTest {
        val backendApi = FakeYoBackendApi()
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi)

        deliveryPort.deliver(YoMessage(id = "message-9", sender = "me", recipient = "Ada"))

        assertEquals(
            listOf(
                SendCall(
                    recipient = "Ada",
                    latitude = null,
                    longitude = null,
                    link = null,
                    hashtag = null,
                ),
            ),
            backendApi.sends,
        )
    }
}
