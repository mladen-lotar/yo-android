package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.YoMessage
import kotlinx.coroutines.flow.Flow

interface YoRepository {
    /**
     * Persists [message] locally, then triggers best-effort remote delivery.
     */
    suspend fun saveSent(message: YoMessage)

    fun observeHistory(): Flow<List<YoMessage>>

    /** Forgets every stored Yo. Used when an account is deleted. */
    suspend fun clear()
}
