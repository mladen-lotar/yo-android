package hr.theshop.yo.data.repository

import hr.theshop.yo.data.local.YoDao
import hr.theshop.yo.data.local.YoEntity
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.repository.SessionStore
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import hr.theshop.yo.domain.repository.YoRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class YoRepositoryImpl @Inject constructor(
    private val yoDao: YoDao,
    private val remoteDeliveryPort: YoRemoteDeliveryPort,
    private val sessionStore: SessionStore,
) : YoRepository {
    override suspend fun saveSent(message: YoMessage): YoSendOutcome {
        // Written BEFORE the attempt, deliberately: link and hashtag exist only in this row, so
        // discarding it on failure would destroy what the user typed at exactly the moment they
        // want to retry (G25). The verdict is stamped onto it afterwards rather than deciding
        // whether it is written at all.
        //
        // ownerAccount is stamped from message.sender, not read from sessionStore here: sender IS
        // the signed-in account for every call reachable through SendYoUseCase, and this keeps a
        // row's ownership tied to the identity it was actually written under.
        yoDao.insert(message.toEntity())
        return try {
            // deliver() already returns the full-fidelity YoSendOutcome - including Rejected -
            // so it is passed straight through rather than remapped. Only "was it delivered"
            // decides the stamped `delivered` column; every non-Delivered outcome, Rejected
            // included, still marks the row as not delivered.
            val outcome = remoteDeliveryPort.deliver(message)
            yoDao.markDelivered(message.id, outcome == YoSendOutcome.Delivered)
            outcome
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

    /**
     * Scopes the observed history to the signed-in account. Nothing here needs to claim a
     * NULL-owner row: [hr.theshop.yo.data.local.YoDatabase.migration3To4] already stamped every
     * pre-upgrade row to the account that wrote it, or deleted it if no account was signed in at
     * upgrade time, so a NULL owner can never reach this query.
     */
    override fun observeHistory(): Flow<List<YoMessage>> =
        flow {
            val account = sessionStore.current()?.username.orEmpty()
            if (account.isNotEmpty()) {
                emitAll(yoDao.observeAll(account).map { entities -> entities.map(YoEntity::toDomain) })
            } else {
                emitAll(flowOf(emptyList()))
            }
        }

    /** Clears only the currently signed-in account's rows - see `ClearLocalAccountDataUseCase`. */
    override suspend fun clear() {
        val account = sessionStore.current()?.username.orEmpty()
        if (account.isNotEmpty()) {
            yoDao.deleteForOwner(account)
        }
    }
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
        ownerAccount = sender,
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
