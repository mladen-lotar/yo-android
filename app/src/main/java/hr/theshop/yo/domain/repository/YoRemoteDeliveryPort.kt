package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome

interface YoRemoteDeliveryPort {
    /**
     * Returns [YoSendOutcome] rather than a plain success/failure boolean so that
     * [YoSendOutcome.Rejected] - a refusal a retry cannot fix - survives all the way to
     * [YoRepository.saveSent] and, from there, to whatever decides whether to offer a retry.
     * A `Boolean` return here is what used to destroy that distinction before it ever reached
     * `saveSent`.
     */
    suspend fun deliver(message: YoMessage): YoSendOutcome
}
