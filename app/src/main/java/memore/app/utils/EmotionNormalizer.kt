package memore.app.utils

import memore.app.data.model.AliasGroup

object EmotionNormalizer {

    @Volatile
    private var aliasMap: Map<String, String> = emptyMap()

    fun rebuildCache(groups: List<AliasGroup>) {
        aliasMap = groups.flatMap { group ->
            (listOf(group.standardName) + group.aliases).map { it to group.standardName }
        }.toMap()
    }

    fun normalize(name: String): String {
        val cleaned = cleanFormat(name)
        return aliasMap[cleaned] ?: cleaned
    }

    fun cleanFormat(name: String): String =
        name.trim()
            .replace(Regex("""\s+"""), " ")
            .trim('。', '，', ',', '.', '！', '!', '？', '?', '；', ';', '、', '·', '…')

    fun getAliasMap(): Map<String, String> = aliasMap
}
