package hr.theshop.yo.data.remote

import com.google.firebase.messaging.FirebaseMessaging
import hr.theshop.yo.domain.repository.FcmTokenProvider
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

class FirebaseFcmTokenProvider @Inject constructor() : FcmTokenProvider {
    // firebase-messaging 25.x deprecates getToken() in favour of register(), which returns no
    // token and instead delivers it to FirebaseMessagingService.onRegistered(). Adopting it turns
    // registration from something this app can ask for and retry into something it can only wait
    // for, which would rewrite RegisterDeviceUseCase's retry/backoff and the "not receiving Yos"
    // state along with it. Deprecated is not removed; this path is the one proven end-to-end on a
    // handset, so it stays for the first release. Tracked as gap G19.
    @Suppress("DEPRECATION")
    override suspend fun getToken(): String = FirebaseMessaging.getInstance().token.await()
}
