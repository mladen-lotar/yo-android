package hr.theshop.yo.domain.model

import java.util.UUID

data class YoMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val recipient: String,
    val timestamp: Long = System.currentTimeMillis(),
    val link: String? = null,
    val hashtag: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Null when unknown: written before delivery was recorded, or still in flight. */
    val delivered: Boolean? = null,
)
