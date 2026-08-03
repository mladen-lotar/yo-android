package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.repository.DeviceRegistrationStore
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.YoRepository
import javax.inject.Inject

/**
 * Wipes everything this device stored for the signed-in account: Yo history, groups and the
 * device-registration cache. Shared between logging out and deleting the account, which both
 * need the exact same three clears — logOut() gained this only because the server now deletes the
 * devices row on logout too (see backend `_handle_logout`), and a client that still thought it was
 * registered for the same (username, fcmToken) pair would never re-POST `/v1/register` on the next
 * sign-in.
 *
 * Deliberately does NOT touch [hr.theshop.yo.domain.repository.SessionStore]: callers own the
 * ordering of clearing the session relative to this, and that ordering differs between them (see
 * `MainViewModel.logOut`).
 *
 * Each clear runs in its own [runCatching] rather than one wrapping all three: a repository that
 * throws must not stop the other two from running, or a single misbehaving store would leave
 * history or the registration cache behind for the next account on this device.
 */
class ClearLocalAccountDataUseCase @Inject constructor(
    private val yoRepository: YoRepository,
    private val groupRepository: GroupRepository,
    private val deviceRegistrationStore: DeviceRegistrationStore,
) {
    suspend operator fun invoke() {
        runCatching { yoRepository.clear() }
        runCatching { groupRepository.clear() }
        runCatching { deviceRegistrationStore.clear() }
    }
}
