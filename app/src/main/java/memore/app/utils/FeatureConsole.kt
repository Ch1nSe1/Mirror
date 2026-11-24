package memore.app.utils

import android.content.Context

/**
 * 功能控制台：控制各功能入口的显示/隐藏（默认全显；关闭 = 隐藏）。
 *
 * 层级：父级关闭 → 子级一并隐藏。判断用 [visible]：
 *   可见性 = 自身开关 且 所有祖先开关全开。
 * 存储：SharedPreferences（key = show_{featureKey}，默认 true）。
 */
object FeatureConsole {

    data class Feature(val key: String, val label: String, val parentKey: String? = null, val defaultShown: Boolean = true)

    // ===== 一级（父）=====
    const val TOPICS = "topics"
    /** 向量与关联（2026-11-05）：聚类、关联、AI 记录、向量索引。 */
    const val VECTOR = "vector"
    const val LINKS = "links"
    const val AI_RUNS = "ai_runs"
    const val VECTOR_INDEX = "vector_index"
    /** 旧节点系统：已隔离，不再出现在控制台/入口（保留常量给归档页） */
    const val GEN_SUMMARY = "gen_summary"
    const val ALBUM = "album"
    const val ZONES = "zones"
    const val SORT_TIMELINE = "sort_timeline"
    const val AGENT_LINK = "agent_link"
    const val INSIGHT_ARCHIVE = "insight_archive"
    const val TAG_PROTOCOL_MANAGE = "tag_protocol_manage"
    const val FILTER = "filter"
    const val AI_CONFIG = "ai_config"
    const val APP_LOCK = "app_lock"
    const val ALIAS_MANAGE = "alias_manage"
    const val EMOTION = "emotion"
    const val PROMPT_MANAGE = "prompt_manage"
    /** 来源（2026-11-11）：管理入口、卡片标记、筛选维度。 */
    const val SOURCE = "source"
    const val TUTORIAL = "tutorial"
    const val BATCH = "batch"
    const val ID_DISPLAY = "id_display"
    const val ADDITIONS = "additions"
    const val VOICE = "voice"
    const val VOICE_LAB = "voice_lab"
    const val VOICE_ICONS = "voice_icons"
    const val VOICE_FUNCS = "voice_funcs"
    const val ENCRYPTION = "encryption"
    const val ENCRYPTION_MANAGE = "encryption_manage"
    const val ENCRYPTION_ICON = "encryption_icon"
    const val ENCRYPTION_FUNCS = "encryption_funcs"

    // ===== 二级（子）=====
    const val AI_ORGANIZE = "ai_organize"
    const val GEN_SUMMARY_BTN = "gen_summary_btn"
    const val SUMMARY_VIEW = "summary_view"
    const val MANAGE_TAG_GROUPS = "manage_tag_groups"
    const val MANAGE_PROTOCOLS = "manage_protocols"
    const val AI_API = "ai_api"
    const val AI_THINKING = "ai_thinking"
    const val EMOTION_GRAPH = "emotion_graph"
    const val EMOTION_RECORDS = "emotion_records"
    const val EMOTION_STATS = "emotion_stats"
    const val ADD_TAG = "add_tag"
    const val ADD_PROTOCOL = "add_protocol"
    const val SOURCE_MANAGE = "source_manage"
    const val SOURCE_BADGE = "source_badge"
    const val SOURCE_FILTER = "source_filter"
    const val ADD_SOURCE = "add_source"
    const val ADD_ATTACHMENT = "add_attachment"
    const val ADD_AMENDMENT = "add_amendment"

    val FEATURES: List<Feature> = listOf(
        Feature(VECTOR, "向量与关联"),
        Feature(TOPICS, "主题", VECTOR),
        Feature(LINKS, "关联", VECTOR),
        Feature(AI_RUNS, "AI 记录", VECTOR),
        Feature(VECTOR_INDEX, "向量索引", VECTOR),
        Feature(GEN_SUMMARY, "生成总结"),
        Feature(GEN_SUMMARY_BTN, "生成总结", GEN_SUMMARY),
        Feature(SUMMARY_VIEW, "总结查看", GEN_SUMMARY),
        Feature(ALBUM, "图文集"),
        Feature(ZONES, "区域"),
        Feature(SORT_TIMELINE, "时间线排序"),
        Feature(AGENT_LINK, "Agent 连接"),
        Feature(INSIGHT_ARCHIVE, "洞察档案"),
        Feature(TAG_PROTOCOL_MANAGE, "管理标签组/协议"),
        Feature(MANAGE_TAG_GROUPS, "管理标签组", TAG_PROTOCOL_MANAGE),
        Feature(MANAGE_PROTOCOLS, "管理协议", TAG_PROTOCOL_MANAGE),
        Feature(FILTER, "筛选"),
        Feature(AI_CONFIG, "AI配置"),

        Feature(APP_LOCK, "应用锁"),
        Feature(ALIAS_MANAGE, "别名映射管理"),
        Feature(EMOTION, "情绪分析"),
        Feature(EMOTION_GRAPH, "情绪图谱", EMOTION),
        Feature(EMOTION_RECORDS, "情绪记录", EMOTION),
        Feature(EMOTION_STATS, "情绪统计", EMOTION),
        Feature(PROMPT_MANAGE, "提示词管理"),
    Feature(SOURCE, "来源"),
    Feature(SOURCE_MANAGE, "来源管理", SOURCE),
    Feature(SOURCE_BADGE, "卡片显示来源", SOURCE),
    Feature(SOURCE_FILTER, "按来源筛选", SOURCE),
        Feature(TUTORIAL, "使用教程"),
        Feature(BATCH, "批量选择"),
        Feature(ID_DISPLAY, "ID显示", defaultShown = false),
        Feature(ADDITIONS, "增添"),
        Feature(ADD_TAG, "添加标签", ADDITIONS),
        Feature(ADD_PROTOCOL, "添加协议", ADDITIONS),
        Feature(ADD_ATTACHMENT, "添加附件", ADDITIONS),
        Feature(ADD_AMENDMENT, "增修", ADDITIONS),
    Feature(ADD_SOURCE, "选择来源", ADDITIONS),
        Feature(VOICE, "语音"),
        Feature(VOICE_LAB, "语音实验室", VOICE),
        Feature(VOICE_ICONS, "显示语音图标", VOICE, defaultShown = false),
        Feature(VOICE_FUNCS, "显示语音功能", VOICE, defaultShown = false),
        Feature(ENCRYPTION, "加密"),
        Feature(ENCRYPTION_MANAGE, "加密管理", ENCRYPTION),
        Feature(ENCRYPTION_ICON, "显示加密图标", ENCRYPTION),
        Feature(ENCRYPTION_FUNCS, "显示加密功能", ENCRYPTION)
    )

    private val featureMap: Map<String, Feature> = FEATURES.associateBy { it.key }

    /** 每个一级下的子功能（供控制台树渲染）。 */
    val childrenOf: Map<String, List<Feature>> = FEATURES.filter { it.parentKey != null }.groupBy { it.parentKey!! }

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("feature_console", Context.MODE_PRIVATE)
    }

    private fun flag(key: String): Boolean {
        val def = featureMap[key]?.defaultShown ?: true
        return prefs?.getBoolean("show_$key", def) ?: def
    }

    fun setFlag(key: String, shown: Boolean) {
        prefs?.edit()?.putBoolean("show_$key", shown)?.apply()
    }

    /** 原始开关值（true=显示）。用 [visible] 判断实际是否显示。 */
    fun rawShown(key: String): Boolean = flag(key)

    /** 该功能是否显示：自身开关 且 父级链全开。 */
    fun visible(key: String): Boolean {
        val f = featureMap[key] ?: return false   // 已退役的功能（如旧节点）不再意外出现
        if (!flag(key)) return false
        val p = f.parentKey
        return p == null || visible(p)
    }

    /** 一组功能里是否有任一可见（用于"全关则入口消失"的判断）。 */
    fun anyVisible(keys: List<String>): Boolean = keys.any { visible(it) }
}

