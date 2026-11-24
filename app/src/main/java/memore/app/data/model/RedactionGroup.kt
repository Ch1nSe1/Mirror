package memore.app.data.model

import java.util.UUID

/**
 * 脱敏组：同一组内的多个形态一起管理，命中时各自替换为独立随机乱码。
 * 无默认值、全部手动添加。
 */
data class RedactionGroup(
    val id: String = UUID.randomUUID().toString(),
    val standardName: String,
    val aliases: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
