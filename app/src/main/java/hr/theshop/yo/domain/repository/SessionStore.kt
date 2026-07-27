package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.YoSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the signed-in session. Exposed as a [StateFlow] because the whole UI branches on it:
 * a null session shows the sign-in screen, a present one shows the bands.
 */
interface SessionStore {
    val session: StateFlow<YoSession?>

    fun current(): YoSession?

    fun save(session: YoSession)

    fun clear()
}
