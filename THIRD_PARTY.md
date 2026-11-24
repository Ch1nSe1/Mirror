# 第三方组件与模型

本项目的源代码以 MIT 许可发布；随附的模型与依赖库仍遵循各自的许可。

## 内置模型

| 组件 | 许可 | 来源 |
|---|---|---|
| bge-small-zh-v1.5-int8（量化 ONNX） | MIT | BAAI / FlagEmbedding：https://github.com/FlagOpen/FlagEmbedding · 模型卡：https://huggingface.co/BAAI/bge-small-zh-v1.5 |
| 对应词表 `assets/models/vocab.txt` | MIT | 同上 |

模型随 APK 打包，仅用于本地向量检索；不会上传任何数据。

## 主要依赖

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose / Room / Lifecycle | Apache-2.0 |
| Kotlin / Kotlin Coroutines | Apache-2.0 |
| ONNX Runtime Android (`com.microsoft.onnxruntime`) | Apache-2.0 |
| Coil（图片加载） | Apache-2.0 |
| Gson | Apache-2.0 |
| jieba-analysis | Apache-2.0 |

完整的依赖版本见 `app/build.gradle.kts`。

## 字体

界面使用系统字体（无衬线 + 系统衬线），不打包第三方字体文件。
