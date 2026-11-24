# Memore ProGuard Rules

# 模型层
-keep class memore.app.data.model.** { *; }

# 工具类
-keep class memore.app.utils.** { *; }

# Worker
-keep class memore.app.data.worker.** { *; }

# UI 组件（SVG 图标等）
-keep class memore.app.ui.components.** { *; }

# UI 页面
-keep class memore.app.ui.screens.** { *; }

# Repository
-keep class memore.app.data.repository.** { *; }

# Room 实体和数据库
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Gson 序列化（反射所需）
-keep class memore.app.data.model.EmotionTagEntry { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# JSON 系统类（Ollama 协议解析）
-keep class org.json.JSONObject { *; }
-keep class org.json.JSONArray { *; }

# ONNX Runtime（2026-11-23 release 真机崩溃：JNI 找不到 Java 类）
# 症状：保存记录触发向量化 → SIGABRT "java_class == null in GetMethodID"
# 原因：R8 把 ai.onnxruntime 的类/成员裁掉或改名，JNI 侧按原名反射找不到。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# 诊断/发布保留源文件名与行号（2026-11-23）
-keepattributes SourceFile,LineNumberTable


# Gson 依赖泛型签名（2026-11-23）：没保留 Signature 时 Map<String,List<AlbumFootnote>> 会被解析成 LinkedTreeMap，导出/图文集里 cast 崩溃。
-keepattributes Signature,InnerClasses,EnclosingMethod
