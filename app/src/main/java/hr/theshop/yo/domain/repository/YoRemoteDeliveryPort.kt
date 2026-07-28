package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.YoMessage

interface YoRemoteDeliveryPort {
    suspend fun deliver(message: YoMessage): Boolean
}
