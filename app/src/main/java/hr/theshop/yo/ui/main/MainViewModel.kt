package hr.theshop.yo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.theshop.yo.di.InviteUrl
import hr.theshop.yo.domain.location.OneShotLocationProvider
import hr.theshop.yo.domain.model.DeviceRegistrationOutcome
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.model.HashtagRule
import hr.theshop.yo.domain.model.PhoneContact
import hr.theshop.yo.data.remote.AddFriendOutcome
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.repository.ContactsRepository
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.SessionStore
import hr.theshop.yo.domain.repository.YoRepository
import hr.theshop.yo.domain.usecase.BuildInviteMessageUseCase
import hr.theshop.yo.domain.usecase.ClearLocalAccountDataUseCase
import hr.theshop.yo.domain.usecase.FetchFriendsUseCase
import hr.theshop.yo.domain.usecase.FilterContactsUseCase
import hr.theshop.yo.domain.usecase.RegisterDeviceUseCase
import hr.theshop.yo.domain.usecase.SendYoToGroupUseCase
import hr.theshop.yo.domain.usecase.SendYoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sendYoUseCase: SendYoUseCase,
    private val sendYoToGroupUseCase: SendYoToGroupUseCase,
    private val fetchFriendsUseCase: FetchFriendsUseCase,
    private val registerDeviceUseCase: RegisterDeviceUseCase,
    private val groupRepository: GroupRepository,
    private val yoRepository: YoRepository,
    private val locationProvider: OneShotLocationProvider,
    private val contactsRepository: ContactsRepository,
    private val buildInviteMessage: BuildInviteMessageUseCase,
    private val filterContacts: FilterContactsUseCase,
    private val sessionStore: SessionStore,
    private val clearLocalAccountData: ClearLocalAccountDataUseCase,
    private val backendApi: YoBackendApi,
    @param:InviteUrl private val inviteUrl: String,
) : ViewModel() {
    /** The signed-in account. Empty only in the instant before the sign-in screen takes over. */
    val username: String
        get() = sessionStore.current()?.username.orEmpty()

    /**
     * Captured once, at construction. MainActivity keys the composable's `hiltViewModel(key = ...)`
     * on the username, so this same instance is what survives a sign-out and a sign-back-in as the
     * SAME account — there is no fresh construction to hang re-registration off. [init] below
     * therefore has to watch [sessionStore] for the rest of this instance's life rather than run
     * once, and this is the account it is allowed to act for.
     */
    private val boundAccount: String = sessionStore.current()?.username.orEmpty()

    private val _friends = MutableStateFlow<List<String>>(emptyList())
    val friends: StateFlow<List<String>> = _friends.asStateFlow()

    private val _blocked = MutableStateFlow<List<String>>(emptyList())
    val blocked: StateFlow<List<String>> = _blocked.asStateFlow()

    private val _blockedLoadFailed = MutableStateFlow(false)
    val blockedLoadFailed: StateFlow<Boolean> = _blockedLoadFailed.asStateFlow()

    private val _addFriendOutcome = MutableStateFlow<AddFriendOutcome?>(null)
    val addFriendOutcome: StateFlow<AddFriendOutcome?> = _addFriendOutcome.asStateFlow()

    private val _deletingAccount = MutableStateFlow(false)
    val deletingAccount: StateFlow<Boolean> = _deletingAccount.asStateFlow()

    private val _deleteAccountFailed = MutableStateFlow(false)
    val deleteAccountFailed: StateFlow<Boolean> = _deleteAccountFailed.asStateFlow()

    private val _friendsLoadFailed = MutableStateFlow(false)
    val friendsLoadFailed: StateFlow<Boolean> = _friendsLoadFailed.asStateFlow()

    /**
     * True once registering for push has actually failed. Starts false so a working install never
     * flashes a warning, and stays false before sign-in, when there is nothing to register.
     */
    private val _pushUnavailable = MutableStateFlow(false)
    val pushUnavailable: StateFlow<Boolean> = _pushUnavailable.asStateFlow()

    private val _pushRetrying = MutableStateFlow(false)
    val pushRetrying: StateFlow<Boolean> = _pushRetrying.asStateFlow()

    /**
     * Send state lives here rather than in the composable because it is the only place it can be
     * tested: there are no instrumented UI tests in this project, so anything held in a
     * `remember` is unverifiable. It used to be a `remember` set on tap, which is exactly why a
     * failed send was indistinguishable from a delivered one.
     */
    private val _sendInFlightTo = MutableStateFlow<String?>(null)
    val sendInFlightTo: StateFlow<String?> = _sendInFlightTo.asStateFlow()

    private val _sendDeliveredTo = MutableStateFlow<String?>(null)
    val sendDeliveredTo: StateFlow<String?> = _sendDeliveredTo.asStateFlow()

    private val _sendFailure = MutableStateFlow<SendFailure?>(null)
    val sendFailure: StateFlow<SendFailure?> = _sendFailure.asStateFlow()

    private var failedAttempt: (suspend () -> YoSendOutcome)? = null

    /** Monotonic; only the newest send is allowed to publish an outcome. See [runSend]. */
    private var sendGeneration = 0

    private val _contacts = MutableStateFlow<List<PhoneContact>>(emptyList())

    private val _contactQuery = MutableStateFlow("")
    val contactQuery: StateFlow<String> = _contactQuery.asStateFlow()

    /**
     * The invite list, already narrowed by whatever is typed in the search field. Derived rather
     * than stored, so the raw address book is filtered once per keystroke and the UI never has to
     * hold two copies in sync.
     */
    val contacts: StateFlow<List<PhoneContact>> =
        combine(_contacts, _contactQuery) { all, query -> filterContacts(all, query) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** True when a query is hiding everything, so the sheet can say so instead of looking broken. */
    val contactsFilteredToNothing: StateFlow<Boolean> =
        combine(_contacts, contacts, _contactQuery) { all, shown, query ->
            query.isNotBlank() && all.isNotEmpty() && shown.isEmpty()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun onContactQueryChange(query: String) {
        _contactQuery.value = query
    }

    /** The share text is built here so the wording stays testable and out of the Composable. */
    fun inviteMessageFor(contact: PhoneContact?): String =
        buildInviteMessage(
            contact = contact,
            inviteUrl = inviteUrl,
            senderUsername = username,
        )

    /**
     * Re-read on every open of the invite sheet rather than once at startup: the user may have
     * granted READ_CONTACTS only moments ago, or edited their address book since.
     */
    fun refreshContacts() {
        viewModelScope.launch {
            _contacts.value = runCatching { contactsRepository.loadContacts() }.getOrDefault(emptyList())
        }
    }

    val groups: StateFlow<List<Group>> = groupRepository.observeGroups()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val history: StateFlow<List<YoMessage>> = yoRepository.observeHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * A collector rather than a one-shot block: see [boundAccount]. `sessionStore.session` is a
     * StateFlow, so this still emits immediately on cold start and runs exactly one register and
     * one loadFriends, same as before — but it also fires again on every later change, which is
     * what lets the SAME account signing back in re-register instead of relying on an instance
     * that is never recreated. A different account signing in on this instance is left alone
     * entirely: MainActivity constructs a fresh ViewModel for a new username, and this one has
     * nothing to do with an account it was never bound to.
     */
    init {
        viewModelScope.launch {
            sessionStore.session
                .map { it?.username.orEmpty() }
                .distinctUntilChanged()
                .collect { current ->
                    if (current != boundAccount) return@collect
                    registerDevice()
                    loadFriends()
                }
        }
    }

    private suspend fun registerDevice() {
        // Forced rather than left to the use case's own cache: that cache is keyed on
        // (username, fcmToken), and the server deletes the devices row on logout (see backend
        // `_handle_logout`), so the cache would otherwise report "already registered" for a
        // registration the server has already forgotten, and the client would never re-POST
        // /v1/register on the next sign-in.
        val outcome =
            runCatching { registerDeviceUseCase(force = true) }
                .getOrDefault(DeviceRegistrationOutcome.Failed)
        // NotSignedIn is not a failure: there is simply no account to bind a token to yet.
        _pushUnavailable.value = outcome == DeviceRegistrationOutcome.Failed
    }

    /**
     * Ask again, after the use case's own retries have already given up. The failure that prompts
     * this is usually transient, so the one thing the user can do about it is worth offering.
     */
    fun retryDeviceRegistration() {
        if (_pushRetrying.value) {
            return
        }
        viewModelScope.launch {
            _pushRetrying.value = true
            try {
                registerDevice()
            } finally {
                _pushRetrying.value = false
            }
        }
    }

    /**
     * `internal` for the same reason [loadBlocked] is: the cancellation contract is only
     * assertable if a test can call it directly.
     */
    internal suspend fun loadFriends() {
        try {
            _friends.value = fetchFriendsUseCase()
            _friendsLoadFailed.value = false
        } catch (e: CancellationException) {
            // Not a failed load. `runCatching` caught this too, so leaving the screen mid-fetch
            // emptied the friends list and drew COULDN'T LOAD FRIENDS - a claim about the server
            // made out of the user's own navigation. G36 fixed exactly this in loadBlocked and
            // cited loadFriends as the sibling that already got it right; loadFriends did not.
            throw e
        } catch (e: Throwable) {
            _friends.value = emptyList()
            _friendsLoadFailed.value = true
        }
    }

    /**
     * Add someone by username. This is what populates the home screen: friendships are explicit
     * now, so a new account starts with no bands until it adds somebody (gap G5).
     */
    fun addFriend(rawUsername: String) {
        val candidate = rawUsername.trim().uppercase()
        if (candidate.isEmpty()) {
            return
        }
        viewModelScope.launch {
            val outcome = backendApi.addFriend(candidate)
            _addFriendOutcome.value = outcome
            if (outcome == AddFriendOutcome.Added) {
                loadFriends()
            }
        }
    }

    fun clearAddFriendOutcome() {
        _addFriendOutcome.value = null
    }

    fun removeFriend(friend: String) {
        viewModelScope.launch {
            backendApi.removeFriend(friend)
            loadFriends()
        }
    }

    fun blockFriend(friend: String) {
        viewModelScope.launch {
            backendApi.block(friend)
            loadFriends()
            loadBlocked()
        }
    }

    fun unblock(username: String) {
        viewModelScope.launch {
            backendApi.unblock(username)
            loadBlocked()
        }
    }

    /** Called when the blocked sheet opens; a stale list here is a list you cannot act on. */
    fun refreshBlocked() {
        viewModelScope.launch { loadBlocked() }
    }

    /**
     * `internal` purely so the cancellation contract below is assertable. Called from inside
     * `viewModelScope.launch` everywhere in production, where a rethrown cancellation cancels the
     * child coroutine and reaches no caller a test could hold - so private, this rule could only
     * be asserted by reading it.
     */
    internal suspend fun loadBlocked() {
        try {
            _blocked.value = backendApi.fetchBlocked()
            _blockedLoadFailed.value = false
        } catch (e: CancellationException) {
            // Not a failed load. A plain runCatching would swallow it here exactly as it
            // used to in saveSent, and this is the one screen that undoes a one-way door.
            throw e
        } catch (e: Throwable) {
            // The list keeps its previous value, but the screen must SAY the fetch failed.
            // Without this the sheet rendered "NOBODY" - an affirmative claim that you have
            // blocked no one - out of a failed request, on the one surface whose whole job is
            // the safety control. loadFriends already distinguished the two; this did not, and
            // the asymmetry was the evidence rather than a judgement call.
            _blockedLoadFailed.value = true
        }
    }

    /**
     * Drops the token server-side first, so a stolen copy of it dies with the logout. Local data
     * comes next: the server also deletes the devices row on logout now (see backend
     * `_handle_logout`), and yoRepository/groupRepository are not scoped to an account either, so
     * leaving them would show this account's history and the device-registration cache to
     * whoever signs in next. The session is cleared LAST — clearing it first flips MainActivity
     * to the sign-in screen and lets a fast re-login race the wipe still in flight.
     */
    fun logOut() {
        viewModelScope.launch {
            backendApi.logOut()
            clearLocalAccountData()
            sessionStore.clear()
        }
    }

    /**
     * Deletes the account, which Google Play requires any app that creates one to offer.
     *
     * The server call comes first and its result is respected: clearing the session locally on a
     * failed request would sign the user out of an account that still exists, look like success,
     * and leave them no way back to the thing they asked to delete.
     *
     * On success everything this device holds goes too. Yo history and groups are not scoped to
     * an account, so leaving them would show the deleted account's messages to whoever signs in
     * next on the same phone.
     */
    fun deleteAccount() {
        // Claimed here rather than inside the coroutine: two taps in the same frame both reach
        // launch{} before either body runs, so a guard set inside the coroutine lets both through
        // and the account is deleted twice.
        if (_deletingAccount.value) return
        _deletingAccount.value = true
        _deleteAccountFailed.value = false
        viewModelScope.launch {
            try {
                val deleted = runCatching { backendApi.deleteAccount() }.getOrDefault(false)
                if (!deleted) {
                    _deleteAccountFailed.value = true
                    return@launch
                }
                clearLocalAccountData()
                sessionStore.clear()
            } finally {
                _deletingAccount.value = false
            }
        }
    }

    fun clearDeleteAccountFailure() {
        _deleteAccountFailed.value = false
    }

    fun sendYo(
        recipient: String,
        link: String? = null,
        hashtag: String? = null,
        attachLocation: Boolean = false,
        label: String = recipient,
    ) {
        launchSend(label) {
            val coords = if (attachLocation) locationProvider.getCurrentLocation() else null
            sendYoUseCase(sender = username, recipient = recipient) {
                copy(
                    link = normalizeLink(link),
                    hashtag = normalizeHashtag(hashtag),
                    latitude = coords?.latitude,
                    longitude = coords?.longitude,
                )
            }
        }
    }

    /**
     * A hashtag the backend will accept, or null.
     *
     * The server refuses anything outside `[\w-]+` with a 400, because a hashtag is interpolated
     * into the recipient's notification body beside the app's own "TAP TO OPEN" wording and could
     * otherwise forge a tap target. That rule is right and stays. What was wrong was leaving the
     * client to send whatever was typed: a space is the most ordinary thing a person puts in a
     * two-word tag, and it failed the ENTIRE Yo - not the hashtag, the Yo - with
     * `COULDN'T YO <NAME> - TAP TO RETRY` and a retry that re-issued the identical doomed request
     * forever. Validation the user cannot see, on a field they cannot get right, is not a control.
     *
     * So "world cup" is sent as "worldcup" rather than rejected. It is the reading the user meant,
     * it matches what every other product does with a hashtag, and it keeps the security rule
     * intact by making the server's 400 unreachable from our own client instead of by relaxing it.
     */
    internal fun normalizeHashtag(raw: String?): String? = HashtagRule.sanitize(raw)

    /**
     * "example.com" is what people type, and the recipient's notification only ever opens http or
     * https - so without this the most likely input travels the whole way and is then discarded at
     * the last step, which is the same shows-as-attached-arrives-as-nothing failure the delivery
     * work exists to remove. Anything already carrying a scheme is left exactly as typed, so a
     * deliberate non-web scheme is still rejected later rather than rewritten into a web one.
     */
    internal fun normalizeLink(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (SCHEME_PREFIX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    }

    fun createGroup(name: String, memberUsernames: List<String>) {
        viewModelScope.launch {
            groupRepository.createGroup(name, memberUsernames)
        }
    }

    fun sendYoToGroup(groupId: String, label: String) {
        launchSend(label) {
            collapse(sendYoToGroupUseCase(sender = username, groupId = groupId))
        }
    }

    /**
     * One verdict for a fan-out. Anything short of everybody is reported as not delivered: the
     * sender cannot tell which member missed it, so claiming success because the loop finished
     * is the same lie as the old unconditional flash, one level up.
     */
    private fun collapse(outcomes: List<YoSendOutcome>): YoSendOutcome =
        when {
            outcomes.isEmpty() -> YoSendOutcome.NotDelivered
            outcomes.all { it == YoSendOutcome.Delivered } -> YoSendOutcome.Delivered
            outcomes.all { it == YoSendOutcome.Unreachable } -> YoSendOutcome.Unreachable
            else -> YoSendOutcome.NotDelivered
        }

    private fun launchSend(label: String, block: suspend () -> YoSendOutcome) {
        viewModelScope.launch { runSend(label, block) }
    }

    private suspend fun runSend(label: String, block: suspend () -> YoSendOutcome) {
        // Nothing stops the user tapping a second band while the first send is still open, and
        // the two can finish in either order. Without this token a slow send to Alice lands its
        // verdict after a fast send to Bob, leaving Bob's "YO!" on screen beside Alice's failure
        // - and the retry row would then re-issue whichever attempt happened to be stored last,
        // sending a second Yo to Bob under Alice's name.
        val generation = ++sendGeneration
        _sendInFlightTo.value = label
        _sendFailure.value = null
        _sendDeliveredTo.value = null
        val outcome =
            try {
                block()
            } catch (e: CancellationException) {
                if (generation == sendGeneration) {
                    _sendInFlightTo.value = null
                }
                throw e
            } catch (e: Throwable) {
                // getCurrentLocation() is the one step outside saveSent's own handling.
                YoSendOutcome.Unreachable
            }
        if (generation != sendGeneration) {
            // Superseded. The newer send owns every one of these fields now.
            return
        }
        _sendInFlightTo.value = null
        if (outcome == YoSendOutcome.Delivered) {
            _sendDeliveredTo.value = label
        } else {
            // Kept together with the failure, never in a separate slot: they must describe the
            // same attempt or the retry silently addresses somebody else.
            failedAttempt = block
            _sendFailure.value = SendFailure(label, outcome)
        }
    }

    /**
     * Re-issues the attempt that failed. The failures that produce it are usually transient -
     * except [YoSendOutcome.Rejected], which is the server refusing this exact request for a
     * reason a retry cannot change. Re-issuing it would just re-send the identical bytes into
     * the identical refusal, forever, so this refuses outright rather than silently repeating a
     * doomed request.
     */
    fun retrySend() {
        val failure = _sendFailure.value ?: return
        if (failure.outcome == YoSendOutcome.Rejected) {
            return
        }
        val attempt = failedAttempt ?: return
        if (_sendInFlightTo.value != null) {
            return
        }
        viewModelScope.launch { runSend(failure.label, attempt) }
    }

    /** Ends the "YO!" flash. Driven by the composable's timer, so the duration stays a UI concern. */
    fun clearSendDelivered() {
        _sendDeliveredTo.value = null
    }

    data class SendFailure(val label: String, val outcome: YoSendOutcome)

    private companion object {
        /** RFC 3986 scheme: letter, then letters/digits/+/-/. up to the colon. */
        val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
