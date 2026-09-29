package com.photoeditor.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.photoeditor.app.core.DetailMath
import com.photoeditor.app.image.DetailSource
import com.photoeditor.app.image.DetailTile
import com.photoeditor.app.model.RetouchOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Детальні тайли для збільшеного прев'ю: фрагмент оригіналу в повній роздільності
 * з уже застосованим ретушем. Декодер відкривається один раз на фото й працює в окремому потоці.
 */
internal class DetailTileController(private val scope: CoroutineScope) {

    /** Поточний фрагмент (або null). */
    var tile by mutableStateOf<DetailTile?>(null)
        private set

    /** Звідки вирізати тайли: файл + EXIF-орієнтація. null — недоступно (немає робочої копії). */
    private var spec: Pair<File, Int>? = null
    /** Відкритий декодер разом зі специфікацією, для якої його відкрито (щоб не взяти декодер старого фото). */
    @Volatile private var source: Pair<Pair<File, Int>, DetailSource>? = null
    private var job: Job? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    val hasSource: Boolean get() = spec != null

    /**
     * Запит фрагмента для видимої області (uv джерела l, t, r, b), що займає [targetMaxSide] пікселів екрана.
     * Якщо поточний тайл уже покриває область з потрібною деталізацією — нічого не робимо.
     * @param stillValid перевіряється перед показом результату (фото не змінилось, ретуш той самий)
     */
    fun request(want: FloatArray, targetMaxSide: Int, ops: List<RetouchOp>, srcW: Int, srcH: Int, stillValid: () -> Boolean) {
        val s = spec ?: return
        val current = tile
        job?.cancel()
        job = scope.launch {
            val result = withContext(io) {
                val src = source?.takeIf { it.first == s }?.second
                    ?: DetailSource.open(s.first, s.second)?.also { opened ->
                        val stale = source
                        source = s to opened
                        stale?.second?.close()
                    }
                    ?: return@withContext null
                val regionPx = max((want[2] - want[0]) * src.orientedWidth, (want[3] - want[1]) * src.orientedHeight).toInt()
                val sample = DetailMath.sampleSize(regionPx, targetMaxSide)
                if (current != null && current.ops === ops && DetailMath.covers(current.rect, current.sampleSize, want, sample)) {
                    return@withContext current
                }
                runCatching { src.decode(want, targetMaxSide, ops, srcW, srcH) }.getOrNull()
            }
            if (result != null && stillValid()) tile = result
        }
    }

    /** Ретуш змінився — тайл містить старий, його треба перерахувати. */
    fun invalidate() {
        job?.cancel()
        tile = null
    }

    /** Нове фото (або закриття): закриваємо старий декодер. */
    fun reset(newSpec: Pair<File, Int>?) {
        invalidate()
        spec = newSpec
        val old = source
        source = null
        if (old != null) scope.launch(io) { old.second.close() }
    }

    fun close() {
        source?.second?.close()
        source = null
    }
}
