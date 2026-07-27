package hr.theshop.yo.domain.repository

interface FcmTokenProvider {
    suspend fun getToken(): String
}
