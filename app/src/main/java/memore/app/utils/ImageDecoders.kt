package memore.app.utils

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.Decoder
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.fetch.SourceResult
import coil.request.Options

/**
 * 动图解码（GIF / 动图 WebP）。
 *
 * **为什么必须显式处理**：Coil 2 没有「装上扩展库就自动支持动图」的机制。
 * 已核对 coil-base 2.6.0 字节码：其中 0 处引用 `GifDecoder` / `ImageDecoderDecoder`，
 * 也 0 处引用 `ServiceLoader` / `Class.forName`（按文件头自动识别是 Coil 3 的行为），
 * 默认 ImageLoader 只注册 `BitmapFactoryDecoder`。
 * 因此加了 coil-gif 依赖之后，App 里其它位置（记录附件条、附件列表、图文集缩略图与主图）
 * **仍然只会显示首帧**，不需要额外拦截；动图只走下面这个专用 loader。
 *
 * **为什么挂到 ImageLoader 而不是 `ImageRequest.decoderFactory`**：
 * 请求级 factory 在「AsyncImage」这条路径上实测不生效（`AsyncImage` 会用
 * `imageLoader.defaults` + sizeResolver 重建请求，重建后 decoderFactory 丢了）。
 * 注册进 ImageLoader 的组件表则不受请求重建影响 —— 这也是 Coil 官方文档的做法。
 *
 * **目前唯一的用户**：图文集「放大查看器」（`AlbumScreen.ZoomableImageView`）。
 * 这台设备经常被 lowmemorykiller 杀掉，同屏多个动画解码器代价太大，故不铺开。
 *
 * 非动图（jpg / png / 静态 webp）会退回 [BitmapFactoryDecoder]，行为与默认 loader 一致。
 */
object ImageDecoders {

    /** 动图解码器。API 28+ 走系统 ImageDecoder，更快且支持动图 WebP；更低版本只能解 GIF。 */
    private val animated: Decoder.Factory =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoderDecoder.Factory()
        } else {
            GifDecoder.Factory()
        }

    private val static: Decoder.Factory = BitmapFactoryDecoder.Factory()

    /** 动图优先；不是动图就返回 null 交给后面的 [static]。 */
    private val animatedOrStatic: Decoder.Factory = object : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? =
            animated.create(result, options, imageLoader)
                ?: static.create(result, options, imageLoader)
    }

    @Volatile
    private var loader: ImageLoader? = null

    /**
     * 带动图解码器的 ImageLoader（进程内单例，只给放大查看器用）。
     *
     * 刻意关掉内存缓存：动图的 drawable 会持有全部帧，体积很大，
     * 而这台设备经常被 lowmemorykiller 杀 —— 查看器是单张、短时的场景，
     * 每次现解一次的代价远小于把它留在缓存里。
     */
    fun animatedImageLoader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(animatedOrStatic) }
                .memoryCache(null)
                .build()
                .also { loader = it }
        }
}
