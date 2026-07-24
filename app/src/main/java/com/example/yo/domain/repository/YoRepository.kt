package com.example.yo.domain.repository

import com.example.yo.domain.model.YoMessage
import kotlinx.coroutines.flow.Flow

interface YoRepository {
    /**
     * Persists [message] locally, then triggers best-effort remote delivery.
     */
    suspend fun saveSent(message: YoMessage)

    fun observeHistory(): Flow<List<YoMessage>>
}
