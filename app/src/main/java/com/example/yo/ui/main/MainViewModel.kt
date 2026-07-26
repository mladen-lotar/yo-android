package com.example.yo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yo.di.InviteUrl
import com.example.yo.domain.location.OneShotLocationProvider
import com.example.yo.domain.model.DeviceRegistrationOutcome
import com.example.yo.domain.model.Group
import com.example.yo.domain.model.PhoneContact
import com.example.yo.data.remote.AddFriendOutcome
import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.ContactsRepository
import com.example.yo.domain.repository.GroupRepository
import com.example.yo.domain.repository.SessionStore
import com.example.yo.domain.repository.YoRepository
import com.example.yo.domain.usecase.BuildInviteMessageUseCase
import com.example.yo.domain.usecase.FetchFriendsUseCase
import com.example.yo.domain.usecase.FilterContactsUseCase
import com.example.yo.domain.usecase.RegisterDeviceUseCase
import com.example.yo.domain.usecase.SendYoToGroupUseCase
import com.example.yo.domain.usecase.SendYoUseCase
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
    repository: YoRepository,
    private val locationProvider: OneShotLocationProvider,
    private val contactsRepository: ContactsRepository,
    private val buildInviteMessage: BuildInviteMessageUseCase,
    private val filterContacts: FilterContactsUseCase,
    private val sessionStore: SessionStore,
    private val backendApi: YoBackendApi,
    @InviteUrl private val inviteUrl: String,
) : ViewModel() {
    /** The signed-in account. Empty only in the instant before the sign-in screen takes over. */
    val username: String
        get() = sessionStore.current()?.username.orEmpty()

    private val _friends = MutableStateFlow<List<String>>(emptyList())
    val friends: StateFlow<List<String>> = _friends.asStateFlow()

    private val _addFriendOutcome = MutableStateFlow<AddFriendOutcome?>(null)
    val addFriendOutcome: StateFlow<AddFriendOutcome?> = _addFriendOutcome.asStateFlow()

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

    val history: StateFlow<List<YoMessage>> = repository.observeHistory()
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
