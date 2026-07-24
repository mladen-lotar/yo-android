package com.example.yo.data.repository

import com.example.yo.data.local.YoDao
import com.example.yo.data.local.YoEntity
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.YoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class YoRepositoryImpl @Inject constructor(
    private val yoDao: YoDao,
) : YoRepository {
    override suspend fun saveSent(message: YoMessage) {
        yoDao.insert(message.toEntity())
    }

    override fun observeHistory(): Flow<List<YoMessage>> =
        yoDao.observeAll().map { entities -> entities.map(YoEntity::toDomain) }
}

private fun YoMessage.toEntity() =
    YoEntity(
        id = id,
        sender = sender,
        recipient = recipient,
        timestamp = timestamp,
        link = link,
        hashtag = hashtag,
        latitude = latitude,
        longitude = longitude,
        photoUri = photoUri,
    )

private fun YoEntity.toDomain() =
    YoMessage(
        id = id,
        sender = sender,
        recipient = recipient,
        timestamp = timestamp,
        link = link,
        hashtag = hashtag,
        latitude = latitude,
        longitude = longitude,
        photoUri = photoUri,
    )
