package hr.theshop.yo.data.repository

import hr.theshop.yo.data.local.YoDao
import hr.theshop.yo.data.local.YoEntity
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import hr.theshop.yo.domain.repository.YoRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class YoRepositoryImpl @Inject constructor(
    private val yoDao: YoDao,
    private val remoteDeliveryPort: YoRemoteDeliveryPort,
) : YoRepository {
    override suspend fun saveSent(message: YoMessage): YoSendOutcome {
        yoDao.insert(message.toEntity())
        return try {
            if (remoteDeliveryPort.deliver(message)) {
                YoSendOutcome.Delivered
            } else {
                YoSendOutcome.NotDelivered
            }
        } catch (e: CancellationException) {
            // A plain runCatching would swallow this and report a failed send, breaking
            // structured concurrency: the caller's scope is already gone and there is nobody
            // left to tell.
            throw e
        } catch (e: Throwable) {
            YoSendOutcome.Unreachable
        }
    }

    override fun observeHistory(): Flow<List<YoMessage>> =
        yoDao.observeAll().map { entities -> entities.map(YoEntity::toDomain) }

    override suspend fun clear() = yoDao.deleteAll()
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
    )
