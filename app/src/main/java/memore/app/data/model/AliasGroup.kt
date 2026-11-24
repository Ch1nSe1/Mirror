package memore.app.data.model

import java.util.UUID

data class AliasGroup(
    val id: String = UUID.randomUUID().toString(),
    val standardName: String,
    val aliases: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val kind: String = "concept"
)
