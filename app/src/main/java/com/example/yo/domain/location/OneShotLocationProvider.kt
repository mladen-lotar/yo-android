package com.example.yo.domain.location

data class LocationCoordinates(
    val latitude: Double,
    val longitude: Double,
)

interface OneShotLocationProvider {
    suspend fun getCurrentLocation(): LocationCoordinates?
}
