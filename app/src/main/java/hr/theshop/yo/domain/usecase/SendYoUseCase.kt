package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.repository.YoRepository
import javax.inject.Inject

/**
 * Every send entry point (main button T2, group fan-out T5, API-triggered T6) MUST call through
 * this use case. Do not add a second send path.
 */
class SendYoUseCase @Inject constructor(
    private val repository: YoRepository,
) {
    suspend operator fun invoke(
        sender: String,
        recipient: String,
        extras: YoMessage.() -> YoMessage = { this },
    ): YoSendOutcome {
        val message = YoMessage(sender = sender, recipient = recipient).extras()
        return repository.saveSent(message)
    }
}
