package hr.theshop.yo.data.remote

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import javax.inject.Inject

class YoRemoteDeliveryPortImpl @Inject constructor(
    private val backendApi: YoBackendApi,
) : YoRemoteDeliveryPort {
    override suspend fun deliver(message: YoMessage): Boolean =
        // No sender argument: the backend derives it from this device's token.
        //
        // Every attachment on the message is named here on purpose. Anything left out is stored
        // in the sender's own history, rendered back to them as attached, and never seen by the
        // person they sent it to - which is how location (G20) and then link and hashtag (G23)
        // each shipped looking like features.
        backendApi.sendYo(
            recipient = message.recipient,
            latitude = message.latitude,
            longitude = message.longitude,
            link = message.link,
            hashtag = message.hashtag,
        )
}
