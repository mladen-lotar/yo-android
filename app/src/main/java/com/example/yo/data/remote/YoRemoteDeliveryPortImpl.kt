package com.example.yo.data.remote

import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.YoRemoteDeliveryPort
import javax.inject.Inject

class YoRemoteDeliveryPortImpl @Inject constructor(
    private val backendApi: YoBackendApi,
) : YoRemoteDeliveryPort {
    override suspend fun deliver(message: YoMessage): Boolean =
        backendApi.sendYo(
            sender = message.sender,
            recipient = message.recipient,
        )
}
