package hr.theshop.yo.data.remote

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.photo.PhotoEncoder
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class YoRemoteDeliveryPortImpl @Inject constructor(
    private val backendApi: YoBackendApi,
    private val photoEncoder: PhotoEncoder,
) : YoRemoteDeliveryPort {
    override suspend fun deliver(message: YoMessage): Boolean {
        // No sender argument: the backend derives it from this device's token.
        val sent = backendApi.sendYo(recipient = message.recipient)
        message.photoUri?.let { uri ->
            runCatchingIgnoringCancellation { photoEncoder.encodeForUpload(uri) }
                .getOrNull()
                ?.let { payload ->
                    runCatchingIgnoringCancellation {
                        backendApi.uploadPhoto(
                            messageId = message.id,
                            base64Data = payload.base64Data,
                            mimeType = payload.mimeType,
                            recipient = message.recipient,
                        )
                    }
                }
        }
        return sent
    }
}

private inline fun <T> runCatchingIgnoringCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
