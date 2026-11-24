package memore.app.data.model

data class AnalysisData(
    val summary: String = "",
    val memory_nodes: List<String> = emptyList(),
    val emotion: List<EmotionTagEntry> = emptyList(),
    val relations: List<String> = emptyList(),
    val meta: AnalysisMeta = AnalysisMeta()
)

data class AnalysisMeta(
    val model: String = "",
    val created_at: String = "",
    val analysis_version: String = "1.0",
    val prompt_version: String = "1",
    val content_truncated: Boolean = false
)