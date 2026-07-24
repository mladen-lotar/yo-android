package com.example.yo.domain.usecase

import com.example.yo.data.remote.YoBackendApi
import javax.inject.Inject

class FetchFriendsUseCase @Inject constructor(
    private val backendApi: YoBackendApi,
) {
    suspend operator fun invoke(): List<String> = backendApi.fetchFriends()
}
