package memore.app.data.model

enum class RecordType(val label: String) {
    ESSAY("随笔"),
    DIALOGUE("对话录"),
    AMENDMENT("增修"),

    /**
     * AI 札记（2026-11-14）：**只有 agent 能写**，用户界面上没有新建入口。
     *
     * 为什么做成类型而不是来源：它和随笔/对话录是并列的"这是什么东西"，
     * 而来源回答的是"这段内容从哪来"。AI 写的周报，类型是札记，来源也是它自己。
     */
    AI_NOTE("AI 札记")
}
