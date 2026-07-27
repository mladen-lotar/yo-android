package hr.theshop.yo.data.repository

import hr.theshop.yo.data.local.YoDao
import hr.theshop.yo.data.local.YoEntity
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import hr.theshop.yo.domain.repository.YoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class YoRepositoryImpl @Inject constructor(
    private val yoDao: YoDao,
    private val remoteDeliveryPort: YoRemoteDeliveryPort,
) : YoRepository {
    override suspend fun saveSent(message: YoMessage) {
        yoDao.insert(message.toEntity())
        runCatching { remoteDeliveryPort.deliver(message) }
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
