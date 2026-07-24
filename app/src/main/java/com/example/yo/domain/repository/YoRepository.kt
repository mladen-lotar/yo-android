package com.example.yo.domain.repository

import com.example.yo.domain.model.YoMessage
import kotlinx.coroutines.flow.Flow

interface YoRepository {
    suspend fun saveSent(message: YoMessage)

    fun observeHistory(): Flow<List<YoMessage>>
}
