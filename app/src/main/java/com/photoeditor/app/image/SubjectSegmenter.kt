package com.photoeditor.app.image

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.photoeditor.app.core.AlphaMap
import kotlinx.coroutines.tasks.await
import kotlin.math.max

/**
 * AI-сегментація об'єктів на пристрої (ML Kit Subject Segmentation).
 * Модель (~ кілька МБ) Google Play Services завантажує при першому використанні.
 */
class SubjectSegmenter : AutoCloseable {

    data class Result(val foreground: AlphaMap?, val subjects: List<AlphaMap>)

    private val client = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .build()
            )
            .build()
    )

    /** Карти повертаються в розмірі вхідного зображення (після пониження до [maxSide]). */
    suspend fun segment(source: Bitmap, maxSide: Int = 1024): Result {
        val scale = minOf(1f, maxSide.toFloat() / max(source.width, source.height))
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else source
        try {
            val w = input.width
            val h = input.height
            val res = client.process(InputImage.fromBitmap(input, 0)).await()

            val fg = res.foregroundConfidenceMask?.let { buf ->
                buf.rewind()
                val arr = FloatArray(w * h)
                buf.get(arr, 0, minOf(arr.size, buf.remaining()))
                AlphaMap.fromFloats(w, h, arr)
            }
            val subjects = res.subjects.mapNotNull { s ->
                val buf = s.confidenceMask ?: return@mapNotNull null
                buf.rewind()
                val sw = s.width
                val sh = s.height
                val local = FloatArray(sw * sh)
                buf.get(local, 0, minOf(local.size, buf.remaining()))
                val full = FloatArray(w * h)
                for (y in 0 until sh) {
                    val ty = y + s.startY
                    if (ty !in 0 until h) continue
                    for (x in 0 until sw) {
                        val tx = x + s.startX
                        if (tx !in 0 until w) continue
                        full[ty * w + tx] = local[y * sw + x]
                    }
                }
                AlphaMap.fromFloats(w, h, full)
            }
            return Result(fg, subjects)
        } finally {
            if (input !== source) input.recycle()
        }
    }

    override fun close() = client.close()
}
