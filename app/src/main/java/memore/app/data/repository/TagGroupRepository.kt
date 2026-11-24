package memore.app.data.repository

import memore.app.data.local.dao.TagDao
import memore.app.data.local.dao.TagGroupDao
import memore.app.data.model.Tag
import memore.app.data.model.TagGroup

/**
 * 标签组仓库：组织壳，非标签本身。成员 = group_id 指向某组的标签。
 * 组仅用于分类浏览；记录仍挂在具体标签上，组的连接是成员标签的连接聚合（此处只管理归属）。
 */
class TagGroupRepository(
    private val groupDao: TagGroupDao,
    private val tagDao: TagDao
) {

    suspend fun getGroups(): List<TagGroup> = groupDao.getAll()

    suspend fun getById(id: Int): TagGroup? = groupDao.getById(id)

    suspend fun create(name: String): TagGroup {
        val trimmed = name.trim()
        val existing = groupDao.getByName(trimmed)
        if (existing != null) return existing
        val id = groupDao.insert(TagGroup(name = trimmed))
        return groupDao.getById(id.toInt()) ?: TagGroup(id = id.toInt(), name = trimmed)
    }

    suspend fun rename(id: Int, name: String) {
        val g = groupDao.getById(id) ?: return
        groupDao.update(g.copy(name = name.trim()))
    }

    /** 删除分组：先将其下标签置为未分组（不删标签本身），再删组。 */
    suspend fun delete(id: Int) {
        groupDao.detachTags(id)
        groupDao.delete(id)
    }

    // ---- 成员 ----

    suspend fun getMembers(groupId: Int): List<Tag> = tagDao.getTagsByGroup(groupId)

    suspend fun getUngroupedTags(): List<Tag> = tagDao.getUngroupedTags()

    suspend fun addMember(groupId: Int, tagId: Int) {
        tagDao.setTagGroup(tagId, groupId)
    }

    suspend fun removeMember(tagId: Int) {
        tagDao.setTagGroup(tagId, null)
    }

    /** 某组所有成员标签的活跃记录数之和。 */
    suspend fun groupUsage(groupId: Int): Int = tagDao.getTagsByGroup(groupId).sumOf { it.usageCount }

    /** 清理空标签组（无任何成员的组织壳），返回删除数量。 */
    suspend fun deleteEmpty(): Int {
        val empties = groupDao.getAll().filter { tagDao.getTagsByGroup(it.id).isEmpty() }
        empties.forEach { groupDao.delete(it.id) }
        return empties.size
    }
}
