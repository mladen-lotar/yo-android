package com.example.yo.service

import com.example.yo.domain.usecase.RegisterDeviceUseCase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class YoFirebaseMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var registerDeviceUseCase: RegisterDeviceUseCase

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
        YoNotifier.postYoNotification(this, sender)
    }
}
