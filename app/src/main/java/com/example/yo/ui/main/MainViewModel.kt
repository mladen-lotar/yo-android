package com.example.yo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yo.domain.location.OneShotLocationProvider
import com.example.yo.domain.model.Group
import com.example.yo.domain.model.YoIdentity
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.GroupRepository
import com.example.yo.domain.repository.YoRepository
import com.example.yo.domain.usecase.FetchFriendsUseCase
import com.example.yo.domain.usecase.RegisterDeviceUseCase
import com.example.yo.domain.usecase.SendYoToGroupUseCase
import com.example.yo.domain.usecase.SendYoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {
    private val _friends = MutableStateFlow<List<String>>(emptyList())
    val friends: StateFlow<List<String>> = _friends.asStateFlow()

    private val _friendsLoadFailed = MutableStateFlow(false)
    val friendsLoadFailed: StateFlow<Boolean> = _friendsLoadFailed.asStateFlow()

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
            runCatching { registerDeviceUseCase() }
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
    }

    fun sendYo(
        recipient: String,
        link: String? = null,
        hashtag: String? = null,
        attachLocation: Boolean = false,
    ) {
        viewModelScope.launch {
            val coords = if (attachLocation) locationProvider.getCurrentLocation() else null
            sendYoUseCase(sender = YoIdentity.CURRENT_USERNAME, recipient = recipient) {
                copy(
                    link = link?.takeIf { it.isNotBlank() },
                    hashtag = hashtag?.takeIf { it.isNotBlank() }?.trimStart('#')?.takeIf { it.isNotBlank() },
                    latitude = coords?.latitude,
                    longitude = coords?.longitude,
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
            sendYoToGroupUseCase(sender = YoIdentity.CURRENT_USERNAME, groupId = groupId)
        }
    }
}
