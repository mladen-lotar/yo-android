package hr.theshop.yo.data.remote

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.photo.PhotoEncoder
import hr.theshop.yo.domain.photo.PhotoPayload
import hr.theshop.yo.testing.StubYoBackendApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YoRemoteDeliveryPortImplTest {
    @Test
    fun `deliver sends the text Yo and uploads an encoded photo`() = runTest {
        val backendApi = FakeYoBackendApi(sendResult = false)
        val payload = PhotoPayload(base64Data = "encoded-photo", mimeType = "image/jpeg")
        val photoEncoder = FakePhotoEncoder(payload = payload)
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)
        val message =
            YoMessage(
                id = "message-1",
                sender = "me",
                recipient = "Ada",
                photoUri = "content://photos/message-1",
            )

        val delivered = deliveryPort.deliver(message)

        assertFalse(delivered)
        assertEquals(listOf(SendCall("Ada")), backendApi.sends)
        assertEquals(listOf("content://photos/message-1"), photoEncoder.encodedUris)
        assertEquals(
            listOf(UploadCall("message-1", "encoded-photo", "image/jpeg", "Ada")),
            backendApi.uploads,
        )
    }

    @Test
    fun `deliver without a photo never encodes or uploads`() = runTest {
        val backendApi = FakeYoBackendApi()
        val photoEncoder = FakePhotoEncoder()
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)

        val delivered =
            deliveryPort.deliver(
                YoMessage(
                    id = "message-2",
                    sender = "me",
                    recipient = "Lin",
                ),
            )

        assertTrue(delivered)
        assertEquals(listOf(SendCall("Lin")), backendApi.sends)
        assertTrue(photoEncoder.encodedUris.isEmpty())
        assertTrue(backendApi.uploads.isEmpty())
    }

    @Test
    fun `deliver skips upload when a photo cannot be encoded`() = runTest {
        val backendApi = FakeYoBackendApi()
        val photoEncoder = FakePhotoEncoder(payload = null)
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)

        val delivered =
            deliveryPort.deliver(
                YoMessage(
                    id = "message-3",
                    sender = "me",
                    recipient = "Grace",
                    photoUri = "content://photos/missing",
                ),
            )

        assertTrue(delivered)
        assertEquals(listOf("content://photos/missing"), photoEncoder.encodedUris)
        assertTrue(backendApi.uploads.isEmpty())
    }

    @Test
    fun `deliver keeps the text result when photo upload throws`() = runTest {
        val backendApi = FakeYoBackendApi(uploadFailure = IllegalStateException("offline"))
        val photoEncoder =
            FakePhotoEncoder(
                payload = PhotoPayload(base64Data = "encoded-photo", mimeType = "image/jpeg"),
            )
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)

        val delivered =
            deliveryPort.deliver(
                YoMessage(
                    id = "message-4",
                    sender = "me",
                    recipient = "Katherine",
                    photoUri = "content://photos/message-4",
                ),
            )

        assertTrue(delivered)
        assertEquals(
            listOf(UploadCall("message-4", "encoded-photo", "image/jpeg", "Katherine")),
            backendApi.uploads,
        )
    }

    @Test
    fun `deliver keeps the text result when photo encoding throws`() = runTest {
        val backendApi = FakeYoBackendApi()
        val photoEncoder = FakePhotoEncoder(failure = IllegalArgumentException("bad uri"))
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)

        val delivered =
            deliveryPort.deliver(
                YoMessage(
                    id = "message-5",
                    sender = "me",
                    recipient = "Margaret",
                    photoUri = "content://photos/message-5",
                ),
            )

        assertTrue(delivered)
        assertEquals(listOf(SendCall("Margaret")), backendApi.sends)
        assertTrue(backendApi.uploads.isEmpty())
    }

    @Test
    fun `deliver propagates cancellation from photo encoding`() = runTest {
        val cancellation = CancellationException("encoding cancelled")
        val backendApi = FakeYoBackendApi()
        val photoEncoder = FakePhotoEncoder(failure = cancellation)
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)

        val failure =
            try {
                deliveryPort.deliver(
                    YoMessage(
                        id = "message-6",
                        sender = "me",
                        recipient = "Radia",
                        photoUri = "content://photos/message-6",
                    ),
                )
                null
            } catch (e: CancellationException) {
                e
            }

        assertSame(cancellation, failure)
    }

    @Test
    fun `deliver propagates cancellation from photo upload`() = runTest {
        val cancellation = CancellationException("upload cancelled")
        val backendApi = FakeYoBackendApi(uploadFailure = cancellation)
        val photoEncoder =
            FakePhotoEncoder(
                payload = PhotoPayload(base64Data = "encoded-photo", mimeType = "image/jpeg"),
            )
        val deliveryPort = YoRemoteDeliveryPortImpl(backendApi, photoEncoder)

        val failure =
            try {
                deliveryPort.deliver(
                    YoMessage(
                        id = "message-7",
                        sender = "me",
                        recipient = "Hedy",
                        photoUri = "content://photos/message-7",
                    ),
                )
                null
            } catch (e: CancellationException) {
                e
            }

        assertSame(cancellation, failure)
    }

    private class FakeYoBackendApi(
        private val sendResult: Boolean = true,
        private val uploadFailure: Throwable? = null,
    ) : StubYoBackendApi() {
        val sends = mutableListOf<SendCall>()
        val uploads = mutableListOf<UploadCall>()

        override suspend fun sendYo(recipient: String): Boolean {
            sends += SendCall(recipient)
            return sendResult
        }

        override suspend fun uploadPhoto(
            messageId: String,
            base64Data: String,
            mimeType: String,
            recipient: String?,
        ): Boolean {
            uploads += UploadCall(messageId, base64Data, mimeType, recipient)
            uploadFailure?.let { throw it }
            return true
        }
    }

    private class FakePhotoEncoder(
        private val payload: PhotoPayload? = null,
        private val failure: Throwable? = null,
    ) : PhotoEncoder {
        val encodedUris = mutableListOf<String>()

        override suspend fun encodeForUpload(photoUri: String): PhotoPayload? {
            encodedUris += photoUri
            failure?.let { throw it }
            return payload
        }
    }

    private data class SendCall(
        val recipient: String,
    )

    private data class UploadCall(
        val messageId: String,
        val base64Data: String,
        val mimeType: String,
        val recipient: String?,
    )
}
