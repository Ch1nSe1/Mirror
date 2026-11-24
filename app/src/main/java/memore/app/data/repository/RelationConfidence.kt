package memore.app.data.repository

import memore.app.data.model.RecordRelation

/**
 * 关联边的置信度（2026-11-20）。
 *
 * 为什么要有：图里 838 条边，权重其实全是 1 —— AI 边有引文、历史 USER 边没有，
 * 两者在 UI/agent 眼里一模一样。这里把**已有信号**组合成一个 0..1 的数，不做新数据：
 *
 *  - quote（0.40）：两句引文都在各自正文里。这是唯一能机械验证的“证据”；
 *  - anchors（0.20）：两条记录共享锚点的程度（0 / 1 / ≥2）；
 *  - vector（0.25）：建边时的向量相似度（历史 USER 边为 0）；
 *  - type（0.10）：CITE / SUPPLEMENT / CORRECT 比泛 LINK 更有信息量；
 *  - source（0.05）：人工/agent 带证据的边略高于裸 LINK。
 *
 * 这不是“校准”：它只回答“这条边有多少可核对的依据”，不回答“它是否成立”。
 * 真正的校准要靠 agent 审 + 少量人判，置信度只是把待审队列排好序。
 */
object RelationConfidence {

    data class Parts(
        val quote: Double,
        val anchors: Double,
        val vector: Double,
        val type: Double,
        val source: Double,
        val value: Double
    )

    fun evaluate(
        relation: RecordRelation,
        anchorNamesA: Set<String>,
        anchorNamesB: Set<String>,
        contentA: String?,
        contentB: String?
    ): Parts {
        if (relation.status == "REJECTED") {
            return Parts(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val quote = if (
            relation.quoteA.isNotBlank() && relation.quoteB.isNotBlank() &&
            contentA != null && contentB != null &&
            contentA.contains(relation.quoteA) && contentB.contains(relation.quoteB)
        ) 1.0 else 0.0

        val shared = anchorNamesA.intersect(anchorNamesB).size
        val anchors = (shared.coerceAtMost(2)) / 2.0

        val vector = relation.score.coerceIn(0.0, 1.0)

        val type = when (relation.type) {
            "CITE", "SUPPLEMENT", "CORRECT" -> 0.8
            "LINK" -> 0.5
            else -> 0.3
        }

        val source = when (relation.source) {
            "AGENT" -> 0.75
            "USER" -> 0.6
            "AI" -> 0.5
            "VECTOR" -> 0.3
            else -> 0.4
        }

        val value = 0.40 * quote + 0.20 * anchors + 0.25 * vector + 0.10 * type + 0.05 * source
        return Parts(quote, anchors, vector, type, source, value.coerceIn(0.0, 1.0))
    }
}
