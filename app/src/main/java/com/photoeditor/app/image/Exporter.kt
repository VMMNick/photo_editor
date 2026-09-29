package com.photoeditor.app.image

import android.content.Context
import android.net.Uri
import com.photoeditor.app.R
import com.photoeditor.app.core.CubeLut
import com.photoeditor.app.core.MaskMaps
import com.photoeditor.app.core.Retouch
import com.photoeditor.app.gl.GlWorker
import com.photoeditor.app.gl.OffscreenRenderer
import com.photoeditor.app.gl.RenderParams
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.EditState

/** Підписи — ui/Labels.kt (exportSizeLabel). */
enum class ExportSize(val maxSide: Int) {
    FULL(Int.MAX_VALUE),
    LARGE(4096),
    MEDIUM(2048),
    SOCIAL(1080),
}

/** Повний експорт: повторне декодування оригіналу → ретуш → GPU-обробка → галерея. */
object Exporter {

    /** Обмеження, щоб не впасти по пам'яті на телефонах (~24 Мп). */
    private const val MAX_PIXELS = 24_000_000L

    suspend fun export(
        context: Context,
        uri: Uri,
        /** Ім'я оригіналу — основа імені файлу результату (робоча копія називається source.*). */
        baseName: String,
        state: EditState,
        effective: Adjustments,
        size: ExportSize,
        format: ExportFormat,
        quality: Int,
        /** Завантажений LUT, якщо в налаштуваннях вибрано LUT. */
        lut: CubeLut? = null,
        onProgress: (String) -> Unit,
    ): Uri = GlWorker.withRenderer { gl ->
        try {
            onProgress(context.getString(R.string.progress_loading_original))
            val maxSide = minOf(gl.maxTextureSize, 8192)
            val loaded = ImageLoader.load(context, uri, maxSide, MAX_PIXELS)
            var bitmap = loaded.bitmap

            if (state.retouch.isNotEmpty()) {
                val px = bitmap.toPixelImage()
                val out = Retouch.applyAll(px, state.retouch, inPlace = true) { done ->
                    onProgress(context.getString(R.string.progress_retouch, done, state.retouch.size))
                }
                if (bitmap.isMutable) out.writeInto(bitmap) else {
                    bitmap.recycle()
                    bitmap = out.toBitmap()
                }
            }

            onProgress(context.getString(R.string.progress_color))
            val maps = state.masks.map { MaskMaps.forMask(it, bitmap.width, bitmap.height) }
            val params = RenderParams.from(state, bitmap.width, bitmap.height, effective, maps, lut = lut)
            gl.setSource(bitmap)
            val outSize = OffscreenRenderer.outputSize(params, minOf(size.maxSide, gl.maxTextureSize))
            val result = gl.render(params, outSize[0], outSize[1])
            bitmap.recycle()

            onProgress(context.getString(R.string.progress_saving))
            try {
                GallerySaver.save(context, result, format, quality, baseName, exifSource = uri)
            } finally {
                result.recycle()
            }
        } finally {
            gl.releaseSource()
        }
    }
}
