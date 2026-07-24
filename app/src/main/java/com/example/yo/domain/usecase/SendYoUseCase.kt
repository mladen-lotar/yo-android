package com.example.yo.domain.usecase

import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.YoRepository
import javax.inject.Inject

/**
 * Every send entry point (main button T2, group fan-out T5, API-triggered T6, photo T7) MUST call through this use case. Do not add a second send path.
 */
class SendYoUseCase @Inject constructor(
    private val repository: YoRepository,
) {
    suspend operator fun invoke(
        sender: String,
        recipient: String,
        extras: YoMessage.() -> YoMessage = { this },
    ): YoMessage {
        val message = YoMessage(sender = sender, recipient = recipient).extras()

        // Real delivery (FCM/backend) plugs in via a YoRepository implementation swap or delivery-port addition in T3; this signature and its call sites must not change for T3.
        repository.saveSent(message)
        return message
    }
}
