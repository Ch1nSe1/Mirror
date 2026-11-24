package memore.app.utils

import memore.app.data.model.AliasGroup

/**
 * 别名归并（2026-11-18 概念系统下线后保留）。
 *
 * 名字来自「别名映射管理」里的非情绪分组：把同一个东西的不同写法归一到标准名。
 * 现在不再有"概念节点"去写入它，但导出时仍用它把标签/人名归一，
 * 行为与之前一致，且删掉它就等于改动导出格式——所以留着这一个薄壳。
 */
object MemoryNodeNormalizer {

    @Volatile
    private var aliasMap: Map<String, String> = emptyMap()

    fun rebuildCache(groups: List<AliasGroup>) {
        aliasMap = groups.flatMap { group ->
            (listOf(group.standardName) + group.aliases).map { it to group.standardName }
        }.toMap()
    }

    fun normalize(name: String): String {
        val trimmed = name.trim()
        return aliasMap[trimmed] ?: trimmed
    }

    fun getAliasMap(): Map<String, String> = aliasMap
}
