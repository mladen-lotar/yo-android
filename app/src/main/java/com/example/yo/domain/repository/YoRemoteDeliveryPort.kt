package com.example.yo.domain.repository

import com.example.yo.domain.model.YoMessage

interface YoRemoteDeliveryPort {
    suspend fun deliver(message: YoMessage): Boolean
}
