package hr.theshop.yo.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import hr.theshop.yo.domain.location.LocationCoordinates
import hr.theshop.yo.domain.location.LocationLink
import hr.theshop.yo.domain.usecase.RegisterDeviceUseCase
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class YoFirebaseMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var registerDeviceUseCase: RegisterDeviceUseCase

    // Deprecated in firebase-messaging 25.x in favour of onRegistered(). Paired with
    // FirebaseFcmTokenProvider.getToken(); see the note there and gap G19 - the two have to move
    // together, because they are the two halves of one registration story.
    @Deprecated("Superseded by onRegistered(); migrate together with FirebaseFcmTokenProvider.")
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        runBlocking(Dispatchers.IO) {
            runCatching {
                registerDeviceUseCase(fcmToken = token, force = true)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != "yo") {
            return
        }
        val sender = message.data["sender"]?.takeIf(String::isNotBlank) ?: return
        YoNotifier.postYoNotification(
            context = this,
            sender = sender,
            coordinates = coordinatesFrom(message),
            link = message.data["link"],
            hashtag = message.data["hashtag"],
        )
    }

    /**
     * FCM data values are always strings, so the coordinates arrive as text and may be absent,
     * partial or junk. Anything short of a complete, valid pair yields null, which downgrades the
     * push to a plain Yo - the Yo itself must still arrive when only the location is malformed.
     */
    private fun coordinatesFrom(message: RemoteMessage): LocationCoordinates? {
        val latitude = message.data["latitude"]?.toDoubleOrNull() ?: return null
        val longitude = message.data["longitude"]?.toDoubleOrNull() ?: return null
        if (!LocationLink.isValid(latitude, longitude)) return null
        return LocationCoordinates(latitude = latitude, longitude = longitude)
    }
}
