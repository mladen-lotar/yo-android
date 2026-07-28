package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import kotlinx.coroutines.flow.Flow

interface YoRepository {
    /**
     * Persists [message] locally, then attempts remote delivery and reports what happened.
     *
     * The local write happens either way, and deliberately so: the link and hashtag exist only
     * here, so discarding the row on a failed send would destroy what the user typed at exactly
     * the moment they want to try again.
     */
    suspend fun saveSent(message: YoMessage): YoSendOutcome

    fun observeHistory(): Flow<List<YoMessage>>

    /** Forgets every stored Yo. Used when an account is deleted. */
    suspend fun clear()
}
