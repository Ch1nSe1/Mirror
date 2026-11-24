package memore.app.utils

/**
 * 提示音位置枚举：**代码预制**的触发位置表（运行时不能临时造位置）。
 * 每个位置带稳定语义 id、清晰的中文名（功能·操作）、以及默认音组（音源为 assets/sounds/ 下的 wav 路径）。
 * 用户可在「提示音编辑」对某位置绑定音频做覆盖（换/删/加/导入），配置存本地；未覆盖时用默认值。
 * 主题类带参数（themeName）由 playThemeVoice 传入；问候类按时段细分由 playEvent 内逻辑处理。
 */
enum class SoundEvent(
    val label: String,
    val defaultAssets: List<String>,
    val isThemed: Boolean = false
) {
    SAVE("记录·保存", listOf("sounds/save_success.wav", "sounds/save_1.wav", "sounds/save_2.wav", "sounds/save_3.wav")),
    DELETE("记录·删除", listOf("sounds/delete.wav", "sounds/delete_1.wav", "sounds/delete_2.wav", "sounds/complete.wav")),
    ERROR("通用·错误", listOf("sounds/error_1.wav", "sounds/error_2.wav", "sounds/error_3.wav", "sounds/error_4.wav", "sounds/error_5.wav")),
    PASSWORD_ERROR("加密·密码错误", listOf("sounds/password_error.wav")),
    FORGOT("改写·遗忘", listOf("sounds/forgot.wav")),
    UNLOCK("启动·解锁", listOf("sounds/unlock.wav", "sounds/startup_1.wav", "sounds/startup_2.wav")),
    COMPLETE("通用·完成", listOf("sounds/complete.wav")),
    CLEAR_TRASH("回收站·清空", listOf("sounds/clear_1.wav", "sounds/clear_2.wav", "sounds/clear_3.wav")),
    TRANSLATE("语音·翻译完成", listOf("sounds/translate_1.wav", "sounds/translate_2.wav", "sounds/translate_3.wav")),
    SYNTH("语音·合成完成", listOf("sounds/synth_1.wav", "sounds/synth_2.wav", "sounds/synth_3.wav", "sounds/synth_4.wav")),
    ATTACH("附件·添加完成", listOf("sounds/attach_1.wav", "sounds/attach_2.wav", "sounds/attach_3.wav")),
    MERGE("概念·合并完成", listOf("sounds/merge_1.wav", "sounds/merge_2.wav")),
    ENCRYPT("加密·加密完成", listOf("sounds/encrypt_1.wav", "sounds/encrypt_2.wav", "sounds/encrypt_3.wav")),
    DECRYPT("加密·解密完成", listOf("sounds/decrypt_1.wav", "sounds/decrypt_2.wav")),
    SUMMARY("AI·总结完成", listOf("sounds/summary_1.wav", "sounds/summary_2.wav", "sounds/summary_3.wav", "sounds/summary_4.wav", "sounds/summary_5.wav", "sounds/summary_6.wav", "sounds/summary_7.wav", "sounds/summary_8.wav")),
    DAOZUO_DONE("改写·完成", listOf("sounds/daozuo_done_1.wav", "sounds/daozuo_done_2.wav")),
    EXPORT_DONE("导出·成功", listOf("sounds/export_done_1.wav", "sounds/export_done_2.wav")),
    AI_CLEAN_DONE("AI·整理完成", listOf("sounds/ai_clean_done_1.wav", "sounds/ai_clean_done_2.wav")),
    HIDDEN_ENCRYPT("加密·隐藏入口", listOf("sounds/hidden_encrypt_1.wav")),
    TUTORIAL("教程·打开", listOf("sounds/tutorial_1.wav", "sounds/tutorial_2.wav", "sounds/tutorial_3.wav")),
    ANCHOR_SET("锚点·设置", listOf("sounds/anchor_set_1.wav", "sounds/anchor_set_2.wav", "sounds/anchor_set_3.wav")),
    ANCHOR_CLEAR("锚点·清除", listOf("sounds/anchor_clear_1.wav", "sounds/anchor_clear_2.wav")),
    THINKING_ON("AI·思考开启", listOf("sounds/thinking_on_1.wav", "sounds/thinking_on_2.wav")),
    THINKING_OFF("AI·思考关闭", listOf("sounds/thinking_off_1.wav", "sounds/thinking_off_2.wav")),
    FOOTNOTE_ADD("图文集·注脚添加", listOf("sounds/footnote_add_1.wav", "sounds/footnote_add_2.wav", "sounds/footnote_add_3.wav", "sounds/footnote_add_4.wav")),
    FOOTNOTE_DELETE("图文集·注脚删除", listOf("sounds/footnote_delete_1.wav", "sounds/footnote_delete_2.wav", "sounds/footnote_delete_3.wav")),
    ALBUM_ENTER("图文集·进入", listOf("sounds/album_enter_1.wav", "sounds/album_enter_2.wav", "sounds/album_enter_3.wav", "sounds/album_enter_4.wav")),
    IMPORT_DONE("导入·成功", listOf("sounds/import_done_1.wav", "sounds/import_done_2.wav", "sounds/import_done_3.wav", "sounds/import_done_4.wav")),
    AMENDMENT_DONE("增修·完成", listOf("sounds/amendment_done_1.wav", "sounds/amendment_done_2.wav", "sounds/amendment_done_3.wav", "sounds/amendment_done_4.wav")),
    RESTORE_DONE("回收站·恢复", listOf("sounds/restore_done_1.wav", "sounds/restore_done_2.wav", "sounds/restore_done_3.wav", "sounds/restore_done_4.wav")),
    BATCH_ENTER("批量·选择进入", listOf("sounds/batch_enter_1.wav", "sounds/batch_enter_2.wav", "sounds/batch_enter_3.wav")),
    BATCH_EXIT("批量·选择退出", listOf("sounds/batch_exit_1.wav", "sounds/batch_exit_2.wav", "sounds/batch_exit_3.wav")),
    TAG_CREATE("标签·创建", listOf("sounds/tag_create_1.wav", "sounds/tag_create_2.wav", "sounds/tag_create_3.wav")),
    TAG_USE("标签·使用", listOf("sounds/tag_use_1.wav", "sounds/tag_use_2.wav", "sounds/tag_use_3.wav")),
    TAG_DELETE("标签·删除", listOf("sounds/tag_delete_1.wav", "sounds/tag_delete_2.wav")),
    PROTOCOL_CREATE("协议·创建", listOf("sounds/protocol_create_1.wav", "sounds/protocol_create_2.wav", "sounds/protocol_create_3.wav")),
    PROTOCOL_USE("协议·使用", listOf("sounds/protocol_use_1.wav", "sounds/protocol_use_2.wav", "sounds/protocol_use_3.wav")),
    PROTOCOL_DELETE("协议·删除", listOf("sounds/protocol_delete_1.wav", "sounds/protocol_delete_2.wav")),
    PRESET_SAVE("提示词·预设保存", listOf("sounds/preset_save_1.wav", "sounds/preset_save_2.wav", "sounds/preset_save_3.wav", "sounds/preset_save_4.wav")),
    API_PRESET_SWITCH("AI·预设切换", listOf("sounds/api_preset_1.wav", "sounds/api_preset_2.wav", "sounds/api_preset_3.wav", "sounds/api_preset_4.wav")),
    FILTER_DONE("筛选·完成", listOf("sounds/filter_done_1.wav", "sounds/filter_done_2.wav", "sounds/filter_done_3.wav", "sounds/filter_done_4.wav")),
    CLEANUP_DONE("数据·清算完成", listOf("sounds/cleanup_done_1.wav", "sounds/cleanup_done_2.wav", "sounds/cleanup_done_3.wav")),
    REDACTION_ON("脱敏·开启", listOf("sounds/redaction_on_1.wav", "sounds/redaction_on_2.wav", "sounds/redaction_on_3.wav", "sounds/redaction_on_4.wav")),
    REDACTION_OFF("脱敏·关闭", listOf("sounds/redaction_off_1.wav", "sounds/redaction_off_2.wav", "sounds/redaction_off_3.wav", "sounds/redaction_off_4.wav")),
    ENC_TEMP_DEC("加密·临时解加密", listOf("sounds/enc_temp_dec_1.wav", "sounds/enc_temp_dec_2.wav", "sounds/enc_temp_dec_3.wav")),
    ENC_TEMP_UNDEC("加密·解除临时解", listOf("sounds/enc_temp_undec_1.wav", "sounds/enc_temp_undec_2.wav", "sounds/enc_temp_undec_3.wav")),
    TICK_CREATE("刻度·新建/编辑", listOf("sounds/tick_create_1.wav", "sounds/tick_create_2.wav", "sounds/tick_create_3.wav")),
    TICK_DELETE("刻度·删除", listOf("sounds/tick_delete_1.wav", "sounds/tick_delete_2.wav", "sounds/tick_delete_3.wav")),
    CAT_CREATE("父项·新建", listOf("sounds/cat_create_1.wav", "sounds/cat_create_2.wav", "sounds/cat_create_3.wav")),
    CAT_DELETE("父项·删除", listOf("sounds/cat_delete_1.wav", "sounds/cat_delete_2.wav", "sounds/cat_delete_3.wav")),
    ALBUM_STAR("图文集·加星标", listOf("sounds/album_star_1.wav", "sounds/album_star_2.wav", "sounds/album_star_3.wav")),
    EXPORT_RULE_ON("导出·规则prompt开", listOf("sounds/export_rule_on_1.wav", "sounds/export_rule_on_2.wav", "sounds/export_rule_on_3.wav")),
    EXPORT_RULE_OFF("导出·规则prompt关", listOf("sounds/export_rule_off_1.wav", "sounds/export_rule_off_2.wav", "sounds/export_rule_off_3.wav")),
    LOCK_ON("应用锁·开启", listOf("sounds/lock_on_1.wav", "sounds/lock_on_2.wav", "sounds/lock_on_3.wav")),
    LOCK_OFF("应用锁·关闭", listOf("sounds/lock_off_1.wav", "sounds/lock_off_2.wav", "sounds/lock_off_3.wav")),
    LOCK_RESET("应用锁·重置", listOf("sounds/lock_reset_1.wav", "sounds/lock_reset_2.wav", "sounds/lock_reset_3.wav")),
    IMPORT_BLOCK_ON("脱敏·拒导入开", listOf("sounds/import_block_on_1.wav", "sounds/import_block_on_2.wav", "sounds/import_block_on_3.wav")),
    IMPORT_BLOCK_OFF("脱敏·拒导入关", listOf("sounds/import_block_off_1.wav", "sounds/import_block_off_2.wav", "sounds/import_block_off_3.wav")),
    SOUND_EDIT_DELETE("音效·编辑删除", listOf("sounds/sound_del_1.wav", "sounds/sound_del_2.wav", "sounds/sound_del_3.wav", "sounds/sound_del_like.wav")),
    SOUND_EDIT_IMPORT("音效·编辑导入", listOf("sounds/sound_import_1.wav", "sounds/sound_import_2.wav", "sounds/sound_import_3.wav")),
    SOUND_EDIT_CLEAR("音效·编辑清空", listOf("sounds/sound_clear_1.wav", "sounds/sound_clear_2.wav", "sounds/sound_clear_3.wav")),
    SOUND_EDIT_RESET("音效·编辑重置", listOf("sounds/sound_reset_1.wav", "sounds/sound_reset_2.wav", "sounds/sound_reset_3.wav")),
    THEME("主题·切换", listOf(
        "sounds/theme_graphite_1.wav",
        "sounds/theme_paper_1.wav", "sounds/theme_paper_2.wav",
        "sounds/theme_cream_1.wav", "sounds/theme_cream_2.wav",
        "sounds/theme_midnight_1.wav", "sounds/theme_midnight_2.wav",
        "sounds/theme_night_1.wav", "sounds/theme_amber_1.wav"
    ), isThemed = true),
    GREETING("问候·启动", listOf(
        "sounds/greeting_morning_1.wav", "sounds/greeting_morning_2.wav", "sounds/greeting_morning_3.wav",
        "sounds/greeting_noon_1.wav", "sounds/greeting_noon_2.wav", "sounds/greeting_noon_3.wav",
        "sounds/greeting_evening_1.wav", "sounds/greeting_evening_2.wav", "sounds/greeting_evening_3.wav",
        "sounds/greeting_night_1.wav", "sounds/greeting_night_2.wav", "sounds/greeting_night_3.wav", "sounds/greeting_night_4.wav", "sounds/greeting_night_5.wav",
        "sounds/greeting_common_1.wav", "sounds/greeting_common_2.wav", "sounds/greeting_common_3.wav"
    ));

    companion object {
        fun byName(name: String?): SoundEvent? = entries.firstOrNull { it.name == name }
    }
}
