package hr.theshop.yo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.theshop.yo.di.InviteUrl
import hr.theshop.yo.domain.location.OneShotLocationProvider
import hr.theshop.yo.domain.model.DeviceRegistrationOutcome
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.model.PhoneContact
import hr.theshop.yo.data.remote.AddFriendOutcome
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.repository.ContactsRepository
import hr.theshop.yo.domain.repository.DeviceRegistrationStore
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.SessionStore
import hr.theshop.yo.domain.repository.YoRepository
import hr.theshop.yo.domain.usecase.BuildInviteMessageUseCase
import hr.theshop.yo.domain.usecase.FetchFriendsUseCase
import hr.theshop.yo.domain.usecase.FilterContactsUseCase
import hr.theshop.yo.domain.usecase.RegisterDeviceUseCase
import hr.theshop.yo.domain.usecase.SendYoToGroupUseCase
import hr.theshop.yo.domain.usecase.SendYoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val deviceRegistrationStore: DeviceRegistrationStore,
    private val backendApi: YoBackendApi,
    @param:InviteUrl private val inviteUrl: String,
) : ViewModel() {
    /** The signed-in account. Empty only in the instant before the sign-in screen takes over. */
    val username: String
        get() = sessionStore.current()?.username.orEmpty()

    private val _friends = MutableStateFlow<List<String>>(emptyList())
    val friends: StateFlow<List<String>> = _friends.asStateFlow()

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

    init {
        viewModelScope.launch {
            registerDevice()
            loadFriends()
        }
    }

    private suspend fun registerDevice() {
        val outcome =
            runCatching { registerDeviceUseCase() }
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

    private suspend fun loadFriends() {
        runCatching { fetchFriendsUseCase() }
            .onSuccess { loadedFriends ->
                _friends.value = loadedFriends
                _friendsLoadFailed.value = false
            }
            .onFailure {
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
        }
    }

    /** Drops the token server-side first, so a stolen copy of it dies with the logout. */
    fun logOut() {
        viewModelScope.launch {
            backendApi.logOut()
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
                runCatching {
                    yoRepository.clear()
                    groupRepository.clear()
                    deviceRegistrationStore.clear()
                }
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
        photoUri: String? = null,
    ) {
        viewModelScope.launch {
            val coords = if (attachLocation) locationProvider.getCurrentLocation() else null
            sendYoUseCase(sender = username, recipient = recipient) {
                copy(
                    link = link?.takeIf { it.isNotBlank() },
                    hashtag = hashtag?.takeIf { it.isNotBlank() }?.trimStart('#')?.takeIf { it.isNotBlank() },
                    latitude = coords?.latitude,
                    longitude = coords?.longitude,
                    photoUri = photoUri,
                )
            }
        }
    }

    fun createGroup(name: String, memberUsernames: List<String>) {
        viewModelScope.launch {
            groupRepository.createGroup(name, memberUsernames)
        }
    }

    fun sendYoToGroup(groupId: String) {
        viewModelScope.launch {
            sendYoToGroupUseCase(sender = username, groupId = groupId)
        }
    }
}
