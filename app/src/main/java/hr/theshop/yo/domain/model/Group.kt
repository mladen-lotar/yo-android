package hr.theshop.yo.domain.model

import java.util.UUID

data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val memberUsernames: List<String>,
)
