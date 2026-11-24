package memore.app.data.model

data class AiAnalysisResult(
    val version: String = "1.0",
    val summary: String = "",
    val memory_nodes: List<String> = emptyList(),
    val emotion: List<String> = emptyList(),
    val emotionTags: List<EmotionTag> = emptyList(),
    val relations: List<String> = emptyList(),
    /** 关联边：从候选旧记录里挑出来的、真的相关的几条（id + 一句话解释）。 */
    val links: List<AiLink> = emptyList(),
    /** 记录里提到的锚点（人/作品/地点/事件），带原句；落库前做子串校验。 */
    val anchors: List<AiAnchor> = emptyList(),
    val meta: Meta = Meta()
) {
    /** a = 本记录里的原句；b = 候选记录里的原句。两句都经过子串校验才会落库。 */
    data class AiLink(
        val to: String,
        val why: String = "",
        val a: String = "",
        val b: String = "",
        /** LINK（像）/ CORRECT（更正）/ SUPPLEMENT（补充）/ CITE（引用）。 */
        val type: String = "LINK"
    )

    data class AiAnchor(
        val name: String,
        /** PERSON / WORK / PLACE / EVENT / OTHER */
        val kind: String = "OTHER",
        val quote: String = ""
    )

    data class Meta(
        val model: String = "",
        val created_at: String = "",
        val analysis_version: String = "1.0",
        val prompt_version: String = "1"
    )
}

data class EmotionTag(
    val name: String,
    val weight: Float? = null,
    val trend: String? = null,
    /** 判定依据：从原文摘的一句（≤15 字），空表示未给（2026-11-08 新增）。 */
    val why: String? = null
)
