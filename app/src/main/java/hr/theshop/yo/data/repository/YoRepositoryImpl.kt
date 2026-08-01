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
        // Written BEFORE the attempt, deliberately: link and hashtag exist only in this row, so
        // discarding it on failure would destroy what the user typed at exactly the moment they
        // want to retry (G25). The verdict is stamped onto it afterwards rather than deciding
        // whether it is written at all.
        yoDao.insert(message.toEntity())
        return try {
            if (remoteDeliveryPort.deliver(message)) {
                yoDao.markDelivered(message.id, true)
                YoSendOutcome.Delivered
            } else {
                yoDao.markDelivered(message.id, false)
                YoSendOutcome.NotDelivered
            }
        } catch (e: CancellationException) {
            // A plain runCatching would swallow this and report a failed send, breaking
            // structured concurrency: the caller's scope is already gone and there is nobody
            // left to tell.
            throw e
        } catch (e: Throwable) {
            yoDao.markDelivered(message.id, false)
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
        delivered = delivered,
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
        delivered = delivered,
    )
