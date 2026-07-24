package com.example.yo.domain.repository

interface FcmTokenProvider {
    suspend fun getToken(): String
}
