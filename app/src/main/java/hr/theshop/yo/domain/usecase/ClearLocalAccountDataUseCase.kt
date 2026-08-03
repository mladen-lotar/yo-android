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
 * `yoRepository.clear()` and `groupRepository.clear()` now only delete rows owned by the account
 * that is signed in at the moment this runs — see `YoDatabase.migration3To4` — rather than the
 * whole table. That is exactly why this must run BEFORE the session is cleared:
 * once the session is gone there is no account left to scope the delete to. Logout no longer costs
 * the user their own groups: a different account signing in afterwards reads its own rows, not an
 * empty table.
 *
 * Deliberately does NOT touch [hr.theshop.yo.domain.repository.SessionStore]: callers own the
 * ordering of clearing the session relative to this, and that ordering differs between them (see
 * `MainViewModel.logOut`) — and, since G30, it is also load-bearing for scoping the deletes above
 * to the right account.
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
