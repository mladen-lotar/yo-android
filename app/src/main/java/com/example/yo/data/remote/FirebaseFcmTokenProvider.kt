package com.example.yo.data.remote

import com.example.yo.domain.repository.FcmTokenProvider
import com.google.firebase.messaging.FirebaseMessaging
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

class FirebaseFcmTokenProvider @Inject constructor() : FcmTokenProvider {
    override suspend fun getToken(): String = FirebaseMessaging.getInstance().token.await()
}
