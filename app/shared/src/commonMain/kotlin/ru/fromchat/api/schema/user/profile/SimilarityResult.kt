package ru.fromchat.api.schema.user.profile

import kotlinx.serialization.Serializable

@Serializable
data class SimilarityResult(
    val isSimilar: Boolean,
    val similarTo: String? = null
)