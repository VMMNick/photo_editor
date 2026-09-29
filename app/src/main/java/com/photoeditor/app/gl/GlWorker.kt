package com.photoeditor.app.gl

import android.graphics.Bitmap
import com.photoeditor.app.core.Histogram
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.Preset
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Окремий потік для офскрін-GL (EGL-контекст прив'язаний до потоку). */
object GlWorker {
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "offscreen-gl") }.asCoroutineDispatcher()

    /**
     * Спільний офскрін-рендерер: EGL-контекст і скомпільований шейдер живуть увесь час роботи.
     * Раніше кожна гістограма / набір мініатюр / експорт створювали контекст і компілювали шейдер заново
     * (на повільних GPU — сотні мілісекунд щоразу). Доступ лише з потоку [dispatcher].
     */
    private var shared: OffscreenRenderer? = null

    suspend fun <T> withRenderer(block: (OffscreenRenderer) -> T): T = withContext(dispatcher) {
        val gl = shared ?: OffscreenRenderer().also { shared = it }
        try {
            block(gl)
        } catch (e: Throwable) {
            // Контекст міг зламатися (втрата GPU) — наступного разу створимо новий
            runCatching { gl.close() }
            shared = null
            throw e
        }
    }

    /** Мініатюри фільтрів: кожен пресет, застосований до маленької копії фото. */
    suspend fun presetThumbnails(source: Bitmap, presets: List<Preset>, side: Int = 180): Map<String, Bitmap> =
        withRenderer { gl ->
            val k = side.toFloat() / maxOf(source.width, source.height)
            val small = Bitmap.createScaledBitmap(
                source, (source.width * k).toInt().coerceAtLeast(1), (source.height * k).toInt().coerceAtLeast(1), true
            )
            val out = LinkedHashMap<String, Bitmap>()
            gl.setSource(small)
            for (p in presets) {
                val adj = Adjustments().plusScaled(p.delta, 1f)
                val params = RenderParams.from(EditState(adjust = adj), small.width, small.height, adj, emptyList())
                out[p.id] = gl.render(params, small.width, small.height)
            }
            if (small !== source) small.recycle()
            out
        }

    /**
     * Гістограма результату: рендер маленької копії з поточними налаштуваннями та підрахунок кольорів.
     * @param small зменшене прев'ю (~256 px), build — параметри для його розміру
     */
    suspend fun histogram(small: Bitmap, build: (Int, Int) -> RenderParams): Histogram {
        val out = renderSmall(small, build)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        out.recycle()
        return Histogram.compute(px)
    }

    /** Результат для маленької копії фото (гістограма, мініатюра в «Нещодавніх»). */
    suspend fun renderSmall(small: Bitmap, build: (Int, Int) -> RenderParams): Bitmap =
        withRenderer { gl ->
            gl.setSource(small)
            val params = build(small.width, small.height)
            val size = OffscreenRenderer.outputSize(params, maxOf(small.width, small.height))
            gl.render(params, size[0], size[1])
        }
}
